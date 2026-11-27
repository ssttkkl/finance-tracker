import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { Agent, get } from 'node:https';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { measureCommand, parsePeakRss, parseProcessResources } from './large-measure.mjs';
import { openProxy, verifyPreparedFiles } from './large-support.mjs';

test('the command timer normalizes GNU KiB and BSD byte measurements', () => {
    assert.equal(parsePeakRss('12345\n', 'linux'), 12_641_280);
    assert.equal(parsePeakRss('       12345  maximum resident set size\n', 'darwin'), 12_345);
    assert.throws(() => parsePeakRss('', 'linux'), /missing peak RSS/);
    assert.throws(() => parsePeakRss('0\n', 'linux'), /missing peak RSS/);
    assert.throws(() => parsePeakRss('100\n200\n', 'linux'), /missing peak RSS/);
});

test('raw GNU and BSD timers preserve CPU, elapsed and memory resources', () => {
    assert.deepEqual(parseProcessResources('Command exited non-zero\nft-process 100 95.25 31.00 1.00 0 200 0 10 2\n', 'linux'), {
        rssBytes: 102400, timerWallMs: 95250, userCpuMs: 31000, systemCpuMs: 1000,
        majorFaults: 0, minorFaults: 200, swaps: 0, involuntaryContextSwitches: 10, voluntaryContextSwitches: 2,
    });
    const bsd = parseProcessResources('1.50 real 1.20 user 0.10 sys\n111  maximum resident set size\n', 'darwin');
    assert.equal(bsd.timerWallMs, 1500);
    assert.equal(bsd.userCpuMs, 1200);
    assert.equal(bsd.systemCpuMs, 100);
    assert.equal(bsd.rssBytes, 111);
});

test('phase and GC evidence is observable on a real Node subprocess', async () => {
    const outputDir = mkdtempSync(join(tmpdir(), 'ft-perf-process-'));
    try {
        const outcome = await measureCommand(process.execPath, ['--expose-gc', '-e', [
            'process.stderr.write("ft import: read_files\\n");',
            'global.gc();',
            'setImmediate(() => console.log("{}"));',
        ].join('')], { outputDir, label: 'phases', timeoutMs: 5_000, collectGc: true, env: process.env });
        assert.equal(outcome.process.phases[0].phase, 'read_files');
        assert.ok(outcome.process.userCpuMs >= 0);
        assert.ok(outcome.process.phases[0].pressure.utc);
        assert.equal(outcome.process.gc.available, true);
        assert.ok(outcome.process.gc.samples.some(event => event.durationMs > 0));
    } finally {
        rmSync(outputDir, { recursive: true, force: true });
    }
});

test('preparation reports interrupted, missing or failed files without admitting a partial batch', () => {
    const report = [];
    const bank = { name: 'bank.xls', status: 'parsed', code: null };
    const pdf = { name: 'statement.pdf', status: 'parsed', code: null };
    assert.throws(() => verifyPreparedFiles({
        files: [bank], interruption: 'network_unavailable', remaining_files: [pdf.name],
    }, [bank.name, pdf.name], 'prepare', report), /network_unavailable.*statement\.pdf/);
    assert.throws(() => verifyPreparedFiles({
        files: [bank, { ...pdf, status: 'failed', code: 'statement_unreadable' }],
    }, [bank.name, pdf.name], 'prepare', report), /statement\.pdf.*statement_unreadable/);
    assert.throws(() => verifyPreparedFiles({ files: [bank] },
        [bank.name, pdf.name], 'prepare', report), /statement\.pdf/);
    assert.throws(() => verifyPreparedFiles({ files: [bank, pdf, pdf] },
        [bank.name, pdf.name], 'prepare', report), /duplicates.*statement\.pdf/);
    assert.doesNotThrow(() => verifyPreparedFiles({ files: [bank, pdf] },
        [bank.name, pdf.name], 'prepare', report));
    assert.deepEqual(report[0], {
        phase: 'prepare', interruption: 'network_unavailable', remainingFiles: [pdf.name], files: [bank],
    });
});

test('TLS proxy gives its client a stable idle lifetime independent of Ktor sockets', async () => {
    const directory = mkdtempSync(join(tmpdir(), 'ft-perf-proxy-'));
    const upstreamSockets = new Set();
    const upstream = createServer((incoming, outgoing) => {
        upstreamSockets.add(incoming.socket);
        outgoing.writeHead(200, { 'Keep-Alive': 'timeout=1', 'Content-Type': 'text/plain' });
        outgoing.end('ok');
    });
    let proxy;
    let agent;
    try {
        const keyPath = join(directory, 'key.pem');
        const certPath = join(directory, 'cert.pem');
        execFileSync('openssl', [
            'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1', '-subj', '/CN=127.0.0.1',
            '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', keyPath, '-out', certPath,
        ], { stdio: 'ignore' });
        const cert = readFileSync(certPath);
        upstream.listen(0, '127.0.0.1');
        await once(upstream, 'listening');
        const observed = [];
        proxy = openProxy(cert, readFileSync(keyPath), upstream.address().port, observed);
        proxy.listen(0, '127.0.0.1');
        await once(proxy, 'listening');
        agent = new Agent({ ca: cert, keepAlive: true });
        const ask = path => new Promise((resolve, reject) => {
            const client = get({ hostname: '127.0.0.1', path, port: proxy.address().port, agent }, response => {
                response.resume();
                response.once('end', () => resolve({
                    reusedSocket: client.reusedSocket, keepAlive: response.headers['keep-alive'],
                }));
            });
            client.once('error', reject);
        });
        assert.deepEqual(await ask('/first'), { reusedSocket: false, keepAlive: 'timeout=120' });
        assert.deepEqual(await ask('/next'), { reusedSocket: true, keepAlive: 'timeout=120' });
        assert.equal(upstreamSockets.size, 2);
        assert.deepEqual(observed.map(({ path, status }) => [path, status]), [['/first', 200], ['/next', 200]]);
    } finally {
        agent?.destroy();
        proxy?.close();
        upstream.close();
        rmSync(directory, { recursive: true, force: true });
    }
});

test('the command timer requires a successful child, JSON, and a measurement', async () => {
    const outputDir = mkdtempSync(join(tmpdir(), 'ft-perf-timer-'));
    try {
        const options = { outputDir, timeoutMs: 5_000 };
        const report = await measureCommand(process.execPath, ['-e', 'console.log(JSON.stringify({ ok: true }))'], {
            ...options, label: 'success',
        });
        assert.deepEqual(report.value, { ok: true });
        assert.ok(report.ms > 0 && report.rssBytes > 0);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'process.exit(7)'], {
            ...options, label: 'exit',
        }), /code=7/);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'console.log("invalid JSON")'], {
            ...options, label: 'invalid-json',
        }), /JSON/);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
            ...options, label: 'timeout', timeoutMs: 10,
        }), /timed out/);
    } finally {
        rmSync(outputDir, { recursive: true, force: true });
    }
});

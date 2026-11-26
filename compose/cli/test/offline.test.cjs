const assert = require('node:assert/strict');
const { execFile, execFileSync } = require('node:child_process');
const { createServer } = require('node:http');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { once } = require('node:events');
const { test } = require('node:test');

const snapshot = require('../../client-ledger/src/jvmTest/resources/cash-operation-snapshot.json');
const expectedSummary = require('../../client-ledger/src/jvmTest/resources/cash-operation-summary.json');
const workspace = { id: 'workspace-a', name: 'Example', role: 'editor' };
const session = { user: { email: 'person@example.test' }, active_workspace_id: workspace.id, workspaces: [workspace] };

test('installed ft restores the original snapshot in a new process when disconnected', async () => {
    const directory = mkdtempSync(join(tmpdir(), 'ft-offline-'));
    let mode = 'online';
    let downloads = 0;
    let denialSent = false;
    const server = createServer(async (request, response) => {
        response.setHeader('Content-Type', 'application/json');
        const url = new URL(request.url, 'http://127.0.0.1');
        if (url.pathname === '/api/v1/auth/login') {
            let body = '';
            for await (const chunk of request) body += chunk;
            assert.deepEqual(JSON.parse(body), { email: 'person@example.test', password: 'synthetic-password' });
            response.end(JSON.stringify({ ...session, access_token: 'synthetic-token' }));
            return;
        }
        assert.equal(request.headers.authorization, 'Bearer synthetic-token');
        if (mode === 'logout-failure' && url.pathname === '/api/v1/auth/logout') {
            request.socket.end('DATA IS NOT HTTP\r\n');
            return;
        }
        if (mode === 'denied' && url.pathname === '/api/v1/ledger/version') {
            denialSent = true;
            response.writeHead(403).end('{"error":{"code":"workspace_forbidden"}}');
            return;
        }
        if (mode === 'denied' && denialSent && url.pathname === '/api/v1/auth/session') {
            request.socket.destroy();
            return;
        }
        if (mode === 'revoked') {
            response.writeHead(401).end('{"detail":"authentication_required"}');
            return;
        }
        if (mode === 'broken') {
            response.end('{');
            return;
        }
        if (url.pathname === '/api/v1/auth/session') response.end(JSON.stringify(session));
        else if (url.pathname === '/api/v1/auth/workspaces/workspace-a/select') response.end(JSON.stringify(session));
        else {
            assert.equal(url.searchParams.get('workspace_id'), workspace.id);
            if (url.pathname === '/api/v1/ledger/version') response.end(JSON.stringify({ ledger_seq: mode === 'regressed' ? '4' : snapshot.ledger_seq }));
            else if (url.pathname === '/api/v1/ledger/snapshot') {
                downloads++;
                response.end(JSON.stringify(mode === 'regressed' ? { ...snapshot, ledger_seq: '4' } : snapshot));
            } else response.writeHead(404).end('{}');
        }
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const port = server.address().port;
    const state = join(directory, 'state');
    const env = {
        ...process.env,
        FT_CLI_SERVER: `http://127.0.0.1:${port}`,
        FT_CLI_STATE_DIR: state,
        FT_CLI_STORAGE_KEY: Buffer.alloc(32, 123).toString('base64'),
    };
    try {
        const [archive] = JSON.parse(execFileSync('npm', [
            'pack', '--json', '--pack-destination', directory, resolve(__dirname, '../build/npm'),
        ], { encoding: 'utf8' }));
        execFileSync('npm', ['install', '--no-audit', '--no-fund', '--ignore-scripts', join(directory, archive.filename)], {
            cwd: directory, stdio: 'pipe',
        });
        const bin = join(directory, 'node_modules', '.bin', 'ft');
        const run = (args, input = '', override = {}) => new Promise((resolve, reject) => {
            const child = execFile(bin, [...args, '--json'], { cwd: directory, env: { ...env, ...override }, timeout: 10000 }, (error, stdout, stderr) => {
                if (error && typeof error.code !== 'number') return reject(error);
                try { resolve({ status: error?.code || 0, value: JSON.parse(stdout), stderr }); }
                catch (failure) { reject(new Error(`Invalid CLI response: ${stdout} ${stderr}`, { cause: failure })); }
            });
            child.stdin.end(input);
        });
        const ok = async (args, input) => {
            const response = await run(args, input);
            assert.equal(response.status, 0, JSON.stringify(response));
            assert.equal(response.stderr, '');
            return response.value;
        };
        const login = await ok(['auth', 'login', '--email', 'person@example.test', '--password-stdin'], 'synthetic-password\r\n');
        assert.doesNotMatch(JSON.stringify(login), /synthetic-token/);
        await ok(['workspace', 'use', workspace.id]);
        const online = await ok(['cash', 'list', '--workspace', workspace.id]);
        assert.equal(online.page.projection_version, snapshot.ledger_seq);
        const id = online.page.items.find((item) => item.projection_id === 'cash-entry:example-entry').projection_id;
        const evidence = await ok(['cash', 'show', id]);
        assert.equal(evidence.evidence.root_record.source_type, 'alipay');
        assert.equal(evidence.evidence.projection.record_id, '合成-交易号');
        const summary = await ok(['cash', 'summary', '--timezone', 'Asia/Shanghai']);
        assert.equal(summary.ledger_seq, snapshot.ledger_seq);
        assert.equal(summary.workspace_id, workspace.id);
        assert.equal(summary.filters.timezone, 'Asia/Shanghai');
        assert.deepEqual(summary.months, expectedSummary);
        assert.equal(downloads, 1);
        const disk = readFileSync(join(state, 'client.sqlite'));
        for (const secret of ['synthetic-token', 'person@example.test', '合成-交易号']) assert.equal(disk.includes(secret), false);

        mode = 'regressed';
        assert.equal((await run(['cash', 'list'])).value.error.code, 'protocol_error');
        mode = 'broken';
        const malformed = await run(['cash', 'list']);
        assert.notEqual(malformed.status, 0);
        assert.equal(malformed.value.error.code, 'protocol_error');
        mode = 'online';
        await new Promise((resolve) => server.close(resolve));
        const offline = await ok(['cash', 'list']);
        assert.equal(offline.offline, true);
        assert.equal(offline.synced_at, online.synced_at);
        assert.deepEqual(offline.page, online.page);
        assert.deepEqual((await ok(['cash', 'show', id])).evidence, evidence.evidence);
        assert.deepEqual((await ok(['cash', 'summary', '--timezone', 'Asia/Shanghai'])).months, summary.months);

        const file = resolve(__dirname, '../../backend/src/test/resources/cash-import-slice/alipay-01.csv');
        const prepared = await ok(['import', 'prepare', file, '--workspace', workspace.id]);
        assert.equal(prepared.files[0].status, 'parsed');
        const review = await ok(['import', 'review', prepared.batch_id, '--workspace', workspace.id]);
        assert.equal(review.session_id, prepared.batch_id);
        assert.equal(review.workspace_id, workspace.id);
        assert.equal(review.ledger_seq, snapshot.ledger_seq);
        assert.ok(review.file_results[0].recognized_row_count > 0);
        assert.deepEqual(await ok(['import', 'review', prepared.batch_id, '--workspace', workspace.id]), review);

        const wrongKey = await run(['cash', 'list'], '', { FT_CLI_STORAGE_KEY: Buffer.alloc(32, 1).toString('base64') });
        assert.notEqual(wrongKey.status, 0);
        assert.equal(wrongKey.value.error.code, 'local_state_unreadable');
        const wrongWorkspace = await run(['cash', 'list', '--workspace', 'workspace-b']);
        assert.equal(wrongWorkspace.value.error.code, 'workspace_conflict');

        mode = 'revoked';
        server.listen(port, '127.0.0.1');
        await once(server, 'listening');
        assert.equal((await run(['cash', 'list'])).value.error.code, 'authentication_required');
        await new Promise((resolve) => server.close(resolve));
        assert.equal((await run(['cash', 'list'])).value.error.code, 'authentication_required');

        mode = 'online';
        server.listen(port, '127.0.0.1');
        await once(server, 'listening');
        await ok(['auth', 'login', '--email', 'person@example.test', '--password-stdin'], 'synthetic-password');
        await ok(['cash', 'list']);
        mode = 'logout-failure';
        assert.notEqual((await run(['auth', 'logout'])).status, 0);
        await new Promise((resolve) => server.close(resolve));
        assert.equal((await run(['cash', 'list'])).value.error.code, 'authentication_required');

        mode = 'online';
        server.listen(port, '127.0.0.1');
        await once(server, 'listening');
        await ok(['auth', 'login', '--email', 'person@example.test', '--password-stdin'], 'synthetic-password');
        await ok(['cash', 'list']);
        mode = 'denied';
        assert.equal((await run(['cash', 'list'])).value.error.code, 'workspace_forbidden');
        await new Promise((resolve) => server.close(resolve));
        assert.equal((await run(['cash', 'list'])).value.error.code, 'offline_sync_required');
    } finally {
        if (server.listening) await new Promise((resolve) => server.close(resolve));
        rmSync(directory, { recursive: true, force: true });
    }
});

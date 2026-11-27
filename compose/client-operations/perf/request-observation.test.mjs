import { spawn } from 'node:child_process';
import { strict as assert } from 'node:assert';
import { once } from 'node:events';
import { createServer, request } from 'node:http';
import { test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { instrumentClientRequests } from './client-request-clock.mjs';
import { createIsolatedRssRecorder, joinClientRequestClocks, observeProxyRequest } from './request-observation.mjs';
import { startPeakRss } from './peak-rss.mjs';

async function listen(t, handler) {
    const server = createServer(handler);
    t.after(() => {
        server.closeAllConnections();
        return new Promise((done) => server.close(done));
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return new URL(`http://127.0.0.1:${server.address().port}`);
}

test('records gzip wire bytes in both directions and decoded bytes after each body is read', async (t) => {
    const requestPayload = Buffer.alloc(2048, 'a');
    const responsePayload = Buffer.alloc(4096, 'b');
    const compressedRequest = gzipSync(requestPayload);
    const compressedReply = gzipSync(responsePayload);
    const origin = await listen(t, async (incoming, outgoing) => {
        incoming.resume();
        await once(incoming, 'end');
        outgoing.writeHead(202, { 'Content-Encoding': 'gzip' });
        outgoing.end(compressedReply);
    });
    let sample;
    const proxy = await listen(t, (incoming, outgoing) => {
        const observer = observeProxyRequest(incoming, { flow: 'prepare', route: 'parser' });
        const headers = { ...incoming.headers };
        delete headers['x-ft-performance-request-sequence'];
        const upstream = request(origin, { method: incoming.method, headers }, (response) => {
            sample = observer.response(response);
            outgoing.writeHead(response.statusCode, response.headers);
            response.pipe(outgoing);
        });
        observer.startServerWindow();
        incoming.pipe(upstream);
    });
    const clocks = [];
    const client = instrumentClientRequests(fetch, { origin: proxy.origin, record: (event) => clocks.push(event) });
    const response = await client(proxy, {
        method: 'POST', headers: { 'Content-Encoding': 'gzip' }, body: compressedRequest,
    });
    assert.equal(clocks.length, 0);
    assert.equal((await response.text()).length, responsePayload.length);
    const [metrics] = joinClientRequestClocks([await sample], clocks);
    assert.equal(metrics.status, 202);
    assert.equal(metrics.requestWireBytes, compressedRequest.length);
    assert.equal(metrics.requestDecodedBytes, requestPayload.length);
    assert.equal(metrics.responseWireBytes, compressedReply.length);
    assert.equal(metrics.responseDecodedBytes, responsePayload.length);
    assert.ok(metrics.serverMs > 0 && metrics.clientMs > 0);
    assert.throws(() => joinClientRequestClocks([metrics], []), /counts differ/);
});

test('records a paged response only after its streaming reader reaches the end', async (t) => {
    const origin = await listen(t, (_incoming, outgoing) => {
        outgoing.write('{"page":');
        outgoing.end('true}');
    });
    const clocks = [];
    const client = instrumentClientRequests(fetch, {
        origin: origin.origin, record: event => clocks.push(event),
    });
    const response = await client(origin, {});
    const decoder = new TextDecoder();
    const reader = response.body.getReader();
    let body = '';
    for (;;) {
        assert.equal(clocks.length, 0);
        const part = await reader.read();
        if (part.done) break;
        body += decoder.decode(part.value, { stream: true });
    }
    assert.deepEqual(JSON.parse(body + decoder.decode()), { page: true });
    assert.equal(clocks.length, 1);
    assert.ok(clocks[0].clientMs > 0);
});

test('records process high-water mark across a synchronous allocation with no timer tick', () => {
    const recording = startPeakRss();
    const before = recording.readPeakBytes();
    const bytes = Buffer.alloc(32 * 1024 ** 2, 42);
    const after = recording.readPeakBytes();
    assert.ok(before > 0 && after >= before);
    if (process.platform === 'linux') assert.ok(after > before);
    assert.equal(bytes.at(-1), 42);
});

test('isolated replay records a real backend process peak without a timer tick', {
    skip: process.platform !== 'linux',
}, async (t) => {
    const child = spawn(process.execPath, ['-e', `
        const http = require("node:http");
        http.createServer((_, response) => {
            const buffer = Buffer.alloc(32 * 1024 ** 2, 42);
            response.end(String(buffer.at(-1)));
        }).listen(0, "127.0.0.1", function () {
            console.log(this.address().port);
        });
    `], { stdio: ['ignore', 'pipe', 'inherit'] });
    t.after(() => child.kill());
    const [line] = await once(child.stdout, 'data');
    const url = `http://127.0.0.1:${Number(line.toString().trim())}`;
    const measure = createIsolatedRssRecorder(child.pid);
    const sample = await measure(async () => {
        const response = await fetch(url);
        assert.equal(response.status, 200);
        assert.equal(await response.text(), '42');
        return { asserted: true };
    });
    assert.equal(sample.method, 'linux-vmhwm');
    assert.equal(sample.asserted, true);
    assert.ok(sample.peakServiceBytes - sample.baselineServiceBytes >= 16 * 1024 ** 2);
});

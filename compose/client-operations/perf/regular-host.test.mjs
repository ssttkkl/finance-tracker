import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { Agent, get } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { startRegularHost } from './regular-host.mjs';

test('the ordinary benchmark proxy does not retain a client connection across operations', async () => {
    const directory = mkdtempSync(join(tmpdir(), 'ft-ordinary-proxy-'));
    const executable = join(directory, 'backend.cjs');
    const previousBackend = process.env.FT_PERF_KTOR;
    const previousArtifacts = process.env.FT_PERF_ARTIFACTS;
    process.env.FT_PERF_KTOR = executable;
    process.env.FT_PERF_ARTIFACTS = directory;
    writeFileSync(executable, `#!${process.execPath}
        require('node:http').createServer((_, response) => response.end('ready'))
            .listen(Number(process.env.PORT), '127.0.0.1');\n`, { mode: 0o700 });
    const agent = new Agent({ keepAlive: true });
    let host;
    try {
        host = await startRegularHost();
        const request = (pathname) => new Promise((resolve, reject) => {
            const client = get(host.origin + pathname, { agent }, (response) => {
                response.resume();
                response.on('error', reject);
                response.on('end', () => resolve({ connection: response.headers.connection, reused: client.reusedSocket }));
            });
            client.on('error', reject);
        });
        assert.deepEqual(await request('/first'), { connection: 'close', reused: false });
        assert.deepEqual(await request('/next'), { connection: 'close', reused: false });
        assert.equal(host.requestCount(0, 'GET', '/first'), 1);
        assert.equal(host.requestCount(0, 'GET', '/next'), 1);
    } finally {
        agent.destroy();
        try { await host?.close(); } finally {
            if (previousBackend === undefined) delete process.env.FT_PERF_KTOR;
            else process.env.FT_PERF_KTOR = previousBackend;
            if (previousArtifacts === undefined) delete process.env.FT_PERF_ARTIFACTS;
            else process.env.FT_PERF_ARTIFACTS = previousArtifacts;
            rmSync(directory, { recursive: true, force: true });
        }
    }
});

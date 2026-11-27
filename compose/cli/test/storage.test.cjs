const assert = require('node:assert/strict');
const { mkdtempSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { DatabaseSync } = require('node:sqlite');
const { test } = require('node:test');

test('encrypted state avoids full-buffer copies and still rejects a modified tag', async () => {
    const root = mkdtempSync(join(tmpdir(), 'ft-encrypted-storage-'));
    const originalRoot = process.env.FT_CLI_STATE_DIR;
    const originalKey = process.env.FT_CLI_STORAGE_KEY;
    process.env.FT_CLI_STATE_DIR = root;
    const key = Buffer.alloc(32, 71).toString('base64');
    process.env.FT_CLI_STORAGE_KEY = key;
    const { openStore } = await import('../npm/lib/host.mjs');
    const server = 'https://storage.test';
    const bytes = 2 * 1024 ** 2;
    const data = 'a'.repeat(bytes);
    const concat = Buffer.concat;
    const from = Buffer.from;
    let intermediateCopies = 0;
    const store = openStore(server, true);
    try {
        Buffer.concat = function (...args) {
            const buffer = concat.apply(this, args);
            if (buffer.byteLength === bytes) intermediateCopies++;
            return buffer;
        };
        Buffer.from = function (...args) {
            if (ArrayBuffer.isView(args[0]) && args[0].byteLength === bytes + 28) intermediateCopies++;
            return from.apply(this, args);
        };
        try {
            store.write('operation', data);
            assert.equal(store.read('operation'), data);
            const stamp = store.stateStamp('operation');
            assert.equal(store.stateStamp('operation'), stamp);
            store.write('operation', data);
            assert.notEqual(store.stateStamp('operation'), stamp);
            const version = store.dataVersion();
            store.checkpoint();
            assert.equal(store.dataVersion(), version);

            const exec = DatabaseSync.prototype.exec;
            DatabaseSync.prototype.exec = function (sql) {
                if (sql !== 'COMMIT; BEGIN IMMEDIATE') return exec.call(this, sql);
                exec.call(this, 'COMMIT');
                const peer = new DatabaseSync(join(root, 'client.sqlite'));
                try {
                    peer.prepare('INSERT INTO client_state SELECT server, slot || ?, wrapped_key, payload FROM client_state WHERE slot = ?')
                        .run('.other', 'operation');
                } finally {
                    peer.close();
                    exec.call(this, 'BEGIN IMMEDIATE');
                }
            };
            try {
                const observed = store.stateStamp('operation');
                store.checkpoint();
                assert.notEqual(store.dataVersion(), version);
                assert.equal(store.stateStamp('operation'), observed);
            } finally {
                DatabaseSync.prototype.exec = exec;
            }
        } finally {
            Buffer.concat = concat;
            Buffer.from = from;
            store.close(true);
        }

        const database = new DatabaseSync(join(root, 'client.sqlite'));
        try {
            const { payload } = database.prepare('SELECT payload FROM client_state WHERE slot = ?').get('operation');
            payload[12] ^= 1;
            database.prepare('UPDATE client_state SET payload = ? WHERE slot = ?').run(payload, 'operation');
        } finally {
            database.close();
        }
        process.env.FT_CLI_STORAGE_KEY = key;
        const reopened = openStore(server, false);
        assert.equal(reopened.error, undefined);
        try {
            assert.throws(() => reopened.read('operation'));
        } finally {
            reopened.close(false);
        }
        assert.equal(intermediateCopies, 0);
    } finally {
        if (originalRoot === undefined) delete process.env.FT_CLI_STATE_DIR;
        else process.env.FT_CLI_STATE_DIR = originalRoot;
        if (originalKey === undefined) delete process.env.FT_CLI_STORAGE_KEY;
        else process.env.FT_CLI_STORAGE_KEY = originalKey;
        rmSync(root, { recursive: true, force: true });
    }
});

test('large state writes preserve UTF-8 boundaries without whole plaintext buffers', async () => {
    const root = mkdtempSync(join(tmpdir(), 'ft-encrypted-text-'));
    const previousRoot = process.env.FT_CLI_STATE_DIR;
    const previousKey = process.env.FT_CLI_STORAGE_KEY;
    process.env.FT_CLI_STATE_DIR = root;
    process.env.FT_CLI_STORAGE_KEY = Buffer.alloc(32, 71).toString('base64');
    const { openStore } = await import('../npm/lib/host.mjs');
    const text = `${'a'.repeat(64 * 1024 - 1)}𐐷${'人民币'.repeat(128 * 1024)}`;
    const from = Buffer.from;
    const concat = Buffer.concat;
    let fullCopies = 0;
    const store = openStore('https://storage.test', true);
    try {
        Buffer.from = function (...args) {
            if (typeof args[0] === 'string' && args[0].length >= text.length) fullCopies++;
            return from.apply(this, args);
        };
        Buffer.concat = function (...args) {
            const buffer = concat.apply(this, args);
            if (buffer.byteLength >= Buffer.byteLength(text)) fullCopies++;
            return buffer;
        };
        store.write('large', text);
        assert.equal(store.read('large'), text);
        assert.equal(fullCopies, 0);
    } finally {
        Buffer.from = from;
        Buffer.concat = concat;
        try { store.close(true); } finally {
            if (previousRoot === undefined) delete process.env.FT_CLI_STATE_DIR;
            else process.env.FT_CLI_STATE_DIR = previousRoot;
            if (previousKey === undefined) delete process.env.FT_CLI_STORAGE_KEY;
            else process.env.FT_CLI_STORAGE_KEY = previousKey;
            rmSync(root, { recursive: true, force: true });
        }
    }
});

test('the frozen request is stored byte for byte without one large JSON encoding', async () => {
    const root = mkdtempSync(join(tmpdir(), 'ft-frozen-state-text-'));
    const previousRoot = process.env.FT_CLI_STATE_DIR;
    const previousKey = process.env.FT_CLI_STORAGE_KEY;
    process.env.FT_CLI_STATE_DIR = root;
    process.env.FT_CLI_STORAGE_KEY = Buffer.alloc(32, 71).toString('base64');
    const { openStore } = await import('../npm/lib/host.mjs');
    const controls = String.fromCharCode(...Array.from({ length: 32 }, (_, index) => index));
    const request = `${'a'.repeat(64 * 1024 - 1)}𐐷${'中文"\\\t\u0000'.repeat(260_000)}${controls}\udc00x\ud800`;
    const metadata = '{"revision":9223372036854775807,"submission":{"key":"synthetic","request":"","baseRejected":false}}';
    const expected = metadata.replace('""', JSON.stringify(request));
    const stringify = JSON.stringify;
    let fullEncodings = 0;
    let chunkEncodings = 0;
    const store = openStore('https://storage.test', true);
    try {
        JSON.stringify = function (...args) {
            const encoded = stringify.apply(this, args);
            if (encoded?.length >= request.length) fullEncodings++;
            if (typeof args[0] === 'string' && args[0].length >= 64 * 1024 - 1) chunkEncodings++;
            return encoded;
        };
        store.writeFrozenImportBatch('frozen', metadata, request);
        assert.equal(store.read('frozen') === expected, true);
        assert.equal(fullEncodings, 0);
        assert.ok(chunkEncodings <= Math.ceil(request.length / (64 * 1024 - 1)));
        assert.throws(() => store.writeFrozenImportBatch('frozen', metadata.replace('""', '"unexpected"'), request));
        assert.equal(store.read('frozen') === expected, true);
        const collision = metadata.replace('"submission":', '"decoy":"\\u0000ft.import.request","submission":');
        store.writeFrozenImportBatch('collision', collision, request);
        assert.equal(store.read('collision') === collision.replace('""', stringify(request)), true);
    } finally {
        JSON.stringify = stringify;
        try { store.close(true); } finally {
            if (previousRoot === undefined) delete process.env.FT_CLI_STATE_DIR;
            else process.env.FT_CLI_STATE_DIR = previousRoot;
            if (previousKey === undefined) delete process.env.FT_CLI_STORAGE_KEY;
            else process.env.FT_CLI_STORAGE_KEY = previousKey;
            rmSync(root, { recursive: true, force: true });
        }
    }
});

test('large import text round-trips through the old encrypted state JSON shape', async () => {
    const { splitLargeImportRequest } = await import('../npm/lib/host.mjs');
    const request = JSON.stringify({ amount: '0.10', currency: 'USD', opaque: '说明😀"\\'.repeat(180_000) });
    const threshold = 1024 * 1024;
    const metadata = '{"revision":9223372036854775807,"submission":{"key":"synthetic","request":"","baseRejected":false}}';
    const frozen = metadata.replace('""', JSON.stringify(request));
    assert.deepEqual(splitLargeImportRequest(frozen, 'submission', threshold), { metadata, request });
    const reviewed = '{"revision":9223372036854775807,"command":{"request":' + JSON.stringify(request) + '}}';
    assert.equal(splitLargeImportRequest(reviewed, 'command', threshold).request, request);
    assert.equal(splitLargeImportRequest(frozen, 'command', threshold), null);
    assert.equal(splitLargeImportRequest('{"result":' + JSON.stringify(request) + '}', 'submission', threshold), null);
    assert.equal(splitLargeImportRequest(frozen.slice(0, -1), 'submission', threshold), null);
    const duplicateRevision = frozen.replace('"revision":', '"revision":"malformed","revision":');
    const duplicateRequest = frozen.replace('"request":', '"request":1,"request":');
    const escapedDuplicateRequest = frozen.replace('"request":', '"reques\\u0074":1,"request":');
    assert.equal(splitLargeImportRequest(duplicateRevision, 'submission', threshold), null);
    assert.equal(splitLargeImportRequest(duplicateRequest, 'submission', threshold), null);
    assert.equal(splitLargeImportRequest(escapedDuplicateRequest, 'submission', threshold), null);
});

test('paged HTTP responses bound the body and decode split UTF-8 without treating network loss as a page', async () => {
    const { createServer } = require('node:http');
    const { once } = require('node:events');
    const { gzipSync } = require('node:zlib');
    const { request } = await import('../build/npm/lib/host.mjs');
    const value = Buffer.from('{"page":"账😀"}');
    const zipped = gzipSync(value);
    const expansion = gzipSync(Buffer.alloc(2048, 97));
    const server = createServer((incoming, response) => {
        if (incoming.url === '/identity') {
            response.setHeader('X-FT-Read-Pages', '1');
            response.write(value.subarray(0, 11));
            response.end(value.subarray(11));
        } else if (incoming.url === '/oversize') {
            response.setHeader('Content-Length', value.length);
            response.end(value);
        } else if (incoming.url === '/oversize-without-length') {
            response.write(value);
            response.end(value);
        } else if (incoming.url === '/gzip') {
            response.writeHead(200, { 'Content-Encoding': 'gzip', 'Content-Length': zipped.length });
            response.end(zipped);
        } else if (incoming.url === '/gzip-no-length') {
            response.writeHead(200, { 'Content-Encoding': 'gzip' });
            response.write(zipped);
            response.end();
        } else if (incoming.url === '/gzip-expansion') {
            response.writeHead(200, { 'Content-Encoding': 'gzip', 'Content-Length': expansion.length });
            response.end(expansion);
        } else if (incoming.url === '/invalid-utf8') {
            response.end(Buffer.from([0xc3, 0x28]));
        } else if (incoming.url === '/denied') {
            response.writeHead(403, { 'Content-Length': value.length });
            response.end(value);
        } else if (incoming.url === '/dropped') {
            response.writeHead(200, { 'Content-Length': value.length });
            response.write(value.subarray(0, 4), () => response.destroy());
        }
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const origin = `http://127.0.0.1:${server.address().port}`;
    async function send(path, limit) { return request(origin, 'GET', path, null, null, {}, limit); }
    try {
        const valid = await send('/identity', value.length);
        assert.equal(valid.body, value.toString());
        assert.equal(valid.readPages, '1');
        assert.equal((await send('/gzip', zipped.length)).body, value.toString());
        for (const [path, limit] of [
            ['/oversize', value.length - 1],
            ['/oversize-without-length', value.length],
            ['/gzip', value.length],
            ['/gzip-no-length', zipped.length],
            ['/gzip-expansion', expansion.length + 1],
            ['/invalid-utf8', value.length],
        ]) assert.equal((await send(path, limit)).status, -2, path);
        assert.equal((await send('/denied', 1)).status, 403);
        assert.equal((await send('/dropped', value.length)).status, 0);
    } finally {
        server.closeAllConnections();
        await new Promise((done) => server.close(done));
    }
});

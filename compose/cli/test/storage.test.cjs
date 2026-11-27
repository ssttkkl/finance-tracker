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

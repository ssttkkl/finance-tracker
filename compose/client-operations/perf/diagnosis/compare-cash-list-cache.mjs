import assert from 'node:assert/strict';
import { once } from 'node:events';
import { cpSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { cashListFixture, withTransfer } from './cash-list-cache-fixture.mjs';
import { measureCommand } from '../large-measure.mjs';

const [installDir, report] = process.argv.slice(2);
assert(installDir && report && process.env.FT_DIAGNOSTIC_SQLITE_EXTENSION);
const root = mkdtempSync(join(tmpdir(), 'private-ft-derived-cache-'));
const installed = await import(pathToFileURL(resolve(installDir, 'node_modules/finance-tracker-cli/lib/host.mjs')));
const ft = resolve(installDir, 'node_modules/.bin/ft');
const old = cashListFixture();
const email = 'cash-phase@example.invalid';
const token = 'synthetic-offline-fixture';
const session = { user: { email }, active_workspace_id: old.workspace_id,
    workspaces: [{ id: old.workspace_id, name: 'Synthetic', role: 'editor' }] };
const key = Buffer.alloc(32, 138).toString('base64');
const reportData = { samples: [], checks: {} };
let network = 'offline';
let snapshot = old;
let version = old.ledger_seq;
let requests = [];
let onlineSession = session;
const api = createServer((request, response) => {
    if (network === 'offline') {
        request.socket.destroy();
        return;
    }
    if (request.headers.authorization !== 'Bearer ' + token) {
        response.writeHead(401).end();
        return;
    }
    const path = new URL(request.url, 'http://127.0.0.1').pathname;
    requests.push(path);
    response.setHeader('Content-Type', 'application/json');
    if (path === '/api/v1/auth/session') response.end(JSON.stringify(onlineSession));
    else if (path === '/api/v1/ledger/version') response.end(JSON.stringify({ ledger_seq: version }));
    else if (path === '/api/v1/ledger/snapshot') response.end(JSON.stringify(snapshot));
    else response.writeHead(404).end();
});

try {
    api.listen(0, '127.0.0.1');
    await once(api, 'listening');
    const origin = 'http://127.0.0.1:' + api.address().port;
    const offline = join(root, 'original');
    const env = { ...process.env, FT_CLI_SERVER: origin, FT_CLI_STORAGE_KEY: key };
    const dbFile = path => join(path, 'client.sqlite');
    function writeLedger(directory, value) {
        process.env.FT_CLI_STATE_DIR = directory;
        process.env.FT_CLI_STORAGE_KEY = key;
        const store = installed.openStore(origin, true);
        let committed = false;
        try {
            assert(!store.error);
            store.write('session', JSON.stringify({ token, session }));
            const slot = store.workspaceSlot(token, email, value.workspace_id);
            const stored = JSON.stringify({ generation: 'synthetic', ledger: {
                snapshot: value, synced_at: value.facts.cash_transactions[0].created_at,
            } });
            store.write(slot, stored);
            committed = true;
            return Buffer.byteLength(stored);
        } finally {
            store.close(committed);
        }
    }

    async function run(label, state, mode, options, expectedError) {
        requests = [];
        const trace = join(root, label + '-trace.jsonl');
        const args = ['cash', 'list', '--timezone', 'UTC', '--limit', '20', ...options, '--json'];
        const before = statSync(dbFile(state)).size;
        let value;
        let measured;
        try {
            measured = await measureCommand(ft, args, {
                env: { ...env, FT_CLI_STATE_DIR: state, FT_DIAGNOSTIC_CASH_PHASES: trace,
                    ...(mode ? { FT_DIAGNOSTIC_CASH_LIST: mode } : {}) },
                timeoutMs: 30_000, outputDir: root, label,
            });
            value = measured.value;
            assert(!expectedError, 'Expected an error from the synthetic corruption fixture');
        } catch (failure) {
            if (!expectedError || !failure.measurement) throw failure;
            const output = JSON.parse(readFileSync(join(root, label + '.json'), 'utf8'));
            assert.equal(output.error?.code, expectedError);
            measured = failure.measurement;
        }
        let phases = [];
        try { phases = readFileSync(trace, 'utf8').split('\n').filter(Boolean).map(line => JSON.parse(line)); }
        catch (failure) { if (!expectedError || failure.code !== 'ENOENT') throw failure; }
        const sum = (...names) => phases.filter(sample => names.includes(sample.phase))
            .reduce((total, sample) => total + (sample.count ?? 0), 0);
        const from = phases.find(sample => sample.phase === 'diagnostic_list_build_start');
        const to = phases.findLast(sample => sample.phase === 'diagnostic_list_build_ready');
        const entry = {
            label, mode: mode || 'base', ms: measured.ms, rssBytes: measured.rssBytes,
            originalCiphertextBytes: sum('cache_blob_loaded', 'diagnostic_source_ciphertext_read'),
            fullOriginalDecodes: phases.filter(sample => sample.phase === 'ledger_cache_decoded').length,
            directoryBytes: sum('diagnostic_read_directory'),
            columnBytes: sum('diagnostic_read_columns'),
            detailBytes: sum('diagnostic_read_details'),
            detailBlocks: phases.filter(sample => sample.phase === 'diagnostic_read_details').length,
            ciphertextBytesWritten: sum('diagnostic_block_written'),
            sourceStreamReads: phases.filter(sample => sample.phase === 'diagnostic_source_authenticated').length,
            projectionBuildMs: from && to ? to.timeMs - from.timeMs : null,
            hit: phases.some(sample => sample.phase === 'diagnostic_list_hit'),
            databaseBytesBefore: before, databaseBytesAfter: statSync(dbFile(state)).size,
            physicalReadBytes: phases.at(-1)?.physicalReadBytes ?? null,
            requests: [...requests], ...(expectedError ? { error: expectedError } : {}),
        };
        reportData.samples.push(entry);
        return { value, entry };
    }

    const parity = (left, right) => assert(isDeepStrictEqual(left, right), 'CLI result or exact aggregate changed');
    function tamper(path, slotCondition) {
        const db = new DatabaseSync(dbFile(path));
        try {
            const row = db.prepare('SELECT rowid, payload FROM client_state WHERE ' + slotCondition + ' LIMIT 1').get();
            assert(row, 'Expected the synthetic encrypted slot');
            const buffer = Buffer.from(row.payload);
            buffer[buffer.length - 1] ^= 1;
            db.prepare('UPDATE client_state SET payload = ? WHERE rowid = ?').run(buffer, row.rowid);
        } finally {
            db.close();
        }
    }

    reportData.fixturePlainBytes = writeLedger(offline, old);
    const first = await run('first-cold-base', offline, null, []);
    const initialized = await run('first-build', offline, 'build', []);
    parity(first.value, initialized.value);
    assert(initialized.entry.projectionBuildMs !== null && initialized.entry.ciphertextBytesWritten > 0);

    const cases = [
        ['first', []],
        ['keyword-next', ['--keyword', 'orchard', '--start-index', '20']],
        ['pinned-next', ['--keyword', 'orchard', '--start-index', '20', '--ledger-seq', old.ledger_seq]],
        ['cursor-next', ['--cursor', first.value.page.next_cursor]],
    ];
    for (const [name, options] of cases) {
        const base = await run(name + '-base', offline, null, options);
        const hit = await run(name + '-strict', offline, 'strict', options);
        parity(base.value, hit.value);
        assert(hit.entry.hit && hit.entry.fullOriginalDecodes === 0 && hit.entry.sourceStreamReads === 1);
        assert.equal(hit.entry.originalCiphertextBytes, base.entry.originalCiphertextBytes);
        assert(hit.entry.detailBlocks > 0 && hit.entry.detailBlocks < 16 && hit.entry.columnBytes > 0);
    }
    for (const [mode, suffix] of [['strict', 'repeat'], ['fast', 'upper-bound']]) {
        const hit = await run('first-' + suffix, offline, mode, []);
        parity(first.value, hit.value);
        assert(hit.entry.hit);
        assert.equal(hit.entry.originalCiphertextBytes > 0, mode === 'strict');
    }

    const firstRepeat = await run('first-base-repeat', offline, null, []);
    parity(first.value, firstRepeat.value);

    const mixed = join(root, 'mixed');
    writeLedger(mixed, withTransfer(old));
    const transferOptions = ['--account', '20', '--hide-zero-amount', 'false', '--start-index', '0'];
    const transfer = await run('transfer-base', mixed, null, transferOptions);
    assert.equal(transfer.value.page.total_count, 1);
    assert(transfer.value.page.items[0].transfer && transfer.value.page.items[0].composition.includes('transfer_pair'));
    const transferBuilt = await run('transfer-build', mixed, 'build', transferOptions);
    const transferHit = await run('transfer-strict', mixed, 'strict', transferOptions);
    parity(transfer.value, transferBuilt.value);
    parity(transfer.value, transferHit.value);
    assert(transferHit.entry.hit);

    const deriveBad = join(root, 'derived-corrupt');
    cpSync(offline, deriveBad, { recursive: true });
    tamper(deriveBad, "slot LIKE '%.diagnostic-cash-list.%.details.0'");
    const repaired = await run('selected-block-corruption', deriveBad, 'strict', []);
    parity(first.value, repaired.value);
    assert(!repaired.entry.hit && repaired.entry.projectionBuildMs !== null);

    const sourceBad = join(root, 'original-corrupt');
    cpSync(offline, sourceBad, { recursive: true });
    tamper(sourceBad, 'length(slot) = 64');
    await run('original-corruption-base', sourceBad, null, [], 'local_state_unreadable');
    await run('original-corruption-strict', sourceBad, 'strict', [], 'local_state_unreadable');
    const onlyDirectory = await run('original-corruption-fast', sourceBad, 'fast', []);
    assert(onlyDirectory.entry.hit && onlyDirectory.entry.originalCiphertextBytes === 0);
    parity(first.value, onlyDirectory.value);
    reportData.checks.fastMissesOriginalCiphertextTamper = true;

    const refreshBase = join(root, 'refresh-base');
    const refreshDerived = join(root, 'refresh-derived');
    cpSync(offline, refreshBase, { recursive: true });
    cpSync(offline, refreshDerived, { recursive: true });
    network = 'online';
    const online = await run('same-version-strict', refreshDerived, 'strict', []);
    assert(online.entry.hit && online.value.offline === false);
    assert(!online.entry.requests.includes('/api/v1/ledger/snapshot'));
    version = String(BigInt(old.ledger_seq) + 1n);
    await run('failed-new-version', refreshDerived, 'strict', [], 'protocol_error');
    network = 'offline';
    const recovered = await run('offline-after-failed-version', refreshDerived, 'strict', []);
    assert(recovered.entry.hit);
    parity(first.value, recovered.value);
    network = 'online';
    snapshot = structuredClone(old);
    snapshot.ledger_seq = version;
    snapshot.facts.cash_entries.find(entry => entry.id === first.value.page.items[0].entry_id).note = 'Synthetic edit';
    const changedBase = await run('refresh-base', refreshBase, null, []);
    const changedDerived = await run('refresh-build', refreshDerived, 'strict', []);
    const untimed = ({ synced_at, ...rest }) => rest;
    parity(untimed(changedBase.value), untimed(changedDerived.value));
    assert.equal(changedDerived.value.ledger_seq, version);
    assert.equal(changedDerived.value.page.items[0].note, 'Synthetic edit');
    assert(!changedDerived.entry.hit && changedDerived.entry.projectionBuildMs !== null);
    network = 'offline';
    const after = await run('new-version-offline-hit', refreshDerived, 'strict', []);
    parity({ ...changedDerived.value, offline: true }, after.value);
    assert(after.entry.hit && after.entry.sourceStreamReads === 1);
    const otherUser = join(root, 'other-user');
    cpSync(offline, otherUser, { recursive: true });
    process.env.FT_CLI_STATE_DIR = otherUser;
    process.env.FT_CLI_STORAGE_KEY = key;
    const otherStore = installed.openStore(origin, false);
    try {
        otherStore.write('session', JSON.stringify({ token, session: { ...session, user: { email: 'other@example.invalid' } } }));
    } finally {
        otherStore.close(true);
    }
    await run('other-user-same-workspace', otherUser, 'strict', [], 'offline_sync_required');
    network = 'online';
    onlineSession = { ...session, active_workspace_id: null, workspaces: [] };
    await run('online-revocation', refreshDerived, 'strict', [], 'workspace_forbidden');
    reportData.checks.strictParity = true;
    reportData.checks.failedRefreshKeptOriginal = true;
    reportData.checks.selectedBlockCorruptionRebuilt = true;
    reportData.checks.transferAndScopeParity = true;
} finally {
    writeFileSync(report, JSON.stringify(reportData, null, 2));
    await new Promise(resolve => api.close(resolve));
    rmSync(root, { recursive: true, force: true });
}

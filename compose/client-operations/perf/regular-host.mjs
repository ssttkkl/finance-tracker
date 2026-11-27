import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { appendFileSync, closeSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer, request } from 'node:http';
import { once } from 'node:events';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { DatabaseSync } from 'node:sqlite';
import { setTimeout as sleep } from 'node:timers/promises';
import { seed, seedDeletion, deleteDeletionRemainder, restoreBalances, restoreMember, rowCount } from './regular-fixture.mjs';
import { startPeakRss } from './peak-rss.mjs';
import { forwardedLoopbackHeaders } from './loopback-headers.mjs';

async function listen(server) {
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}

async function reservePort() {
    const reservation = createServer();
    const port = await listen(reservation);
    await new Promise((done) => reservation.close(done));
    return port;
}

export async function startRegularHost() {
    const root = mkdtempSync(join(tmpdir(), 'ft-operations-perf-'));
    const state = join(root, 'state');
    const databasePath = join(root, 'ledger_test.db');
    const logPath = join(process.env.FT_PERF_ARTIFACTS ?? root, 'regular-backend.log');
    const backendPort = await reservePort();
    const output = openSync(logPath, 'w');
    const backend = spawn(process.env.FT_PERF_KTOR ?? resolve('compose/backend/build/install/backend/bin/backend'), [], {
        env: {
            ...process.env,
            PORT: String(backendPort),
            FT_HOST: '127.0.0.1',
            FT_WEB_ORIGIN: 'http://127.0.0.1:5173',
            FT_DATABASE_URL: `sqlite+pysqlite:///${databasePath}`,
            FT_IMPORT_STAGING_BACKEND: 'memory',
            FT_IMPORT_STAGING_ALLOW_MEMORY: '1',
        },
        stdio: ['ignore', output, output],
    });
    closeSync(output);
    let startupFailure;
    backend.once('error', (error) => { startupFailure = error; });
    const requests = [];
    let dropResponse;
    const proxy = createServer((incoming, outgoing) => {
        const entry = { method: incoming.method, path: incoming.url };
        requests.push(entry);
        const upstream = request({
            hostname: '127.0.0.1', port: backendPort, path: incoming.url, method: incoming.method,
            agent: false,
            headers: { ...forwardedLoopbackHeaders(incoming.headers), host: `127.0.0.1:${backendPort}` },
        }, (response) => {
            entry.status = response.statusCode;
            entry.requestId = response.headers['x-request-id'];
            if (dropResponse === incoming.method + ' ' + incoming.url.split('?')[0] && entry.status >= 200 && entry.status < 300) {
                dropResponse = undefined;
                response.resume();
                response.once('end', () => incoming.socket.destroy());
                return;
            }
            outgoing.writeHead(response.statusCode, forwardedLoopbackHeaders(response.headers));
            response.pipe(outgoing);
        });
        upstream.on('error', (error) => outgoing.destroy(error));
        incoming.on('error', (error) => upstream.destroy(error));
        incoming.pipe(upstream);
    });
    // A shared Kotlin/JS computation can block the same event loop for seconds.
    // Keep the client socket usable throughout an ordinary review.
    proxy.keepAliveTimeout = 120_000;
    try {
        for (let attempt = 0; attempt < 150; attempt++) {
            if (startupFailure) throw startupFailure;
            if (backend.exitCode !== null) throw new Error(readFileSync(logPath, 'utf8'));
            if (await fetch(`http://127.0.0.1:${backendPort}/health`).then((response) => response.ok, () => false)) break;
            if (attempt === 149) throw new Error('Performance backend did not become healthy');
            await sleep(100);
        }
        const port = await listen(proxy);
        const sqlite = new DatabaseSync(databasePath);
        sqlite.exec('PRAGMA foreign_keys=ON; PRAGMA busy_timeout=8000');
        const master = randomBytes(32).toString('base64');
        return {
            origin: `http://127.0.0.1:${port}`,
            fixturePath(name) { return join(root, name); },
            stateSlot(name = '') {
                process.env.FT_CLI_STATE_DIR = name ? join(state, name) : state + '-owner';
                process.env.FT_CLI_STORAGE_KEY = master;
            },
            seed(owner, initialWorkspace) {
                const fixture = seed(sqlite, owner, initialWorkspace);
                if (process.env.FT_PERF_ARTIFACTS) {
                    writeFileSync(join(process.env.FT_PERF_ARTIFACTS, 'ordinary-shared-fixture.json'), JSON.stringify({
                        backend: 'Ktor with a fresh SQLite database',
                        workspaces: fixture.workspaces.length,
                        membersInPrimaryWorkspace: fixture.members.length + 1,
                        accountsInPrimaryWorkspace: fixture.accounts.length,
                        rowsInPrimaryWorkspace: fixture.rows.length,
                        primaryWorkspaceFactsSha256: fixture.cashDigest,
                        categoriesInCategoryWorkspace: fixture.categories.length,
                        rowsInCategoryWorkspace: fixture.categoryRows.length,
                    }, null, 2) + '\n');
                }
                return fixture;
            },
            seedDeletion(id, owner) { return seedDeletion(sqlite, id, owner); },
            deleteDeletionRemainder(id) { return deleteDeletionRemainder(sqlite, id); },
            restoreMember(workspace, user) { return restoreMember(sqlite, workspace, user); },
            removeAccount(workspace, account) {
                sqlite.prepare('DELETE FROM accounts WHERE workspace_id=? AND id=?').run(workspace, account);
                restoreBalances(sqlite, workspace);
                sqlite.prepare('UPDATE workspaces SET ledger_seq=ledger_seq+1 WHERE id=?').run(workspace);
            },
            discardNewCash(workspace, lastFixtureId) {
                sqlite.exec('BEGIN IMMEDIATE');
                try {
                    sqlite.prepare(`DELETE FROM transaction_relations WHERE workspace_id=?
                        AND primary_transaction_id>? AND secondary_transaction_id>?`)
                        .run(workspace, lastFixtureId, lastFixtureId);
                    sqlite.prepare('DELETE FROM cash_entries WHERE workspace_id=? AND root_transaction_id>?')
                        .run(workspace, lastFixtureId);
                    sqlite.prepare('DELETE FROM cash_transactions WHERE workspace_id=? AND id>?').run(workspace, lastFixtureId);
                    restoreBalances(sqlite, workspace);
                    sqlite.prepare('UPDATE workspaces SET ledger_seq=ledger_seq+1 WHERE id=?').run(workspace);
                    sqlite.exec('COMMIT');
                } catch (error) {
                    sqlite.exec('ROLLBACK');
                    throw error;
                }
            },
            newCash(workspace, lastFixtureId) {
                return sqlite.prepare(`SELECT id,account_id,amount,currency,note FROM cash_transactions
                    WHERE workspace_id=? AND id>? AND deleted_at IS NULL ORDER BY id`).all(workspace, lastFixtureId)
                    .map((row) => ({ ...row, id: String(row.id), account_id: String(row.account_id) }));
            },
            newCashLinks(workspace, lastFixtureId) {
                return sqlite.prepare(`SELECT kind,state,primary_transaction_id AS a,secondary_transaction_id AS b
                    FROM transaction_relations WHERE workspace_id=? AND primary_transaction_id>? AND secondary_transaction_id>?`)
                    .all(workspace, lastFixtureId, lastFixtureId)
                    .map((row) => ({ ...row, a: String(row.a), b: String(row.b) }));
            },
            cashRevision(id) {
                const result = sqlite.prepare(`SELECT c.row_revision,e.id,e.revision FROM cash_transactions c
                    JOIN cash_entry_members m ON m.workspace_id=c.workspace_id AND m.transaction_id=c.id
                    JOIN cash_entries e ON e.workspace_id=m.workspace_id AND e.id=m.entry_id WHERE c.id=?`).get(id);
                if (!result) throw new Error('Fixture entry missing');
                return result;
            },
            cashNote(id) {
                return sqlite.prepare(`SELECT e.note FROM cash_entry_members m
                    JOIN cash_entries e ON e.workspace_id=m.workspace_id AND e.id=m.entry_id WHERE m.transaction_id=?`).get(id)?.note;
            },
            categoryRevision(workspace) {
                return Number(sqlite.prepare('SELECT revision FROM cash_category_states WHERE workspace_id=?').get(workspace)?.revision ?? 0);
            },
            ledgerRevision(workspace) {
                return Number(sqlite.prepare('SELECT ledger_seq FROM workspaces WHERE id=?').get(workspace).ledger_seq);
            },
            categoryAssignmentCount(workspace, ids, category) {
                const match = sqlite.prepare('SELECT category_id FROM cash_entries WHERE workspace_id=? AND id=?');
                return ids.filter((id) => match.get(workspace, id.slice('cash-entry:'.length))?.category_id === category).length;
            },
            captureCategory(id) {
                return sqlite.prepare(`SELECT id,workspace_id,parent_id,parent_scope_key,name,normalized_name,description,
                    category_path,depth,sort_order,revision,created_at,updated_at FROM cash_categories WHERE id=?`).get(id);
            },
            restoreCategory(row) {
                sqlite.prepare(`INSERT INTO cash_categories (id,workspace_id,parent_id,parent_scope_key,name,normalized_name,
                    description,category_path,depth,sort_order,revision,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`)
                    .run(...Object.values(row));
                sqlite.prepare('UPDATE cash_category_states SET revision=revision+1 WHERE workspace_id=?').run(row.workspace_id);
                sqlite.prepare('UPDATE workspaces SET ledger_seq=ledger_seq+1 WHERE id=?').run(row.workspace_id);
            },
            restoreDeletedRows(workspace, rows) {
                sqlite.exec('BEGIN IMMEDIATE');
                try {
                    const restore = sqlite.prepare(`UPDATE cash_transactions SET deleted_at=NULL, deleted_by='', delete_reason=''
                        WHERE workspace_id=? AND id=? AND deleted_at IS NOT NULL`);
                    const restoreEntry = sqlite.prepare(`INSERT INTO cash_entries
                        (id,workspace_id,root_transaction_id,revision,note,counterparty,counterparty_account,
                         counterparty_account_attrs,manual_fields,identity_transaction_id)
                        SELECT ?,?,id,1,note,counterparty,counterparty_account,counterparty_account_attrs,'[]',id
                        FROM cash_transactions WHERE workspace_id=? AND id=?`);
                    const restoreMember = sqlite.prepare(`INSERT INTO cash_entry_members
                        (workspace_id,transaction_id,entry_id,standalone_values,standalone_manual_fields)
                        SELECT ?,id,?,json_object('category_id',category_id,'note',note,'counterparty',counterparty,
                            'counterparty_account',counterparty_account,'counterparty_account_attrs',json(counterparty_account_attrs)),'[]'
                        FROM cash_transactions WHERE workspace_id=? AND id=?`);
                    for (const row of rows) {
                        if (restore.run(workspace,row.id).changes !== 1) throw new Error('Fixture row was not soft-deleted');
                        const entry = row.entryId.slice('cash-entry:'.length);
                        restoreEntry.run(entry,workspace,workspace,row.id);
                        restoreMember.run(workspace,entry,workspace,row.id);
                    }
                    restoreBalances(sqlite, workspace);
                    sqlite.prepare('UPDATE workspaces SET ledger_seq=ledger_seq+1 WHERE id=?').run(workspace);
                    sqlite.exec('COMMIT');
                } catch (error) {
                    sqlite.exec('ROLLBACK');
                    throw error;
                }
            },
            count(kind, workspace) { return rowCount(sqlite, kind, workspace); },
            receiptId(workspace, key) {
                return sqlite.prepare('SELECT id FROM cash_mutation_receipts WHERE workspace_id=? AND idempotency_key=?')
                    .get(workspace, key)?.id ?? null;
            },
            requestMark() { return requests.length; },
            requestCount(mark, method, path) {
                return requests.slice(mark).filter((item) =>
                    item.method === method && item.path.split('?')[0] === path).length;
            },
            writeCount(mark) {
                return requests.slice(mark).filter(({ method, path }) =>
                    !['GET', 'HEAD', 'OPTIONS'].includes(method) && !path.startsWith('/api/v1/cash-mutations/resolve')).length;
            },
            dropReply(method, path) { dropResponse = method + ' ' + path; },
            async backendDuration(mark, method, path) {
                const matched = requests.slice(mark).filter((item) =>
                    item.method === method && item.path.split('?')[0] === path && item.status === 200);
                if (matched.length !== 1 || !matched[0].requestId) throw new Error('Expected one successful backend request');
                const id = matched[0].requestId;
                for (let i = 0; i < 30; i++) {
                    const line = readFileSync(logPath, 'utf8').split('\n').find((entry) => entry.includes(`http_request request_id=${id} `));
                    if (line) {
                        const ms = Number(/duration_ms=(\d+)/.exec(line)?.[1]);
                        if (Number.isFinite(ms)) return Math.max(ms, 0.001);
                    }
                    await sleep(20);
                }
                throw new Error('Backend timing missing');
            },
            writeFixture(name, content) { writeFileSync(join(root, name), content); },
            async close() {
                sqlite.close();
                proxy.closeAllConnections();
                await new Promise((done) => proxy.close(done));
                if (backend.exitCode === null && backend.signalCode === null && backend.pid) {
                    backend.kill();
                    await once(backend, 'exit');
                }
                if (process.env.FT_PERF_KEEP_TEST_DATA !== '1') rmSync(root, { recursive: true, force: true });
            },
        };
    } catch (error) {
        proxy.close();
        if (backend.exitCode === null && backend.signalCode === null) backend.kill();
        throw error;
    }
}

let operationMemory;

export function beginOperationMemory() {
    if (operationMemory) throw new Error('overlapping client RSS windows');
    operationMemory = startPeakRss();
}

export function endOperationMemory() {
    if (!operationMemory) throw new Error('missing client RSS window');
    const recording = operationMemory;
    operationMemory = undefined;
    return recording.readPeakBytes();
}

export function nowMs() { return performance.now(); }

export function recordSample(encoded) {
    const output = process.env.FT_PERF_EVENTS;
    if (!output) throw new Error('FT_PERF_EVENTS is required');
    appendFileSync(output, encoded + '\n');
}

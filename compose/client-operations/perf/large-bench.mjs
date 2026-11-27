import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { createReadStream } from 'node:fs';
import { appendFile, mkdir, mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer as reserveServer } from 'node:net';
import { availableParallelism, release, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { setTimeout as pause } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { measureCommand } from './large-measure.mjs';
import { openProxy, verifyPreparedFiles } from './large-support.mjs';

const perfDirectory = dirname(fileURLToPath(import.meta.url));
const checkout = resolve(perfDirectory, '../../..');
const testPassword = 'synthetic benchmark has no private credentials 683';
const pdfPassword = 'synthetic-fixture-only-613';
const expected = { rows: 14424, posted: 12724, duplicates: 1700, relations: 2995, missingIds: 6 };

function validateTestDatabase(value) {
    assert.ok(value, 'Set FT_TEST_POSTGRES_URL to an empty loopback PostgreSQL test database');
    const uri = new URL(value);
    assert.ok(['postgresql:', 'postgres:'].includes(uri.protocol) &&
        ['127.0.0.1', 'localhost', '[::1]'].includes(uri.hostname) &&
        /^\/[a-z0-9_]+_test$/.test(uri.pathname) &&
        uri.search === '' && uri.hash === '', 'A loopback database with a _test suffix is required');
    return uri.href;
}

function tool(bin, args, options = {}) {
    return execFileSync(bin, args, { timeout: 90_000, encoding: 'utf8', ...options });
}

async function localPort() {
    const reservation = reserveServer().listen(0, '127.0.0.1');
    await once(reservation, 'listening');
    const port = reservation.address().port;
    await new Promise((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()));
    return port;
}

async function sha256(path) {
    const hash = createHash('sha256');
    for await (const block of createReadStream(path)) hash.update(block);
    return hash.digest('hex');
}

async function listen(server, port = 0) {
    server.listen(port, '127.0.0.1');
    await once(server, 'listening');
    return server.address().port;
}

async function closeServer(server) {
    if (server?.listening)
        await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}

function archiveAssertions(value, batch) {
    assert.equal(value.batch_id, batch);
    assert.equal(value.items_complete, true);
    const receipt = value.receipt;
    assert.equal(receipt.operation_kind, 'cash_write_set');
    assert.equal(receipt.committed, true);
    assert.equal(receipt.status, 'completed');
    assert.equal(receipt.ledger_seq, '2');
    assert.equal(receipt.summary.created_posted, expected.posted);
    assert.equal(receipt.summary.duplicate, expected.duplicates);
    assert.equal(receipt.summary.relations_confirmed, expected.relations);
    assert.equal(receipt.summary.relations_deferred, 0);
    assert.equal(receipt.summary.saved_draft, 0);
    assert.equal(receipt.commit_outcome.has_new_failures, false);
    assert.match(receipt.receipt_id, /^[a-f0-9-]{36}$/);
    assert.equal(receipt.write_set_result.transactions.length, expected.posted);
    assert.equal(value.page.items.length, 50);
    for (const item of value.page.items) {
        assert.equal(item.result_kind, 'row');
        assert.equal(item.outcome, 'created');
    }
    return receipt;
}

function readCashList(value, workspace, ledgerSeq, offline) {
    assert.equal(value.workspace_id, workspace);
    assert.equal(value.ledger_seq, ledgerSeq);
    assert.equal(value.page.projection_version, ledgerSeq);
    assert.equal(value.offline, offline);
    assert.ok(Number.isFinite(Date.parse(value.synced_at)));
    assert.deepEqual(value.page.filter_options.currencies, ['CNY', 'USD']);
    assert.equal(value.page.items.length, 20);
    assert.equal(value.page.items[0].counterparty, '合成商户9913');
    assert.equal(value.page.items[0].amount, '-103.13');
    assert.equal(value.page.items[0].currency, 'CNY');
    assert.equal(value.page.pending_item_count, 0);
    return value.page.items.map(item => [item.transaction_id, item.projection_id, item.currency, item.amount]);
}

async function main() {
    const dbUrl = validateTestDatabase(process.env.FT_TEST_POSTGRES_URL);
    assert.ok(process.env.FT_PERF_EVENTS, 'FT_PERF_EVENTS is required');
    const eventPath = resolve(process.env.FT_PERF_EVENTS);
    const evidenceDir = resolve(process.env.FT_PERF_ARTIFACTS ?? dirname(eventPath));
    await mkdir(dirname(eventPath), { recursive: true });
    await mkdir(evidenceDir, { recursive: true });
    const work = await mkdtemp(join(tmpdir(), 'ft-perf-large-'));
    const install = join(work, 'installed');
    const outputDir = join(work, 'command-logs');
    const fixture = join(work, 'fixture');
    await mkdir(install);
    await mkdir(outputDir);
    const backend = resolve(process.env.FT_PERF_BACKEND_INSTALL ?? join(checkout, 'compose/backend/build/install/backend'));
    const psqlArgs = ['-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '--dbname', dbUrl];
    const psql = args => tool('psql', [...psqlArgs, ...args]).trim();
    const logs = await open(join(evidenceDir, 'large-backend.log'), 'w');
    let server;
    let proxy;
    const observed = [];
    const raw = [];
    const checks = {};
    const evidencePath = join(evidenceDir, 'large-details.json');
    const evidence = {
        environment: {
            os: process.platform, arch: process.arch, osRelease: release(), parallelism: availableParallelism(),
            node: process.version,
            imageOs: process.env.ImageOS ?? null, imageVersion: process.env.ImageVersion ?? null,
            runner: process.env.RUNNER_NAME ?? null, runId: process.env.GITHUB_RUN_ID ?? null,
            git: tool('git', ['-C', checkout, 'rev-parse', 'HEAD']).trim(),
            dirty: tool('git', ['-C', checkout, 'status', '--porcelain']).trim() !== '',
        },
        measurements: raw, checks,
    };
    const record = async (id, measured, facts) => {
        const sample = { id, ms: measured.ms, rssBytes: measured.rssBytes, asserted: true };
        raw.push({ ...sample, checks: facts });
        await appendFile(eventPath, JSON.stringify(sample) + '\n');
        console.log(`${id}: ${sample.ms.toFixed(1)} ms, peak RSS ${sample.rssBytes} bytes`);
    };

    try {
        assert.equal(psql(['-c', "select count(*) from pg_tables where schemaname = 'public'"]), '0',
            'The performance database must have an empty public schema');
        evidence.environment.postgres = psql(['-c', 'show server_version']);
        const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin/java') : 'java';
        evidence.environment.java = tool(java, ['--version']).trim();
        evidence.environment.qpdf = tool('qpdf', ['--version']).split('\n')[0];

        let packagePath = process.env.FT_PERF_CLI_PACKAGE;
        if (!packagePath) {
            const [archive] = JSON.parse(tool('npm', [
                'pack', '--json', '--pack-destination', work, join(checkout, 'compose/cli/build/npm'),
            ]));
            assert.equal(archive.name, 'finance-tracker-cli');
            packagePath = join(work, archive.filename);
        }
        packagePath = resolve(packagePath);
        evidence.packageSha256 = await sha256(packagePath);
        tool('npm', ['install', '--engine-strict', '--ignore-scripts', '--no-audit', '--no-fund', packagePath],
            { cwd: install });
        const ft = join(install, 'node_modules/.bin/ft');
        evidence.cliVersion = tool(ft, ['--version']).trim();

        tool('python3', [join(perfDirectory, 'fixtures/large-cash.py'), '--output', fixture, '--backend-install', backend]);
        const manifest = JSON.parse(await readFile(join(fixture, 'manifest.json'), 'utf8'));
        assert.equal(manifest.rows, expected.rows);
        assert.deepEqual(manifest.formats, { csv: 6, pdf: 5, xls: 2, xlsx: 6 });
        assert.equal(manifest.files.length, 19);
        assert.equal(manifest.files.filter(file => file.password_required).length, 3);
        for (const file of manifest.files) assert.equal(await sha256(join(fixture, file.file)), file.sha256);
        evidence.fixture = manifest;

        const keyPath = join(work, 'loopback-key.pem');
        const certPath = join(work, 'loopback-cert.pem');
        tool('openssl', [
            'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256', '-days', '1', '-subj', '/CN=127.0.0.1',
            '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', keyPath, '-out', certPath,
        ], { stdio: 'ignore' });
        const backendPort = await localPort();
        server = spawn(join(backend, 'bin/backend'), [], {
            env: {
                ...process.env, PORT: String(backendPort), FT_HOST: '127.0.0.1',
                FT_WEB_ORIGIN: 'https://127.0.0.1:5173', FT_DATABASE_URL: dbUrl, FT_SENTRY_DSN: '',
                FT_IMPORT_STAGING_BACKEND: 'memory', FT_IMPORT_STAGING_ALLOW_MEMORY: '1',
            },
            stdio: ['ignore', logs.fd, logs.fd], detached: true,
        });
        let spawnError;
        server.once('error', error => { spawnError = error; });
        const health = `http://127.0.0.1:${backendPort}/health`;
        const deadline = performance.now() + 60_000;
        while (!(await fetch(health, { signal: AbortSignal.timeout(1_500) }).then(response => response.ok, () => false))) {
            if (spawnError) throw spawnError;
            if (server.exitCode !== null || performance.now() >= deadline)
                throw new Error('Loopback Ktor backend did not become healthy; see large-backend.log');
            await pause(200);
        }
        proxy = openProxy(await readFile(certPath), await readFile(keyPath), backendPort, observed);
        const proxyPort = await listen(proxy);
        const primaryState = join(work, 'state-primary');
        const coldState = join(work, 'state-cold');
        const cliEnv = {
            ...process.env, NODE_EXTRA_CA_CERTS: certPath,
            FT_CLI_SERVER: `https://127.0.0.1:${proxyPort}`,
            FT_CLI_STORAGE_KEY: Buffer.alloc(32, 23).toString('base64'),
        };
        delete cliEnv.NODE_OPTIONS;
        delete cliEnv.NODE_TLS_REJECT_UNAUTHORIZED;
        let sequence = 0;
        const run = (args, { input, state = primaryState } = {}) => measureCommand(ft, [...args, '--json'], {
            outputDir, label: `${++sequence}-${args[0]}-${args[1]}`, timeoutMs: 300_000, input,
            env: { ...cliEnv, FT_CLI_STATE_DIR: state },
        });

        const email = `synthetic-${randomUUID()}@example.test`;
        const name = 'Synthetic large cash import';
        await run(['auth', 'register', '--email', email, '--password-stdin'], { input: testPassword });
        await run(['auth', 'logout']);
        await run(['auth', 'login', '--email', email, '--password-stdin'], { input: testPassword });
        const original = (await run(['workspace', 'create', '--name', name])).value.active_workspace_id;
        await run(['workspace', 'delete', '--workspace', original, '--confirm-name', name]);
        const workspace = (await run(['workspace', 'create', '--name', name])).value.active_workspace_id;
        assert.notEqual(workspace, original);
        const selected = await run(['workspace', 'use', workspace]);
        assert.equal(selected.value.active_workspace_id, workspace);
        const scope = ['--workspace', workspace];
        const snapshotPath = '/api/v1/ledger/snapshot';
        const snapshots = after => observed.slice(after)
            .filter(item => item.path === snapshotPath && item.method === 'GET' && item.status === 200).length;
        const writeRequests = () => observed
            .filter(item => item.path === '/api/v1/cash-import/write-set' && item.method === 'POST');
        const mutatingRequests = after => observed.slice(after)
            .filter(item => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(item.method));

        const plainFiles = manifest.files.filter(file => !file.password_required).map(file => file.file);
        const encryptedFiles = manifest.files.filter(file => file.password_required).map(file => file.file);
        const expectedFiles = [...plainFiles];
        const preparationReport = [];
        checks.preparation = preparationReport;
        const startPrepare = performance.now();
        const preparations = [await run(['import', 'prepare', ...plainFiles.map(file => join(fixture, file)), ...scope])];
        let prepared = preparations[0].value;
        verifyPreparedFiles(prepared, expectedFiles, 'prepare', preparationReport);
        const batch = prepared.batch_id;
        for (const file of encryptedFiles) {
            const addition = await run(
                ['import', 'file', 'add', batch, join(fixture, file), ...scope, '--revision', prepared.revision, '--password-stdin'],
                { input: pdfPassword },
            );
            preparations.push(addition);
            prepared = addition.value;
            expectedFiles.push(file);
            verifyPreparedFiles(prepared, expectedFiles, `add ${file}`, preparationReport);
        }
        const prepareMs = performance.now() - startPrepare;
        assert.equal(prepared.batch_id, batch);
        assert.equal(prepared.files.length, 19);
        assert.equal(prepared.source_accounts.length, 8);
        await record('large.prepare', { ms: prepareMs, rssBytes: Math.max(...preparations.map(r => r.rssBytes)) }, {
            files: prepared.files.length, groups: prepared.source_accounts.length, encryptedPdfs: 3,
            subprocesses: preparations.map(({ label, ms, rssBytes }) => ({ label, ms, rssBytes })),
        });
        const account = (await run([
            'import', 'account', 'create', batch, '--revision', prepared.revision, '--name', 'Synthetic settlement',
            '--type', 'cash', '--currency', 'CNY,USD', ...scope,
        ])).value.account;
        assert.deepEqual(account.currencies, ['CNY', 'USD']);
        const choice = join(work, 'choices.json');
        await writeFile(choice, JSON.stringify({
            expected_session_revision: Number(prepared.revision), previous_plan_id: null,
            account_selections: prepared.source_accounts.map(group => ({
                source_account_ref: group.source_account_ref, account_id: account.account_id,
                expected_account_revision: Number(account.account_revision),
            })),
            row_choices: [], relation_choices: [], entry_choices: [],
        }));

        const reviewStart = observed.length;
        const review = await run(['import', 'review', batch, '--choices', choice, ...scope]);
        assert.equal(review.value.can_commit, true);
        assert.deepEqual(review.value.summary, {
            create_posted: expected.posted, duplicate: expected.duplicates, relations_accepted: expected.relations,
            relations_deferred: 0, relations_failed: 0, relations_ignored: 0, relations_removed: 0, skip: 0,
        });
        assert.deepEqual(review.value.item_counts, { confirmed: 9729, pending: 0 });
        assert.equal(mutatingRequests(reviewStart).length, 0);
        await record('large.review', review, { summary: review.value.summary, itemCounts: review.value.item_counts });

        const confirm = await run(['import', 'confirm', batch, '--review', review.value.review_revision, ...scope]);
        const receipt = archiveAssertions(confirm.value, batch);
        const serverFacts = JSON.parse(psql([
            '-v', `workspace=${workspace}`, '-v', `receipt=${receipt.receipt_id}`,
            '-f', join(perfDirectory, 'large-receipt.sql'),
        ]));
        assert.equal(serverFacts.cny, 12719);
        assert.equal(serverFacts.usd, 5);
        assert.equal(serverFacts.posted, expected.posted);
        assert.equal(serverFacts.drafts, 0);
        assert.equal(serverFacts.missingNativeIds, expected.missingIds);
        assert.equal(serverFacts.relations, expected.relations);
        assert.equal(serverFacts.exactPairs, expected.relations);
        assert.deepEqual(serverFacts.pairIds, receipt.write_set_result.relations.map(relation => relation.id).sort());
        assert.deepEqual(serverFacts.receipts, [{ id: receipt.receipt_id, idempotency_key: batch }]);
        assert.deepEqual(serverFacts.rowOutcomes, { created: expected.posted });
        const writes = writeRequests();
        assert.equal(writes.length, 1);
        assert.equal(writes[0].key, batch);
        assert.equal(writes[0].status, 200);
        assert.ok(writes[0].bytes > 0);
        const { pairIds, ...factsSummary } = serverFacts;
        await record('large.confirm', confirm, {
            ...factsSummary, exactRelationIdsSha256: createHash('sha256').update(pairIds.join('\n')).digest('hex'),
            receiptSummary: receipt.summary, itemsComplete: confirm.value.items_complete, submittedBytes: writes[0].bytes,
        });

        const resultStart = observed.length;
        const result = await run(['import', 'result', batch, ...scope]);
        assert.deepEqual(result.value, confirm.value);
        // The archive merges the verified 12,724 server writes with local duplicate outcomes.
        const tail = (await run([
            'import', 'result', batch, '--kind', 'row', '--offset', String(expected.rows - 1), '--limit', '2', ...scope,
        ])).value.page;
        assert.equal(tail.nextAfter, null);
        assert.equal(tail.items.length, 1);
        assert.equal(tail.items[0].result_kind, 'row');
        assert.equal(tail.items[0].outcome, 'duplicate');
        assert.equal(mutatingRequests(resultStart).length, 0);
        const batches = await run(['import', 'batches', ...scope]);
        assert.equal(batches.value.batches.find(entry => entry.batch_id === batch).status, 'committed');
        await record('large.result', result, {
            receipt: receipt.receipt_id, itemsComplete: result.value.items_complete,
            lastRowOffset: expected.rows - 1, lastRowOutcome: tail.items[0].outcome,
        });

        const firstStart = observed.length;
        const signedIn = await run(['auth', 'login', '--email', email, '--password-stdin'], { input: testPassword, state: coldState });
        assert.equal(signedIn.value.active_workspace_id, workspace);
        assert.equal(snapshots(firstStart), 0, 'Fresh login must not seed the cash cache');
        const listArgs = ['cash', 'list', '--limit', '20', ...scope];
        const first = await run(listArgs, { state: coldState });
        const expectedPage = readCashList(first.value, workspace, '2', false);
        assert.equal(snapshots(firstStart), 1);
        await record('large.cash-first', first, { ledgerSeq: '2', snapshotDownloads: 1, rows: expectedPage.length });

        const onlineStart = observed.length;
        const online = await run(listArgs, { state: coldState });
        assert.deepEqual(readCashList(online.value, workspace, '2', false), expectedPage);
        assert.equal(online.value.synced_at, first.value.synced_at);
        assert.equal(snapshots(onlineStart), 0);
        assert.ok(observed.slice(onlineStart).some(item => item.path === '/api/v1/ledger/version' && item.status === 200));
        await record('large.cash-online', online, { ledgerSeq: '2', snapshotDownloads: 0, rows: expectedPage.length });

        await closeServer(proxy);
        const offlineStart = observed.length;
        const offline = await run(listArgs, { state: coldState });
        assert.deepEqual(readCashList(offline.value, workspace, '2', true), expectedPage);
        assert.equal(offline.value.synced_at, first.value.synced_at);
        assert.equal(observed.length, offlineStart);
        await record('large.cash-offline', offline, { ledgerSeq: '2', snapshotDownloads: 0, rows: expectedPage.length });

        await listen(proxy, proxyPort);
        const marker = 'Synthetic refresh marker';
        const change = await run(['account', 'create', '--name', marker, '--type', 'cash', '--currency', 'CNY', ...scope]);
        assert.equal(change.value.receipt.committed, true);
        assert.equal(change.value.account.name, marker);
        const refreshStart = observed.length;
        const refresh = await run(listArgs, { state: coldState });
        assert.deepEqual(readCashList(refresh.value, workspace, '3', false), expectedPage);
        assert.notEqual(refresh.value.synced_at, first.value.synced_at);
        const accountList = await run(['account', 'list', ...scope], { state: coldState });
        const refreshed = accountList.value.accounts.find(account => account.id === change.value.account.account_id);
        assert.equal(refreshed?.name, marker);
        assert.deepEqual(refreshed.currencies, ['CNY']);
        assert.equal(snapshots(refreshStart), 1);
        assert.equal(writeRequests().length, 1);
        await record('large.cash-refresh', refresh, {
            ledgerSeq: '3', snapshotDownloads: 1, rows: expectedPage.length, newAccount: refreshed.id,
        });
        checks.completed = true;
    } catch (error) {
        checks.completed = false;
        checks.failure = error.message;
        checks.transportErrors = observed.filter(item => item.transportError);
        throw error;
    } finally {
        try {
            await writeFile(evidencePath, JSON.stringify(evidence, null, 2) + '\n');
        } finally {
            await closeServer(proxy);
            if (server?.pid && server.exitCode === null && server.signalCode === null) {
                const finished = once(server, 'exit');
                process.kill(-server.pid, 'SIGTERM');
                await Promise.race([finished, pause(5_000, undefined, { ref: false })]);
                if (server.exitCode === null && server.signalCode === null) process.kill(-server.pid, 'SIGKILL');
            }
            await logs.close();
            await rm(work, { recursive: true, force: true });
        }
    }
}

await main();

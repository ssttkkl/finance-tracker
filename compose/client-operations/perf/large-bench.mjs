import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { once } from 'node:events';
import { createReadStream } from 'node:fs';
import { appendFile, cp, mkdir, mkdtemp, open, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer as reserveServer } from 'node:net';
import { availableParallelism, release, tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { setTimeout as pause } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { measureCommand } from './large-measure.mjs';
import { trackDisk } from './workspace-diagnostic/disk.mjs';
import {
    collectConfirmObservations, inspectLostConfirmation, observedTime, openProxy, sampleConfirmDatabase, verifyPreparedFiles,
} from './large-support.mjs';
import { holdCashWriteLock, waitForCashWriter } from './postgres-lock.mjs';
import { createServiceMemory } from './service-memory.mjs';
import { ordinaryLedgerCsv } from './cli-fixture.mjs';
import { readEvents } from './gate.mjs';
import { joinClientRequestClocks, openSerialRssWindows } from './request-observation.mjs';
import { collectRunnerFacts } from './large-telemetry.mjs';

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
    const diagnosticInstall = process.env.FT_PERF_WORKSPACE_DIAGNOSTIC_INSTALL;
    const install = diagnosticInstall || join(work, 'installed');
    const outputDir = join(work, 'command-logs');
    const fixture = join(work, 'fixture');
    if (!diagnosticInstall) await mkdir(install);
    await mkdir(outputDir);
    const backend = resolve(process.env.FT_PERF_BACKEND_INSTALL ?? join(checkout, 'compose/backend/build/install/backend'));
    const psqlArgs = ['-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '--dbname', dbUrl];
    const psql = (args) => {
        try {
            return tool('psql', [...psqlArgs, ...args]).trim();
        } catch (error) {
            throw new Error(`Synthetic PostgreSQL benchmark command failed (${error.status ?? error.code ?? error.name})`);
        }
    };
    const logs = await open(join(evidenceDir, 'large-backend.log'), 'w');
    let serviceMemory;
    let server;
    let worker;
    let beginRequestRss;
    let proxy;
    const isolated = process.env.FT_PERF_ISOLATED_BACKEND === '1';
    const legacyComparison = process.env.FT_PERF_LEGACY_COMPARISON === '1';
    assert.ok(!isolated || !legacyComparison);
    if (isolated) assert.ok(process.env.FT_PERF_CGROUP_PARENT, 'isolated replay requires a delegated memory cgroup');
    const observed = [];
    let requestFlow;
    const requestReports = [];
    const payloadShapes = process.env.FT_PERF_PAYLOAD_PROFILE === '1' ? [] : undefined;
    const raw = [];
    const processes = [];
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
        runnerFacts: collectRunnerFacts(), measurements: raw, processes, checks, requestReports,
        ...(payloadShapes ? { payloadProfile: { diagnosticOnly: true, payloadShapes } } : {}),
    };
    const reviewVariants = process.env.FT_DIAGNOSTIC_REVIEW_VARIANTS
        ? JSON.parse(process.env.FT_DIAGNOSTIC_REVIEW_VARIANTS) : null;
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
        if (!diagnosticInstall) tool('npm', ['install', '--engine-strict', '--ignore-scripts', '--no-audit', '--no-fund', packagePath],
            { cwd: install });
        const ft = join(install, 'node_modules/.bin/ft');
        evidence.cliVersion = tool(ft, ['--version']).trim();
        const installedHost = join(install, 'node_modules/finance-tracker-cli/lib/host.mjs');
        const installedHostBytes = await readFile(installedHost);
        const installedSource = installedHostBytes.toString('utf8');
        const bulkTest = /\bconst\s+bulk\s*=([\s\S]*?);/.exec(installedSource)?.[1];
        const timeout = /AbortSignal\.timeout\(\s*bulk\s*\?\s*(\d[\d_]*)\s*:\s*(\d[\d_]*)\s*\)/.exec(installedSource);
        evidence.installedTransport = {
            sha256: createHash('sha256').update(installedHostBytes).digest('hex'),
            cashImportWriteSetInBulk: bulkTest ? /['"]\/api\/v1\/cash-import\/write-set['"]/.test(bulkTest) : null,
            timeoutExpression: timeout?.[0] ?? null,
            bulkTimeoutMs: timeout ? Number(timeout[1].replaceAll('_', '')) : null,
            regularTimeoutMs: timeout ? Number(timeout[2].replaceAll('_', '')) : null,
        };

        const diagnosticFixture = process.env.FT_PERF_WORKSPACE_DIAGNOSTIC_FIXTURE;
        if (diagnosticFixture) await cp(diagnosticFixture, fixture, { recursive: true });
        else tool('python3', [join(perfDirectory, 'fixtures/large-cash.py'), '--output', fixture, '--backend-install', backend]);
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
        const startBackend = async (bin, args, port, log, extraEnv = {}) => {
            const service = spawn(bin, args, {
                env: {
                    ...process.env, PORT: String(port), FT_HOST: '127.0.0.1',
                    FT_WEB_ORIGIN: 'https://127.0.0.1:5173', FT_DATABASE_URL: dbUrl, FT_SENTRY_DSN: '',
                    FT_IMPORT_STAGING_BACKEND: 'memory', FT_IMPORT_STAGING_ALLOW_MEMORY: '1',
                    ...extraEnv,
                },
                stdio: ['ignore', log.fd, log.fd], detached: true,
            });
            let spawnError;
            service.once('error', error => { spawnError = error; });
            const health = `http://127.0.0.1:${port}/health`;
            const deadline = performance.now() + 60_000;
            while (!(await fetch(health, { signal: AbortSignal.timeout(1_500) }).then(response => response.ok, () => false))) {
                if (spawnError) throw spawnError;
                if (service.exitCode !== null || performance.now() >= deadline) {
                    if (service.exitCode === null) process.kill(-service.pid, 'SIGTERM');
                    throw new Error('Loopback Ktor backend did not become healthy; see large-backend.log');
                }
                await pause(200);
            }
            return service;
        };
        const backendPort = await localPort();
        if (!legacyComparison && process.env.FT_PERF_CGROUP_PARENT) {
            serviceMemory = createServiceMemory(process.env.FT_PERF_CGROUP_PARENT, `ktor-${process.pid}`);
        }
        const startMeasured = (bin, args, extraEnv) =>
            startBackend(...(serviceMemory ? serviceMemory.command(bin, args) : [bin, args]), backendPort, logs, extraEnv);
        const productionEntry = join(backend, 'bin/backend');
        if (isolated) {
            const classpath = (await readFile(join(checkout, 'compose/backend/build/performance/replay-classpath'), 'utf8')).trim();
            server = await startMeasured(java, ['-cp', classpath, 'com.finance.tracker.backend.PerformanceReplayServerKt'],
                { FT_PERF_REPLAY: '1' });
            worker = await startBackend(productionEntry, [], await localPort(), logs);
        } else {
            server = await startMeasured(productionEntry, []);
        }
        proxy = openProxy(await readFile(certPath), await readFile(keyPath), backendPort, observed, () => requestFlow,
            payloadShapes && (shape => payloadShapes.push(shape)));
        const proxyPort = await listen(proxy);
        const primaryState = join(work, 'state-primary');
        const coldState = join(work, 'state-cold');
        const liveMemoryFlows = new Set((process.env.FT_PERF_LIVE_MEMORY_FLOWS ?? '').split(',').filter(Boolean));
        const cliEnv = {
            ...process.env, NODE_EXTRA_CA_CERTS: certPath,
            FT_CLI_SERVER: `https://127.0.0.1:${proxyPort}`,
            FT_CLI_STORAGE_KEY: Buffer.alloc(32, 23).toString('base64'),
        };
        delete cliEnv.NODE_OPTIONS;
        delete cliEnv.NODE_TLS_REJECT_UNAUTHORIZED;
        if (!legacyComparison || diagnosticInstall) {
            evidence.workspaceMedium = legacyComparison ? 'memory' : 'file';
            evidence.workspaceDisk = [];
        }
        let sequence = 0;
        const run = async (args, { input, state = primaryState, flow = args[0] === 'import' ? args[1] : undefined,
            concurrent = false, client = ft, sampleMemory = false } = {}) => {
            const label = `${++sequence}-${args[0]}-${args[1]}`;
            const clocksPath = join(work, `${label}-http.jsonl`);
            if (flow) {
                assert.equal(requestFlow, undefined);
                requestFlow = { id: flow, concurrent, beginRss: beginRequestRss, started: 0, pending: [], errors: [] };
                await writeFile(clocksPath, '');
            }
            const observeDisk = (!legacyComparison && flow === 'review') ||
                (diagnosticInstall && ['prepare', 'review', 'confirm', 'result'].includes(flow));
            const disk = observeDisk ? trackDisk(state) : null;
            try {
                const measured = await measureCommand(client, [...args, '--json'], {
                    outputDir, label, timeoutMs: 300_000, input,
                    collectLiveMemory: sampleMemory || liveMemoryFlows.has(flow),
                    collectGc: !diagnosticInstall && args[0] === 'import' && args[1] === 'review',
                    env: { ...cliEnv, FT_CLI_STATE_DIR: state, ...(flow ? {
                        NODE_OPTIONS: `--import=${new URL('./client-request-clock.mjs', import.meta.url).href}`,
                        FT_PERF_CLIENT_REQUEST_CLOCK: clocksPath,
                    } : {}) },
                });
                processes.push({ label, ...measured.process });
                return measured;
            } catch (error) {
                processes.push({ label, error: error.message, ...error.measurement });
                throw error;
            } finally {
                if (disk) evidence.workspaceDisk.push({ label, flow, ...disk() });
                if (flow) {
                    const { started, pending, errors } = requestFlow;
                    requestFlow = undefined;
                    const observedRequests = (await Promise.all(pending)).filter(Boolean);
                    const clocks = readEvents(clocksPath);
                    if (observedRequests.length !== started) errors.push('missing a complete proxy request observation');
                    const requestReport = { label, flow, started, requests: observedRequests, errors };
                    requestReports.push(requestReport);
                    try {
                        requestReport.requests = joinClientRequestClocks(observedRequests, clocks);
                    } catch (error) {
                        errors.push(error.message);
                    }
                }
            }
        };

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
        if (isolated) {
            const prewarmState = join(work, 'state-prewarm');
            const prewarm = { state: prewarmState };
            const session = await run(['auth', 'login', '--email', email, '--password-stdin'],
                { ...prewarm, input: testPassword });
            assert.equal(session.value.active_workspace_id, workspace);
            for (let i = 0; i < 5; i++) await run(['account', 'list', ...scope], prewarm);
            beginRequestRss = openSerialRssWindows(() => serviceMemory.start());
        }
        const legacySnapshot = legacyComparison || payloadShapes && process.env.FT_PERF_PAYLOAD_PROFILE_LEGACY === '1';
        const snapshotPath = legacySnapshot ? '/api/v1/ledger/snapshot' : '/api/v1/ledger/snapshot/pages';
        const snapshots = after => observed.slice(after)
            .filter(item => item.path === snapshotPath && item.method === 'GET' && item.status === 200 &&
                (legacySnapshot || item.page?.section === null)).length;
        const writeRequests = (key) => observed
            .filter(item => item.path === '/api/v1/cash-import/write-set' && item.method === 'POST' && item.key === key);
        const mutatingRequests = after => observed.slice(after)
            .filter(item => ['POST', 'PUT', 'PATCH', 'DELETE'].includes(item.method));

        if (!legacyComparison && !reviewVariants) {
            const [, controlCsv] = ordinaryLedgerCsv();
            const controlFile = join(work, 'confirmation-control.csv');
            await writeFile(controlFile, controlCsv);
            async function prepareControl(label) {
                const controlWorkspace = (await run(['workspace', 'create', '--name', 'Synthetic ' + label])).value.active_workspace_id;
                const controlScope = ['--workspace', controlWorkspace];
                const controlBatch = (await run(['import', 'prepare', controlFile, ...controlScope], { flow: null })).value;
                assert.equal(controlBatch.files[0].status, 'parsed');
                const controlAccount = (await run([
                    'import', 'account', 'create', controlBatch.batch_id, '--revision', controlBatch.revision,
                    '--name', 'Synthetic control settlement', '--type', 'cash', '--currency', 'CNY', ...controlScope,
                ], { flow: null })).value.account;
                const choicePath = join(work, `control-${label}.json`);
                await writeFile(choicePath, JSON.stringify({
                    expected_session_revision: Number(controlBatch.revision), previous_plan_id: null,
                    account_selections: controlBatch.source_accounts.map(group => ({
                        source_account_ref: group.source_account_ref, account_id: controlAccount.account_id,
                        expected_account_revision: Number(controlAccount.account_revision),
                    })),
                    row_choices: [], relation_choices: [], entry_choices: [],
                }));
                const reviewed = (await run(['import', 'review', controlBatch.batch_id, '--choices', choicePath, ...controlScope],
                    { flow: null })).value;
                assert.equal(reviewed.can_commit, true);
                const key = controlBatch.batch_id;
                const args = ['import', 'confirm', key, '--review', reviewed.review_revision, '--no-wait', ...controlScope];
                return { controlWorkspace, controlScope, key, args };
            }

            const cancellable = await prepareControl('cancel');
            const exclusive = await holdCashWriteLock(psqlArgs);
            const memory = !isolated ? serviceMemory?.start() : undefined;
            let cancelledId;
            let cancelStart;
            let cancelPeak;
            try {
                const accepted = await run(cancellable.args, { flow: 'confirm-no-wait' });
                assert.ok(['accepted', 'running'].includes(accepted.value.status));
                assert.equal(accepted.value.operation_id, cancellable.key);
                assert.equal(accepted.value.receipt_id, null);
                await record('large.confirm-no-wait', accepted, { status: accepted.value.status, sameOperation: true });
                await waitForCashWriter(psql, exclusive.pid);
                const current = await run(['import', 'status', cancellable.key, ...cancellable.controlScope],
                    { flow: 'status', concurrent: true });
                assert.equal(current.value.status, 'running');
                assert.equal(current.value.operation_id, cancellable.key);
                await record('large.confirmation-status', current, { status: current.value.status, writerBlocked: true });
                cancelStart = performance.now();
                const cancel = await run(['import', 'cancel-confirmation', cancellable.key, ...cancellable.controlScope],
                    { flow: 'cancel', concurrent: true });
                assert.equal(cancel.value.status, 'cancel_requested');
                assert.equal(cancel.value.operation_id, cancellable.key);
                cancelledId = cancel.value.operation_id;
                cancelPeak = cancel.rssBytes;
                checks.controls = { accepted: true, active: true, cancellationRecorded: true };
            } finally {
                await exclusive.release();
            }
            // The short cancel response and eventual transaction rollback have separate clocks.
            const deadlineForCancellation = performance.now() + 120_000;
            let terminal;
            do {
                const read = await run(['import', 'status', cancellable.key, ...cancellable.controlScope], { flow: 'status' });
                assert.equal(read.value.operation_id, cancelledId);
                terminal = read.value.status;
                cancelPeak = Math.max(cancelPeak, read.rssBytes);
                if (terminal === 'cancelled') break;
                assert.equal(terminal, 'cancel_requested');
                await pause(300);
            } while (performance.now() < deadlineForCancellation);
            if (memory) {
                evidence.concurrentServicePeakBytes = memory.finish();
                evidence.concurrentSamplingMethod = memory.method;
            }
            assert.equal(terminal, 'cancelled');
            const cancelled = await sampleConfirmDatabase(psqlArgs, cancellable.controlWorkspace, email, cancellable.key);
            assert.equal(cancelled.ledgerSeq, '1');
            assert.equal(cancelled.originalReceipt, null);
            assert.equal(writeRequests(cancellable.key).length, 1);
            checks.controls.cancelled = true;
            await record('large.cancel-confirmation', { ms: performance.now() - cancelStart, rssBytes: cancelPeak },
                { status: terminal, sameOperation: true, receiptAbsent: true, unchangedLedger: true });

            const recoverable = await prepareControl('resume');
            const accepted = await run(recoverable.args, { flow: 'confirm-no-wait' });
            assert.ok(['accepted', 'running'].includes(accepted.value.status));
            assert.equal(accepted.value.operation_id, recoverable.key);
            const recovery = await run(['import', 'resume', recoverable.key, ...recoverable.controlScope], { flow: 'resume' });
            const resumed = recovery.value;
            assert.equal(resumed.batch_id, recoverable.key);
            assert.equal(resumed.items_complete, true);
            assert.equal(resumed.receipt.committed, true);
            assert.equal(resumed.receipt.summary.created_posted, 3000);
            const recovered = await sampleConfirmDatabase(psqlArgs, recoverable.controlWorkspace, email, recoverable.key);
            assert.equal(recovered.originalReceipt?.id, resumed.receipt.receipt_id);
            assert.equal(recovered.ledgerSeq, '2');
            assert.equal(writeRequests(recoverable.key).length, 1);
            checks.controls.recovered = true;
            await record('large.resume', recovery, { receiptVerified: true, complete: true, created: resumed.receipt.summary.created_posted });
            await run(['workspace', 'use', workspace]);
        }

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

        if (reviewVariants) {
            assert.ok(!isolated && !legacyComparison);
            assert.deepEqual(Object.keys(reviewVariants).sort(), ['base', 'terminal']);
            const matrix = [{ name: 'base', sampled: false }, { name: 'terminal', sampled: false },
                { name: 'terminal', sampled: true }, { name: 'base', sampled: true }];
            const beforeLedger = await sampleConfirmDatabase(psqlArgs, workspace, email, batch);
            let expectedView;
            const results = [];
            for (const [index, { name, sampled }] of matrix.entries()) {
                const state = join(work, `review-comparison-${index}`);
                await cp(primaryState, state, { recursive: true });
                const start = observed.length;
                try {
                    const result = await run(['import', 'review', batch, '--choices', choice, ...scope],
                        { state, client: reviewVariants[name], sampleMemory: sampled });
                    assert.equal(mutatingRequests(start).length, 0);
                    assert.equal(result.value.can_commit, true);
                    assert.equal(result.value.summary.create_posted, expected.posted);
                    assert.equal(result.value.summary.duplicate, expected.duplicates);
                    assert.equal(result.value.summary.relations_accepted, expected.relations);
                    assert.deepEqual(result.value.item_counts, { confirmed: 9729, pending: 0 });
                    const { plan_id, ...view } = result.value;
                    assert.ok(plan_id);
                    expectedView ??= view;
                    assert.deepEqual(view, expectedView);
                    const temporary = evidence.workspaceDisk.at(-1);
                    assert.equal(temporary.after.scratch, 0);
                    assert.equal(temporary.after.scratchDirectories, 0);
                    results.push({ name, sampled, label: processes.at(-1).label, ms: result.ms, rssBytes: result.rssBytes });
                } finally {
                    await rm(state, { recursive: true, force: true });
                }
            }
            const afterLedger = await sampleConfirmDatabase(psqlArgs, workspace, email, batch);
            assert.equal(afterLedger.ledgerSeq, beforeLedger.ledgerSeq);
            assert.equal(afterLedger.originalReceipt, null);
            checks.reviewComparison = { seedPreparations: 1, sharedLedgerVersion: true, zeroWrites: true, results };
            checks.completed = false;
            return;
        }

        const reviewStart = observed.length;
        const review = await run(['import', 'review', batch, '--choices', choice, ...scope]);
        assert.equal(review.value.can_commit, true);
        assert.deepEqual(review.value.summary, {
            create_posted: expected.posted, duplicate: expected.duplicates, relations_accepted: expected.relations,
            relations_deferred: 0, relations_failed: 0, relations_ignored: 0, relations_removed: 0, skip: 0,
        });
        assert.deepEqual(review.value.item_counts, { confirmed: 9729, pending: 0 });
        assert.equal(mutatingRequests(reviewStart).length, 0);
        const reviewDisk = evidence.workspaceDisk?.at(-1);
        if (!legacyComparison) {
            assert.equal(reviewDisk?.flow, 'review', 'Missing cash review disk samples');
            assert.ok(reviewDisk.samples > 2 && reviewDisk.peak.scratch > 0, 'Missing temporary cash review peak');
            assert.equal(reviewDisk.after.scratch, 0, 'Cash review left temporary blocks');
            assert.equal(reviewDisk.after.scratchDirectories, 0, 'Cash review left a temporary directory');
            assert.ok(Number.isSafeInteger(reviewDisk.reusableBytes), 'Missing reusable SQLite space');
        }
        await record('large.review', review, {
            summary: review.value.summary, itemCounts: review.value.item_counts, reviewDisk,
            gcObserver: review.process.gc?.available ?? false,
        });

        const confirmation = {
            workspace, originalKey: batch, started: observedTime(), ended: null,
            posts: [], databaseDuring: [], databaseAfterFailure: [],
        };
        checks.confirm = confirmation;
        const sampleDatabase = () => sampleConfirmDatabase(psqlArgs, workspace, email, batch);
        const monitorController = new AbortController();
        const monitor = collectConfirmObservations(sampleDatabase, confirmation.databaseDuring, monitorController.signal);
        let confirm;
        try {
            confirm = await run(['import', 'confirm', batch, '--review', review.value.review_revision, ...scope]);
            confirmation.ended = observedTime();
            confirmation.process = confirm.process;
        } catch (error) {
            confirmation.ended = observedTime();
            confirmation.failure = error.message;
            confirmation.process = error.measurement ?? null;
            monitorController.abort();
            await monitor;
            const post = writeRequests(batch)[0];
            const bodyForwarded = post?.events.find(event => event.event === 'proxy_forwarded_body');
            const activeWindow = bodyForwarded
                ? confirmation.databaseDuring.filter(entry => entry.started?.monotonicMs >= bodyForwarded.monotonicMs)
                : [];
            try {
                confirmation.recovery = await inspectLostConfirmation(
                    sampleDatabase, confirmation.databaseAfterFailure, activeWindow, post ? {} : { waitMs: 0 },
                );
            } catch (recoveryError) {
                confirmation.recovery = { state: 'diagnostics_failed', error: recoveryError.message };
            }
            throw error;
        } finally {
            monitorController.abort();
            await monitor;
            confirmation.posts = writeRequests(batch);
        }
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
        const writes = writeRequests(batch);
        assert.equal(writes.length, 1);
        assert.equal(writes[0].key, batch);
        assert.equal(writes[0].status, legacyComparison ? 200 : 202);
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
        const first = await run(listArgs, { state: coldState, flow: 'cold-snapshot' });
        const expectedPage = readCashList(first.value, workspace, '2', false);
        assert.equal(snapshots(firstStart), 1);
        await record('large.cash-first', first, { ledgerSeq: '2', snapshotDownloads: 1, rows: expectedPage.length });

        const onlineStart = observed.length;
        const online = await run(listArgs, { state: coldState, flow: 'revalidated' });
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
        const refresh = await run(listArgs, { state: coldState, flow: 'refreshed-snapshot' });
        assert.deepEqual(readCashList(refresh.value, workspace, '3', false), expectedPage);
        assert.notEqual(refresh.value.synced_at, first.value.synced_at);
        const accountList = await run(['account', 'list', ...scope], { state: coldState });
        const refreshed = accountList.value.accounts.find(account => account.id === change.value.account.account_id);
        assert.equal(refreshed?.name, marker);
        assert.deepEqual(refreshed.currencies, ['CNY']);
        assert.equal(snapshots(refreshStart), 1);
        assert.equal(writeRequests(batch).length, 1);
        await record('large.cash-refresh', refresh, {
            ledgerSeq: '3', snapshotDownloads: 1, rows: expectedPage.length, newAccount: refreshed.id,
        });
        checks.completed = true;
    } catch (error) {
        checks.completed = false;
        const assertionSite = error.code === 'ERR_ASSERTION' &&
            error.stack.split('\n').find(line => /^\s+at .*large-bench\.mjs:\d+:\d+/.test(line));
        checks.failure = assertionSite ? `Synthetic benchmark assertion failed (${assertionSite.trim()})` : error.message;
        checks.transportErrors = observed.filter(item => item.transportError);
        throw new Error(checks.failure);
    } finally {
        try {
            evidence.runnerFactsAfter = collectRunnerFacts();
            for (const process of processes) if (process.liveMemoryFile) {
                try {
                    await cp(join(outputDir, process.liveMemoryFile), join(evidenceDir, process.liveMemoryFile));
                } catch (error) {
                    process.liveMemoryArtifactError = error.code ?? 'copy_failed';
                }
            }
            await writeFile(evidencePath, JSON.stringify(evidence, null, 2) + '\n');
        } finally {
            await closeServer(proxy);
            for (const service of [worker, server]) {
                if (!service?.pid || service.exitCode !== null || service.signalCode !== null) continue;
                const finished = once(service, 'exit');
                process.kill(-service.pid, 'SIGTERM');
                await Promise.race([finished, pause(5_000, undefined, { ref: false })]);
                if (service.exitCode === null && service.signalCode === null) {
                    process.kill(-service.pid, 'SIGKILL');
                    await finished;
                }
            }
            serviceMemory?.remove();
            await logs.close();
            await rm(work, { recursive: true, force: true });
        }
    }
}

await main();

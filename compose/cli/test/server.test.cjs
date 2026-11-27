const assert = require('node:assert/strict');
const { spawn, execFile, execFileSync } = require('node:child_process');
const { randomUUID } = require('node:crypto');
const { createServer } = require('node:http');
const { mkdtempSync, readFileSync, writeFileSync, truncateSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve } = require('node:path');
const { once } = require('node:events');
const { test } = require('node:test');
const { setTimeout: sleep } = require('node:timers/promises');

const fixture = resolve(__dirname, '../../backend/src/test/resources/cash-import-slice/alipay-01.csv');

const postgres = process.env.FT_TEST_POSTGRES_URL;
if (postgres) assert.match(new URL(postgres).pathname, /_test$/);
const backends = postgres ? ['sqlite', 'postgres'] : ['sqlite'];
for (const database of backends) test(`the installed CLI resumes the original cash import after the response is lost on ${database}`, async () => {
    const dir = mkdtempSync(join(tmpdir(), 'ft-server-'));
    const reservation = createServer();
    reservation.listen(0, '127.0.0.1');
    await once(reservation, 'listening');
    const port = reservation.address().port;
    await new Promise((resolve) => reservation.close(resolve));
    const origin = `http://127.0.0.1:${port}`;
    const backend = spawn(resolve(__dirname, '../../backend/build/install/backend/bin/backend'), [], {
        env: {
            ...process.env,
            PORT: String(port),
            FT_HOST: '127.0.0.1',
            FT_WEB_ORIGIN: 'http://127.0.0.1:5173',
            FT_IMPORT_STAGING_BACKEND: "memory", FT_IMPORT_STAGING_ALLOW_MEMORY: "1",
            FT_DATABASE_URL: database === 'postgres' ? postgres : `sqlite+pysqlite:///${join(dir, 'ledger_test.db')}`,
        },
        stdio: ['ignore', 'ignore', 'pipe'],
    });
    let backendLogs = '';
    backend.stderr.on('data', (data) => { backendLogs = (backendLogs + data).slice(-5000); });
    const requestedPaths = [];
    let writeCount = 0;
    let acceptedConfirmation;
    let sentBody;
    let sentKey;
    let manualWrites = 0;
    let transferWrites = 0;
    let transferReceipt;
    let maintenancePreviews = 0;
    let maintenanceCommit;
    const maintenanceResolutions = [];
    let loseMaintenanceResult = false;
    let afterManualCommit;
    let lostManualRejection;
    let authorization;
    let dropAccountRequest = false;
    let damageAccountReceipt = false;
    let rejectReceiptLookup = false;
    let oversizedImportReply;
    const oversizedImportAttempts = [];
    const proxy = createServer(async (req, res) => {
        try {
            requestedPaths.push(new URL(req.url, origin).pathname);
            const body = Buffer.concat(await Array.fromAsync(req));
            if (oversizedImportReply && req.method === 'POST' && req.url.startsWith('/api/v1/cash-import/write-set?')) {
                oversizedImportAttempts.push(req.headers['idempotency-key']);
                res.writeHead(413, { 'Content-Type': 'application/json' }).end(JSON.stringify(oversizedImportReply));
                return;
            }
            if (dropAccountRequest && req.method === 'POST' && req.url.startsWith('/api/v1/accounts?')) {
                dropAccountRequest = false;
                req.socket.destroy();
                return;
            }
            if (rejectReceiptLookup && req.method === 'GET' && req.url.startsWith('/api/v1/cash-import/write-set/receipt/pages?')) {
                rejectReceiptLookup = false;
                res.writeHead(409, { 'Content-Type': 'application/json' }).end(JSON.stringify({
                    error: { code: 'ledger_base_changed' }, commit_state: 'not_committed',
                }));
                return;
            }
            const response = await fetch(origin + req.url, {
                method: req.method,
                headers: {
                    ...req.headers,
                    host: `127.0.0.1:${port}`,
                    'accept-encoding': 'identity',
                },
                body: body.length ? body : undefined,
            });
            const text = await response.text();
            if (req.method === 'POST' && req.url === '/api/v1/cashflow/transfer/commands') {
                transferWrites++;
                if (response.ok && transferWrites === 1) {
                    transferReceipt = JSON.parse(text);
                    req.socket.destroy();
                    return;
                }
            }
            if (damageAccountReceipt && req.method === 'GET' && req.url.startsWith('/api/v1/cash-mutations/receipts/by-key?') && response.ok) {
                const receipt = JSON.parse(text);
                if (receipt.operation_kind === 'account_create') {
                    damageAccountReceipt = false;
                    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify({ ...receipt, account: null }));
                    return;
                }
            }
            if (req.method === 'POST' && req.url === '/api/v1/cash-mutations/plans') maintenancePreviews++;
            if (req.method === 'POST' && req.url === '/api/v1/cash-mutations/resolve') maintenanceResolutions.push(JSON.parse(body));
            if (loseMaintenanceResult && req.method === 'POST' && req.url.endsWith('/commit') && response.ok) {
                loseMaintenanceResult = false;
                maintenanceCommit = { receipt: JSON.parse(text), key: req.headers['idempotency-key'] };
                req.socket.destroy();
                return;
            }
            if (req.method === 'POST' && req.url.startsWith('/api/v1/cash-import/write-set?')) {
                authorization = req.headers.authorization;
                writeCount++;
                if (response.ok && writeCount === 1) {
                    assert.equal(response.status, 202);
                    acceptedConfirmation = JSON.parse(text);
                    sentKey = req.headers['idempotency-key'];
                    sentBody = JSON.parse(body);
                    req.socket.destroy();
                    return;
                }
            }
            if (req.method === 'POST' && req.url === '/api/v1/cash-records/commands') {
                manualWrites++;
                if (response.ok && afterManualCommit) {
                    const done = afterManualCommit;
                    afterManualCommit = undefined;
                    done(JSON.parse(text));
                    req.socket.destroy();
                    return;
                }
                if (response.status === 409) {
                    lostManualRejection = JSON.parse(text);
                    req.socket.destroy();
                    return;
                }
            }
            const readPages = response.headers.get('X-FT-Read-Pages');
            res.writeHead(response.status, { 'Content-Type': 'application/json',
                ...(readPages ? { 'X-FT-Read-Pages': readPages } : {}) }).end(text);
        } catch {
            res.writeHead(502).end('{}');
        }
    });
    try {
        for (let attempt = 0; attempt < 150; attempt++) {
            if (backend.exitCode !== null) assert.fail(`Backend exited: ${backendLogs}`);
            const ready = await fetch(origin + '/health').then((response) => response.ok, () => false);
            if (ready) break;
            if (attempt === 149) assert.fail(`Backend did not start: ${backendLogs}`);
            await sleep(100);
        }
        proxy.listen(0, '127.0.0.1');
        await once(proxy, 'listening');
        const [archive] = JSON.parse(execFileSync('npm', [
            'pack', '--json', '--pack-destination', dir, resolve(__dirname, '../build/npm'),
        ], { encoding: 'utf8' }));
        execFileSync('npm', ['install', '--no-audit', '--no-fund', '--ignore-scripts', join(dir, archive.filename)], { cwd: dir });
        const bin = join(dir, 'node_modules', '.bin', 'ft');
        const env = {
            ...process.env, FT_CLI_SERVER: `http://127.0.0.1:${proxy.address().port}`,
            FT_CLI_STATE_DIR: join(dir, 'state'), FT_CLI_STORAGE_KEY: Buffer.alloc(32, 23).toString('base64'),
        };
        const run = (args, input = '', override = {}) => new Promise((resolve, reject) => {
            const child = execFile(bin, [...args, '--json'], { cwd: dir, env: { ...env, ...override }, timeout: 20000 }, (error, stdout, stderr) => {
                if (error && typeof error.code !== 'number') return reject(error);
                try { resolve({ status: error?.code || 0, value: JSON.parse(stdout), stderr }); }
                catch (failure) { reject(new Error(`Invalid CLI response: ${stdout} ${stderr}`, { cause: failure })); }
            });
            child.stdin.end(input);
        });
        const ok = async (args, input, override) => {
            const output = await run(args, input, override);
            assert.equal(output.status, 0, JSON.stringify(output));
            return output.value;
        };
        await ok(['auth', 'register', '--email', `synthetic-${randomUUID()}@example.test`, '--password-stdin'], 'synthetic password both sides');
        const workspace = (await ok(['workspace', 'create', '--name', 'Receipt integration'])).active_workspace_id;
        const account = (await ok(['account', 'create', '--name', 'Synthetic wallet', '--type', 'cash', '--currency', 'CNY'])).account;
        damageAccountReceipt = true;
        const unproved = await run(['account', 'create', '--name', 'Receipt identity check', '--type', 'cash', '--currency', 'CNY']);
        assert.equal(unproved.value.error?.code, 'result_unknown');
        const proved = await ok(['cash', 'resume', unproved.value.error.operation_id]);
        assert.equal(proved.account.name, 'Receipt identity check');
        const lines = new TextDecoder('gb18030').decode(readFileSync(fixture)).split(/\r?\n/);
        const statement = join(dir, 'synthetic.csv');
        writeFileSync(statement, [...lines.slice(0, 7), lines[9], ''].join('\r\n'));
        const prepared = await ok(['import', 'prepare', statement, '--workspace', workspace]);
        assert.ok(prepared.source_accounts.length > 0);
        const invalidMapping = join(dir, 'invalid-account-choice.json');
        writeFileSync(invalidMapping, JSON.stringify({
            expected_session_revision: Number(prepared.revision), previous_plan_id: null,
            account_selections: [{
                source_account_ref: prepared.source_accounts[0].source_account_ref,
                account_id: '9223372036854775807', expected_account_revision: 1,
            }],
            row_choices: [], relation_choices: [], entry_choices: [],
        }));
        assert.equal((await run(['import', 'review', prepared.batch_id, '--choices', invalidMapping])).value.error?.code, 'account_not_found');
        let revision = prepared.revision;
        for (const group of prepared.source_accounts) {
            const review = await ok(['import', 'map', prepared.batch_id, '--revision', revision,
                '--source-account', group.source_account_ref, '--account', account.account_id, '--account-revision', account.account_revision]);
            revision = review.review_revision;
        }
        const review = await ok(['import', 'review', prepared.batch_id]);
        assert.equal(review.can_commit, true);
        dropAccountRequest = true;
        const lostAccount = await run(['import', 'account', 'create', prepared.batch_id, '--revision', revision,
            '--name', 'Synthetic wallet', '--type', 'cash', '--currency', 'CNY']);
        assert.equal(lostAccount.value.error?.code, 'result_unknown');
        const blocked = await run(['import', 'confirm', prepared.batch_id, '--review', revision]);
        assert.equal(blocked.value.error?.code, 'account_result_unknown');
        assert.equal(writeCount, 0);
        const accountOperation = (await ok(['cash', 'pending'])).operations[0].id;
        const accountRejected = await run(['cash', 'resume', accountOperation]);
        assert.equal(accountRejected.value.error?.code, 'account_name_unavailable');
        const lost = await run(['import', 'confirm', prepared.batch_id, '--review', revision]);
        assert.equal(lost.value.error?.code, 'result_unknown', JSON.stringify(lost));
        assert.equal(lost.value.error.commit_state, 'unknown');
        assert.equal(writeCount, 1);
        assert.equal(sentKey, prepared.batch_id);
        assert.equal(sentBody.base_ledger_seq, review.ledger_seq);
        assert.equal(acceptedConfirmation.operation_id, sentKey);
        assert.equal(acceptedConfirmation.status, 'accepted');

        rejectReceiptLookup = true;
        const failedLookup = await run(['import', 'resume', prepared.batch_id]);
        assert.equal(failedLookup.value.error?.code, 'result_unknown');
        assert.equal(failedLookup.value.error.commit_state, 'unknown');
        assert.equal(writeCount, 1);
        const cancelStart = requestedPaths.length;
        const alreadyCommitted = await ok(['import', 'cancel-confirmation', prepared.batch_id]);
        assert.equal(alreadyCommitted.status, 'committed');
        assert.equal(alreadyCommitted.receipt.receipt_id, alreadyCommitted.receipt_id);
        assert.ok(requestedPaths.slice(cancelStart).includes('/api/v1/cash-import/write-set/receipt/pages'));
        assert.ok(!requestedPaths.slice(cancelStart).some((path) => path.startsWith('/api/v1/ledger/')));
        const resumeStart = requestedPaths.length;
        const recovered = await ok(['import', 'resume', prepared.batch_id]);
        const resumePaths = requestedPaths.slice(resumeStart);
        assert.ok(resumePaths.filter(path => path === '/api/v1/cash-import/write-set/receipt/pages').length >= 10);
        assert.ok(!resumePaths.some(path => path.startsWith('/api/v1/ledger/')));
        assert.ok(!resumePaths.includes('/api/v1/cash-import/write-set/receipt'));
        assert.equal(recovered.receipt.receipt_id, alreadyCommitted.receipt_id);
        const reopened = await ok(['import', 'result', prepared.batch_id]);
        assert.deepEqual(reopened, recovered);
        const statusStart = requestedPaths.length;
        const confirmedOperation = await ok(['import', 'status', prepared.batch_id]);
        assert.ok(!requestedPaths.slice(statusStart).some((path) => path.startsWith('/api/v1/ledger/')));
        assert.equal(confirmedOperation.status, 'committed');
        assert.equal(confirmedOperation.receipt_id, recovered.receipt.receipt_id);
        assert.equal(confirmedOperation.operation_id, prepared.batch_id);
        assert.equal(recovered.receipt.committed, true);
        assert.equal(recovered.items_complete, true);
        assert.equal(recovered.page.items.filter((item) => item.result_kind === 'row' && item.transaction_id).length, 1);
        const stale = await run(['import', 'confirm', prepared.batch_id, '--review', prepared.revision]);
        assert.equal(stale.value.error.code, 'plan_changed');
        assert.equal(writeCount, 1);
        const ledger = await ok(['cash', 'list']);
        assert.equal(ledger.page.items.length, 1);
        const balances = await ok(['account', 'show', account.account_id]);
        assert.equal(balances.balances.find((item) => item.currency === 'CNY').balance, '-13.00');

        const rejectedBatch = await ok(['import', 'prepare', statement]);
        const reviewedAgain = join(dir, 'repeat-import-review.json');
        const revisedChoices = { expected_session_revision: Number(rejectedBatch.revision), previous_plan_id: null,
            account_selections: rejectedBatch.source_accounts.map(({ source_account_ref }) => ({ source_account_ref,
                account_id: account.account_id, expected_account_revision: Number(account.account_revision) })),
            row_choices: [], relation_choices: [] };
        writeFileSync(reviewedAgain, JSON.stringify(revisedChoices));
        const initialRejectedPlan = await ok(['import', 'review', rejectedBatch.batch_id, '--choices', reviewedAgain]);
        revisedChoices.expected_session_revision = Number(initialRejectedPlan.review_revision);
        revisedChoices.previous_plan_id = initialRejectedPlan.plan_id;
        writeFileSync(reviewedAgain, JSON.stringify(revisedChoices));
        const rejectedPlan = await ok(['import', 'review', rejectedBatch.batch_id, '--choices', reviewedAgain]);
        assert.equal(rejectedPlan.can_commit, true);
        oversizedImportReply = { error: { code: 'payload_too_large' } };
        const opaque = await run(['import', 'confirm', rejectedBatch.batch_id, '--review', rejectedPlan.review_revision]);
        assert.equal(opaque.value.error.code, 'result_unknown');
        assert.equal(opaque.value.error.commit_state, 'unknown');
        assert.match(opaque.stderr, /ft import: submit_write_set/);
        assert.doesNotMatch(opaque.stderr, /ft import: (read_files|discover_relations)/);
        const listRejectedBatch = async () => (await ok(['import', 'batches'])).batches.find(({ batch_id }) =>
            batch_id === rejectedBatch.batch_id);
        assert.equal((await listRejectedBatch()).status, 'result_pending');
        oversizedImportReply.commit_state = 'not_committed';
        const refusal = await run(['import', 'resume', rejectedBatch.batch_id]);
        assert.equal(refusal.value.error.code, 'payload_too_large');
        assert.equal(refusal.value.error.commit_state, 'not_committed');
        assert.equal((await listRejectedBatch()).status, 'open');
        const retainedImport = await ok(['import', 'resume', rejectedBatch.batch_id]);
        assert.equal(retainedImport.files.length, 1);
        assert.deepEqual(oversizedImportAttempts, [rejectedBatch.batch_id, rejectedBatch.batch_id]);
        oversizedImportReply = undefined;
        assert.equal(writeCount, 1);
        assert.equal((await ok(['cash', 'list'])).page.items.length, 1);

        const add = ['cash', 'add', '--workspace', workspace, '--account', account.account_id, '--amount', '0.123456789012345678',
            '--currency', 'CNY', '--occurred-at', '2026-11-26T01:02:03Z', '--type', 'income', '--subtype', 'not_applicable'];
        const processKilled = spawn(bin, [...add, '--json'], { cwd: dir, env, stdio: 'ignore' });
        const stop = once(processKilled, 'exit');
        const first = new Promise((resolve) => {
            afterManualCommit = (receipt) => {
                processKilled.kill('SIGKILL');
                resolve(receipt);
            };
        });
        let killTimeout;
        const firstReceipt = await Promise.race([first, new Promise((resolve, reject) => {
            killTimeout = setTimeout(() => {
                processKilled.kill('SIGKILL');
                reject(new Error('Manual write did not reach the committed response boundary'));
            }, 15000);
        })]).finally(() => clearTimeout(killTimeout));
        assert.equal((await stop)[1], 'SIGKILL');
        const pending = (await ok(['cash', 'pending'])).operations;
        assert.equal(pending.length, 1);
        const replayed = await ok(['cash', 'resume', pending[0].id]);
        assert.equal(replayed.receipt.receipt_id, firstReceipt.receipt_id);
        assert.equal(manualWrites, 1);
        const another = await ok(add);
        assert.notEqual(another.receipt.receipt_id, firstReceipt.receipt_id);
        assert.equal(manualWrites, 2);

        const incompatible = add.map((argument) => argument === 'CNY' ? 'USD' : argument);
        const rejectedOriginal = await run(incompatible);
        assert.equal(rejectedOriginal.value.error?.code, 'result_unknown');
        assert.equal(lostManualRejection?.error?.code, 'account_changed');
        const failedOperation = (await ok(['cash', 'pending'])).operations[0].id;
        const recoveredRejection = await run(['cash', 'resume', failedOperation]);
        assert.equal(recoveredRejection.value.error?.code, 'account_changed');
        assert.equal(recoveredRejection.value.error?.commit_state, 'not_committed');
        assert.deepEqual((await ok(['cash', 'pending'])).operations, []);
        assert.equal(manualWrites, 3);

        const invitation = await ok(['workspace', 'invite', 'create', '--role', 'viewer']);
        const anonymous = { FT_CLI_STATE_DIR: join(dir, 'anonymous'), FT_CLI_STORAGE_KEY: undefined };
        assert.equal((await ok(['workspace', 'invite', 'preview', invitation.token], '', anonymous)).role, 'viewer');
        const viewer = { ...anonymous, FT_CLI_STORAGE_KEY: Buffer.alloc(32, 27).toString('base64') };
        await ok(['auth', 'register', '--email', `viewer-${randomUUID()}@example.test`, '--password-stdin'], 'synthetic viewer password', viewer);
        const access = await ok(['workspace', 'invite', 'accept', invitation.token], '', viewer);
        assert.equal(access.active_workspace_id, workspace);
        assert.equal(access.workspaces.find((item) => item.id === workspace).role, 'viewer');
        assert.equal((await ok(['cash', 'list'], '', viewer)).page.items.length, 3);
        assert.equal((await run(add, '', viewer)).value.error.code, 'workspace_forbidden');

        const categoryDirectory = await ok(['category', 'list']);
        const parent = await ok(['category', 'create', '--name', 'Synthetic parent', '--revision', categoryDirectory.revision]);
        const parentDirectory = await ok(['category', 'list']);
        const child = await ok(['category', 'create', '--name', 'Synthetic category', '--parent', parent.id,
            '--description', 'Keep this description', '--revision', parentDirectory.revision]);
        const childDirectory = await ok(['category', 'list']);
        const category = await ok(['category', 'edit', child.id, '--name', 'Synthetic renamed category', '--revision', childDirectory.revision]);
        assert.equal(category.parent_id, parent.id);
        assert.equal(category.description, 'Keep this description');
        const current = await ok(['cash', 'list']);
        const projection = current.page.items.find((item) => item.record_id === sentBody.changes.transactions[0].values.record_id)
            || current.page.items.find((item) => item.source_type === 'alipay');
        assert.ok(projection);
        const classified = await ok(['cash', 'categorize', projection.projection_id, '--category', category.id, '--ledger-seq', current.ledger_seq]);
        assert.equal(classified.category_id, category.id);
        const detail = (await ok(['cash', 'record', projection.transaction_id])).record;
        const input = join(dir, 'edit.json');
        writeFileSync(input, JSON.stringify({
            intent: 'edit_entry', transaction_id: detail.transaction_id, expected_revision: Number(detail.row_revision),
        }));
        const invalidPreview = await run(['cash', 'edit', '--input', input]);
        assert.equal(invalidPreview.value.error?.code, 'import_input_invalid');
        assert.equal(maintenancePreviews, 0);
        writeFileSync(input, JSON.stringify({
            intent: 'edit_entry', transaction_id: detail.transaction_id, expected_revision: Number(detail.row_revision),
            entry_id: detail.entry.entry_id, expected_entry_revision: Number(detail.entry.revision), patch: { note: 'synthetic edited note' },
        }));
        const plan = await ok(['cash', 'edit', '--input', input]);
        assert.equal(plan.can_commit, true);
        loseMaintenanceResult = true;
        const unclear = await run(['cash', 'commit', plan.plan_id, '--review', String(plan.plan_revision)]);
        assert.equal(unclear.value.error?.code, 'result_unknown');
        // Recreate the encrypted command record written by the previous ft version.
        execFileSync(process.execPath, ['--input-type=module', '-e', `
            import { openStore } from './node_modules/finance-tracker-cli/lib/host.mjs';
            import assert from 'node:assert/strict';
            const database = openStore(process.env.FT_CLI_SERVER, false);
            const id = process.argv[1];
            const slot = database.slots('').find(slot => slot.endsWith('.command.' + id));
            assert.ok(slot);
            const command = JSON.parse(database.read(slot));
            delete command.planId;
            database.write(slot, JSON.stringify(command));
            database.close(true);
        `, unclear.value.error.operation_id], { cwd: dir, env });
        const edited = await ok(['cash', 'resume', unclear.value.error.operation_id]);
        assert.equal(edited.receipt.committed, true);
        assert.equal(edited.receipt.receipt_id, maintenanceCommit.receipt.receipt_id);
        assert.deepEqual(maintenanceResolutions, [{ plan_id: plan.plan_id, idempotency_key: maintenanceCommit.key }]);
        assert.equal((await ok(['cash', 'show', projection.projection_id])).evidence.projection.note, 'synthetic edited note');

        const next = await ok(['import', 'prepare', join(dir, 'missing.csv'), statement]);
        assert.deepEqual(new Set(next.files.map((file) => file.status)), new Set(['parsed', 'failed']));
        const accountCreate = ['import', 'account', 'create', next.batch_id, '--revision', next.revision,
            '--name', 'Temporary import account', '--type', 'cash', '--currency', 'CNY'];
        const initial = await ok(accountCreate);
        const duplicate = await run(accountCreate);
        assert.equal(duplicate.value.error.code, 'account_name_unavailable');
        assert.equal(duplicate.value.error.commit_state, 'not_committed');
        const supplemented = await ok(['import', 'account', 'currencies', next.batch_id, '--revision', next.revision,
            '--account', initial.account.account_id, '--account-revision', initial.account.account_revision, '--currency', 'USD']);
        assert.deepEqual(supplemented.account.currencies, ['CNY', 'USD']);
        const undone = await ok(['import', 'account', 'undo', next.batch_id, '--revision', next.revision]);
        assert.deepEqual(undone.account_changes.map((item) => item.status), ['restored', 'restored']);
        assert.equal((await run(['account', 'show', initial.account.account_id])).status, 1);
        const cancelled = await ok(['import', 'cancel', next.batch_id, '--revision', next.revision]);
        assert.equal(cancelled.cancelled, true);
        assert.deepEqual(cancelled.account_changes, []);

        const sampleFolder = resolve(fixture, '..');
        const oversized = join(dir, 'oversized.csv');
        writeFileSync(oversized, '');
        truncateSync(oversized, 100 * 1024 * 1024 + 1);
        const formats = await ok(['import', 'prepare', join(sampleFolder, 'wechat-01.xlsx'),
            join(sampleFolder, 'ccb-debit-01.xls'), join(sampleFolder, 'icbc-debit-01.pdf'), oversized]);
        assert.deepEqual(formats.files.map((file) => file.status), ['parsed', 'parsed', 'parsed', 'failed']);
        assert.equal(formats.files[3].code, 'import_file_too_large');
        const listed = (await ok(['import', 'batches'])).batches.find((batch) => batch.batch_id === formats.batch_id);
        assert.equal(listed.file_count, 3);

        const accountData = (await ok(['account', 'show', account.account_id])).account;
        const ensure = (current, currency) => ok(['import', 'account', 'currencies', formats.batch_id, '--revision', formats.revision,
            '--account', current.account_id, '--account-revision', String(current.account_revision), '--currency', currency]);
        const previousLedger = await ok(['cash', 'list']);
        const redundant = await ensure(accountData, ' cNy ');
        assert.equal(redundant.changed, false);
        assert.equal(redundant.account.account_revision, accountData.account_revision);
        assert.equal((await ok(['cash', 'list'])).ledger_seq, previousLedger.ledger_seq);
        await ensure(accountData, 'USD');
        assert.deepEqual((await ok(['import', 'account', 'undo', formats.batch_id, '--revision', formats.revision]))
            .account_changes.map(item => item.status), ['restored']);
        const restored = (await ok(['account', 'show', account.account_id])).account;
        await ensure(restored, 'USD');
        assert.deepEqual((await ok(['import', 'account', 'undo', formats.batch_id, '--revision', formats.revision]))
            .account_changes.map(item => item.status), ['restored']);
        const settled = (await ok(['account', 'show', account.account_id])).account;
        const withUsd = await ensure(settled, 'USD');
        const external = await fetch(origin + `/api/v1/accounts/by-id/${account.account_id}?workspace_id=${workspace}`, {
            method: 'PATCH',
            headers: { Authorization: authorization, 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() },
            body: JSON.stringify({ expected_revision: Number(withUsd.account.account_revision), patch: { currencies: ['CNY', 'USD', 'EUR'] } }),
        });
        assert.equal(external.status, 200);
        const externalAccount = await external.json();
        const latest = await ensure(externalAccount, 'JPY');
        const conflicted = await ok(['import', 'account', 'undo', formats.batch_id, '--revision', formats.revision]);
        assert.equal(conflicted.account_changes.length, 2);
        assert.ok(conflicted.account_changes.every((item) => item.code === 'account_changed'));
        const latestDetails = await ok(['account', 'show', account.account_id]);
        assert.deepEqual(latestDetails.account.currencies, latest.account.currencies);

        const target = (await ok(['account', 'create', '--name', 'Transfer destination', '--type', 'cash', '--currency', 'CNY'])).account;
        const transfer = await run(['cash', 'transfer', '--from-account', account.account_id, '--to-account', target.account_id,
            '--from-currency', 'CNY', '--to-currency', 'CNY', '--amount', '5.80', '--date', '2026-11-26', '--time', '04:00']);
        assert.equal(transfer.value.error?.code, 'result_unknown');
        const transferRecovery = await ok(['cash', 'resume', transfer.value.error.operation_id]);
        assert.equal(transferRecovery.receipt.receipt_id, transferReceipt.receipt_id);
        assert.equal(transferWrites, 1);
        const targetBalance = await ok(['account', 'show', target.account_id]);
        assert.equal(targetBalance.balances.find(item => item.currency === 'CNY').balance, '5.80');
    } finally {
        if (proxy.listening) await new Promise((resolve) => proxy.close(resolve));
        if (backend.exitCode === null) {
            backend.kill('SIGTERM');
            await once(backend, 'exit');
        }
        rmSync(dir, { recursive: true, force: true });
    }
});

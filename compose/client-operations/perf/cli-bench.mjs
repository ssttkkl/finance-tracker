import assert from 'node:assert/strict';
import { execFile, spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { once } from 'node:events';
import { appendFile, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createServer, request } from 'node:http';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { performance } from 'node:perf_hooks';
import { setTimeout as delay } from 'node:timers/promises';
import { promisify } from 'node:util';
import { ordinaryImportSources, ordinaryLedgerCsv, range } from './cli-fixture.mjs';

const exec = promisify(execFile);
const PASSWORD = 'synthetic-perf-only-password';
const EMAIL = 'performance@example.test';
const bindAddress = '127.0.0.1';
const root = resolve(import.meta.dirname, '../../..');
const events = process.env.FT_PERF_EVENTS;
const installedFt = resolve(process.env.FT_PERF_FT ?? '.');
const backend = resolve(process.env.FT_PERF_BACKEND ?? join(root, 'compose/backend/build/install/backend/bin/backend'));
const hostEnv = Object.fromEntries(Object.entries(process.env).filter(([key]) =>
    !key.startsWith('FT_') && key !== 'NODE_OPTIONS'));

assert.ok(events, 'FT_PERF_EVENTS is required');
assert.equal(basename(installedFt), 'ft', 'FT_PERF_FT must point to the installed ft entry');
assert.equal(basename(dirname(installedFt)), '.bin', 'Install the npm archive before benchmarking');
assert.ok(['linux', 'darwin'].includes(process.platform), 'GNU time (Linux) or BSD time (macOS) is required');

function sampleCount(name, fallback) {
    const number = process.env[name] === undefined ? fallback : Number(process.env[name]);
    assert.ok(Number.isInteger(number) && number >= 0 && number <= 1000, `invalid ${name}`);
    return number;
}

const warmups = sampleCount('FT_PERF_WARMUPS', 5);
const samples = sampleCount('FT_PERF_SAMPLES', 30);
assert.ok(samples > 0, 'at least one measured sample is required');

const ids = [
    'cli.help',
    'cli.cash-list',
    'cli.cash-summary',
    'cli.import-items',
    'cli.import-item',
    'cli.import-processing',
];
const selected = new Set(process.env.FT_PERF_ONLY?.split(',') ?? ids);
for (const id of selected) assert.ok(ids.includes(id), `unknown CLI benchmark ${id}`);
const needsImport = [...selected].some(id => id.startsWith('cli.import-'));
const needsLedger = needsImport || selected.has('cli.cash-list') || selected.has('cli.cash-summary');

const work = await mkdtemp(join(tmpdir(), 'ft-perf-cli-'));
const env = {
    ...hostEnv,
    FT_CLI_STATE_DIR: join(work, 'state'),
    FT_CLI_STORAGE_KEY: randomBytes(32).toString('base64'),
};
const log = [];

function listen(server) {
    server.listen(0, bindAddress);
    return once(server, 'listening').then(() => server.address().port);
}

function close(server) {
    return new Promise((done, fail) => server.close(error => error ? fail(error) : done()));
}

async function run(args, { measured = false, stdin } = {}) {
    const command = measured ? '/usr/bin/time' : installedFt;
    const timeArgs = process.platform === 'linux' ? ['-f', 'ft_rss_kib %M'] : ['-l'];
    const argv = [...(measured ? [...timeArgs, installedFt] : []), ...args, '--json'];
    const started = performance.now();
    const child = exec(command, argv, { env, timeout: 120_000, maxBuffer: 16 * 1024 * 1024 });
    child.child.stdin.end(stdin);
    let output;
    try {
        output = await child;
    } catch (error) {
        throw new Error(`ft ${args.slice(0, 2).join(' ')} stopped (${error.signal ?? error.code}): ${error.stderr?.slice(-1800) ?? error.message}\n${error.stdout?.slice(-700) ?? ''}`);
    }
    const ms = performance.now() - started;
    const value = JSON.parse(output.stdout);
    assert.ok(value && typeof value === 'object' && !value.error, `unexpected ft ${args[0]} result`);
    if (!measured) return value;
    const rssMatch = process.platform === 'linux'
        ? output.stderr.match(/^ft_rss_kib (\d+)$/m)
        : output.stderr.match(/^\s*(\d+)\s+maximum resident set size$/m);
    assert.ok(rssMatch, 'child peak RSS missing');
    const rssBytes = Number(rssMatch[1]) * (process.platform === 'linux' ? 1024 : 1);
    assert.ok(rssBytes > 0 && Number.isSafeInteger(rssBytes), 'invalid child peak RSS');
    return { value, ms, rssBytes };
}

async function startBackend() {
    const reservation = createServer();
    const port = await listen(reservation);
    await close(reservation);
    let output = '';
    const child = spawn(backend, [], {
        env: {
            ...hostEnv,
            PORT: String(port),
            FT_HOST: bindAddress,
            FT_WEB_ORIGIN: 'http://127.0.0.1:5173',
            FT_DATABASE_URL: `sqlite+pysqlite:///${join(work, 'ledger_test.db')}`,
            FT_IMPORT_STAGING_BACKEND: 'memory',
            FT_IMPORT_STAGING_ALLOW_MEMORY: '1',
        },
        stdio: ['ignore', 'pipe', 'pipe'],
    });
    for (const stream of [child.stdout, child.stderr]) {
        stream.on('data', chunk => { output = (output + chunk).slice(-8192); });
    }
    const stopped = once(child, 'exit');
    // Observe spawn errors even if health polling is in progress.
    stopped.catch(() => {});
    const proxy = createServer((incoming, outgoing) => {
        const { method, url, headers } = incoming;
        log.push({ method, path: new URL(url, 'http://127.0.0.1').pathname });
        const forward = request({ hostname: bindAddress, port, method, path: url, headers }, response => {
            outgoing.writeHead(response.statusCode, response.headers);
            response.pipe(outgoing);
        });
        forward.on('error', error => outgoing.destroy(error));
        incoming.pipe(forward);
    });
    const health = new AbortController();
    try {
        const address = `http://${bindAddress}:${port}`;
        await Promise.race([
            stopped.then(([code, signal]) => { throw new Error(`backend exited (${signal ?? code})\n${output}`); }),
            (async () => {
                for (let attempt = 0; attempt < 160; attempt++) {
                    const signal = AbortSignal.any([health.signal, AbortSignal.timeout(1000)]);
                    const ready = await fetch(address + '/health', { signal })
                        .then(response => response.ok, () => false);
                    if (ready) return;
                    await delay(250, undefined, { signal: health.signal });
                }
                throw new Error(`backend health deadline\n${output}`);
            })(),
        ]);
        env.FT_CLI_SERVER = `http://${bindAddress}:${await listen(proxy)}`;
    } catch (error) {
        child.kill();
        throw error;
    } finally {
        health.abort();
    }
    return async () => {
        await close(proxy);
        child.kill();
        await stopped;
    };
}

async function runFixture() {
    const session = await run(['auth', 'register', '--email', EMAIL, '--password-stdin'], { stdin: PASSWORD });
    assert.equal(session.user.email, EMAIL);
    const name = 'Synthetic CLI performance';
    const created = await run(['workspace', 'create', '--name', name]);
    const workspace = created.active_workspace_id;
    assert.ok(workspace && created.workspaces.some(value => value.id === workspace && value.name === name));
    const scope = ['--workspace', workspace];
    const fixtureHash = createHash('sha256');
    async function prepare(files) {
        const paths = [];
        for (const [name, content] of files) {
            const path = join(work, name);
            await writeFile(path, content, { flag: 'wx' });
            fixtureHash.update(name).update('\0').update(content);
            paths.push(path);
        }
        const result = await run(['import', 'prepare', ...paths, ...scope]);
        assert.equal(result.files.length, files.length);
        assert.ok(result.files.every(file => file.status === 'parsed'), JSON.stringify(result.files));
        assert.equal(result.source_accounts.length, 1);
        return result;
    }
    async function review(batch) {
        const choices = join(work, `choices-${batch.batch_id}.json`);
        await writeFile(choices, JSON.stringify({
            expected_session_revision: Number(batch.revision),
            previous_plan_id: null,
            account_selections: batch.source_accounts.map(group => ({
                source_account_ref: group.source_account_ref,
                account_id: account.account_id,
                expected_account_revision: Number(account.account_revision),
            })),
            row_choices: [], relation_choices: [], entry_choices: [],
        }));
        const value = await run(['import', 'review', batch.batch_id, '--choices', choices, ...scope]);
        assert.equal(value.workspace_id, workspace);
        assert.equal(value.session_id, batch.batch_id);
        assert.equal(value.can_commit, true);
        return value;
    }

    const { account } = await run(['account', 'create', '--name', 'Synthetic settlement', '--type', 'cash',
        '--currency', 'CNY', ...scope]);
    assert.ok(account.account_id);
    const baseline = await prepare([ordinaryLedgerCsv()]);
    const ledgerFixture = fixtureHash.copy().digest('hex');
    const baselineReview = await review(baseline);
    assert.equal(baselineReview.summary.create_posted, 3000);
    const committed = await run(['import', 'confirm', baseline.batch_id, '--review', baselineReview.review_revision, ...scope]);
    assert.ok(committed.items_complete && committed.receipt.committed);
    assert.equal(committed.receipt.summary.created_posted, 3000);
    const verifyCash = value => {
        assert.equal(value.ledger_seq, committed.receipt.ledger_seq);
        assert.equal(value.workspace_id, workspace);
        assert.equal(value.offline, false);
    };

    const scenarios = {
        'cli.cash-list': {
            fixture: ledgerFixture,
            args: ['cash', 'list', '--start-index', '0', '--limit', '20', ...scope],
            verify(value) {
                verifyCash(value);
                assert.equal(value.page.total_count, 3000);
                assert.equal(value.page.items.length, 20);
                assert.equal(new Set(value.page.items.map(item => item.projection_id)).size, 20);
                assert.equal(value.page.items_after, 2980);
                assert.ok(value.page.items.every(item => item.currency === 'CNY' && /^-?12\.4(?:0+)?$/.test(item.amount)));
            },
        },
        'cli.cash-summary': {
            fixture: ledgerFixture,
            args: ['cash', 'summary', ...scope],
            verify(value) {
                verifyCash(value);
                assert.equal(value.pending_item_count, 0);
                assert.deepEqual(value.months.map(month => month.month).sort(),
                    range(1, 13).map(month => `2026-${String(month).padStart(2, '0')}`));
                for (const month of value.months) {
                    assert.equal(month.currencies.length, 1);
                    const { currency, income, expense } = month.currencies[0];
                    assert.equal(currency, 'CNY');
                    assert.match(income, /^0(?:\.0+)?$/);
                    assert.match(expense, /^-3100(?:\.0+)?$/);
                }
            },
        },
    };
    if (needsImport) {
        const batch = await prepare(ordinaryImportSources());
        const fixture = fixtureHash.digest('hex');
        const plan = await review(batch);
        assert.deepEqual(plan.file_results.map(file => file.recognized_row_count), [300, 350, 350]);
        assert.equal(plan.summary.create_posted, 985);
        assert.equal(plan.summary.duplicate, 10);
        assert.equal(plan.summary.skip, 5);
        assert.equal(plan.item_counts.confirmed, 985);
        const first = plan.items[0];
        assert.ok(first?.detail_available && first.member_refs[0]?.row_ref);
        scenarios['cli.import-items'] = {
            fixture,
            args: ['import', 'items', batch.batch_id, '--limit', '20', ...scope],
            verify(value) {
                assert.equal(value.items.length, 20);
                assert.deepEqual(value.items[0], first);
                assert.equal(new Set(value.items.map(item => item.preview_item_id)).size, 20);
                assert.ok(value.next_cursor);
            },
        };
        scenarios['cli.import-item'] = {
            fixture,
            args: ['import', 'item', batch.batch_id, '--id', first.preview_item_id, ...scope],
            verify(value) {
                assert.equal(value.preview_item_id, first.preview_item_id);
                assert.equal(value.members.length, 1);
                assert.equal(value.members[0].row_ref, first.member_refs[0].row_ref);
                assert.equal(value.members[0].planned_action, 'create_posted');
            },
        };
        scenarios['cli.import-processing'] = {
            fixture,
            args: ['import', 'processing', batch.batch_id, '--limit', '20', ...scope],
            verify(value) {
                assert.equal(value.items.length, 15);
                assert.equal(value.next_cursor, null);
                assert.equal(value.items.filter(row => row.planned_action === 'duplicate_in_batch').length, 10);
                assert.equal(value.items.filter(row => row.planned_action === 'skip' && row.code === 'unpaid_closed').length, 5);
                const refs = value.items.map(row => row.row_ref);
                assert.ok(refs.every(Boolean) && new Set(refs).size === 15);
            },
        };
    }
    return scenarios;
}

let stop;
try {
    if (needsLedger) stop = await startBackend();
    const scenarios = needsLedger ? await runFixture() : {};
    scenarios['cli.help'] = {
        args: ['--help'],
        verify: value => assert.match(value.help, /^Usage: ft /),
    };
    for (const id of selected) {
        const { args, verify, fixture = null } = scenarios[id];
        for (let sample = 0; sample < warmups + samples; sample++) {
            const start = log.length;
            const { value, ms, rssBytes } = await run(args, { measured: true });
            verify(value);
            const requests = log.slice(start);
            assert.ok(requests.every(entry => entry.method === 'GET' || entry.method === 'HEAD'),
                `read-only cold command attempted a write: ${JSON.stringify(requests)}`);
            if (id === 'cli.help') assert.equal(requests.length, 0);
            else assert.ok(requests.length > 0, 'expected a real loopback request');
            const event = sample < warmups ? { id, warmup: true, asserted: true }
                : { id, ms, rssBytes, asserted: true, fixture };
            await appendFile(events, JSON.stringify(event) + '\n');
        }
    }
} finally {
    try {
        await stop?.();
    } finally {
        await rm(work, { recursive: true, force: true });
    }
}

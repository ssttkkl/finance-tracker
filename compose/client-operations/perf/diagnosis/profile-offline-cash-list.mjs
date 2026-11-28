import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { measureCommand } from '../large-measure.mjs';

function cpuSummary(profile) {
    const nodes = new Map(profile.nodes.map(node => [node.id, node]));
    const parents = new Map(profile.nodes.flatMap(node => (node.children ?? []).map(child => [child, node.id])));
    const costs = new Map();
    let projectionMicros = 0;
    for (const [index, leaf] of profile.samples.entries()) {
        const stack = [];
        for (let id = leaf; id !== undefined; id = parents.get(id)) stack.push(nodes.get(id));
        if (!stack.some(node => /^buildLocalCashProjections/.test(node.callFrame.functionName))) continue;
        const micros = profile.timeDeltas[index];
        projectionMicros += micros;
        const visited = new Set();
        for (const node of stack) {
            const { functionName, lineNumber, url } = node.callFrame;
            if (!functionName || !url.endsWith('FinanceTracker-business-domain.mjs')) continue;
            const name = functionName + ':' + (lineNumber + 1);
            if (visited.has(name)) continue;
            visited.add(name);
            const cost = costs.get(name) ?? { functionName, line: lineNumber + 1, ownMicros: 0, inclusiveMicros: 0 };
            cost.inclusiveMicros += micros;
            if (node.id === leaf) cost.ownMicros += micros;
            costs.set(name, cost);
        }
    }
    return {
        projectionMicros, sampleCount: profile.samples.length,
        top: [...costs.values()].sort((a, b) => b.inclusiveMicros - a.inclusiveMicros).slice(0, 20),
    };
}

const [baseInstall, candidateInstall, report] = process.argv.slice(2);
assert(baseInstall && candidateInstall && report && !process.env.FT_DIAGNOSTIC_CASH_PHASES);
const bins = Object.fromEntries(Object.entries({ base: baseInstall, candidate: candidateInstall })
    .map(([key, directory]) => [key, resolve(directory, 'node_modules/.bin/ft')]));
const installedHost = pathToFileURL(resolve(baseInstall, 'node_modules/finance-tracker-cli/lib/host.mjs'));
const { openStore } = await import(installedHost);
const source = new URL('../../src/jvmTest/resources/cash-operation-snapshot.json', import.meta.url);
const root = mkdtempSync(join(tmpdir(), 'private-ft-cash-phases-'));
try {
    const socket = createServer();
    socket.listen(0, '127.0.0.1');
    await once(socket, 'listening');
    const origin = 'http://127.0.0.1:' + socket.address().port;
    await new Promise((done, reject) => socket.close(error => error ? reject(error) : done()));
    const state = join(root, 'state');
    const key = Buffer.alloc(32, 138).toString('base64');
    const env = { ...process.env, FT_CLI_SERVER: origin, FT_CLI_STATE_DIR: state, FT_CLI_STORAGE_KEY: key };
    const snapshot = JSON.parse(readFileSync(source, 'utf8'));
    const facts = snapshot.facts;
    const count = 512;
    const [transaction] = facts.cash_transactions;
    const [component] = facts.cash_transaction_components;
    const [entry] = facts.cash_entries;
    const [member] = facts.cash_entry_members;
    const collections = ['cash_transactions', 'cash_transaction_components', 'cash_entries', 'cash_entry_members'];
    for (const name of collections) facts[name] = [];
    for (let i = 0; i < count; i++) {
        const id = String(BigInt(transaction.id) + BigInt(i));
        const entryId = 'example-entry-' + i;
        const counterparty = i % 3 === 0 ? 'Synthetic orchard' : 'Synthetic store';
        facts.cash_transactions.push({ ...transaction, id, record_id: 'example-record-' + i, counterparty });
        facts.cash_transaction_components.push({ ...component, id: String(7001 + i), cash_transaction_id: id });
        facts.cash_entries.push({ ...entry, id: entryId, root_transaction_id: id, identity_transaction_id: id, counterparty });
        facts.cash_entry_members.push({ ...member, transaction_id: id, entry_id: entryId });
        facts.cash_source_bindings.push({
            id: String(i + 1), transaction_id: id, source_type: 'alipay', source_scope_kind: 'account',
            source_scope_key: 'fixture', identity_kind: 'provider_id', identity_key: 'record-' + i,
            identity_strength: 'provider', stable_evidence_key: null, strategy_version: 'fixture', revision: '1', updated_at: transaction.created_at,
            created_at: transaction.created_at, coverage: { fixture: '合成条目'.repeat(180) },
        });
    }
    const token = 'synthetic-offline-fixture';
    const email = 'cash-phase@example.invalid';
    const session = {
        user: { email }, active_workspace_id: snapshot.workspace_id,
        workspaces: [{ id: snapshot.workspace_id, name: 'Synthetic', role: 'editor' }],
    };
    function writeLedger(directory, value) {
        process.env.FT_CLI_STATE_DIR = directory;
        process.env.FT_CLI_STORAGE_KEY = key;
        const cache = openStore(origin, true);
        let committed = false;
        const plaintext = JSON.stringify({ generation: 'fixture', ledger: { snapshot: value, synced_at: transaction.created_at } });
        try {
            assert(!cache.error);
            cache.write('session', JSON.stringify({ token, session }));
            cache.write(cache.workspaceSlot(token, email, value.workspace_id), plaintext);
            committed = true;
        } finally {
            cache.close(committed);
        }
        return Buffer.byteLength(plaintext);
    }
    const cachePlainBytes = writeLedger(state, snapshot);
    const cases = [
        { name: 'first', filter: [], expectedCount: 20 },
        { name: 'other-page-and-filter', filter: ['--start-index', '20', '--ledger-seq', snapshot.ledger_seq, '--keyword', 'orchard'], expectedCount: 20 },
        { name: 'other-page-with-monthly-summaries', filter: ['--start-index', '20', '--keyword', 'orchard'], expectedCount: 20 },
    ];
    const schedule = [
        { variant: 'base', mode: 'plain' }, { variant: 'candidate', mode: 'plain' },
        { variant: 'candidate', mode: 'plain' }, { variant: 'base', mode: 'plain' },
        { variant: 'base', mode: 'traced' }, { variant: 'candidate', mode: 'traced' },
        { variant: 'candidate', mode: 'cpu' }, { variant: 'base', mode: 'cpu' },
    ];
    const results = [];
    for (const test of cases) {
        const first = test.name === 'first';
        const pinned = test.name === 'other-page-and-filter';
        const total = first ? count : Math.ceil(count / 3);
        const ordered = Array.from({ length: count }, (_, index) => index)
            .filter(index => first || index % 3 === 0).map(index => 'cash-entry:example-entry-' + index)
            .sort().reverse();
        const offset = first ? 0 : 20;
        const expectedIds = ordered.slice(offset, offset + test.expectedCount);
        const args = ['cash', 'list', '--limit', '20', '--timezone', 'UTC', ...test.filter, '--json'];
        const samples = [];
        let reference;
        for (const [index, { variant, mode }] of schedule.entries()) {
            const label = test.name + '-' + variant + '-' + mode + '-' + index;
            const trace = join(root, label + '-trace.jsonl');
            const cpuFile = label + '.cpuprofile';
            const run = await measureCommand(bins[variant], args, {
                env: mode === 'traced' ? { ...env, FT_DIAGNOSTIC_CASH_PHASES: trace } : mode === 'cpu' ?
                    { ...env, NODE_OPTIONS: '--cpu-prof --cpu-prof-dir=' + root + ' --cpu-prof-name=' + cpuFile } : env,
                timeoutMs: 30_000, outputDir: root, label,
            });
            reference ??= run.value;
            assert(isDeepStrictEqual(run.value, reference), 'Cash list output differs between packages');
            const page = run.value.page;
            assert(isDeepStrictEqual(page.items.map(item => item.projection_id), expectedIds), 'Cash entries are out of order');
            assert.equal(page.total_count ?? null, first ? null : total);
            if (!first) {
                assert.equal(page.items_before, offset);
                assert.equal(page.items_after, total - offset - test.expectedCount);
            }
            const expectedExpense = first ? '-6318.080000000000000512' : '-2110.140000000000000171';
            const expectedMonthly = pinned ? [] : [{ month: '2026-07', currencies: [{ currency: 'CNY', expense: expectedExpense, income: '0' }] }];
            assert(isDeepStrictEqual(page.monthly_summaries, expectedMonthly), 'The filtered cash totals changed');
            const expectedPositions = !first && !pinned ? [{ month: '2026-07', start_index: 0, end_index: total }] : [];
            assert(isDeepStrictEqual(page.month_positions, expectedPositions), 'The month range changed');
            const sample = { variant, mode, ms: run.ms, rssBytes: run.rssBytes };
            if (mode === 'traced') {
                sample.phases = readFileSync(trace, 'utf8').trim().split('\n').map(line => JSON.parse(line));
                const counts = Object.fromEntries(sample.phases.filter(phase => phase.count !== null).map(phase => [phase.phase, phase.count]));
                assert.equal(counts.ledger_projection_facts, count);
                assert.equal(counts.ledger_projections, count);
                assert.equal(counts.ledger_entries, count);
                assert.equal(counts.ledger_items_ready, test.expectedCount);
                assert.equal(counts.ledger_scan_ready, total);
            }
            if (mode === 'cpu') {
                sample.cpu = cpuSummary(JSON.parse(readFileSync(join(root, cpuFile), 'utf8')));
                assert(sample.cpu.projectionMicros > 0 && sample.cpu.top.length > 0, 'Missing production projection samples');
            }
            samples.push(sample);
        }
        results.push({ case: test.name, items: test.expectedCount, total, samples });
    }

    const invalid = structuredClone(snapshot);
    // The keyword excludes this row, and its cache is otherwise identical and correctly authenticated.
    invalid.facts.cash_transactions[1].created_at = '0001-01-01T00:00:00+01:00';
    const invalidState = join(root, 'invalid-state');
    writeLedger(invalidState, invalid);
    const errors = [];
    for (const [variant, bin] of Object.entries(bins)) {
        const label = variant + '-invalid-after-filter';
        try {
            await measureCommand(bin, ['cash', 'list', '--keyword', 'orchard', '--json'], {
                env: { ...env, FT_CLI_STATE_DIR: invalidState }, timeoutMs: 30_000, outputDir: root, label,
            });
            assert.fail('Expected an out-of-range timestamp to fail before filtering');
        } catch (failure) {
            if (!failure.measurement || failure.measurement.code === 0) throw failure;
            const parsed = JSON.parse(readFileSync(join(root, label + '.json'), 'utf8'));
            assert(typeof parsed.error?.code === 'string');
            errors.push({ code: parsed.error.code, exitCode: failure.measurement.code });
        }
    }
    assert(isDeepStrictEqual(errors[0], errors[1]), 'The page-external validation failure changed');
    writeFileSync(report, JSON.stringify({ transactions: count, cachePlainBytes, results, pageExternalBoundary: errors[0] }, null, 2));
} finally {
    rmSync(root, { recursive: true, force: true });
}

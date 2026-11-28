import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
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

const [installDir, report] = process.argv.slice(2);
assert(installDir && report && !process.env.FT_DIAGNOSTIC_CASH_PHASES);
const installedHost = pathToFileURL(resolve(installDir, 'node_modules/finance-tracker-cli/lib/host.mjs'));
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
    process.env.FT_CLI_STATE_DIR = state;
    process.env.FT_CLI_STORAGE_KEY = key;
    const cache = openStore(origin, true);
    let committed = false;
    const plaintext = JSON.stringify({ generation: 'fixture', ledger: { snapshot, synced_at: transaction.created_at } });
    try {
        assert(!cache.error);
        cache.write('session', JSON.stringify({ token, session }));
        cache.write(cache.workspaceSlot(token, email, snapshot.workspace_id), plaintext);
        committed = true;
    } finally {
        cache.close(committed);
    }
    const bin = resolve(installDir, 'node_modules/.bin/ft');
    const cases = [
        { name: 'first', filter: [], expectedCount: 20 },
        { name: 'other-page-and-filter', filter: ['--start-index', '20', '--ledger-seq', snapshot.ledger_seq, '--keyword', 'orchard'], expectedCount: 20 },
        { name: 'other-page-with-monthly-summaries', filter: ['--start-index', '20', '--keyword', 'orchard'], expectedCount: 20 },
    ];
    const results = [];
    for (const test of cases) {
        const args = ['cash', 'list', '--limit', '20', ...test.filter, '--json'];
        const trace = join(root, test.name + '-trace.jsonl');
        const samples = {};
        const cpuFile = test.name + '.cpuprofile';
        for (const mode of ['plain', 'traced', 'cpu']) {
            const run = await measureCommand(bin, args, {
                env: mode === 'traced' ? { ...env, FT_DIAGNOSTIC_CASH_PHASES: trace } : mode === 'cpu' ?
                    { ...env, NODE_OPTIONS: '--cpu-prof --cpu-prof-dir=' + root + ' --cpu-prof-name=' + cpuFile } : env,
                timeoutMs: 30_000, outputDir: root, label: test.name + '-' + mode,
            });
            assert.equal(run.value.page.items.length, test.expectedCount);
            if (mode === 'plain') samples.value = run.value;
            else assert.deepEqual(run.value, samples.value);
            samples[mode] = { ms: run.ms, rssBytes: run.rssBytes };
        }
        const phases = readFileSync(trace, 'utf8').trim().split('\n').map(line => JSON.parse(line));
        for (const name of ['cache_blob_start', 'cache_blob_loaded', 'cache_authenticated', 'cache_utf8',
            'ledger_cache_decoded', 'ledger_graph_start', 'ledger_items_ready', 'terminal_output_ready']) {
            assert(phases.some(sample => sample.phase === name));
        }
        const counts = Object.fromEntries(phases.filter(sample => sample.count !== null).map(sample => [sample.phase, sample.count]));
        assert.equal(counts.ledger_projection_facts, count);
        assert.equal(counts.ledger_projections, count);
        assert.equal(counts.ledger_entries, count);
        assert.equal(counts.ledger_items_ready, test.expectedCount);
        assert.equal(counts.ledger_option_transfer_views, 0);
        assert.equal(counts.ledger_scan_ready, test.name === 'first' ? count : Math.ceil(count / 3));
        assert.equal(samples.value.page.total_count ?? null, test.name === 'first' ? null : Math.ceil(count / 3));
        assert.equal(samples.value.page.monthly_summaries.length, test.name === 'other-page-and-filter' ? 0 : 1);
        const { plain, traced } = samples;
        const cpu = cpuSummary(JSON.parse(readFileSync(join(root, cpuFile), 'utf8')));
        assert(cpu.projectionMicros > 0, 'Missing production projection samples');
        results.push({ case: test.name, items: test.expectedCount, plain, traced, phases, counts, cpu });
    }
    writeFileSync(report, JSON.stringify({ transactions: count, cachePlainBytes: Buffer.byteLength(plaintext), results }, null, 2));
} finally {
    rmSync(root, { recursive: true, force: true });
}

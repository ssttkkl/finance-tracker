import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const snapshot = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const { nodes, edges, strings } = snapshot;
const { node_fields: nodeFields, edge_fields: edgeFields, node_types: nodeTypes, edge_types: edgeTypes } = snapshot.snapshot.meta;
const width = nodeFields.length;
const edgeWidth = edgeFields.length;
const n = nodes.length / width;
const typeAt = nodeFields.indexOf('type');
const nameAt = nodeFields.indexOf('name');
const bytesAt = nodeFields.indexOf('self_size');
const countAt = nodeFields.indexOf('edge_count');
const edgeTypeAt = edgeFields.indexOf('type');
const edgeToAt = edgeFields.indexOf('to_node');
assert.ok([typeAt, nameAt, bytesAt, countAt, edgeTypeAt, edgeToAt].every(index => index >= 0));
const weak = edgeTypes[edgeTypeAt].indexOf('weak');
assert.ok(weak >= 0);
const starts = new Uint32Array(n + 1);
for (let i = 0; i < n; i++) starts[i + 1] = starts[i] + nodes[i * width + countAt] * edgeWidth;
assert.equal(starts[n], edges.length);
const outgoing = (edge) => edges[edge + edgeToAt] / width;
const strong = (edge) => edges[edge + edgeTypeAt] !== weak;

// Roots and dominators use V8's strong edges. The snapshot's synthetic root is node zero.
const visited = new Uint8Array(n);
const orders = [];
const stack = [0];
const cursors = [starts[0]];
visited[0] = 1;
while (stack.length) {
    const i = stack.length - 1;
    const here = stack[i];
    const e = cursors[i];
    if (e === starts[here + 1]) {
        orders.push(here);
        stack.pop();
        cursors.pop();
        continue;
    }
    cursors[i] += edgeWidth;
    if (!strong(e)) continue;
    const next = outgoing(e);
    if (visited[next]) continue;
    visited[next] = 1;
    stack.push(next);
    cursors.push(starts[next]);
}
orders.reverse();
const predecessorStarts = new Uint32Array(n + 1);
const rank = new Uint32Array(n);
for (const [position, here] of orders.entries()) {
    rank[here] = position;
    for (let e = starts[here]; e < starts[here + 1]; e += edgeWidth)
        if (strong(e)) predecessorStarts[outgoing(e) + 1]++;
}
for (let i = 0; i < n; i++) predecessorStarts[i + 1] += predecessorStarts[i];
const fill = predecessorStarts.slice();
const predecessors = new Uint32Array(fill[n]);
for (const here of orders) for (let e = starts[here]; e < starts[here + 1]; e += edgeWidth)
    if (strong(e)) predecessors[fill[outgoing(e)]++] = here;
const idom = new Int32Array(n).fill(-1);
idom[0] = 0;
function common(a, b) {
    while (a !== b) {
        while (rank[a] > rank[b]) a = idom[a];
        while (rank[b] > rank[a]) b = idom[b];
    }
    return a;
}
let changed;
do {
    changed = false;
    for (let j = 1; j < orders.length; j++) {
        const node = orders[j];
        let known = -1;
        for (let p = predecessorStarts[node]; p < predecessorStarts[node + 1]; p++) {
            const parent = predecessors[p];
            if (idom[parent] === -1) continue;
            known = known === -1 ? parent : common(known, parent);
        }
        if (idom[node] !== known) {
            idom[node] = known;
            changed = true;
        }
    }
} while (changed);
const retained = Float64Array.from({ length: n }, (_, i) => nodes[i * width + bytesAt]);
for (let j = orders.length - 1; j > 0; j--) {
    const node = orders[j];
    retained[idom[node]] += retained[node];
}

const parent = new Int32Array(n).fill(-1);
const breadth = [0];
parent[0] = 0;
for (let j = 0; j < breadth.length; j++) {
    const node = breadth[j];
    for (let e = starts[node]; e < starts[node + 1]; e += edgeWidth) {
        const next = outgoing(e);
        if (!strong(e) || parent[next] >= 0) continue;
        parent[next] = node;
        breadth.push(next);
    }
}
// Output only fixed runtime or domain class names. V8 strings can contain synthetic credentials and amounts.
const allowed = new Set(['(GC roots)', '(Stack roots)', '(Handle scope)', '(Global handles)', 'system / Context',
    'Object', 'Array', 'ArrayList', 'Map', 'LinkedHashMap', 'HashMap', 'ArrayBuffer', 'Buffer', 'Uint8Array',
    'JsonObject', 'JsonArray', 'JsonLiteral', 'ParsedImportFile', 'ParsedCashRow', 'LedgerSnapshotDto', 'LedgerFactsDto',
    'CashWriteSetDto', 'CashWriteSetRequestDto', 'CashTransactionWriteDto', 'CashComponentWriteDto', 'CashRelationWriteDto',
    'CashImportCalculation', 'PreparedCashImportReview', 'LocalCashImportReview', 'ClientCashImportReviewedCommand',
    'CashImportPlanDto', 'CashPlanItemDto', 'TerminalResult']);
function label(node) {
    const type = nodeTypes[typeAt][nodes[node * width + typeAt]];
    const name = strings[nodes[node * width + nameAt]];
    return ['object','synthetic','native','array','hidden'].includes(type) && allowed.has(name) ? name : type;
}
function path(links, start) {
    const result = [];
    for (let i = start; i !== 0 && result.length < 30; i = links[i]) result.push(label(i));
    return result.reverse();
}
const grouped = new Map();
for (const node of orders) {
    const name = label(node);
    const row = grouped.get(name) ?? { type: name, count: 0, shallowBytes: 0, maxRetainedBytes: 0, top: -1 };
    row.count++;
    row.shallowBytes += nodes[node * width + bytesAt];
    if (retained[node] > row.maxRetainedBytes && node !== 0) {
        row.maxRetainedBytes = retained[node];
        row.top = node;
    }
    grouped.set(name, row);
}
const groups = [...grouped.values()].sort((a,b) => b.shallowBytes-a.shallowBytes).map(({top, ...row}) => ({ ...row,
    ...(top < 0 ? {} : { strongPath: path(parent, top), dominatorPath: path(idom, top) }),
}));
console.log(JSON.stringify({ reachable: orders.length, nodes: n, reachableShallowBytes: retained[0], groups }, null, 2));

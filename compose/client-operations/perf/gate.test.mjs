import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { evaluate, compare, inventory, readSignatures, requireMaximumEvidence } from './gate.mjs';

const scenario = { id: 'cash.summary', job: 'ordinary', warmups: 5, samples: 30, budgetMs: 1000,
  workload: '3000 rows', checks: 'exact totals', mode: 'warm', relativeFloorMs: 100 };
const maximum = { id: 'large.review', job: 'maximum', warmups: 0, samples: 1, budgetMs: 150000,
  maxRssBytes: 5 * 1024 ** 3, workload: '19 files', checks: 'exact rows', mode: 'cold' };
const latency = (ms, id = scenario.id) => ({ id, ms, asserted: true });
const samples = (ms) => Array.from({ length: 30 }, () => latency(ms));
const warmups = (id = scenario.id) => Array.from({ length: 5 }, () => ({ id, warmup: true, asserted: true }));

test('requires every measured case and its assertions, warmups and sample count', () => {
  const cases = [scenario, maximum];
  const ok = [...warmups(), ...samples(200), { ...latency(100, maximum.id), rssBytes: 1024 }];
  assert.equal(evaluate(cases, ok).ok, true);
  assert.equal(evaluate(cases, ok, false).ok, false);
  for (const invalid of [
    ok.slice(0, -1), ok.slice(1), [...ok, latency(1, 'unlisted')], [...ok, latency(1)], [...ok, null],
    ok.map((v, i) => i === 6 ? { ...v, asserted: false } : v),
    ok.map((v, i) => i === 6 ? { ...v, ms: -1 } : v),
    ok.map((v, i) => i === ok.length - 1 ? latency(50, maximum.id) : v),
  ]) assert.equal(evaluate(cases, invalid).ok, false);
});

test('individual p95 applies nearest rank and memory is independently enforced', () => {
  const clean = [...warmups(), ...samples(200)];
  const tail = [latency(1200), latency(1400)];
  assert.equal(evaluate([scenario], [...clean.slice(0, -1), tail[0]]).ok, true);
  assert.equal(evaluate([scenario], [...clean.slice(0, -2), ...tail]).ok, false);
  assert.equal(evaluate([maximum], [{ ...latency(1, maximum.id), rssBytes: maximum.maxRssBytes + 1 }]).ok, false);
});

test('relative trigger requires both thresholds and verifies all declared hotspots', () => {
  const cases = [scenario];
  const measure = (ms) => evaluate(cases, [...warmups(), ...samples(ms)]);
  assert.deepEqual(compare(cases, measure(200), measure(299)), []);
  assert.deepEqual(compare(cases, measure(500), measure(600)), []);
  assert.deepEqual(compare(cases, measure(200), measure(300)).map(r => r.id), [scenario.id]);
  assert.throws(() => compare(cases, measure(200), { results: [] }), /cash.summary/);
});

test('inventory rejects a new overload, nested operation and top-level operation', () => {
  const signatures = ['Client.run|run(kotlin.String){}', 'Client.Inner.query|query(){}', 'normalise|normalise(){}'];
  const manifest = { scenarios: [scenario], entrypoints: [
    { signature: signatures[0], scenarios: [scenario.id] },
    { signature: signatures[1], disposition: 'dto', reason: 'Derived metadata read' },
    { signature: signatures[2], disposition: 'implementation', reason: 'Included in result construction' },
  ] };
  assert.doesNotThrow(() => inventory(manifest, signatures));
  for (const added of ['Client.run|run(kotlin.Long){}', 'Client.Inner.mutate|mutate(){}', 'mutate|mutate(){}']) {
    assert.throws(() => inventory(manifest, [...signatures, added]), /unregistered/);
  }
  assert.throws(() => inventory(manifest, signatures.slice(0, -1)), /stale/);
});

test('compiled declarations with generated-looking names are classified by exact signature', () => {
  const file = join(mkdtempSync(join(tmpdir(), 'ft-perf-inventory-')), 'abi');
  writeFileSync(file, [
    'final fun copy(kotlin/String) // com.finance.tracker.client/Operations.copy|copy(kotlin.String){}[0]',
    '    final fun copy(kotlin/Long) // com.finance.tracker.client/Operations.copy|copy(kotlin.Long){}[0]',
    'final fun transfer() // com.finance.tracker.client.cash/transfer|transfer(){}[0]',
    'final fun query() // com.finance.tracker.账本/query|query(){}[0]',
  ].join('\n'));
  const signatures = readSignatures(file);
  assert.equal(signatures.length, 4);
  assert.ok(signatures.includes('com.finance.tracker.client.cash/transfer|transfer(){}'));
  assert.ok(signatures.includes('com.finance.tracker.账本/query|query(){}'));
  const manifest = {
    scenarios: [scenario], entrypoints: [],
    nonOperations: [{ disposition: 'data-model', reason: 'Structural copy', signatures: [signatures[1]] }],
  };
  assert.doesNotThrow(() => inventory(manifest, [signatures[1]]));
  assert.throws(() => inventory(manifest, signatures), /Operations.copy/);
});

test('a completed maximum fixture report must substantiate every raw timing', () => {
  const events = [{ ...latency(500, maximum.id), rssBytes: 100 }];
  const measurements = [{ ...events[0], checks: { rows: 14424 } }];
  const fixture = {
    rows: 14424,
    formats: { xlsx: 6, xls: 2, csv: 6, pdf: 5 },
    files: Array.from({ length: 19 }, (_, index) => ({
      file: `synthetic-${index}`, sha256: 'a'.repeat(64), password_required: index < 3,
    })),
  };
  const report = { checks: { completed: true }, fixture, measurements };
  assert.doesNotThrow(() => requireMaximumEvidence(report, events));
  assert.throws(() => requireMaximumEvidence({}, events), /fixture/);
  assert.throws(() => requireMaximumEvidence({ ...report, checks: { completed: false } }, events), /fixture/);
  assert.throws(() => requireMaximumEvidence({ ...report, measurements: [] }, events), /raw samples/);
  assert.throws(() => requireMaximumEvidence(report, [{ ...events[0], ms: 501 }]), /raw samples/);
});

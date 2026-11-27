import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { summarizePayloadShape } from './payload-shape.mjs';

test('reports exact UTF-8 size and repeated values without recording values or identifiers', () => {
    const coverage = '{"PRIVATE-KEY":"秘密😀","scope":1e0}';
    const input = Buffer.from(`{"changes":{"source_bindings":[{"coverage":${coverage}},{"coverage":${coverage}}],"transactions":[{"record_id":"fixture-ID","amount":"0.0000"}]},"rules_version":"secret"}`);
    const report = summarizePayloadShape('submit', input);
    assert.equal(report.bytes, input.length);
    const recorded = report.repetition.find(row => row.field === 'changes.source_bindings.[].coverage');
    assert.equal(recorded.count, 2);
    assert.equal(recorded.repeatedValues, 1);
    assert.equal(recorded.repeatedValueBytes, Buffer.byteLength(coverage));
    assert.equal(recorded.bytes, 2 * Buffer.byteLength(coverage));
    for (const secret of ['PRIVATE-KEY', '秘密', 'fixture-ID', 'secret', '1e0']) {
        assert.ok(!JSON.stringify(report).includes(secret));
    }
    assert.throws(() => summarizePayloadShape('submit', input.subarray(0, input.length - 1)));
});

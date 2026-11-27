import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { evaluateImportRequests } from './request-gate.mjs';

const routes = {
    upload: { method: 'POST', template: '/cash-import/write-set', maxServerMs: 8_000,
        maxRequestWireBytes: 64 * 1024 ** 2, maxRequestDecodedBytes: 64 * 1024 ** 2,
        maxResponseWireBytes: 2048, maxResponseDecodedBytes: 2048, maxPeakServiceBytes: 10_000, maxServiceIncreaseBytes: 2_000 },
    poll: { method: 'GET', template: '/cash-import/operations/{key}', maxServerMs: 8_000,
        maxRequestWireBytes: 0, maxRequestDecodedBytes: 0, maxResponseWireBytes: 2048,
        maxResponseDecodedBytes: 2048, maxPeakServiceBytes: 10_000, maxServiceIncreaseBytes: 1_000 },
};
const flows = { confirm: { upload: { minimum: 1, maximum: 1, statuses: [202] },
    poll: { minimum: 2, statuses: [200], concurrent: true } } };
const request = (sequence, route, status, requestWireBytes, requestDecodedBytes, concurrent) => ({
    sequence, flow: 'confirm', route, status, serverMs: 15, clientMs: 16, concurrent,
    requestWireBytes, requestDecodedBytes, responseWireBytes: 100, responseDecodedBytes: 124,
});
const requests = [request(1, 'upload', 202, 1024, 2048, false), request(2, 'poll', 200, 0, 0, true),
    request(3, 'poll', 200, 0, 0, false)];
const replays = requests.map(({ sequence, route, status, requestWireBytes, requestDecodedBytes,
    responseWireBytes, responseDecodedBytes }) => ({
    sequence, route, status, requestWireBytes, requestDecodedBytes, responseWireBytes, responseDecodedBytes,
    serverMs: 15, asserted: true, isolated: true, prewarmed: true, method: 'linux-cgroup-v2-memory-peak', baselineServiceBytes: 5000, peakServiceBytes: 5500,
}));
const report = { requests, replays, concurrentServicePeakBytes: 5500, concurrentSamplingMethod: 'linux-cgroup-v2-memory-peak' };
const check = (sample = report, budget = routes) => evaluateImportRequests(budget, flows, sample, 768 * 1024 ** 2);
const change = (field, index, values) => ({ ...report,
    [field]: report[field].map((row, i) => i === index ? { ...row, ...values } : row) });

test('one slow poll fails even when complete confirmation and every other request are timely', () => {
    assert.equal(check().ok, true);
    const failed = check(change('requests', 1, { serverMs: 8001 }));
    assert.equal(failed.ok, false);
    assert.match(failed.errors.join('\n'), /confirm.*poll.*serverMs/);
});

test('missing, duplicated, unclassified, failed and unobserved requests fail closed', () => {
    for (const invalid of [
        { ...report, requests: requests.slice(0, 2) },
        change('requests', 2, { sequence: 2 }),
        change('requests', 1, { route: 'unclassified' }),
        change('requests', 1, { route: 'upload' }),
        change('requests', 1, { status: 500 }),
        change('requests', 1, { clientMs: undefined }),
        change('requests', 1, { serverMs: NaN }),
        change('requests', 1, { concurrent: false }),
        { ...report, concurrentServicePeakBytes: undefined },
        { ...report, replays: replays.slice(0, 2) },
    ]) assert.equal(check(invalid).ok, false);
});

test('wire bytes, decompressed bytes and request-window memory each have an independent limit', () => {
    for (const invalid of [
        change('requests', 0, { requestWireBytes: 64 * 1024 ** 2 + 1 }),
        change('requests', 0, { requestDecodedBytes: 64 * 1024 ** 2 + 1 }),
        change('requests', 1, { requestWireBytes: 1 }),
        change('requests', 1, { responseDecodedBytes: 2049 }),
        change('requests', 1, { responseWireBytes: 2049 }),
        change('replays', 0, { peakServiceBytes: 10001 }),
        change('replays', 1, { peakServiceBytes: 6001 }),
        change('replays', 2, { peakServiceBytes: undefined }),
        change('replays', 2, { requestDecodedBytes: 1 }),
        change('replays', 2, { isolated: false }),
        change('replays', 2, { prewarmed: false }),
    ]) assert.equal(check(invalid).ok, false);
});

test('missing fixed budgets invalidate the report', () => {
    const result = check(report, { ...routes, poll: { ...routes.poll, maxPeakServiceBytes: null } });
    assert.equal(result.valid, false);
    assert.equal(result.ok, false);
    assert.match(result.errors.join('\n'), /missing or invalid/);
});

test('a repeated isolated poll still blocks on its own memory peak', () => {
    const rawReplays = replays.map(row => ({ ...row, flow: 'confirm' }));
    rawReplays.push({ ...rawReplays[1], sequence: 4, peakServiceBytes: 15_000 });
    const result = check({ ...report, rawReplays });
    assert.equal(result.ok, false);
    assert.match(result.errors.join('\n'), /isolated confirm\/poll #4: peakServiceBytes/);
});

test('the concurrent service group is allowed at 768 MiB and fails one byte above it', () => {
    const limit = 768 * 1024 ** 2;
    const at = evaluateImportRequests(routes, flows, { ...report, concurrentServicePeakBytes: limit }, limit);
    assert.equal(at.ok, true);
    const above = evaluateImportRequests(routes, flows, { ...report, concurrentServicePeakBytes: limit + 1 }, limit);
    assert.equal(above.valid, true);
    assert.equal(above.ok, false);
    assert.match(above.errors.join('\n'), /concurrent service peak/);
});

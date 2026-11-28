import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { compatibleReadSections, readPageCoordinates, readPageChunks } from './import-request-routes.mjs';
import { completeReadPageSequence, evaluateMaximumHttpGate } from './maximum-http-gate.mjs';

const position = (section, offset = 0, fragmentOffset = 0) => ({ page: { section, offset, fragmentOffset } });

test('missing sections, duplicate positions and repeated first pages cannot stand in for all requests', () => {
    for (const sections of Object.values(compatibleReadSections)) {
        const exact = sections.map((section, index) => position(index === 0 ? null : section));
        assert.ok(completeReadPageSequence(exact, sections));
        assert.ok(completeReadPageSequence([exact[0], position(sections[0], 1024), ...exact.slice(1)], sections));
        assert.ok(completeReadPageSequence([exact[0], position(sections[0], 0, 32), ...exact.slice(1)], sections));
        assert.equal(completeReadPageSequence(exact.map(() => position(null)), sections), false);
        assert.equal(completeReadPageSequence([exact[0], exact[1], ...exact.slice(1)], sections), false);
        assert.equal(completeReadPageSequence(exact.filter((_, i) => i !== 2), sections), false);
        assert.equal(completeReadPageSequence(exact.slice(0, -1), sections), false);
    }
});

test('evidence does not copy arbitrary fields or pins into page positions', () => {
    const url = new URL('http://loopback.test/page?section=private_identifier&workspace_id=secret&receipt_id=real-key&offset=0x10');
    const page = readPageCoordinates('receiptPage', url);
    assert.deepEqual(page, { section: 'invalid', offset: -1, fragmentOffset: 0 });
    assert.equal(readPageCoordinates('status', url), undefined);
});

const chunk = (section, offset, total, rows, fragmentOffset = 0, fragmentTotal = 0, fragmentBytes = 0) =>
    ({ section, offset, fragmentOffset, total, rows, fragmentTotal, fragmentBytes });
const bundle = (section, chunks, offset = 0, fragmentOffset = 0) =>
    ({ ...position(section, offset, fragmentOffset), bundled: true, pageChunks: chunks });

test('a request can cover several sections and the next request must match its last segment', () => {
    const sections = ['first', 'second', 'third'];
    const first = bundle(null, [chunk('first', 0, 2, 2), chunk('second', 0, 3, 1)]);
    const second = bundle('second', [chunk('second', 1, 3, 2), chunk('third', 0, 0, 0)], 1);
    assert.ok(completeReadPageSequence([first, second], sections));
    assert.equal(completeReadPageSequence([first], sections), false);
    assert.equal(completeReadPageSequence([first, { ...second, pageChunks: [] }], sections), false);
    assert.equal(completeReadPageSequence([first, { ...second, pageChunks: second.pageChunks.slice(1) }], sections), false);
    assert.equal(completeReadPageSequence([first, bundle('second', second.pageChunks, 2)], sections), false);
    assert.equal(completeReadPageSequence([first, bundle('second', [chunk('second', 1, 2, 1), second.pageChunks[1]], 1)], sections), false);
    assert.equal(completeReadPageSequence([first, bundle('second', [second.pageChunks[0], ...second.pageChunks], 1)], sections), false);
    assert.equal(completeReadPageSequence([first, { ...second, bundled: false }], sections), false);
});

test('returning fragments in several requests must finish one exact record and every section', () => {
    const sections = ['first', 'second'];
    const first = bundle(null, [chunk('first', 0, 1, 0, 0, 12, 8)]);
    const second = bundle('first', [chunk('first', 0, 1, 0, 8, 12, 4), chunk('second', 0, 0, 0)], 0, 8);
    assert.ok(completeReadPageSequence([first, second], sections));
    assert.equal(completeReadPageSequence([first, bundle('first', second.pageChunks, 0, 9)], sections), false);
    assert.equal(completeReadPageSequence([first, bundle('first', [chunk('first', 0, 1, 0, 8, 13, 5), second.pageChunks[1]], 0, 8)], sections), false);
});

test('unbundled positions alone cannot pass the current installed client and service gate', () => {
    const sections = compatibleReadSections.snapshotPage;
    const requests = sections.map((section, index) => ({
        ...position(index === 0 ? null : section), route: 'snapshotPage', sequence: index + 1, flow: 'cold-snapshot',
    }));
    assert.ok(completeReadPageSequence(requests, sections));
    const details = { fixture: {}, checks: { completed: true, controls: { recovered: true, cancelled: true } },
        requestReports: [{ label: 'cash-list', flow: 'cold-snapshot', started: requests.length, requests, errors: [] }] };
    const { http } = JSON.parse(readFileSync(new URL('./budgets.json', import.meta.url)));
    const evaluated = evaluateMaximumHttpGate(details, details, http);
    assert.equal(evaluated.ok, false);
    assert.ok(evaluated.errors.includes('real/cash-list: missing negotiated snapshotPage chunk evidence'));
    assert.ok(evaluated.errors.includes('replay/cash-list: missing negotiated snapshotPage chunk evidence'));
});

test('page witness retains only known collection names and bounded numbers', () => {
    assert.deepEqual(readPageChunks('receiptPage', 'header:0:0:1:1:0:0,transactions:0:0:2:2:0:0'),
        [chunk('header', 0, 1, 1), chunk('transactions', 0, 2, 2)]);
    assert.throws(() => readPageChunks('receiptPage', 'not-a-private-id:0:0:1:1:0:0'), /invalid read page chunks/);
    assert.throws(() => readPageChunks('receiptPage', 'header:01:0:1:1:0:0'), /invalid read page chunks/);
    assert.throws(() => readPageChunks('receiptPage', 'header:2147483648:0:1:1:0:0'), /invalid read page chunks/);
    assert.throws(() => readPageChunks('receiptPage', undefined), /missing read page chunks/);
});

test('smaller isolated decoded bodies cannot substantiate the same real chunk positions', () => {
    const sections = compatibleReadSections.snapshotPage;
    const request = { ...bundle(null, sections.map(section => chunk(section, 0, 0, 0))),
        route: 'snapshotPage', sequence: 1, flow: 'cold-snapshot', status: 200, responseDecodedBytes: 200_000 };
    const evidence = (row) => ({
        fixture: {},
        checks: { completed: true, controls: { recovered: true, cancelled: true } },
        requestReports: [{ label: 'cash-list', flow: 'cold-snapshot', started: 1, requests: [row], errors: [] }],
    });
    const { http } = JSON.parse(readFileSync(new URL('./budgets.json', import.meta.url)));
    const result = evaluateMaximumHttpGate(evidence(request), evidence({ ...request, responseDecodedBytes: 20_000 }), http);
    assert.equal(result.ok, false);
    assert.ok(result.errors.includes('Real and isolated runs used different page positions or decoded sizes'));
});

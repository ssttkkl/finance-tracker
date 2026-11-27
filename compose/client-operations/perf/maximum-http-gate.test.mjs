import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { compatibleReadSections, readPageCoordinates } from './import-request-routes.mjs';
import { completeReadPageSequence } from './maximum-http-gate.mjs';

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

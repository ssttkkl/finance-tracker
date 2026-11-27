import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { measureCommand, parsePeakRss } from './large-measure.mjs';

test('the command timer normalizes GNU KiB and BSD byte measurements', () => {
    assert.equal(parsePeakRss('12345\n', 'linux'), 12_641_280);
    assert.equal(parsePeakRss('       12345  maximum resident set size\n', 'darwin'), 12_345);
    assert.throws(() => parsePeakRss('', 'linux'), /missing peak RSS/);
    assert.throws(() => parsePeakRss('0\n', 'linux'), /missing peak RSS/);
    assert.throws(() => parsePeakRss('100\n200\n', 'linux'), /missing peak RSS/);
});

test('the command timer requires a successful child, JSON, and a measurement', async () => {
    const outputDir = mkdtempSync(join(tmpdir(), 'ft-perf-timer-'));
    try {
        const options = { outputDir, timeoutMs: 5_000 };
        const report = await measureCommand(process.execPath, ['-e', 'console.log(JSON.stringify({ ok: true }))'], {
            ...options, label: 'success',
        });
        assert.deepEqual(report.value, { ok: true });
        assert.ok(report.ms > 0 && report.rssBytes > 0);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'process.exit(7)'], {
            ...options, label: 'exit',
        }), /code=7/);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'console.log("invalid JSON")'], {
            ...options, label: 'invalid-json',
        }), /JSON/);
        await assert.rejects(measureCommand(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], {
            ...options, label: 'timeout', timeoutMs: 10,
        }), /timed out/);
    } finally {
        rmSync(outputDir, { recursive: true, force: true });
    }
});

import { Cipheriv, Decipheriv } from 'node:crypto';
import { appendFileSync, readFileSync } from 'node:fs';
import { PerformanceObserver } from 'node:perf_hooks';
import { DatabaseSync } from 'node:sqlite';
import { getHeapStatistics, writeHeapSnapshot } from 'node:v8';

const destination = process.env.FT_PERF_LIVE_MEMORY_FILE;
const linux = process.platform === 'linux';
const large = 1024 ** 2;
const reads = { session: 0, ledger: 0, import: 0, other: 0 };
const writes = { session: 0, ledger: 0, import: 0, other: 0 };
const cryptoBytes = { cipherUpdate: 0, decipherUpdate: 0 };
const forcePhaseGc = process.env.FT_PERF_LIVE_MEMORY_FORCE_GC === '1';
if (forcePhaseGc && typeof globalThis.gc !== 'function') throw new Error('Retained-heap sampling requires --expose-gc');
let phase = 'startup';
let busy = false;
const snapshotPath = process.env.FT_DIAGNOSTIC_HEAP_PATH;
let snapshotTaken = false;

function bucket(slot) {
    if (slot === 'session') return 'session';
    if (slot?.includes('.import.')) return 'import';
    return slot && !slot.includes('.') ? 'ledger' : 'other';
}

function sample(event) {
    if (busy) return;
    busy = true;
    try {
        const memory = process.memoryUsage();
        const { malloced_memory: v8MallocBytes, total_physical_size: v8CommittedBytes } = getHeapStatistics();
        const kernel = {};
        if (linux) {
            const status = readFileSync('/proc/self/status', 'utf8');
            for (const [, name, kib] of status.matchAll(/^(VmHWM|RssAnon|RssFile|RssShmem):\s+(\d+) kB$/gm)) {
                kernel[name] = Number(kib) * 1024;
            }
        }
        appendFileSync(destination, JSON.stringify({ t: performance.now(), phase, event, ...memory,
            v8MallocBytes, v8CommittedBytes, kernel, reads, writes, cryptoBytes }) + '\n');
    } finally {
        busy = false;
    }
}

const prepare = DatabaseSync.prototype.prepare;
DatabaseSync.prototype.prepare = function (sql) {
    const statement = prepare.call(this, sql);
    if (sql.startsWith('SELECT wrapped_key, payload FROM client_state')) {
        const get = statement.get;
        statement.get = function (...args) {
            const row = get.apply(this, args);
            if (row) {
                const bytes = row.payload.byteLength;
                reads[bucket(args[1])] += bytes;
                if (bytes >= large) sample('sqlite-read');
            }
            return row;
        };
    } else if (sql.startsWith('INSERT OR REPLACE INTO client_state')) {
        const run = statement.run;
        statement.run = function (...args) {
            const bytes = args[3].byteLength;
            writes[bucket(args[1])] += bytes;
            if (bytes >= large) sample('sqlite-write-before');
            const result = run.apply(this, args);
            if (bytes >= large) sample('sqlite-write-after');
            return result;
        };
    }
    return statement;
};

function sampleCipherUpdate(Type, metric) {
    const update = Type.prototype.update;
    Type.prototype.update = function (data, ...args) {
        const result = update.call(this, data, ...args);
        const bytes = typeof data === 'string' ? Buffer.byteLength(data, args[0]) : data.byteLength;
        cryptoBytes[metric] += bytes;
        if (bytes >= large) sample(metric);
        return result;
    };
}
sampleCipherUpdate(Cipheriv, 'cipherUpdate');
sampleCipherUpdate(Decipheriv, 'decipherUpdate');

const stderrWrite = process.stderr.write;
process.stderr.write = function (chunk, ...args) {
    const match = typeof chunk === 'string' && /^ft import: ([a-z_]+)\n$/.exec(chunk);
    if (match) {
        phase = match[1];
        sample('import-phase');
        if (forcePhaseGc) {
            globalThis.gc();
            sample('import-phase-after-gc');
        }
        if (snapshotPath && !snapshotTaken && phase === 'diagnostic_rendered_preview') {
            snapshotTaken = true;
            const available = process.availableMemory();
            const required = Math.max(2 * 1024 ** 3, 3 * process.memoryUsage.rss());
            if (linux && process.env.GITHUB_ACTIONS === 'true' && available >= required) {
                sample('retained-snapshot-before');
                writeHeapSnapshot(snapshotPath);
            } else sample('retained-snapshot-skipped');
        }
    }
    return stderrWrite.call(this, chunk, ...args);
};

const gc = new PerformanceObserver(() => sample('gc-observer'));
gc.observe({ entryTypes: ['gc'] });
setInterval(() => sample('interval'), 50).unref();
process.on('exit', () => sample('exit'));
sample('startup');

import { writeFileSync } from 'node:fs';
import { Session } from 'node:inspector';

const destination = process.env.FT_DIAGNOSTIC_REVIEW_ALLOCATIONS;
if (destination) {
    const profiler = new Session();
    profiler.connect();
    const send = (method, parameters = {}) => new Promise((resolve, reject) =>
        profiler.post(method, parameters, (error, result) => error ? reject(error) : resolve(result)));
    const samplingInterval = 32 * 1024;
    await send('HeapProfiler.startSampling', {
        samplingInterval,
        includeObjectsCollectedByMajorGC: true,
        includeObjectsCollectedByMinorGC: true,
    });
    process.once('beforeExit', async () => {
        try {
            const { profile: { head } } = await send('HeapProfiler.stopSampling');
            const selected = [];
            const totals = {};
            function visit(node, ancestry) {
                const { functionName, lineNumber, url } = node.callFrame;
                const frame = url.includes('FinanceTracker-business-domain.mjs') ?
                    { functionName, line: lineNumber + 1 } : undefined;
                const stack = frame ? [...ancestry, frame] : ancestry;
                const names = stack.map(item => item.functionName);
                if (node.selfSize > 0) {
                    const narrowDecode = names.some(name => name.includes('projectedCashImportIssuesUnchanged'));
                    const sourceWrite = names.some(name => name.includes('applySourceWrites') || name.includes('buildLocalCashImportSourceWrites'));
                    const valueDecode = names.some(name => name.includes('decodeLocalCashValue'));
                    const bucket = narrowDecode ? 'projectedIssueCheck' : sourceWrite && valueDecode ? 'sourceWriteValueDecode' :
                        sourceWrite ? 'otherSourceWrite' : 'other';
                    totals[bucket] = (totals[bucket] || 0) + node.selfSize;
                    if (bucket !== 'other') selected.push({ bucket, bytes: node.selfSize, frames: stack.slice(-10) });
                }
                for (const child of node.children ?? []) visit(child, stack);
            }
            visit(head, []);
            selected.sort((a, b) => b.bytes - a.bytes);
            writeFileSync(destination, JSON.stringify({ samplingInterval, totals, largestDomainStacks: selected.slice(0, 15) }) + '\n');
        } finally {
            profiler.disconnect();
        }
    });
}

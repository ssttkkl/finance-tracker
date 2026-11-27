import { writeFileSync } from 'node:fs';
import { PerformanceObserver, performance } from 'node:perf_hooks';

const destination = process.env.FT_PERF_GC_EVIDENCE;
if (destination) {
    const samples = [];
    const collect = entries => {
        for (const entry of entries) {
            samples.push({
                startMs: entry.startTime,
                durationMs: entry.duration,
                kind: entry.detail.kind,
                flags: entry.detail.flags,
            });
        }
    };
    const observer = new PerformanceObserver(list => collect(list.getEntries()));
    observer.observe({ entryTypes: ['gc'] });
    process.once('exit', () => {
        collect(observer.takeRecords());
        observer.disconnect();
        writeFileSync(destination, JSON.stringify({
            available: true, timeOriginMs: performance.timeOrigin, samples,
        }), { flag: 'wx' });
    });
}

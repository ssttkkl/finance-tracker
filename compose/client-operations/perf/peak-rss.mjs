import { readFileSync, writeFileSync } from 'node:fs';

function linuxRss(pid) {
    const status = readFileSync(`/proc/${pid}/status`, 'utf8');
    const read = (field) => {
        const kib = Number(new RegExp(`^${field}:\\s+(\\d+) kB$`, 'm').exec(status)?.[1]);
        if (!Number.isSafeInteger(kib) || kib <= 0) throw new Error(`missing ${field} for measured process`);
        return kib * 1024;
    };
    return { resident: read('VmRSS'), highWaterMark: read('VmHWM') };
}

export function startPeakRss(pid = process.pid) {
    if (process.platform === 'linux') {
        writeFileSync(`/proc/${pid}/clear_refs`, '5');
        const initial = linuxRss(pid);
        return {
            method: 'linux-vmhwm',
            baselineRssBytes: initial.resident,
            readPeakBytes: () => Math.max(initial.resident, initial.highWaterMark, linuxRss(pid).highWaterMark),
        };
    }
    if (process.platform === 'darwin' && pid === process.pid) {
        return {
            method: 'darwin-process-lifetime-diagnostic',
            readPeakBytes: () => process.resourceUsage().maxRSS * 1024,
        };
    }
    throw new Error('request-window peak RSS requires Linux /proc');
}

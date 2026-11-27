import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { availableParallelism, cpus, freemem, loadavg, totalmem } from 'node:os';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';

function readCounter(path) {
    try {
        return readFileSync(path, 'utf8').trim();
    } catch (error) {
        if (!['ENOENT', 'EACCES', 'EPERM', 'ENOTDIR'].includes(error.code)) throw error;
        return null;
    }
}

function linuxCgroupRoot() {
    const cgroup = readCounter('/proc/self/cgroup')?.split('\n').find(line => line.startsWith('0::'))?.slice(3);
    const mount = '/sys/fs/cgroup';
    if (!cgroup) return null;
    const candidate = join(mount, cgroup);
    if (existsSync(join(candidate, 'cgroup.controllers'))) return candidate;
    return existsSync(join(mount, 'cgroup.controllers')) ? mount : null;
}

const cgroupRoot = process.platform === 'linux' ? linuxCgroupRoot() : null;

export function collectRunnerFacts() {
    const processors = cpus();
    // Runtime counts are included because the hosted label can use more than one VM type.
    return {
        cpuModels: [...new Set(processors.map(cpu => cpu.model))],
        reportedCpuMhz: [...new Set(processors.map(cpu => cpu.speed))],
        logicalProcessors: processors.length,
        availableParallelism: availableParallelism(),
        totalMemoryBytes: totalmem(),
        cgroupMode: cgroupRoot ? 'v2' : null,
        cpuQuota: cgroupRoot ? readCounter(join(cgroupRoot, 'cpu.max')) : null,
        memoryLimit: cgroupRoot ? readCounter(join(cgroupRoot, 'memory.max')) : null,
        initial: samplePressure(),
    };
}

export function samplePressure() {
    const system = process.platform === 'linux' ? {
        cpu: readCounter('/proc/pressure/cpu'),
        memory: readCounter('/proc/pressure/memory'),
        io: readCounter('/proc/pressure/io'),
        // Raw aggregate counters permit delta-based busy, idle, and steal-time comparison.
        cpuTicks: readCounter('/proc/stat')?.split('\n')[0] ?? null,
    } : null;
    const cgroup = cgroupRoot ? Object.fromEntries(
        ['cpu.stat', 'cpu.pressure', 'memory.pressure', 'memory.current', 'memory.peak', 'memory.events', 'io.pressure']
            .map(name => [name, readCounter(join(cgroupRoot, name))]),
    ) : null;
    return {
        utc: new Date().toISOString(),
        monotonicMs: performance.now(),
        loadAverage: loadavg(),
        freeMemoryBytes: freemem(),
        system,
        cgroup,
    };
}

export function sampleHostProcesses() {
    if (process.platform !== 'linux') return null;
    return readdirSync('/proc').filter(name => /^[0-9]+$/.test(name)).flatMap((pid) => {
        const entry = readCounter(join('/proc', pid, 'stat'));
        if (!entry) return [];
        // Linux stat field 2 is parenthesized and may itself contain spaces or parentheses.
        const end = entry.lastIndexOf(')');
        if (end < 0) return [];
        const fields = entry.slice(end + 1).trim().split(/\s+/);
        const ticks = [fields[11], fields[12], fields[19]].map(Number);
        if (!ticks.every(Number.isSafeInteger)) return [];
        const [userTicks, systemTicks, startTicks] = ticks;
        return [{ pid: Number(pid), name: entry.slice(entry.indexOf('(') + 1, end), userTicks, systemTicks, startTicks }];
    });
}

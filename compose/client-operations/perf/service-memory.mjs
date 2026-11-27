import { closeSync, mkdirSync, openSync, readFileSync, readSync, rmdirSync, writeSync } from 'node:fs';
import { join } from 'node:path';

export const serviceMemoryMethod = 'linux-cgroup-v2-memory-peak';

export function createServiceMemory(parent, label) {
    if (!/^[a-z0-9-]+$/.test(label)) throw new Error('Invalid benchmark cgroup label');
    const group = join(parent, label);
    mkdirSync(group);
    const membership = join(group, 'cgroup.procs');
    return {
        command(bin, args) {
            if (process.env.FT_PERF_CGROUP_SUDO === '1') {
                return ['/bin/sh', ['-ec',
                    `sudo -n /bin/sh -ec 'echo "$1" > "$2"' attach "$$" "$1"; shift; exec "$@"`,
                    'benchmark-cgroup', membership, bin, ...args]];
            }
            return ['/bin/sh', ['-ec', 'echo "$$" > "$1"; shift; exec "$@"', 'benchmark-cgroup', membership, bin, ...args]];
        },
        start() {
            const fd = openSync(join(group, 'memory.peak'), 'r+');
            const readPeak = () => {
                const data = Buffer.alloc(64);
                const length = readSync(fd, data, 0, data.length, 0);
                const value = Number(data.subarray(0, length).toString().trim());
                if (!Number.isSafeInteger(value) || value <= 0) throw new Error('Missing service memory peak');
                return value;
            };
            try {
                writeSync(fd, 'reset');
                const baselineBytes = readPeak();
                return {
                    method: serviceMemoryMethod,
                    baselineBytes,
                    finish() {
                        try {
                            return Math.max(baselineBytes, readPeak());
                        } finally {
                            closeSync(fd);
                        }
                    },
                };
            } catch (error) {
                closeSync(fd);
                throw error;
            }
        },
        remove() {
            if (readFileSync(membership, 'utf8').trim()) throw new Error('Benchmark cgroup still has live processes');
            rmdirSync(group);
        },
    };
}

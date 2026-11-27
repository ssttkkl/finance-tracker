import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { createServiceMemory } from './service-memory.mjs';

const parent = process.env.FT_PERF_CGROUP_PARENT;

test('retains a terminated descendant peak, resets the next window and excludes the controller', { skip: !parent }, async () => {
    const group = createServiceMemory(parent, `regression-${process.pid}`);
    const [bin, args] = group.command(process.execPath, ['-e', `
        const {spawnSync} = require('node:child_process');
        process.stdout.write('ready');
        process.stdin.once('data', () => {
            const child = spawnSync(process.execPath, ['-e', 'Buffer.alloc(48 * 1024 ** 2, 42)']);
            if (child.status !== 0) process.exit(1);
            process.stdout.write('child-exited');
        });
    `]);
    const child = spawn(bin, args, { stdio: ['pipe', 'pipe', 'inherit'] });
    const exit = once(child, 'exit');
    try {
        assert.equal(String((await once(child.stdout, 'data'))[0]), 'ready');
        const first = group.start();
        child.stdin.write('allocate');
        assert.equal(String((await once(child.stdout, 'data'))[0]), 'child-exited');
        const peak = first.finish();
        assert.ok(peak - first.baselineBytes >= 40 * 1024 ** 2);
        const second = group.start();
        const outside = Buffer.alloc(96 * 1024 ** 2, 42);
        assert.ok(second.finish() < peak);
        assert.equal(outside.at(-1), 42);
    } finally {
        child.stdin.end();
        await exit;
        group.remove();
    }
});

import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';
import { setTimeout as pause } from 'node:timers/promises';

// This must share only the synthetic test schema with the real backend.
export async function holdCashWriteLock(psqlArgs) {
    const child = spawn('psql', psqlArgs, {
        env: { ...process.env, PGAPPNAME: 'ft-performance-write-lock' },
        stdio: ['pipe', 'pipe', 'ignore'],
    });
    const lines = createInterface({ input: child.stdout });
    const exited = new Promise((resolve) => child.once('close', (status) => resolve(status)));
    const startup = Promise.race([
        once(lines, 'line').then(([line]) => {
            const pid = Number(line);
            if (!Number.isSafeInteger(pid) || pid < 1) throw new Error('invalid PostgreSQL lock holder PID');
            return pid;
        }),
        once(child, 'error').then(() => { throw new Error('failed to start PostgreSQL lock holder'); }),
        exited.then(() => { throw new Error('PostgreSQL lock holder exited before acquiring lock'); }),
        pause(8_000, undefined, { ref: false }).then(() => { throw new Error('PostgreSQL lock acquisition timed out'); }),
    ]);
    child.stdin.on('error', () => {});
    child.stdin.write(`
begin;
set local lock_timeout = '6s';
lock table public.cash_transactions in access exclusive mode;
select pg_backend_pid();
`);
    let pid;
    try {
        pid = await startup;
    } catch (error) {
        child.kill();
        await exited;
        throw error;
    }
    return {
        pid,
        async release() {
            child.stdin.end('rollback;\n');
            const status = await Promise.race([exited, pause(4_000, undefined, { ref: false })]);
            if (status !== 0) {
                child.kill();
                await exited;
                throw new Error('PostgreSQL lock holder did not release cleanly');
            }
        },
    };
}

export async function waitForCashWriter(psql, holderPid, { waitMs = 8_000 } = {}) {
    if (!Number.isSafeInteger(holderPid) || holderPid < 1) throw new Error('invalid lock holder PID');
    const deadline = performance.now() + waitMs;
    const query = `select pid from pg_stat_activity
        where datname = current_database()
        and ${holderPid} = any(pg_blocking_pids(pid))
        and pid <> pg_backend_pid()`;
    do {
        const blocked = await psql(['-c', query]);
        if (/^\d+$/.test(blocked)) return Number(blocked);
        if (blocked) throw new Error('unexpected extra connection blocking the controlled cash writer');
        await pause(100);
    } while (performance.now() < deadline);
    throw new Error('cash import worker did not reach its PostgreSQL write barrier');
}

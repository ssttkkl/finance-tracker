import { spawn } from 'node:child_process';
import { closeSync, openSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';

export function parsePeakRss(report, platform = process.platform) {
    const match = platform === 'darwin'
        ? report.match(/^\s*(\d+)\s+maximum resident set size\s*$/m)
        : report.match(/^\s*(\d+)\s*$/);
    const bytes = Number(match?.[1]) * (platform === 'darwin' ? 1 : 1024);
    if (!Number.isSafeInteger(bytes) || bytes <= 0) throw new Error('missing peak RSS');
    return bytes;
}

/** Measures the installed client process, excluding fixture setup and backend memory. */
export async function measureCommand(bin, args, { env, input, timeoutMs, outputDir, label }) {
    const platform = process.platform;
    if (platform !== 'linux' && platform !== 'darwin') throw new Error('Unsupported process measurement platform');
    const stdoutPath = join(outputDir, label + '.json');
    const stderrPath = join(outputDir, label + '.log');
    const reportPath = join(outputDir, label + '.rss');
    const stdout = openSync(stdoutPath, 'wx');
    const stderr = openSync(stderrPath, 'wx');
    let timer;
    let timedOut = false;
    let start;
    let childResult;
    try {
        const timeOptions = platform === 'darwin' ? ['-l'] : ['-f', '%M', '-o', reportPath];
        start = performance.now();
        const child = spawn('/usr/bin/time', [...timeOptions, bin, ...args], {
            env: { ...env, LC_ALL: 'C' }, detached: true, stdio: ['pipe', stdout, stderr],
        });
        timer = setTimeout(() => {
            timedOut = true;
            if (child.pid == null) return;
            try {
                process.kill(-child.pid, 'SIGKILL');
            } catch (error) {
                if (error.code !== 'ESRCH') throw error;
            }
        }, timeoutMs);
        child.stdin.on('error', () => { /* Exit status records a failed child. */ });
        child.stdin.end(input);
        childResult = await new Promise((resolve, reject) => {
            child.once('error', reject);
            child.once('close', (code, signal) => resolve({ code, signal, ms: performance.now() - start }));
        });
    } finally {
        clearTimeout(timer);
        closeSync(stdout);
        closeSync(stderr);
    }
    if (timedOut) throw new Error(`${label} timed out after ${timeoutMs} ms`);
    const diagnostics = readFileSync(stderrPath, 'utf8');
    const stdoutText = readFileSync(stdoutPath, 'utf8');
    if (childResult.code !== 0 || childResult.signal !== null) {
        throw new Error(`${label} failed (code=${childResult.code}, signal=${childResult.signal})\n` +
            diagnostics.slice(-1800) + stdoutText.slice(0, 600));
    }
    const measurement = platform === 'darwin' ? diagnostics : readFileSync(reportPath, 'utf8');
    let value;
    try {
        value = JSON.parse(stdoutText);
    } catch (cause) {
        throw new Error(`${label} produced invalid or missing JSON`, { cause });
    }
    return { value, ms: childResult.ms, rssBytes: parsePeakRss(measurement, platform), label, stdoutPath };
}

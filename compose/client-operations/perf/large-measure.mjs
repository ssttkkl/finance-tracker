import { spawn } from 'node:child_process';
import { closeSync, existsSync, openSync, readFileSync, writeSync } from 'node:fs';
import { join } from 'node:path';
import { performance } from 'node:perf_hooks';
import { StringDecoder } from 'node:string_decoder';
import { sampleHostProcesses, samplePressure } from './large-telemetry.mjs';

const gnuFields = 'ft-process %M %e %U %S %F %R %W %c %w';

export function parsePeakRss(report, platform = process.platform) {
    const match = platform === 'darwin'
        ? report.match(/^\s*(\d+)\s+maximum resident set size\s*$/m)
        : report.match(/^ft-process (\d+) /m) ?? report.match(/^\s*(\d+)\s*$/);
    const bytes = Number(match?.[1]) * (platform === 'darwin' ? 1 : 1024);
    if (!Number.isSafeInteger(bytes) || bytes <= 0) throw new Error('missing peak RSS');
    return bytes;
}

export function parseProcessResources(report, platform = process.platform) {
    const rssBytes = parsePeakRss(report, platform);
    if (platform === 'darwin') {
        const [, elapsed, user, system] = report.match(/^\s*([\d.]+) real\s+([\d.]+) user\s+([\d.]+) sys\b/m) ?? [];
        const count = name => {
            const value = report.match(new RegExp(`^\\s*(\\d+)\\s+${name}\\s*$`, 'm'))?.[1];
            return value === undefined ? null : Number(value);
        };
        return {
            rssBytes, timerWallMs: seconds(elapsed), userCpuMs: seconds(user), systemCpuMs: seconds(system),
            majorFaults: count('page faults'), minorFaults: count('page reclaims'),
            swaps: count('swaps'), involuntaryContextSwitches: count('involuntary context switches'),
            voluntaryContextSwitches: count('voluntary context switches'),
        };
    }
    const values = report.match(/^ft-process [^\n]+/m)?.[0].trim().split(/\s+/).slice(1).map(Number);
    if (values?.length !== 9 || !values.every(value => Number.isFinite(value) && value >= 0)) {
        throw new Error('missing GNU process resource metrics');
    }
    const [, elapsed, user, system, major, minor, swaps, involuntary, voluntary] = values;
    return {
        rssBytes, timerWallMs: seconds(elapsed), userCpuMs: seconds(user), systemCpuMs: seconds(system),
        majorFaults: major, minorFaults: minor, swaps,
        involuntaryContextSwitches: involuntary, voluntaryContextSwitches: voluntary,
    };
}

function seconds(value) {
    return value === undefined ? null : Number(value) * 1000;
}

/** Measure the installed client only. Diagnostic timestamps use the same clock as the TLS proxy. */
export async function measureCommand(bin, args, { env, input, timeoutMs, outputDir, label, collectGc = false }) {
    const platform = process.platform;
    if (platform !== 'linux' && platform !== 'darwin') throw new Error('Unsupported process measurement platform');
    const stdoutPath = join(outputDir, label + '.json');
    const stderrPath = join(outputDir, label + '.log');
    const reportPath = join(outputDir, label + '.time');
    const gcPath = join(outputDir, label + '.gc.json');
    const stdout = openSync(stdoutPath, 'wx');
    const stderr = openSync(stderrPath, 'wx');
    const phases = [];
    const before = { ...samplePressure(), ...(collectGc ? { processes: sampleHostProcesses() } : {}) };
    let timer;
    let timedOut = false;
    let childResult;
    const startedAt = new Date().toISOString();
    const start = performance.now();
    try {
        const timeOptions = platform === 'darwin' ? ['-l'] : ['-f', gnuFields, '-o', reportPath];
        const gcEnv = collectGc ? {
            NODE_OPTIONS: `--import=${new URL('./large-gc-observer.mjs', import.meta.url).href}`,
            FT_PERF_GC_EVIDENCE: gcPath,
        } : {};
        const child = spawn('/usr/bin/time', [...timeOptions, bin, ...args], {
            env: { ...env, LC_ALL: 'C', ...gcEnv }, detached: true, stdio: ['pipe', stdout, 'pipe'],
        });
        const decoder = new StringDecoder('utf8');
        let pending = '';
        child.stderr.on('data', (chunk) => {
            writeSync(stderr, chunk);
            const lines = (pending + decoder.write(chunk)).split('\n');
            pending = lines.pop();
            for (const line of lines) {
                const phase = /^ft import: ([a-z_]+)$/.exec(line)?.[1];
                if (phase) phases.push({ phase, elapsedMs: performance.now() - start, pressure: samplePressure() });
            }
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
    const endedAt = new Date().toISOString();
    const after = { ...samplePressure(), ...(collectGc ? { processes: sampleHostProcesses() } : {}) };
    const diagnostics = readFileSync(stderrPath, 'utf8');
    const stdoutText = readFileSync(stdoutPath, 'utf8');
    const report = platform === 'darwin' ? diagnostics : existsSync(reportPath) ? readFileSync(reportPath, 'utf8') : '';
    const measurement = { label, startedAt, endedAt, before, after, phases, timedOut, ...childResult };
    if (collectGc) {
        measurement.gc = existsSync(gcPath)
            ? JSON.parse(readFileSync(gcPath, 'utf8'))
            : { available: false };
    }
    try {
        Object.assign(measurement, parseProcessResources(report, platform));
    } catch (error) {
        measurement.resourceError = error.message;
    }
    try {
        if (timedOut) throw new Error(`${label} timed out after ${timeoutMs} ms`);
        if (childResult.code !== 0 || childResult.signal !== null) {
            throw new Error(`${label} failed (code=${childResult.code}, signal=${childResult.signal})\n` +
                diagnostics.slice(-1800) + stdoutText.slice(0, 600));
        }
        if (measurement.resourceError) throw new Error(measurement.resourceError);
        let value;
        try {
            value = JSON.parse(stdoutText);
        } catch (cause) {
            throw new Error(`${label} produced invalid or missing JSON`, { cause });
        }
        return { value, ms: childResult.ms, rssBytes: measurement.rssBytes, label, stdoutPath, process: measurement };
    } catch (error) {
        error.measurement = measurement;
        throw error;
    }
}

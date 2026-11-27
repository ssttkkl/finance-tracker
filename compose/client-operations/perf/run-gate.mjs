import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { appendFileSync, closeSync, cpSync, existsSync, mkdirSync, mkdtempSync, openSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { arch, release, tmpdir, type } from 'node:os';
import { join, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { compare, evaluate, inventory, readEvents, readSignatures, requireMaximumEvidence, scenariosForJob } from './gate.mjs';
import { evaluateMaximumHttpGate } from './maximum-http-gate.mjs';

const perf = fileURLToPath(new URL('./', import.meta.url));
const root = resolve(perf, '../../..');
const [job, reportPath] = process.argv.slice(2);
if (!['ordinary', 'maximum'].includes(job) || !reportPath) throw new Error('Usage: node run-gate.mjs ordinary|maximum REPORT_DIR');
const report = resolve(reportPath);
mkdirSync(report, { recursive: true });
const manifestBytes = readFileSync(join(perf, 'budgets.json'));
const manifest = JSON.parse(manifestBytes);
const lane = process.env.FT_PERF_ORDINARY_LANE ?? 'all';
const scenarios = scenariosForJob(manifest, job, lane, true);
const baseSha = process.env.FT_PERF_BASE_SHA;
const ownedTemporary = [];
function temporaryDirectory(label) {
  const path = mkdtempSync(join(tmpdir(), label));
  const entry = { path };
  ownedTemporary.push(entry);
  return entry;
}
const tested = {
  head: capture('git', ['rev-parse', 'HEAD']).trim(), base: baseSha ?? null,
  job, lane,
  os: type(), release: release(), arch: arch(), node: process.version,
  image: process.env.ImageOS ?? null, imageVersion: process.env.ImageVersion ?? null,
  runId: process.env.GITHUB_RUN_ID ?? null, attempt: process.env.GITHUB_RUN_ATTEMPT ?? null,
  clientPeakRss: {
    shared: type() === 'Linux' ? 'linux-vmhwm (operation window, kernel page accounting)' : 'darwin-process-lifetime-diagnostic',
    cold: 'OS child process high-water mark',
  },
  runner: process.env.RUNNER_NAME ?? null, replica: process.env.FT_PERF_REPLICA ?? null,
  budgetsSha256: createHash('sha256').update(manifestBytes).digest('hex'),
  java: capture('java', ['-version'], root, true).trim(),
};
writeReport('environment.json', tested);
if (process.env.GITHUB_ACTIONS === 'true' &&
    (type() !== 'Linux' || arch() !== 'x64' || process.version !== 'v26.13.1' ||
      !/^VERSION_ID="26\.04"$/m.test(readFileSync('/etc/os-release', 'utf8')) || !/version "21[."]/i.test(tested.java))) {
  throw new Error('Performance gate requires Ubuntu 26.04 x64, Node.js 26.13.1 and Java 21');
}

function writeReport(name, data) {
  writeFileSync(join(report, name), JSON.stringify(data, null, 2) + '\n');
}

function command(program, args, { cwd = root, env = process.env, log, allowFailure = false } = {}) {
  const fd = log ? openSync(log, 'a') : null;
  try {
    const child = spawnSync(program, args, { cwd, env, stdio: fd === null ? 'inherit' : ['ignore', fd, fd] });
    if (child.error) throw child.error;
    if (child.status !== 0 && !allowFailure) throw new Error(`${program} exited with ${child.signal ?? child.status}; see ${log ?? 'process output'}`);
    return child.status === 0;
  } finally {
    if (fd !== null) closeSync(fd);
  }
}

function capture(program, args, cwd = root, useStderr = false) {
  const child = spawnSync(program, args, { cwd, encoding: 'utf8' });
  if (child.status !== 0) throw new Error(`${program}: ${child.error ?? child.stderr}`);
  return useStderr ? child.stderr : child.stdout;
}

function prepare(checkout, name) {
  const logs = join(report, name);
  mkdirSync(logs, { recursive: true });
  const output = temporaryDirectory(`ft-perf-${name}-`).path;
  const logfile = join(logs, 'build.log');
  const gradle = join(checkout, 'compose/gradlew');
  const targets = [':backend:installDist'];
  if (job === 'maximum' && name === 'head') targets.push(':backend:stagePerformanceReplayClasspath');
  if (lane !== 'shared') targets.push(':cli:stageNpm');
  if (job === 'ordinary' && lane !== 'cli') {
    targets.push(':client-operations-perf:stageBenchmark');
    if (name === 'head') targets.push(':client-operations:dumpPerformanceAbi');
  }
  command(gradle, [...targets, '--no-configuration-cache'], { cwd: join(checkout, 'compose'), log: logfile });
  command(gradle, ['--stop'], { cwd: join(checkout, 'compose'), log: logfile });
  if (lane === 'shared') return { checkout };

  const packages = JSON.parse(capture('npm', ['pack', '--json', '--pack-destination', output, join(checkout, 'compose/cli/build/npm')]));
  if (packages.length !== 1) throw new Error('Expected one staged npm archive');
  const archive = join(output, packages[0].filename);
  command('npm', ['install', '--ignore-scripts', '--engine-strict', '--no-audit', '--no-fund', archive], { cwd: output, log: logfile });
  return { checkout, archive, bin: join(output, 'node_modules/.bin/ft') };
}

function baseCheckout() {
  if (!baseSha) return null;
  if (!/^[a-f0-9]{40}$/.test(baseSha)) throw new Error('Invalid base SHA');
  const paths = capture('git', ['ls-tree', '-d', '--name-only', baseSha, 'compose/client-operations', 'compose/cli']).trim().split('\n');
  if (paths.length !== 2) return null;
  const location = temporaryDirectory('ft-perf-comparison-');
  const checkout = join(location.path, 'checkout');
  command('git', ['worktree', 'add', '--detach', checkout, baseSha]);
  location.worktree = checkout;
  const basePerf = join(checkout, 'compose/client-operations/perf');
  cpSync(perf, basePerf, {
    recursive: true,
    filter: (source) => !source.split(sep).some((part) => part === 'build' || part === 'node_modules'),
  });
  cpSync(join(root, 'scripts/generate_cash_import_fixtures.py'), join(checkout, 'scripts/generate_cash_import_fixtures.py'));
  const settings = join(checkout, 'compose/settings.gradle.kts');
  if (!readFileSync(settings, 'utf8').includes('":client-operations-perf"')) {
    appendFileSync(settings, '\ninclude(":client-operations-perf")\nproject(":client-operations-perf").projectDir = file("client-operations/perf")\n');
  }
  return checkout;
}

function verifyFixture(first, second, cases) {
  if (job === 'maximum') {
    const readFixture = (name) => JSON.parse(readFileSync(join(report, name, 'large-details.json'))).fixture;
    if (JSON.stringify(readFixture(first)) !== JSON.stringify(readFixture(second))) {
      throw new Error(`Fixture mismatch between ${first} and ${second}`);
    }
    return;
  }
  const collect = (phase) => readEvents(join(report, phase, 'measurements.jsonl')).filter((event) => !event.warmup);
  const a = collect(first);
  const b = collect(second);
  for (const { id } of cases) {
    const samples = [...a, ...b].filter((event) => event.id === id);
    const fixture = JSON.stringify(samples[0]?.fixture);
    if (!fixture || !samples.every((sample) => JSON.stringify(sample.fixture) === fixture)) {
      throw new Error(`Missing or different fixture for ${id} between ${first} and ${second}`);
    }
  }
}

function postgresUrl(phase) {
  if (!process.env.FT_PERF_PG_ADMIN_URL) {
    if (phase !== 'head') throw new Error('Repeat measurements need a PostgreSQL provisioner');
    return process.env.FT_TEST_POSTGRES_URL;
  }
  const admin = new URL(process.env.FT_PERF_PG_ADMIN_URL);
  if (admin.protocol !== 'postgresql:' || admin.hostname !== '127.0.0.1' || admin.pathname !== '/postgres' ||
      !/^[a-z-]+$/.test(phase)) throw new Error('Expected a loopback PostgreSQL test provisioner');
  const database = `ft_perf_${phase.replaceAll('-', '_')}_${process.pid}_test`;
  command('psql', [admin.toString(), '-v', 'ON_ERROR_STOP=1', '-c', `CREATE DATABASE "${database}"`]);
  admin.pathname = '/' + database;
  return admin.toString();
}

function measure(prepared, phase, selected, enforceBudget) {
  const out = join(report, phase);
  mkdirSync(out, { recursive: true });
  const eventsPath = join(out, 'measurements.jsonl');
  writeFileSync(eventsPath, '');
  const env = { ...process.env, FT_PERF_ARTIFACTS: out, FT_PERF_EVENTS: eventsPath,
    ...(prepared.archive ? { FT_PERF_FT: prepared.bin, FT_PERF_CLI_PACKAGE: prepared.archive } : {}) };
  for (const variable of ['NODE_OPTIONS', 'FT_PERF_DIAGNOSTIC', 'FT_PERF_WARMUPS', 'FT_PERF_SAMPLES', 'FT_PERF_ONLY', 'FT_PERF_SELECT']) {
    delete env[variable];
  }
  delete env.FT_PERF_LIVE_MEMORY_FLOWS;
  delete env.FT_PERF_ISOLATED_BACKEND;
  delete env.FT_PERF_LEGACY_COMPARISON;
  const legacyComparison = !enforceBudget && !existsSync(join(prepared.checkout,
    'compose/backend/src/main/kotlin/com/finance/tracker/backend/CashImportConfirmationService.kt'));
  if (legacyComparison) env.FT_PERF_LEGACY_COMPARISON = '1';
  const checkoutPerf = join(prepared.checkout, 'compose/client-operations/perf');
  const run = (script, options = {}) => command(process.execPath, [join(checkoutPerf, script)], {
    cwd: prepared.checkout, env: { ...env, ...options }, log: join(out, 'runtime.log'), allowFailure: true,
  });
  let ok = true;
  let replayDetails;
  let replayOk = true;
  if (job === 'maximum') {
    env.FT_TEST_POSTGRES_URL = postgresUrl(phase);
    ok = run('large-bench.mjs');
    if (enforceBudget) {
      const replayDir = join(out, 'replay');
      mkdirSync(replayDir);
      replayOk = run('large-bench.mjs', {
        FT_PERF_ISOLATED_BACKEND: '1',
        FT_TEST_POSTGRES_URL: !process.env.FT_PERF_PG_ADMIN_URL && phase === 'head' && process.env.FT_PERF_REPLAY_POSTGRES_URL
          ? process.env.FT_PERF_REPLAY_POSTGRES_URL : postgresUrl(`${phase}-replay`),
        FT_PERF_ARTIFACTS: replayDir,
        FT_PERF_EVENTS: join(replayDir, 'measurements.jsonl'),
      });
      if (existsSync(join(replayDir, 'large-details.json'))) {
        replayDetails = JSON.parse(readFileSync(join(replayDir, 'large-details.json')));
      }

    }
  } else {
    const shared = selected.filter((scenario) => !scenario.id.startsWith('cli.'));
    const cli = selected.filter((scenario) => scenario.id.startsWith('cli.'));
    if (shared.length) {
      const overrides = shared.length === scenarios.filter((scenario) => !scenario.id.startsWith('cli.')).length
        ? {} : { FT_PERF_SELECT: shared.map(({ id }) => id).join(',') };
      ok = run('build/benchmark/operations-benchmarks.mjs', overrides) && ok;
    }
    if (cli.length) {
      const overrides = cli.length === scenarios.filter((scenario) => scenario.id.startsWith('cli.')).length
        ? {} : { FT_PERF_ONLY: cli.map(({ id }) => id).join(',') };
      ok = run('cli-bench.mjs', overrides) && ok;
    }
  }
  const comparable = legacyComparison && job === 'maximum'
    ? selected.filter((scenario) => !['large.confirm-no-wait', 'large.confirmation-status',
      'large.cancel-confirmation', 'large.resume'].includes(scenario.id))
    : selected;
  const budgets = enforceBudget ? comparable : comparable.map((scenario) => ({
    ...scenario, budgetMs: Infinity,
    ...(scenario.maxRssBytes === undefined ? {} : { maxRssBytes: Number.MAX_SAFE_INTEGER }),
  }));
  let events = [];
  let reportError;
  let httpOk = true;
  try {
    events = readEvents(eventsPath);
    if (job === 'maximum') {
      const details = JSON.parse(readFileSync(join(out, 'large-details.json')));
      if (enforceBudget) {
        const http = evaluateMaximumHttpGate(details, replayDetails, manifest.http);
        writeReport(`${phase}-http.json`, http);
        if (!http.ok || !replayOk) {
          console.error(`HTTP performance: ${http.errors.length} errors (see ${phase}-http.json)`);
          httpOk = false;
        }
      }
      requireMaximumEvidence(details, events);
    }
  } catch (error) {
    reportError = error.message;
  }
  const result = evaluate(budgets, events, ok && !reportError);
  if (reportError) result.errors.unshift(reportError);
  if (!httpOk) {
    result.ok = false;
    result.errors.push('HTTP performance failed');
  }
  writeReport(`${phase}.json`, result);
  console.log(`${phase}: ${result.results.length} scenarios, ${result.errors.length} errors`);
  result.errors.forEach((error) => console.error(error));
  return result;
}

function runGate() {
  const head = prepare(root, 'head');
  let inventoryError;
  if (job === 'ordinary' && lane !== 'cli') {
    try {
      inventory(manifest, readSignatures(join(root, 'compose/client-operations/build/performance/client-operations.abi')));
    } catch (error) {
      inventoryError = error.message;
      console.error(inventoryError);
      writeReport('inventory-errors.json', { error: inventoryError });
    }
  }
  let base;
  let baselinePreparationError;
  try {
    const alternate = baseCheckout();
    base = alternate && prepare(alternate, 'base');
  } catch (error) {
    baselinePreparationError = error.message;
    console.error(baselinePreparationError);
  }
  const initial = measure(head, 'head', scenarios, true);
  const relative = scenarios.filter(({ relativeFloorMs }) => relativeFloorMs !== undefined);
  let ok = initial.ok && !inventoryError;
  if (baselinePreparationError) {
    writeReport('relative.json', { status: 'baseline preparation failed', baseSha, error: baselinePreparationError });
    ok = false;
  } else if (!base) {
    writeReport('relative.json', {
      status: baseSha ? 'baseline lacks CLI or shared module' : 'base SHA not provided (local run)', baseSha: baseSha ?? null,
    });
  } else {
    // The complete maximum fixture creates its receipt once per independent database.
    const selected = job === 'maximum' ? scenarios : relative;
    const baseline = measure(base, 'base', selected, false);
    if (!baseline.valid || !initial.valid) {
      writeReport('relative.json', { status: 'initial measurement failed', baseSha });
      ok = false;
    } else {
      verifyFixture('head', 'base', relative);
      const detected = compare(relative, baseline, initial);
      const triggered = relative.filter((scenario) => detected.some(({ id }) => id === scenario.id));
      if (!triggered.length) {
        writeReport('relative.json', { status: 'comparable', detected });
      } else {
        const retryScenarios = job === 'maximum' ? scenarios : triggered;
        const newBase = measure(base, 'base-repeat', retryScenarios, false);
        const newHead = measure(head, 'head-repeat', retryScenarios, true);
        if (newBase.valid && newHead.valid) {
          verifyFixture('base', 'base-repeat', triggered);
          verifyFixture('base', 'head-repeat', triggered);
        }
        const reproduced = newBase.valid && newHead.valid ? compare(triggered, newBase, newHead) : [];
        writeReport('relative.json', { status: 'retested', detected, reproduced });
        if (!newBase.ok || !newHead.ok || reproduced.length) ok = false;
        for (const regression of reproduced) console.error(`Reproduced regression ${regression.id}: ${regression.before} to ${regression.after} ms`);
      }
    }
  }
  if (!ok) process.exitCode = 1;
}

try {
  runGate();
} finally {
  for (const item of ownedTemporary.reverse()) {
    if (item.worktree) {
      command('git', ['worktree', 'remove', '--force', item.worktree]);
    }
    rmSync(item.path, { recursive: true, force: true });
  }
}

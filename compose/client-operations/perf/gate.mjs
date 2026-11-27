import { readFileSync, writeFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function scenariosForJob(manifest, job, lane = 'all') {
  if (!['ordinary', 'maximum'].includes(job) ||
      (job === 'ordinary' ? !['all', 'shared', 'cli'].includes(lane) : lane !== 'all')) {
    throw new Error(`invalid benchmark job and lane: ${job}/${lane}`);
  }
  const scenarios = manifest.scenarios.filter((scenario) => scenario.job === job &&
    (lane === 'all' || scenario.id.startsWith('cli.') === (lane === 'cli')));
  if (!scenarios.length) throw new Error(`no scenarios for ${job}/${lane}`);
  return scenarios;
}

export function evaluate(scenarios, events, commandOk = true) {
  const expected = new Map(scenarios.map((scenario) => [scenario.id, { scenario, warmups: 0, samples: [] }]));
  const errors = commandOk ? [] : ['benchmark command failed'];
  const absoluteErrors = [];
  for (const event of events) {
    if (!event || typeof event !== 'object' || typeof event.id !== 'string' ||
        (event.warmup !== undefined && event.warmup !== true)) {
      errors.push('invalid event');
      continue;
    }
    const group = expected.get(event.id);
    if (!group) {
      errors.push(`unexpected scenario: ${event.id}`);
    } else if (event.asserted !== true) {
      errors.push(`${event.id}: correctness was not verified`);
    } else if (event.warmup === true) {
      group.warmups += 1;
    } else if (!Number.isFinite(event.ms) || event.ms <= 0 ||
        (!Number.isSafeInteger(event.rssBytes) || event.rssBytes <= 0)) {
      errors.push(`${event.id}: invalid measurement`);
    } else {
      group.samples.push(event);
    }
  }
  const results = [];
  for (const { scenario, warmups, samples } of expected.values()) {
    if (warmups !== scenario.warmups || samples.length !== scenario.samples) {
      errors.push(`${scenario.id}: expected ${scenario.warmups} warmups and ${scenario.samples} samples, saw ${warmups} and ${samples.length}`);
    }
    const sorted = samples.map((sample) => sample.ms).sort((a, b) => a - b);
    const ms = sorted[Math.ceil(0.95 * sorted.length) - 1] ?? null;
    const rssBytes = Math.max(0, ...samples.map((sample) => sample.rssBytes ?? 0)) || null;
    if (scenario.budgetMs === null) absoluteErrors.push(`${scenario.id}: uncalibrated operation latency budget`);
    else if (ms > scenario.budgetMs) absoluteErrors.push(`${scenario.id}: ${ms} ms exceeds ${scenario.budgetMs} ms`);
    if (scenario.maxRssBytes !== null &&
        (!Number.isSafeInteger(scenario.maxRssBytes) || scenario.maxRssBytes <= 0)) {
      errors.push(`${scenario.id}: invalid client RSS budget`);
    }
    if (scenario.maxRssBytes === null) absoluteErrors.push(`${scenario.id}: uncalibrated client RSS budget`);
    else if (rssBytes > scenario.maxRssBytes) absoluteErrors.push(`${scenario.id}: ${rssBytes} B exceeds ${scenario.maxRssBytes} B`);
    results.push({ id: scenario.id, ms, rssBytes, warmups, sampleCount: samples.length });
  }
  const valid = errors.length === 0;
  return { ok: valid && absoluteErrors.length === 0, valid, errors: [...errors, ...absoluteErrors], results };
}

export function compare(scenarios, baseline, head) {
  return scenarios.filter(({ relativeFloorMs }) => relativeFloorMs !== undefined).flatMap(({ id, relativeFloorMs }) => {
    const before = baseline.results.find((row) => row.id === id)?.ms;
    const after = head.results.find((row) => row.id === id)?.ms;
    if (!(before > 0 && after > 0)) throw new Error(`missing relative measurement: ${id}`);
    const increase = after - before;
    return increase >= relativeFloorMs && increase / before > 0.2 ? [{ id, before, after, increase }] : [];
  });
}

export function inventory(manifest, signatures) {
  const scenarios = new Set(manifest.scenarios.map(({ id }) => id));
  if (scenarios.size !== manifest.scenarios.length || !scenarios.size) throw new Error('duplicate or empty scenarios');
  for (const s of manifest.scenarios) {
    const expectedSampling = s.job === 'maximum' ? [0, 1] : [5, 30];
    if (!/^[a-z][a-z.-]+$/.test(s.id) || !['ordinary', 'maximum'].includes(s.job) ||
        !['warm', 'cold', 'warm-remote'].includes(s.mode) ||
        !s.workload?.trim() || !s.checks?.trim() || (s.budgetMs !== null && (!Number.isFinite(s.budgetMs) || s.budgetMs <= 0)) ||
        s.warmups !== expectedSampling[0] || s.samples !== expectedSampling[1] ||
        (s.maxRssBytes !== null &&
          (!Number.isSafeInteger(s.maxRssBytes) || s.maxRssBytes <= 0)) ||
        (s.relativeFloorMs !== undefined && ![100, 2000].includes(s.relativeFloorMs))) {
      throw new Error(`invalid scenario metadata: ${s.id}`);
    }
  }
  const actual = new Set(signatures);
  const registered = new Set();
  for (const entry of manifest.entrypoints) {
    if (!actual.has(entry.signature)) throw new Error(`stale signature: ${entry.signature}`);
    if (registered.has(entry.signature)) throw new Error(`duplicate signature: ${entry.signature}`);
    registered.add(entry.signature);
    if (entry.scenarios) {
      if (!entry.scenarios.length || entry.scenarios.some((id) => !scenarios.has(id))) {
        throw new Error(`missing scenarios for ${entry.signature}`);
      }
    } else if (!['dto', 'spi', 'implementation', 'compiler'].includes(entry.disposition) || !entry.reason?.trim()) {
      throw new Error(`unclassified signature: ${entry.signature}`);
    }
  }
  for (const group of manifest.nonOperations ?? []) {
    if (!['binding', 'data-model', 'compiler'].includes(group.disposition) || !group.reason?.trim()) {
      throw new Error('invalid non-operation classification');
    }
    for (const signature of group.signatures) {
      if (!actual.has(signature)) throw new Error(`stale signature: ${signature}`);
      if (registered.has(signature)) throw new Error(`duplicate signature: ${signature}`);
      registered.add(signature);
    }
  }
  const missing = signatures.filter((signature) => !registered.has(signature));
  if (missing.length) throw new Error(`unregistered public signatures:\n${missing.join('\n')}`);
}

export function readEvents(path) {
  return readFileSync(path, 'utf8').split('\n').filter((line) => line.trim()).map((line) => JSON.parse(line));
}

export function readSignatures(path) {
  // Kotlin's KLib ABI renderer provides signatures after the declaration separator.
  return readFileSync(path, 'utf8').split('\n').flatMap((line) => {
    const match = /^[^/]*?\b(?:fun|constructor)\s+[^/].* \/\/ ([^\s/]*)\/(.+?)(?:\[\d+\])$/.exec(line);
    if (!match) return [];
    const [, packageName, signature] = match;
    return [packageName === 'com.finance.tracker.client' ? signature : `${packageName}/${signature}`];
  }).sort();
}

export function requireMaximumEvidence(evidence, events) {
  const fixture = evidence?.fixture;
  const files = fixture?.files;
  if (evidence?.checks?.completed !== true || fixture?.rows !== 14424 ||
      !Array.isArray(files) || files.length !== 19 ||
      new Set(files.map(({ file }) => file)).size !== 19 ||
      files.some(({ sha256 }) => !/^[a-f0-9]{64}$/.test(sha256)) ||
      files.filter(({ password_required }) => password_required === true).length !== 3 ||
      !Object.entries({ csv: 6, xlsx: 6, pdf: 5, xls: 2 }).every(([key, count]) => fixture.formats?.[key] === count)) {
    throw new Error('Missing or incomplete proof of the 19-file fixture');
  }
  const measurements = evidence.measurements;
  if (!Array.isArray(measurements) || measurements.length !== events.length ||
      !measurements.every((m, i) => m.id === events[i]?.id && m.ms === events[i].ms &&
        m.rssBytes === events[i].rssBytes && m.asserted === events[i].asserted &&
        m.checks && Object.keys(m.checks).length !== 0)) {
    throw new Error('Maximum report does not match raw samples and correctness facts');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [action, manifestPath, input, job, output] = process.argv.slice(2);
  const manifest = JSON.parse(readFileSync(manifestPath));
  if (action === 'inventory') {
    inventory(manifest, readSignatures(input));
  } else if (action === 'evaluate') {
    let events = [];
    let inputError;
    try {
      events = readEvents(input);
    } catch (error) {
      inputError = error.message;
    }
    const result = evaluate(manifest.scenarios.filter((s) => s.job === job), events, !inputError);
    if (inputError) result.errors.unshift(inputError);
    writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
    for (const message of result.errors) console.error(message);
    if (!result.ok) process.exitCode = 1;
  } else {
    throw new Error(`unknown action: ${action}`);
  }
}

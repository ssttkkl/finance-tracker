const sizeMetrics = {
    requestWireBytes: 'maxRequestWireBytes',
    requestDecodedBytes: 'maxRequestDecodedBytes',
    responseWireBytes: 'maxResponseWireBytes',
    responseDecodedBytes: 'maxResponseDecodedBytes',
};

const size = (value) => Number.isSafeInteger(value) && value >= 0;
const time = (value) => Number.isFinite(value) && value > 0;
const peak = (value) => size(value) && value > 0;

export function evaluateImportRequests(routes, flows, report, maxConcurrentServiceBytes) {
    const errors = [];
    const budgetErrors = [];
    const routeUsage = new Set();
    const expected = new Map();
    const maxByRoute = Object.create(null);

    function metric(label, route, name, limitName, value, validate) {
        if (!validate(value)) {
            errors.push(`${label}: missing or invalid ${name}`);
            return;
        }
        const maxima = maxByRoute[route] ??= {};
        maxima[name] = Math.max(maxima[name] ?? 0, value);
        const limit = routes[route][limitName];
        if (!validate(limit)) {
            errors.push(`${route}: missing or invalid ${limitName}`);
        } else if (value > limit) {
            budgetErrors.push(`${label}: ${name} ${value} exceeds ${limit}`);
        }
    }

    function commonMetrics(label, row) {
        metric(label, row.route, 'serverMs', 'maxServerMs', row.serverMs, time);
        for (const [name, limitName] of Object.entries(sizeMetrics)) {
            metric(label, row.route, name, limitName, row[name], size);
        }
    }

    for (const [flow, requirements] of Object.entries(flows)) {
        const entries = new Map();
        expected.set(flow, entries);
        for (const [route, requirement] of Object.entries(requirements)) {
            if (!Object.hasOwn(routes, route)) errors.push(`${flow}/${route}: unregistered route`);
            routeUsage.add(route);
            entries.set(route, { ...requirement, count: 0, sawConcurrent: false });
        }
    }
    for (const route of Object.keys(routes)) {
        if (!routeUsage.has(route)) errors.push(`${route}: no request coverage`);
    }

    const originals = new Map();
    for (const [index, row] of (report?.requests ?? []).entries()) {
        const label = `${row.flow}/${row.route} #${index + 1}`;
        if (row.sequence !== index + 1) errors.push(`${label}: unexpected request sequence`);
        const requirement = expected.get(row.flow)?.get(row.route);
        if (!requirement || !Object.hasOwn(routes, row.route)) {
            errors.push(`${label}: unexpected request`);
            continue;
        }
        originals.set(index + 1, row);
        requirement.count += 1;
        requirement.sawConcurrent ||= row.concurrent === true;
        if (!requirement.statuses.includes(row.status)) errors.push(`${label}: unexpected HTTP status`);
        if (typeof row.concurrent !== 'boolean') errors.push(`${label}: missing concurrency observation`);
        if (!time(row.clientMs)) errors.push(`${label}: missing or invalid clientMs`);
        commonMetrics(label, row);
    }

    for (const [flow, entries] of expected) {
        for (const [route, requirement] of entries) {
            const { count, minimum, maximum = Infinity, concurrent, sawConcurrent } = requirement;
            if (count < minimum || count > maximum || (concurrent && !sawConcurrent)) {
                errors.push(`${flow}/${route}: missing or unexpected request coverage (${count})`);
            }
        }
    }
    if ([...originals.values()].some((row) => row.concurrent)) {
        const concurrent = report.concurrentServicePeakBytes;
        if (!peak(concurrent) || report.concurrentSamplingMethod !== 'linux-cgroup-v2-memory-peak') {
            errors.push('missing concurrent service memory peak');
        } else if (!peak(maxConcurrentServiceBytes)) {
            errors.push('invalid concurrent service memory budget');
        } else if (concurrent > maxConcurrentServiceBytes) {
            budgetErrors.push(`concurrent service peak ${concurrent} exceeds ${maxConcurrentServiceBytes} B`);
        }
    }

    const replayed = new Set();
    for (const row of report?.replays ?? []) {
        const original = originals.get(row.sequence);
        const label = `isolated ${original?.flow}/${row.route} #${row.sequence}`;
        if (!original || replayed.has(row.sequence) || row.route !== original.route ||
            row.status !== original.status || row.requestWireBytes !== original.requestWireBytes || row.requestDecodedBytes !== original.requestDecodedBytes ||
            row.isolated !== true || row.prewarmed !== true || row.method !== 'linux-cgroup-v2-memory-peak') {
            errors.push(`${label}: invalid or non-equivalent isolated replay`);
            continue;
        }
        replayed.add(row.sequence);
    }
    for (const row of report?.rawReplays ?? report?.replays ?? []) {
        const flow = row.flow ?? originals.get(row.sequence)?.flow;
        const label = `isolated ${flow}/${row.route} #${row.sequence}`;
        const requirement = expected.get(flow)?.get(row.route);
        if (!requirement || !Object.hasOwn(routes, row.route)) {
            errors.push(`${label}: unexpected replay request`);
            continue;
        }
        if (!requirement.statuses.includes(row.status) || row.isolated !== true ||
            row.prewarmed !== true || row.method !== 'linux-cgroup-v2-memory-peak' || row.asserted !== true) {
            errors.push(`${label}: invalid or unverified isolated request`);
        }
        commonMetrics(label, row);
        if (!peak(row.baselineServiceBytes) || row.peakServiceBytes < row.baselineServiceBytes) {
            errors.push(`${label}: invalid service memory baseline`);
        }
        metric(label, row.route, 'peakServiceBytes', 'maxPeakServiceBytes', row.peakServiceBytes, peak);
        metric(label, row.route, 'serviceIncreaseBytes', 'maxServiceIncreaseBytes',
            row.peakServiceBytes - row.baselineServiceBytes, size);
    }
    for (const [sequence, row] of originals) {
        if (!replayed.has(sequence)) errors.push(`${row.flow}/${row.route} #${sequence}: isolated replay missing`);
    }
    const valid = errors.length === 0;
    return { ok: valid && budgetErrors.length === 0, valid, errors: [...errors, ...new Set(budgetErrors)], maxByRoute };
}

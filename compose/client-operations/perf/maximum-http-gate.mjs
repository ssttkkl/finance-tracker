import { compatibleReadSections } from './import-request-routes.mjs';
import { requestLimits } from './http-budgets.mjs';
import { evaluateImportRequests } from './request-gate.mjs';

const expectation = (minimum, maximum, statuses = [200]) => ({ minimum, maximum, statuses });
const optional = expectation(0, Infinity);
const sessionReads = { session: optional, version: optional };
const committedReads = {
    receiptPage: expectation(10, Infinity),
    receiptRows: expectation(64, 64),
    receiptRelations: expectation(15, 15),
};

const flows = {
    prepare: { ...sessionReads, snapshotPage: expectation(15, Infinity), pdf: expectation(2, 2), xls: expectation(2, 2) },
    file: { ...sessionReads, pdf: expectation(3, 3) },
    account: { ...sessionReads, createAccount: expectation(1, 1, [201]), accountReceipt: expectation(1, 1) },
    review: { ...sessionReads, snapshotPage: expectation(15, Infinity) },
    confirm: { ...sessionReads, accountReceipt: expectation(0, Infinity, [200, 404]),
        submit: expectation(1, 1, [202]), status: expectation(1, Infinity, [200, 404]), ...committedReads },
    'confirm-no-wait': { ...sessionReads, submit: expectation(2, 2, [202]), status: expectation(0, Infinity, [200, 404]) },
    cancel: { ...sessionReads, cancel: { ...expectation(1, Infinity, [200, 202, 409]), concurrent: true },
        status: expectation(0, Infinity) },
    resume: { ...sessionReads, status: expectation(1, Infinity), receiptPage: expectation(10, Infinity),
        receiptRows: expectation(15, 15), receiptRelations: expectation(1, 1) },
    status: { ...sessionReads, status: { ...expectation(1, Infinity), concurrent: true } },
    result: { session: expectation(2, 2) },
    batches: { session: expectation(1, 1) },
    'cold-snapshot': { ...sessionReads, snapshotPage: expectation(15, Infinity) },
    revalidated: { ...sessionReads, version: expectation(1, Infinity) },
    'refreshed-snapshot': { ...sessionReads, snapshotPage: expectation(15, Infinity) },
};

function workloadRoute(flow, route) {
    if (route === 'submit' && flow === 'confirm-no-wait') return 'submit-control';
    if (route === 'snapshotPage') return flow === 'prepare' || flow === 'review' ? 'snapshot-initial' : 'snapshot-populated';
    return route;
}

const coverage = Object.fromEntries(Object.entries(flows).map(([flow, requirements]) => [
    flow, Object.fromEntries(Object.entries(requirements).map(([route, expected]) => [workloadRoute(flow, route), expected])),
]));

export function completeReadPageSequence(rows, sections) {
    let ordinal = 0;
    let previous;
    for (const [i, row] of rows.entries()) {
        const page = row.page;
        if (!page || !Number.isSafeInteger(page.offset) || page.offset < 0 ||
            !Number.isSafeInteger(page.fragmentOffset) || page.fragmentOffset < 0) return false;
        if (i === 0) {
            if (page.section !== null || page.offset !== 0 || page.fragmentOffset !== 0) return false;
        } else {
            if (page.section === null) return false;
            if (page.section === sections[ordinal]) {
                if (page.offset < previous.offset ||
                    (page.offset === previous.offset && page.fragmentOffset <= previous.fragmentOffset) ||
                    (page.offset > previous.offset && page.fragmentOffset !== 0)) return false;
            } else {
                if (page.section !== sections[ordinal + 1] || page.offset !== 0 || page.fragmentOffset !== 0) return false;
                ordinal++;
            }
        }
        previous = page;
    }
    return previous !== undefined && ordinal === sections.length - 1;
}

function requestEvidence(details, phase) {
    const errors = [];
    const requests = [];
    for (const run of details?.requestReports ?? []) {
        if (!Object.hasOwn(flows, run.flow) || run.started !== run.requests.length || run.errors.length) {
            errors.push(`${phase}/${run.label}: incomplete HTTP observations (${run.errors.join(', ')})`);
        }
        for (const [route, sections] of Object.entries(compatibleReadSections)) {
            const pages = run.requests.filter(row => row.route === route);
            if (pages.length && !completeReadPageSequence(pages, sections)) {
                errors.push(`${phase}/${run.label}: incomplete or duplicate ${route} positions`);
            }
        }
        for (const [index, row] of run.requests.entries()) {
            if (row.sequence !== index + 1 || row.flow !== run.flow) {
                errors.push(`${phase}/${run.label}: mismatched HTTP request order`);
            }
            requests.push({ ...row, route: workloadRoute(run.flow, row.route), sequence: requests.length + 1 });
        }
    }
    return { errors, requests };
}

function replayKey(row) {
    // Poll results in different states can share HTTP status and query size.
    const responseShape = row.route === 'status' ? row.responseDecodedBytes : null;
    return JSON.stringify([row.flow, row.route, row.status, row.concurrent, row.requestWireBytes,
        row.requestDecodedBytes, row.page ?? null, responseShape]);
}

export function evaluateMaximumHttpGate(details, replayDetails, http) {
    const real = requestEvidence(details, 'real');
    const replay = requestEvidence(replayDetails, 'replay');
    const collectionErrors = [...real.errors, ...replay.errors];
    if (details?.payloadProfile || replayDetails?.payloadProfile) {
        collectionErrors.push('Payload shape profiling runs separately from the performance gate');
    }
    if (details?.checks?.completed !== true || details?.checks?.controls?.cancelled !== true ||
        details?.checks?.controls?.recovered !== true) collectionErrors.push('Incomplete real import and controls');
    if (replayDetails?.checks?.completed !== true || replayDetails?.checks?.controls?.cancelled !== true ||
        replayDetails?.checks?.controls?.recovered !== true) collectionErrors.push('Incomplete isolated import and controls');
    if (JSON.stringify(details?.fixture) !== JSON.stringify(replayDetails?.fixture)) {
        collectionErrors.push('isolated replay used a different synthetic fixture');
    }
    function pagedPositions(rows) {
        return rows.filter(row => row.page).map(replayKey).sort();
    }
    if (JSON.stringify(pagedPositions(real.requests)) !== JSON.stringify(pagedPositions(replay.requests))) {
        collectionErrors.push('Real and isolated runs used different page positions');
    }
    const candidates = new Map();
    for (const row of replay.requests) {
        if (row.isolated === true && row.prewarmed === true) {
            const key = replayKey(row);
            const matches = candidates.get(key) ?? [];
            matches.push(row);
            candidates.set(key, matches);
        }
    }
    const matches = [];
    for (const original of real.requests) {
        const candidatesForRequest = candidates.get(replayKey(original));
        if (!candidatesForRequest?.length) continue;
        // Variable polling frequency can repeat the same route and state. Reuse its measured window
        // and preserve the replay sequence in the report.
        const replayed = original.route === 'status' ? candidatesForRequest.reduce((peak, row) =>
            row.peakServiceBytes > peak.peakServiceBytes ? row : peak) : candidatesForRequest.shift();
        matches.push({
            ...replayed, sequence: original.sequence, replaySequence: replayed.sequence,
            asserted: replayDetails.checks.completed === true,
        });
    }

    const result = evaluateImportRequests(requestLimits(http, flows, workloadRoute), coverage, {
        requests: real.requests, replays: matches,
        rawReplays: replay.requests.map(row => ({ ...row, asserted: replayDetails?.checks.completed === true })),
        concurrentServicePeakBytes: details?.concurrentServicePeakBytes,
        concurrentSamplingMethod: details?.concurrentSamplingMethod,
    }, http.maxConcurrentServiceBytes);
    return {
        ...result, ok: result.ok && collectionErrors.length === 0, valid: result.valid && collectionErrors.length === 0,
        realCount: real.requests.length, replayCount: replay.requests.length,
        evaluatedReplaySequences: [...new Set(matches.map(({ replaySequence }) => replaySequence))],
        errors: [...collectionErrors, ...result.errors],
    };
}

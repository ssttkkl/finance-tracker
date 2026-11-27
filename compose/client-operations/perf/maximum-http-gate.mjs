import { importRequestRoutes } from './import-request-routes.mjs';
import { evaluateImportRequests } from './request-gate.mjs';

const expectation = (minimum, maximum, statuses = [200]) => ({ minimum, maximum, statuses });
const optional = expectation(0, Infinity);
const sessionReads = { session: optional, version: optional };
const committedReads = {
    receipt: expectation(1, Infinity, [200, 404]),
    receiptRows: expectation(2, Infinity),
    receiptRelations: expectation(2, Infinity),
};

const flows = {
    prepare: { ...sessionReads, snapshot: expectation(1, 1), pdf: expectation(2, 2), xls: expectation(2, 2) },
    file: { ...sessionReads, pdf: expectation(3, 3) },
    account: { ...sessionReads, createAccount: expectation(1, 1, [201]), accountReceipt: expectation(1, 1) },
    review: { ...sessionReads, snapshot: expectation(1, 1) },
    confirm: { ...sessionReads, accountReceipt: expectation(0, Infinity, [200, 404]),
        submit: expectation(1, 1, [202]), status: expectation(1, Infinity, [200, 404]), ...committedReads },
    'confirm-no-wait': { ...sessionReads, submit: expectation(2, 2, [202]), status: expectation(0, Infinity, [200, 404]) },
    cancel: { ...sessionReads, cancel: { ...expectation(1, Infinity, [200, 202, 409]), concurrent: true },
        status: expectation(0, Infinity) },
    resume: { ...sessionReads, status: expectation(1, Infinity), receipt: expectation(1, Infinity),
        receiptRows: expectation(15, 15), receiptRelations: expectation(1, 1) },
    status: { ...sessionReads, status: { ...expectation(1, Infinity), concurrent: true } },
    result: {},
    batches: {},
    'cold-snapshot': { ...sessionReads, snapshot: expectation(1, 1) },
    revalidated: { ...sessionReads, version: expectation(1, Infinity) },
    'refreshed-snapshot': { ...sessionReads, snapshot: expectation(1, 1) },
};

function workloadRoute(flow, route) {
    if (route === 'submit' && flow === 'confirm-no-wait') return 'submit-control';
    if (route === 'snapshot') return flow === 'prepare' || flow === 'review' ? 'snapshot-initial' : 'snapshot-populated';
    return route;
}

const requestLimits = Object.fromEntries(Object.entries(flows).flatMap(([flow, requirements]) =>
    Object.keys(requirements).map((id) => {
        const [method, template] = importRequestRoutes[id];
        const limit = method === 'GET' ? 0 : id === 'submit' ? 64 * 1024 ** 2 : null;
        return [workloadRoute(flow, id), {
            method, template, maxServerMs: 8000, maxRequestWireBytes: limit, maxRequestDecodedBytes: limit,
            maxResponseWireBytes: null, maxResponseDecodedBytes: null, maxPeakServiceBytes: null, maxServiceIncreaseBytes: null,
        }];
    })));

const coverage = Object.fromEntries(Object.entries(flows).map(([flow, requirements]) => [
    flow, Object.fromEntries(Object.entries(requirements).map(([route, expected]) => [workloadRoute(flow, route), expected])),
]));

function requestEvidence(details, phase) {
    const errors = [];
    const requests = [];
    for (const run of details?.requestReports ?? []) {
        if (!Object.hasOwn(flows, run.flow) || run.started !== run.requests.length || run.errors.length) {
            errors.push(`${phase}/${run.label}: incomplete HTTP observations (${run.errors.join(', ')})`);
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
        row.requestDecodedBytes, responseShape]);
}

export function evaluateMaximumHttpGate(details, replayDetails) {
    const real = requestEvidence(details, 'real');
    const replay = requestEvidence(replayDetails, 'replay');
    const collectionErrors = [...real.errors, ...replay.errors];
    if (details?.checks?.completed !== true || details?.checks?.controls?.cancelled !== true ||
        details?.checks?.controls?.recovered !== true) collectionErrors.push('Incomplete real import and controls');
    if (replayDetails?.checks?.completed !== true || replayDetails?.checks?.controls?.cancelled !== true ||
        replayDetails?.checks?.controls?.recovered !== true) collectionErrors.push('Incomplete isolated import and controls');
    if (JSON.stringify(details?.fixture) !== JSON.stringify(replayDetails?.fixture)) {
        collectionErrors.push('isolated replay used a different synthetic fixture');
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

    const result = evaluateImportRequests(requestLimits, coverage, {
        requests: real.requests, replays: matches, concurrentServicePeakBytes: details?.concurrentServicePeakBytes,
        concurrentSamplingMethod: details?.concurrentSamplingMethod,
    });
    return {
        ...result, ok: result.ok && collectionErrors.length === 0, valid: result.valid && collectionErrors.length === 0,
        realCount: real.requests.length, replayCount: replay.requests.length,
        evaluatedReplaySequences: [...new Set(matches.map(({ replaySequence }) => replaySequence))],
        errors: [...collectionErrors, ...result.errors],
    };
}

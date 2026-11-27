import { importRequestRoutes } from './import-request-routes.mjs';
import { evaluateImportRequests } from './request-gate.mjs';

const expectation = (minimum, maximum, statuses = [200]) => ({ minimum, maximum, statuses });
const optional = expectation(0, Infinity);
const sessionReads = { session: optional, version: optional };
const committedReads = {
    receipt: expectation(1, Infinity),
    receiptRows: expectation(2, Infinity),
    receiptRelations: expectation(2, Infinity),
};

const flows = {
    prepare: { ...sessionReads, snapshot: expectation(1, 1), pdf: expectation(2, 2), xls: expectation(2, 2) },
    file: { ...sessionReads, pdf: expectation(3, 3) },
    account: { ...sessionReads, createAccount: expectation(1, 1, [201]), accountReceipt: optional },
    review: { ...sessionReads, snapshot: expectation(1, 1) },
    confirm: { ...sessionReads, accountReceipt: expectation(0, Infinity, [200, 404]),
        submit: expectation(1, 1, [202]), status: expectation(1, Infinity), ...committedReads },
    'confirm-no-wait': { ...sessionReads, submit: expectation(1, 1, [202]), status: expectation(0, Infinity, [200, 404]) },
    cancel: { ...sessionReads, cancel: { ...expectation(1, Infinity, [200, 202, 409]), concurrent: true },
        status: expectation(0, Infinity) },
    resume: { ...sessionReads, status: expectation(1, Infinity), ...committedReads },
    status: { ...sessionReads, status: { ...expectation(1, Infinity), concurrent: true } },
    result: {},
    batches: {},
    'cold-snapshot': { ...sessionReads, snapshot: expectation(1, 1) },
    revalidated: { ...sessionReads, version: expectation(1, Infinity) },
    'refreshed-snapshot': { ...sessionReads, snapshot: expectation(1, 1) },
};

function workloadRoute(flow, route) {
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

export function evaluateMaximumHttpGate(details) {
    const traced = details.requestReports ?? [];
    const collectionErrors = [];
    const requests = [];
    for (const run of traced) {
        if (!Object.hasOwn(flows, run.flow) || run.started !== run.requests.length || run.errors.length) {
            collectionErrors.push(`${run.label}: incomplete HTTP observations (${run.errors.join(', ')})`);
        }
        for (const [index, row] of run.requests.entries()) {
            if (row.sequence !== index + 1 || row.flow !== run.flow) {
                collectionErrors.push(`${run.label}: mismatched HTTP request order`);
            }
            requests.push({ ...row, route: workloadRoute(run.flow, row.route), sequence: requests.length + 1 });
        }
    }
    const result = evaluateImportRequests(requestLimits, coverage, {
        requests, replays: details.requestReplays, concurrentServicePeakBytes: details.concurrentServicePeakBytes,
        concurrentSamplingMethod: details.concurrentSamplingMethod,
    });
    return {
        ...result, ok: result.ok && collectionErrors.length === 0, valid: result.valid && collectionErrors.length === 0,
        errors: [...collectionErrors, ...result.errors],
    };
}

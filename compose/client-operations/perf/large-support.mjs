import { execFile } from 'node:child_process';
import { request } from 'node:http';
import { createServer } from 'node:https';
import { performance } from 'node:perf_hooks';
import { setTimeout as pause } from 'node:timers/promises';

function forwardedHeaders(headers) {
    const copy = { ...headers };
    for (const name of ['connection', 'keep-alive', 'transfer-encoding']) delete copy[name];
    return copy;
}

export function observedTime() {
    return { utc: new Date().toISOString(), monotonicMs: performance.now() };
}

export function openProxy(cert, key, backendPort, observed) {
    // The CLI may spend several seconds parsing local files between two remote parser calls.
    // The temporary TLS front end owns that idle window separately from Ktor connections.
    const proxy = createServer({ cert, key }, (incoming, outgoing) => {
        const path = new URL(incoming.url, 'https://127.0.0.1').pathname.replace(/\/$/, '');
        const observation = { method: incoming.method, path, bytes: 0, status: null };
        const confirm = path === '/api/v1/cash-import/write-set' && incoming.method === 'POST';
        if (confirm) {
            observation.key = incoming.headers['idempotency-key'] ?? null;
            observation.events = [];
        }
        const note = (event, facts = {}) => observation.events?.push({ event, ...observedTime(), ...facts });
        let proxyClosedDownstream = false;
        note('proxy_received_post');
        observed.push(observation);
        incoming.on('data', chunk => { observation.bytes += chunk.length; });
        incoming.on('end', () => note('client_body_complete', { bytes: observation.bytes }));
        incoming.on('aborted', () => note('client_body_aborted', { bytes: observation.bytes }));
        const forwarded = request({
            hostname: '127.0.0.1', port: backendPort, path: incoming.url, method: incoming.method,
            agent: false,
            headers: { ...forwardedHeaders(incoming.headers), host: '127.0.0.1' },
        }, response => {
            observation.status = response.statusCode;
            note('backend_headers', { status: response.statusCode, requestId: response.headers['x-request-id'] ?? null });
            response.on('end', () => note('backend_body_complete'));
            response.on('aborted', () => note('backend_response_aborted'));
            response.on('error', error => note('backend_response_error', { code: error.code ?? error.name }));
            outgoing.writeHead(response.statusCode, forwardedHeaders(response.headers));
            response.pipe(outgoing);
        });
        forwarded.on('finish', () => note('proxy_forwarded_body', { bytes: observation.bytes }));
        forwarded.on('error', error => {
            observation.transportError = error.code ?? error.name;
            note('backend_socket_error', { code: observation.transportError });
            proxyClosedDownstream = true;
            outgoing.destroy();
        });
        outgoing.on('finish', () => note('proxy_reply_complete'));
        outgoing.on('close', () => {
            if (!outgoing.writableFinished) {
                note(proxyClosedDownstream ? 'proxy_closed_downstream' : 'client_disconnected');
                if (!forwarded.destroyed) {
                    note('proxy_cancelled_upstream');
                    forwarded.destroy();
                }
            }
        });
        incoming.pipe(forwarded);
    });
    proxy.keepAliveTimeout = 120_000;
    return proxy;
}

const confirmDatabaseQuery = `
begin read only;
set local statement_timeout = '3s';
select json_build_object(
    'databaseClock', clock_timestamp(),
    'activity', (
        select coalesce(json_agg(json_build_object(
            'pid', pid, 'applicationName', application_name, 'backendStart', backend_start,
            'xactStart', xact_start, 'queryStart', query_start, 'state', state, 'backendXid', backend_xid::text,
            'waitEventType', wait_event_type, 'waitEvent', wait_event
        ) order by pid), '[]'::json)
        from pg_stat_activity
        where datname = current_database() and backend_type = 'client backend'
          and application_name <> 'ft-performance-observer' and pid <> pg_backend_pid()
    ),
    'ledgerSeq', (select ledger_seq::text from workspaces where id = :'workspace'),
    'originalReceipt', (
        select json_build_object('id', id, 'userId', user_id, 'key', idempotency_key, 'createdAt', created_at)
        from cash_mutation_receipts
        where workspace_id = :'workspace' and user_id = (select id from users where email = :'email')
          and idempotency_key = :'key' and operation_kind = 'cash_write_set'
    )
);
rollback;
`;

export function sampleConfirmDatabase(psqlArgs, workspace, email, key) {
    return new Promise((resolve, reject) => {
        const started = observedTime();
        const child = execFile('psql', [...psqlArgs, '-v', `workspace=${workspace}`, '-v', `email=${email}`, '-v', `key=${key}`], {
            timeout: 4_000, encoding: 'utf8', env: { ...process.env, PGAPPNAME: 'ft-performance-observer' },
        }, (error, stdout) => {
            if (error) {
                reject(new Error(`PostgreSQL read-only diagnostic failed (${error.code ?? error.signal ?? error.name})`));
                return;
            }
            try {
                resolve({ started, finished: observedTime(), ...JSON.parse(stdout.trim()) });
            } catch (cause) {
                reject(new Error('PostgreSQL diagnostic returned invalid JSON', { cause }));
            }
        });
        child.stdin.on('error', () => { /* execFile reports query/process failure. */ });
        child.stdin.end(confirmDatabaseQuery);
    });
}

function openTransactions(sample) {
    return sample.activity?.filter(connection => connection.xactStart !== null) ?? [];
}

/** Save enough observations to distinguish a known receipt from an absent or still-open transaction. */
export async function collectConfirmObservations(sample, history, signal) {
    while (!signal.aborted) {
        try {
            history.push(await sample());
        } catch (error) {
            history.push({ ...observedTime(), error: error.message });
        }
        await pause(2_000, undefined, { signal }).catch(error => {
            if (error.name !== 'AbortError') throw error;
        });
    }
}

export async function inspectLostConfirmation(sample, history, live, { waitMs = 45_000, intervalMs = 2_000 } = {}) {
    const start = observedTime();
    const sawOpenTransaction = live.some(entry => openTransactions(entry).some(row => row.backendXid !== null));
    const deadline = start.monotonicMs + waitMs;
    let idleCount = 0;
    let last;
    do {
        try {
            last = await sample();
            history.push(last);
            if (last.originalReceipt) {
                return { state: 'original_receipt_found', started: start, ended: observedTime(), receipt: last.originalReceipt };
            }
            idleCount = openTransactions(last).length === 0 ? idleCount + 1 : 0;
        } catch (error) {
            idleCount = 0;
            last = null;
            history.push({ ...observedTime(), error: error.message });
        }
        if (sawOpenTransaction && idleCount >= 2) break;
        const remaining = deadline - performance.now();
        if (remaining <= 0) break;
        await pause(Math.min(intervalMs, remaining));
    } while (performance.now() < deadline);
    let state = 'receipt_absent_at_last_read';
    if (!last) state = 'receipt_lookup_failed';
    else if (openTransactions(last).length) state = 'receipt_absent_with_open_transaction';
    else if (sawOpenTransaction && idleCount >= 2) state = 'receipt_absent_after_observed_transactions_ended';
    return { state, started: start, ended: observedTime(), ledgerSeq: last?.ledgerSeq ?? null };
}

export function verifyPreparedFiles(prepared, expected, phase, report) {
    const files = prepared.files.map(({ name, status, code }) => ({ name, status, code }));
    const interruption = prepared.interruption ?? null;
    const remainingFiles = prepared.remaining_files ?? [];
    const actual = files.map(({ name }) => name);
    const missing = expected.filter(name => !actual.includes(name));
    const unexpected = actual.filter(name => !expected.includes(name));
    const duplicates = actual.filter((name, index) => actual.indexOf(name) !== index);
    report.push({ phase, interruption, remainingFiles, files });
    const failed = files.filter(item => item.status !== 'parsed' || item.code !== null);
    if (interruption || remainingFiles.length || missing.length || unexpected.length || duplicates.length || failed.length) {
        throw new Error(`${phase}: incomplete file preparation ` +
            JSON.stringify({ interruption, remainingFiles, missing, unexpected, duplicates, files }));
    }
}

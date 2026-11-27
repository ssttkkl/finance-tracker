import { request } from 'node:http';
import { createServer } from 'node:https';

function forwardedHeaders(headers) {
    const copy = { ...headers };
    for (const name of ['connection', 'keep-alive', 'transfer-encoding']) delete copy[name];
    return copy;
}

export function openProxy(cert, key, backendPort, observed) {
    // The CLI may spend several seconds parsing local files between two remote parser calls.
    // The temporary TLS front end owns that idle window separately from Ktor connections.
    const proxy = createServer({ cert, key }, (incoming, outgoing) => {
        const path = new URL(incoming.url, 'https://127.0.0.1').pathname.replace(/\/$/, '');
        const observation = { method: incoming.method, path, bytes: 0, status: null };
        if (path === '/api/v1/cash-import/write-set')
            observation.key = incoming.headers['idempotency-key'] ?? null;
        observed.push(observation);
        incoming.on('data', chunk => { observation.bytes += chunk.length; });
        const forwarded = request({
            hostname: '127.0.0.1', port: backendPort, path: incoming.url, method: incoming.method,
            agent: false,
            headers: { ...forwardedHeaders(incoming.headers), host: '127.0.0.1' },
        }, response => {
            observation.status = response.statusCode;
            outgoing.writeHead(response.statusCode, forwardedHeaders(response.headers));
            response.pipe(outgoing);
        });
        forwarded.on('error', error => {
            observation.transportError = error.code ?? error.name;
            outgoing.destroy();
        });
        outgoing.on('close', () => {
            if (!outgoing.writableFinished) forwarded.destroy();
        });
        incoming.pipe(forwarded);
    });
    proxy.keepAliveTimeout = 120_000;
    return proxy;
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

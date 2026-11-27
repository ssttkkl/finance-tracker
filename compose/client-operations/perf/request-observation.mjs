import { once } from 'node:events';
import { createGunzip } from 'node:zlib';
import { requestSequenceHeader } from './client-request-clock.mjs';
import { startPeakRss } from './peak-rss.mjs';

function countBody(message) {
    const encoding = message.headers['content-encoding']?.trim().toLowerCase() || 'identity';
    let wireBytes = 0;
    let decodedBytes = 0;
    const decoder = encoding === 'gzip' ? createGunzip() : undefined;
    const decoded = decoder ? once(decoder, 'end') : Promise.resolve();
    decoder?.on('data', (chunk) => { decodedBytes += chunk.length; });
    decoder?.on('error', () => message.resume());
    const completed = once(message, 'end').then(() => {
        if (!message.complete || !['identity', 'gzip'].includes(encoding)) {
            throw new Error('incomplete or unsupported HTTP body encoding');
        }
        decoder?.end();
        return decoded;
    }).then(() => ({
        wireBytes, decodedBytes: decoder ? decodedBytes : wireBytes,
    }));
    // The transport still needs to report an aborted request even when counting fails.
    const aborted = new Promise((_, reject) => message.once('aborted', () => reject(new Error('HTTP body aborted'))));
    const result = Promise.race([completed, aborted]).finally(() => decoder?.destroy());
    result.catch(() => {});
    decoded.catch(() => {});
    message.on('data', (chunk) => {
        wireBytes += chunk.length;
        if (decoder && !decoder.destroyed && !decoder.write(chunk)) {
            message.pause();
            decoder.once('drain', () => message.resume());
        }
    });
    return result;
}

export function observeProxyRequest(incoming, { flow, route, now = () => performance.now() }) {
    const sequence = Number(incoming.headers[requestSequenceHeader]);
    if (!Number.isSafeInteger(sequence) || sequence <= 0) throw new Error('missing client request sequence');
    const requestBody = countBody(incoming);
    let start;
    return {
        startServerWindow() { start = now(); },
        response(response) {
            const responseBody = countBody(response);
            const end = once(response, 'end').then(() => now());
            return Promise.all([requestBody, responseBody, end]).then(([sent, received, endMs]) => ({
                sequence, flow, route, status: response.statusCode, serverMs: endMs - start,
                requestWireBytes: sent.wireBytes, requestDecodedBytes: sent.decodedBytes,
                responseWireBytes: received.wireBytes, responseDecodedBytes: received.decodedBytes,
            }));
        },
    };
}

export function joinClientRequestClocks(observed, clocks) {
    const bySequence = new Map(clocks.map((row) => [row.sequence, row]));
    if (bySequence.size !== clocks.length || clocks.length !== observed.length) {
        throw new Error('client and proxy request counts differ');
    }
    return observed.map((row) => {
        const clock = bySequence.get(row.sequence);
        if (!clock || row.status !== clock.status) throw new Error('client and proxy request disagree');
        bySequence.delete(row.sequence);
        return { ...row, clientMs: clock.clientMs };
    });
}

export function createIsolatedRssRecorder(pid) {
    let active = false;
    return async function measureRequest(sendAndAssert) {
        if (active) throw new Error('isolated HTTP measurement must be serial');
        active = true;
        try {
            const recording = startPeakRss(pid);
            const sample = await sendAndAssert();
            return {
                ...sample, method: recording.method, baselineRssBytes: recording.baselineRssBytes,
                peakRssBytes: recording.readPeakBytes(),
            };
        } finally {
            active = false;
        }
    };
}

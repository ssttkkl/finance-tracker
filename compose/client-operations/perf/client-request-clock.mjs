import { appendFileSync } from 'node:fs';

export const requestSequenceHeader = 'x-ft-performance-request-sequence';

export function instrumentClientRequests(fetchRequest, { origin, record, now = () => performance.now() }) {
    let sequence = 0;
    return async function (resource, options) {
        const url = new URL(resource instanceof Request ? resource.url : resource);
        if (url.origin !== origin) return fetchRequest(resource, options);
        const id = ++sequence;
        const headers = new Headers(options?.headers ?? (resource instanceof Request ? resource.headers : undefined));
        headers.set(requestSequenceHeader, String(id));
        const start = now();
        const response = await fetchRequest(resource, { ...options, headers });
        const readText = response.text.bind(response);
        response.text = async () => {
            const value = await readText();
            record({ sequence: id, status: response.status, clientMs: now() - start });
            return value;
        };
        return response;
    };
}

if (process.env.FT_PERF_CLIENT_REQUEST_CLOCK) {
    const path = process.env.FT_PERF_CLIENT_REQUEST_CLOCK;
    globalThis.fetch = instrumentClientRequests(globalThis.fetch, {
        origin: process.env.FT_CLI_SERVER,
        record: (event) => appendFileSync(path, JSON.stringify(event) + '\n'),
    });
}

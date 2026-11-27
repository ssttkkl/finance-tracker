export function forwardedLoopbackHeaders(headers) {
    const copy = { ...headers };
    for (const name of ['connection', 'keep-alive', 'transfer-encoding']) delete copy[name];
    return copy;
}

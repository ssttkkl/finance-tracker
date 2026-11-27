import { importRequestRoutes } from './import-request-routes.mjs';

export function requestLimits(http, flows, workloadRoute) {
    return Object.fromEntries(Object.entries(flows).flatMap(([flow, requirements]) =>
        Object.keys(requirements).map((id) => {
            const [method, template] = importRequestRoutes[id];
            const { maxServerMs, maxServiceIncreaseBytes, maxRequestBytes, maxResponseBytes } = http.routes[id];
            return [workloadRoute(flow, id), {
                method, template, maxServerMs, maxServiceIncreaseBytes,
                maxPeakServiceBytes: http.maxPeakServiceBytes,
                maxRequestWireBytes: maxRequestBytes, maxRequestDecodedBytes: maxRequestBytes,
                maxResponseWireBytes: maxResponseBytes, maxResponseDecodedBytes: maxResponseBytes,
            }];
        })));
}

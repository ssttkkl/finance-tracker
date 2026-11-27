import { openStore as openProductionStore } from './production-host.mjs';
export * from './production-host.mjs';

export function openStore(origin, createKey) {
    // The production CLI consumes its injected key once. This executable opens the same
    // production store repeatedly in one process, so preserve the synthetic key between calls.
    const key = process.env.FT_CLI_STORAGE_KEY;
    if (!key) throw new Error('Synthetic performance store key missing');
    try {
        return openProductionStore(origin, createKey);
    } finally {
        process.env.FT_CLI_STORAGE_KEY = key;
    }
}

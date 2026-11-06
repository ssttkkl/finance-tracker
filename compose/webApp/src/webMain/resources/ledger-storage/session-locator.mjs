// These references reveal no display identity or token. Encrypted storage still validates every restore.
const prefix = 'ledger-session-current-v1:';
const lockName = 'finance-tracker-ledger-session-locator-v1';

function keyChecked(key) {
  if (typeof key !== 'string' || !key.startsWith(prefix) || !/^[0-9a-f]{64}$/.test(key.slice(prefix.length))) {
    throw new Error('ledger_session_storage_key_invalid');
  }
  return key;
}

export function readLocator(key) {
  return globalThis.localStorage.getItem(keyChecked(key));
}

export async function compareAndSetLocator(key, expected, replacement) {
  keyChecked(key);
  if ((expected !== null && typeof expected !== 'string') || typeof replacement !== 'string') {
    throw new Error('ledger_session_storage_value_invalid');
  }
  if (!globalThis.navigator?.locks?.request) throw new Error('ledger_session_storage_unavailable');
  return navigator.locks.request(lockName, () => {
    const store = globalThis.localStorage;
    if (store.getItem(key) !== expected) return false;
    store.setItem(key, replacement); // Security/quota failures retain their original diagnostic.
    return true;
  });
}

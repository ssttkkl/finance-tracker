// Browser key material only; the cache owner must separately fence sessions and erase database files.
const DATABASE = "ft-ledger-cache-keys-v1";
const STORE = "keys";

function requireCrypto() {
  if (!globalThis.isSecureContext || !globalThis.crypto?.subtle) throw new Error("ledger_cache_unavailable");
}

export async function webLedgerKeyId(scope) {
  requireCrypto();
  if (!Array.isArray(scope) || scope.length !== 3 || scope.some((value) => typeof value !== "string" || !value.trim())) {
    throw new TypeError("ledger_cache_scope_invalid");
  }
  const encoded = new TextEncoder().encode(JSON.stringify(["ledger-key-v1", ...scope]));
  const digest = await crypto.subtle.digest("SHA-256", encoded);
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, "0")).join("");
}

function openDatabase() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    let settled = false;
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onerror = () => { settled = true; reject(request.error); };
    request.onblocked = () => { settled = true; reject(new Error("ledger_cache_key_store_blocked")); };
    request.onsuccess = () => {
      const db = request.result;
      db.onversionchange = () => db.close();
      if (settled) db.close();
      else { settled = true; resolve(db); }
    };
  });
}

function stored(db, id, action, record) {
  return new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, action === "get" ? "readonly" : "readwrite");
    const store = transaction.objectStore(STORE);
    const request = action === "get" ? store.get(id) : action === "add" ? store.add(record, id) : store.delete(id);
    let result;
    request.onsuccess = () => { result = request.result; };
    transaction.oncomplete = () => resolve(result);
    transaction.onerror = transaction.onabort = () => reject(transaction.error || request.error ||
      new DOMException("Ledger key transaction aborted", "AbortError"));
  });
}

async function scoped(scope, action) {
  const id = await webLedgerKeyId(scope);
  if (!globalThis.indexedDB || !globalThis.navigator?.locks?.request) throw new Error("ledger_cache_unavailable");
  return navigator.locks.request(`ft-ledger-key:${id}`, { mode: "exclusive" }, async () => {
    const db = await openDatabase();
    try { return await action(db, id); } finally { db.close(); }
  });
}

async function unwrap(record, id) {
  const key = record?.wrappingKey;
  if (record?.version !== 1 || record.keyId !== id || !(key instanceof CryptoKey) || key.extractable ||
      key.type !== "secret" || key.algorithm.name !== "AES-GCM" || key.algorithm.length !== 256 ||
      key.usages.length !== 2 || !key.usages.includes("encrypt") || !key.usages.includes("decrypt") ||
      !(record.iv instanceof Uint8Array) || record.iv.length !== 12 ||
      !(record.ciphertext instanceof ArrayBuffer) || record.ciphertext.byteLength !== 48) {
    throw new Error("ledger_cache_key_invalid");
  }
  return new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: record.iv,
    additionalData: new TextEncoder().encode(id), tagLength: 128 }, key, record.ciphertext));
}

// Missing material is observable, never permission to replace the key of an existing encrypted file.
export async function readWebLedgerKey(scope) {
  return scoped(scope, async (db, id) => {
    const record = await stored(db, id, "get");
    return record === undefined ? null : unwrap(record, id);
  });
}

// Only a cache owner which has established that this is a new database may request creation.
export async function createWebLedgerKey(scope) {
  return scoped(scope, async (db, id) => {
    const previous = await stored(db, id, "get");
    if (previous !== undefined) return unwrap(previous, id);
    const raw = crypto.getRandomValues(new Uint8Array(32));
    try {
      const wrappingKey = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
      const iv = crypto.getRandomValues(new Uint8Array(12));
      const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv,
        additionalData: new TextEncoder().encode(id), tagLength: 128 }, wrappingKey, raw);
      await stored(db, id, "add", { version: 1, keyId: id, wrappingKey, iv, ciphertext });
      return raw.slice();
    } finally { raw.fill(0); }
  });
}

// This deletes key material only, not database pages, side files or other tabs' open handles.
export async function eraseWebLedgerKey(scope) {
  return scoped(scope, (db, id) => stored(db, id, "delete"));
}

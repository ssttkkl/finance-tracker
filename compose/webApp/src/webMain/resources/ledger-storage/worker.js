// One scoped connection per worker. Session ownership and SQLDelight construction belong to its caller.
let database;
let engine;
let context;
let releaseHold;
let lease;
let state = "new";
let queue = Promise.resolve();
const vault = import("../ledger-key-vault.mjs");

async function acquire(scope) {
  const keys = await vault;
  const id = await keys.webLedgerKeyId(scope);
  if (!navigator.locks?.request || !navigator.storage?.getDirectory) throw new Error("ledger_cache_unavailable");
  let acquired;
  let rejected;
  const ready = new Promise((resolve, reject) => { acquired = resolve; rejected = reject; });
  const hold = new Promise((resolve) => { releaseHold = resolve; });
  lease = navigator.locks.request(`ft-ledger-cache:${id}`, { ifAvailable: true }, async (lock) => {
    if (!lock) { rejected(new Error("ledger_cache_in_use")); return; }
    acquired(); await hold;
  });
  lease.catch(rejected);
  try { await ready; }
  catch (failure) {
    releaseHold?.(); releaseHold = undefined; lease = undefined;
    throw failure;
  }
  try { context = { scope: scope.slice(), id, root: await navigator.storage.getDirectory() }; }
  catch (failure) { await release(); throw failure; }
}

async function release() {
  releaseHold?.(); await lease;
  releaseHold = undefined; lease = undefined; context = undefined;
}

async function directory(create) {
  try {
    const parent = await context.root.getDirectoryHandle("ft-ledger-cache-v1", { create });
    return await parent.getDirectoryHandle(context.id, { create });
  } catch (failure) { if (!create && failure.name === "NotFoundError") return null; throw failure; }
}

function closeDatabase() {
  if (database) { database.close(); database = undefined; }
}

async function reserve(scope) {
  if (state !== "new") throw new Error("ledger_cache_closed");
  await acquire(scope);
  state = "leased";
}

async function open(scope, existingOnly = false) {
  if (state !== "new" && state !== "leased") throw new Error("ledger_cache_closed");
  if (typeof existingOnly !== "boolean") throw new TypeError("ledger_cache_open_mode_invalid");
  if (state === "leased" && await (await vault).webLedgerKeyId(scope) !== context.id)
    throw new Error("ledger_cache_scope_invalid");
  const reserved = state === "leased";
  state = "opening";
  try {
    if (!self.crossOriginIsolated || !self.SharedArrayBuffer) throw new Error("ledger_cache_unavailable");
    if (!context) await acquire(scope);
    importScripts("sqlite3.js");
    engine = await sqlite3InitModule({ locateFile: (name) => new URL(name, self.location.href).href });
    const c = engine.capi;
    if (c.sqlite3_libversion() !== "3.54.0" || c.sqlite3mc_version() !== "SQLite3 Multiple Ciphers 2.6.1" || !c.sqlite3_vfs_find("opfs"))
      throw new Error("ledger_cache_engine_invalid");
    if (c.sqlite3mc_vfs_create("opfs", 0) !== c.SQLITE_OK) throw new Error("ledger_cache_engine_invalid");
    const folder = await directory(!existingOnly);
    if (!folder) throw new Error("ledger_cache_missing");
    let existing = true;
    try {
      const file = await folder.getFileHandle("ledger.db");
      if (existingOnly && (await file.getFile()).size === 0) throw new Error("ledger_cache_invalid");
    }
    catch (failure) { if (failure.name === "NotFoundError") existing = false; else throw failure; }
    if (existingOnly && !existing) throw new Error("ledger_cache_missing");
    const keys = await vault;
    const raw = existing ? await keys.readWebLedgerKey(scope) : await keys.createWebLedgerKey(scope);
    if (raw === null) throw new Error("ledger_cache_key_missing");
    try {
      database = new engine.oo1.DB({ filename: `/ft-ledger-cache-v1/${context.id}/ledger.db`, vfs: "multipleciphers-opfs", flags: existing ? "w" : "c" });
      if (database.selectValue("PRAGMA mc_chacha20_hwaccel=off") !== "off") throw new Error("ledger_cache_engine_invalid");
      c.sqlite3mc_config(database.pointer, "cipher", c.sqlite3mc_cipher_index("chacha20"));
      const pointer = engine.wasm.allocFromTypedArray(raw);
      try {
        const result = engine.wasm.exports.sqlite3_key(database.pointer, pointer, raw.length);
        if (result !== c.SQLITE_OK) throw new Error("ledger_cache_key_rejected");
      } finally { engine.wasm.heap8u().fill(0, pointer, pointer + raw.length); engine.wasm.dealloc(pointer); }
    } finally { raw.fill(0); }
    database.selectValue("SELECT COUNT(*) FROM sqlite_master");
    database.exec("PRAGMA temp_store=MEMORY");
    if (database.selectValue("PRAGMA temp_store") !== 2) throw new Error("ledger_cache_engine_invalid");
    const journal = database.selectValue("PRAGMA journal_mode=DELETE");
    const cipher = c.sqlite3mc_cipher_name(c.sqlite3mc_config(database.pointer, "cipher", -1));
    const hardware = database.selectValue("PRAGMA mc_chacha20_hwaccel");
    if (journal !== "delete" || cipher !== "chacha20" || hardware !== "off") throw new Error("ledger_cache_engine_invalid");
    state = "ready";
    return { sqlite: c.sqlite3_libversion(), multipleCiphers: c.sqlite3mc_version(), cipher, hardware, journal };
  } catch (failure) {
    state = reserved ? "closed" : "failed";
    try { closeDatabase(); if (!reserved) await release(); }
    catch (cleanup) { throw new AggregateError([failure, cleanup], "ledger_cache_cleanup_failed"); }
    throw failure;
  }
}

function closeDatabaseRetainingLease() {
  if (!context || (state !== "ready" && state !== "closed")) throw new Error("ledger_cache_closed");
  state = "closed"; // Fence SQL before attempting physical close; retain ownership on failure.
  closeDatabase();
}

async function erase(scope) {
  if (state === "new") { state = "closed"; await acquire(scope); }
  else {
    if (!context || !["ready", "leased", "closed"].includes(state)) throw new Error("ledger_cache_closed");
    if (scope && await (await vault).webLedgerKeyId(scope) !== context.id) throw new Error("ledger_cache_scope_invalid");
    state = "closed";
  }
  closeDatabase();
  try {
    const parent = await context.root.getDirectoryHandle("ft-ledger-cache-v1");
    await parent.removeEntry(context.id, { recursive: true });
  } catch (failure) { if (failure.name !== "NotFoundError") throw failure; }
  await (await vault).eraseWebLedgerKey(context.scope);
  // Metadata is removed by the owner while this same lease is held. Only final close releases it.
}

function details(failure, seen = new Set()) {
  const result = { name: failure?.name ?? "Error", message: failure?.message ?? String(failure), stack: failure?.stack,
    resultCode: failure?.resultCode };
  if (failure && typeof failure === "object" && !seen.has(failure)) {
    seen.add(failure);
    if (failure.cause) result.cause = details(failure.cause, seen);
    if (failure instanceof AggregateError) result.errors = Array.from(failure.errors, (value) => details(value, seen));
  }
  return result;
}

async function handle(data) {
  let values = [];
  let info;
  switch (data.action) {
    case "acquire": await reserve(data.scope); break;
    case "open": info = await open(data.scope, data.existingOnly); break;
    case "close_database": closeDatabaseRetainingLease(); break;
    case "close": state = "closed"; closeDatabase(); await release(); break;
    case "erase": await erase(data.scope); break;
    case "exec":
    case "begin_transaction":
    case "end_transaction":
    case "rollback_transaction": {
      if (state !== "ready") throw new Error("ledger_cache_closed");
      if (data.action === "exec") {
        if (typeof data.sql !== "string" || !Array.isArray(data.params ?? [])) throw new TypeError("ledger_cache_query_invalid");
        values = database.exec({ sql: data.sql, bind: data.params ?? [], rowMode: "array", returnValue: "resultRows" });
      } else database.exec({ begin_transaction: "BEGIN TRANSACTION", end_transaction: "END TRANSACTION", rollback_transaction: "ROLLBACK TRANSACTION" }[data.action]);
      break;
    }
    default: throw new TypeError("ledger_cache_action_invalid");
  }
  return { id: data.id, results: { values }, ...(info ? { info } : {}) };
}

self.onmessage = ({ data }) => {
  queue = queue.then(() => handle(data)).then((result) => postMessage(result))
    .catch((failure) => postMessage({ id: data?.id, error: details(failure) }));
};

import * as connections from '/ledger-storage/connection.mjs';
import * as vault from '/ledger-key-vault.mjs';

async function materials(scope) {
  const raw = await vault.readWebLedgerKey(scope);
  const hasKey = raw !== null;
  raw?.fill(0);
  let hasFile = false;
  try {
    const root = await navigator.storage.getDirectory();
    const parent = await root.getDirectoryHandle('ft-ledger-cache-v1');
    const folder = await parent.getDirectoryHandle(await vault.webLedgerKeyId(scope));
    await folder.getFileHandle('ledger.db'); hasFile = true;
  } catch (error) { if (error.name !== 'NotFoundError') throw error; }
  return [hasKey, hasFile];
}

async function mustBeLocked(scope) {
  const id = await vault.webLedgerKeyId(scope);
  const available = await navigator.locks.request(`ft-ledger-cache:${id}`, {ifAvailable: true}, lock => !!lock);
  if (available) throw new Error('probe_scope_was_released');
}

function rawRequest(worker, data) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { worker.removeEventListener('message', listener); reject(new Error('probe_control_timeout')); }, 2000);
    const listener = event => {
      if (event.data?.id !== data.id) return;
      clearTimeout(timeout); worker.removeEventListener('message', listener);
      resolve(event.data);
    };
    worker.addEventListener('message', listener);
    worker.postMessage(data);
  });
}

export async function leaseLifecycle(scope) {
  let connection, fresh;
  try {
    connection = await connections.acquireWebLedgerConnection(scope);
    if ((await materials(scope)).some(Boolean)) throw new Error('probe_reservation_created_materials');
    await mustBeLocked(scope);
    await connection.open(false);
    if (!(await materials(scope)).every(Boolean)) throw new Error('probe_open_missing_materials');
    await connection.closeDatabase(); await connection.closeDatabase();
    await mustBeLocked(scope);
    const sql = await rawRequest(connection.worker, {id: 987001, action:'exec', sql:'SELECT 1'});
    if (!sql.error) throw new Error('probe_closed_database_accepted_sql');
    await connection.erase(); await connection.erase();
    if ((await materials(scope)).some(Boolean)) throw new Error('probe_erasure_incomplete');
    await mustBeLocked(scope);
    await connection.close();
    fresh = await connections.acquireWebLedgerConnection(scope);
    await mustBeLocked(scope);
    let rejected = false;
    try { await connection.erase(); } catch (_) { rejected = true; }
    if (!rejected) throw new Error('probe_old_connection_still_active');
    return 'lease-retained-through-erasure';
  } finally {
    await fresh?.close(); await connection?.close();
    await connections.eraseWebLedgerStorage(scope);
  }
}

export async function scopeBinding(scope) {
  let connection;
  const foreign = [scope[0], scope[1], scope[2] + '-foreign'];
  try {
    connection = await connections.acquireWebLedgerConnection(scope);
    const response = await rawRequest(connection.worker, {id:987002, action:'open', scope:foreign, existingOnly:false});
    if (!response.error) throw new Error('probe_foreign_scope_was_opened');
    await mustBeLocked(scope);
    if ((await materials(scope)).some(Boolean) || (await materials(foreign)).some(Boolean)) throw new Error('probe_scope_rejection_created_materials');
    await connection.open(false);
    await connection.closeDatabase(); await connection.erase(); await mustBeLocked(scope);
    return 'foreign-scope-rejected';
  } finally {
    await connection?.close();
    await connections.eraseWebLedgerStorage(scope);
    await connections.eraseWebLedgerStorage(foreign);
  }
}

export async function assertState(scope, key, file, locked) {
  const actual = await materials(scope);
  if (actual[0] !== key || actual[1] !== file) throw new Error('probe_unexpected_materials:' + JSON.stringify(actual));
  const id = await vault.webLedgerKeyId(scope);
  const available = await navigator.locks.request(`ft-ledger-cache:${id}`, {ifAvailable: true}, lock => !!lock);
  if (available === locked) throw new Error('probe_unexpected_lease_state');
  return null;
}

let metadataHold;
export async function holdMetadataLock() {
  if (metadataHold) throw new Error('probe_metadata_already_held');
  let entered, release;
  const ready = new Promise(resolve => { entered = resolve; });
  const gate = new Promise(resolve => { release = resolve; });
  const done = navigator.locks.request('finance-tracker-ledger-session-locator-v1', async () => { entered(); await gate; });
  metadataHold = {release, done};
  await ready;
  return null;
}
export async function releaseMetadataLock() {
  const held = metadataHold;
  if (held) { held.release(); await held.done; metadataHold = undefined; }
  return null;
}

let finalCloseProbe;
export function installFinalCloseProbe(armOnCreate = false) {
  const Original = globalThis.Worker;
  finalCloseProbe = {Original};
  globalThis.Worker = function(url, options) {
    const target = new URL(url, location.href);
    if (target.pathname === '/ledger-storage/worker.js') {
      target.pathname = '/ledger-storage/ledger-worker-fault.js';
      finalCloseProbe.worker = new Original(target, options);
      if (armOnCreate) finalCloseProbe.worker.postMessage({qaFailCloseAckOnce: true});
      globalThis.Worker = Original;
      return finalCloseProbe.worker;
    }
    return new Original(url, options);
  };
  globalThis.Worker.prototype = Original.prototype;
  return null;
}
export function armFinalCloseFailure() {
  finalCloseProbe.worker.postMessage({qaFailCloseAckOnce: true});
  return null;
}
export function restoreFinalCloseProbe() {
  if (finalCloseProbe) { globalThis.Worker = finalCloseProbe.Original; finalCloseProbe = undefined; }
  return null;
}

async function scopeFolder(scope) {
  const root = await navigator.storage.getDirectory();
  const parent = await root.getDirectoryHandle('ft-ledger-cache-v1');
  return parent.getDirectoryHandle(await vault.webLedgerKeyId(scope));
}

export async function materialDigest(scope) {
  const raw = await vault.readWebLedgerKey(scope);
  if (!raw) throw new Error('probe_digest_key_missing');
  let key;
  try { key = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', raw))).join(','); }
  finally { raw.fill(0); }
  const file = await (await scopeFolder(scope)).getFileHandle('ledger.db');
  const bytes = await (await file.getFile()).arrayBuffer();
  return key + ':' + Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))).join(',');
}

export async function alterMaterials(scope, mode) {
  const folder = await scopeFolder(scope);
  if (mode === 'missing-key') await vault.eraseWebLedgerKey(scope);
  else if (mode === 'corrupt') {
    const stream = await (await folder.getFileHandle('ledger.db')).createWritable();
    try { await stream.write('synthetic corrupt database'); } finally { await stream.close(); }
  } else if (mode === 'orphan') {
    await folder.removeEntry('ledger.db');
    const stream = await (await folder.getFileHandle('ledger.db-journal', {create: true})).createWritable();
    try { await stream.write('synthetic orphan journal'); } finally { await stream.close(); }
  } else throw new Error('probe_invalid_alter_mode');
  return null;
}

export async function assertDirectoryMissing(scope) {
  let present = false;
  try { await scopeFolder(scope); present = true; }
  catch (error) { if (error.name !== 'NotFoundError') throw error; }
  if (present) throw new Error('probe_scope_directory_still_present');
  return null;
}

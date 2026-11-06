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

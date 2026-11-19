let idleWorker;

const startupTimeoutMs = 30_000;

function createWorker({ name = "", onIdle, onClose } = {}) {
  const url = new URL("./assets/__ASSET_VERSION__/entry.mjs", import.meta.url);
  url.hash = name;
  const worker = new Worker(url, { type: "module" });
  let pending;
  let ready = false;
  let closed = false;
  let sequence = 0;
  let completedRequestId = 0;
  const startupTimeout = setTimeout(() => fail("startup_timeout"), startupTimeoutMs);
  const slot = {
    start(operation, payload, resultType = "object") {
      if (closed || pending) throw new Error("cash_import_worker_failed");
      let request;
      const result = new Promise((resolve, reject) => {
        request = { message: { requestId: ++sequence, operation, payload }, resultType, resolve, reject };
        pending = request;
        if (ready) send();
      });
      return {
        result,
        cancel() {
          if (pending === request) fail("cancelled");
        },
      };
    },
    close: fail,
  };

  function send() {
    try {
      worker.postMessage(pending.message);
    } catch {
      fail("request_transfer");
    }
  }

  function fail(phase = "closed") {
    if (closed) return;
    closed = true;
    clearTimeout(startupTimeout);
    onClose?.(slot);
    worker.terminate();
    const request = pending;
    pending = undefined;
    request?.reject(new Error("cash_import_worker_failed:" + phase));
  }

  worker.onmessage = ({ data }) => {
    if (closed) return;
    if (data?.ready === true) {
      if (ready) return;
      ready = true;
      clearTimeout(startupTimeout);
      if (pending) send();
      return;
    }
    const requestId = data?.requestId;
    if (Number.isSafeInteger(requestId) && requestId > 0 && requestId <= completedRequestId) return;
    if (!pending || requestId !== pending.message.requestId ||
      data.result == null || typeof data.result !== pending.resultType) {
      fail("response_contract");
      return;
    }
    const request = pending;
    pending = undefined;
    completedRequestId = requestId;
    onIdle?.(slot);
    request.resolve(data.result);
  };
  worker.onerror = (event) => {
    event.preventDefault();
    fail(ready ? "execution" : "startup");
  };
  worker.onmessageerror = () => fail("response_transfer");
  return slot;
}

function start(operation, payload, resultType) {
  const slot = idleWorker;
  idleWorker = undefined;
  return (slot ?? createImportWorker()).start(operation, payload, resultType);
}

function createImportWorker() {
  return createWorker({
    onIdle(slot) {
      if (idleWorker) slot.close();
      else idleWorker = slot;
    },
    onClose(slot) {
      if (idleWorker === slot) idleWorker = undefined;
    },
  });
}

export function startPreview(payload) {
  return start("calculatePreview", payload, "string");
}

export function startFile(metadata, file, limit) {
  return start("captureFile", { metadata, file, limit });
}

export function openPreview(payload) {
  return start("openPreview", payload);
}

export function openCachedPreview(preview, files, snapshot) {
  return start("openCachedPreview", { preview, files, snapshot });
}

export function freezeConfirmation(confirmation) {
  return start("freezeConfirmation", confirmation);
}

export function assembleArchive(batchId, key, receipt, input, items) {
  return start("assembleArchive", { batchId, key, receipt, input, items });
}

export function assembleArchivePages(batchId, key, receipt, input, pages) {
  return start("assembleArchive", { batchId, key, receipt, input, pages });
}

export function inspectArchive(resultBody) {
  return start("inspectArchive", resultBody);
}

export function inspectSubmission(submissionBody) {
  return start("inspectSubmission", submissionBody);
}

export function inspectBatch(body) {
  return start("inspectBatch", body);
}

export function encodeBatch(files, review, submission, attempt) {
  return start("encodeBatch", { files, review, submission, attempt });
}

export function encodeSubmission(key, request, receipt, rejected, resultInput) {
  return start("encodeSubmission", { key, request, receipt, rejected, resultInput });
}

export function rebaseImport(workspace, base, review, files, snapshot) {
  return start("rebaseImport", { workspace, base, review, files, snapshot });
}

export function packJsonRequest(body) {
  return start("packJsonRequest", body);
}

export function investmentDisplay(portfolio, account, options) {
  return start("investmentDisplay", { portfolio, account, options });
}

export function encodeFiles(files) {
  return start("encodeFiles", files);
}

export function summarizeFiles(files) {
  return start("summarizeFiles", files);
}

export function inspectFiles(files) {
  return start("inspectFiles", files);
}

export function readArchivePage(body, kind, offset, limit) {
  return start("readArchivePage", { body, kind, offset, limit });
}

export function captureRemoteFile(metadata, format, data, text) {
  return start("captureRemoteFile", { metadata, format, data, text });
}

export function prepareImportAccounts(batch, workspace, files, snapshot, review) {
  return start("prepareImportAccounts", { batch, workspace, files, snapshot, review });
}

export function inspectSnapshot(workspace, text) {
  return start("inspectSnapshot", { workspace, text });
}

export function prepareSnapshot(workspace, text) {
  return start("prepareSnapshot", { workspace, text });
}

export function createCashLedgerClient() {
  const slot = createWorker({ name: "cash-ledger" });
  const read = (query, snapshot) => slot.start("readLedgerPage", { query, snapshot });
  return {
    page: read,
    read,
    close: slot.close,
  };
}

export function readPreview(query, section) {
  return start("readPreview", { query, section });
}

// Keep the compute module ready when the open page subsequently loses connectivity.
try {
  idleWorker = createImportWorker();
} catch {
  // Retry construction when a preview is requested.
}

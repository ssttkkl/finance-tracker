let idleWorker;

const startupTimeoutMs = 30_000;

function createWorker(cacheWhenIdle, name = "") {
  const url = new URL("./assets/__ASSET_VERSION__/entry.mjs", import.meta.url);
  url.hash = name;
  const worker = new Worker(url, { type: "module" });
  let pending;
  let ready = false;
  let closed = false;
  const startupTimeout = setTimeout(() => fail("startup_timeout"), startupTimeoutMs);
  const slot = {
    start(payload) {
      if (closed || pending) throw new Error("cash_import_worker_failed");
      let request;
      const result = new Promise((resolve, reject) => {
        request = { payload, resolve, reject };
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
      worker.postMessage(pending.payload);
    } catch {
      fail("request_transfer");
    }
  }

  function fail(phase = "closed") {
    if (closed) return;
    closed = true;
    clearTimeout(startupTimeout);
    if (idleWorker === slot) idleWorker = undefined;
    worker.terminate();
    const request = pending;
    pending = undefined;
    request?.reject(new Error("cash_import_worker_failed:" + phase));
  }

  worker.onmessage = ({ data }) => {
    if (data?.ready === true) {
      ready = true;
      clearTimeout(startupTimeout);
      if (pending) send();
      return;
    }
    if (!pending || (typeof pending.payload === "string"
      ? typeof data?.result !== "string"
      : !data?.result || typeof data.result !== "object")) {
      fail("response_contract");
      return;
    }
    const request = pending;
    pending = undefined;
    cacheWhenIdle?.(slot);
    request.resolve(data.result);
  };
  worker.onerror = (event) => {
    event.preventDefault();
    fail(ready ? "execution" : "startup");
  };
  worker.onmessageerror = () => fail("response_transfer");
  return slot;
}

function start(payload) {
  const slot = idleWorker;
  idleWorker = undefined;
  return (slot ?? createImportWorker()).start(payload);
}

function createImportWorker() {
  return createWorker((slot) => {
    if (idleWorker) slot.close();
    else idleWorker = slot;
  });
}

export function startPreview(payload) {
  return start(payload);
}

export function startFile(metadata, file, limit) {
  return start({ metadata, file, limit });
}

export function openPreview(payload) {
  return start({ openPreview: payload });
}

export function openCachedPreview(preview, files, snapshot) {
  return start({ preview, files, snapshot });
}

export function freezeConfirmation(confirmation) {
  return start({ confirmation });
}

export function assembleArchive(batchId, key, receipt, input, items) {
  return start({ archive: { batchId, key, receipt, input, items } });
}

export function inspectArchive(resultBody) {
  return start({ resultBody });
}

export function inspectSubmission(submissionBody) {
  return start({ submissionBody });
}

export function createCashLedgerClient() {
  const slot = createWorker(null, "cash-ledger");
  return {
    page: (ledger, snapshot) => slot.start({ ledger, snapshot }),
    close: slot.close,
  };
}

export function readPreview(query, section) {
  return start({ query, section });
}

// Keep the compute module ready when the open page subsequently loses connectivity.
try {
  idleWorker = createImportWorker();
} catch {
  // Retry construction when a preview is requested.
}

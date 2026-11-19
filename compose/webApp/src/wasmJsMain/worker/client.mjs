let idleWorker;

const startupTimeoutMs = 30_000;

function createWorker() {
  const worker = new Worker(new URL("./assets/__ASSET_VERSION__/entry.mjs", import.meta.url), { type: "module" });
  let pending;
  let ready = false;
  let closed = false;
  const startupTimeout = setTimeout(fail, startupTimeoutMs);
  const slot = {
    start(payload) {
      let request;
      const result = new Promise((resolve, reject) => {
        request = { payload, resolve, reject };
        pending = request;
        if (ready) send();
      });
      return {
        result,
        cancel() {
          if (pending === request) fail();
        },
      };
    },
  };

  function send() {
    try {
      worker.postMessage(pending.payload);
    } catch {
      fail();
    }
  }

  function fail() {
    if (closed) return;
    closed = true;
    clearTimeout(startupTimeout);
    if (idleWorker === slot) idleWorker = undefined;
    worker.terminate();
    const request = pending;
    pending = undefined;
    request?.reject(new Error("cash_import_worker_failed"));
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
      fail();
      return;
    }
    const request = pending;
    pending = undefined;
    if (idleWorker) fail();
    else idleWorker = slot;
    request.resolve(data.result);
  };
  worker.onerror = (event) => {
    event.preventDefault();
    fail();
  };
  worker.onmessageerror = fail;
  return slot;
}

function start(payload) {
  const slot = idleWorker;
  idleWorker = undefined;
  return (slot ?? createWorker()).start(payload);
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

export function readPreview(query, section) {
  return start({ query, section });
}

// Keep the compute module ready when the open page subsequently loses connectivity.
try {
  idleWorker = createWorker();
} catch {
  // Retry construction when a preview is requested.
}

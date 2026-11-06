// Lifecycle messages use negative IDs; SQLDelight owns its nonnegative request IDs.
function controller() {
  const worker = new Worker(new URL("./worker.js", import.meta.url));
  const add = worker.addEventListener.bind(worker);
  const remove = worker.removeEventListener.bind(worker);
  const post = worker.postMessage.bind(worker);
  const messages = new Map();
  const errors = new Map();
  let registration;
  const pending = new Map();
  let sequence = 0;
  let terminated = false;
  let fatal;
  let closing;

  function terminate() {
    terminated = true;
    worker.terminate();
    remove("message", message);
    remove("error", failed);
    remove("messageerror", failed);
  }

  function fail(failure) {
    fatal = failure;
    terminated = true;
    for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(failure); }
    pending.clear();
    try {
      const event = new ErrorEvent("error", { message: failure.message, error: failure });
      for (const pair of Array.from(messages.values())) {
        releasePair(pair);
        if (typeof pair.error === "function") pair.error.call(worker, event);
        else pair.error.handleEvent(event);
      }
    } finally { terminate(); }
  }

  function failed(event) { fail(event.error || new Error(event.message || "ledger_cache_worker_failed")); }

  function message({ data }) {
    const entry = pending.get(data?.id);
    if (!entry) return;
    clearTimeout(entry.timer); pending.delete(data.id);
    if (data.error) entry.reject(Object.assign(new Error(data.error.message), data.error));
    else entry.resolve();
  }

  add("message", message);
  add("error", failed);
  add("messageerror", failed);

  function releasePair(pair) {
    if (!pair) return;
    remove("message", pair.message, pair.messageOptions);
    remove("error", pair.error, pair.errorOptions);
    messages.delete(pair.message); errors.delete(pair.error);
  }

  // SQLDelight 2.4.1 registers message then error immediately before each nonnegative request.
  // Keep the native Worker identity while completing/cancelling both listeners together.
  worker.addEventListener = (type, listener, options) => {
    if (type === "message") registration = { message: listener, messageOptions: options };
    else if (type === "error" && registration) Object.assign(registration, { error: listener, errorOptions: options });
    add(type, listener, options);
  };
  worker.removeEventListener = (type, listener, options) => {
    const pair = type === "message" ? messages.get(listener) : type === "error" ? errors.get(listener) : undefined;
    releasePair(pair);
    if (registration && (registration.message === listener || registration.error === listener)) registration = undefined;
    remove(type, listener, options);
  };
  worker.postMessage = (data, ...options) => {
    let pair;
    if (Number.isInteger(data?.id) && data.id >= 0 && registration?.error) {
      pair = registration; registration = undefined;
      messages.set(pair.message, pair); errors.set(pair.error, pair);
    }
    if (terminated) { releasePair(pair); throw fatal || new Error("ledger_cache_closed"); }
    try { post(data, ...options); }
    catch (failure) { releasePair(pair); throw failure; }
  };

  function request(action, scope) {
    if (terminated) return Promise.reject(fatal || new Error("ledger_cache_closed"));
    return new Promise((resolve, reject) => {
      const id = --sequence;
      const timer = setTimeout(() => fail(new Error("ledger_cache_worker_timeout")), 30000);
      pending.set(id, { resolve, reject, timer });
      try { post({ id, action, ...(scope ? { scope } : {}) }); }
      catch (failure) { clearTimeout(timer); pending.delete(id); reject(failure); }
    });
  }

  function close() {
    if (terminated) return fatal ? Promise.reject(fatal) : Promise.resolve();
    if (!closing) closing = request("close").then(terminate).catch((failure) => {
      closing = undefined;
      throw failure;
    });
    return closing;
  }

  return { worker, request, close, abort: terminate };
}

export async function openWebLedgerConnection(scope) {
  const connection = controller();
  try {
    await connection.request("open", scope);
    return { worker: connection.worker, close: connection.close };
  } catch (failure) {
    try { await connection.close(); }
    catch (cleanup) { throw new AggregateError([failure, cleanup], "ledger_cache_cleanup_failed"); }
    finally { connection.abort(); }
    throw failure;
  }
}

// Erasure never opens/decrypts the database, so missing keys cannot prevent scoped cleanup.
export async function eraseWebLedgerStorage(scope) {
  const connection = controller();
  try { await connection.request("erase", scope); }
  catch (failure) {
    try { await connection.close(); }
    catch (cleanup) { throw new AggregateError([failure, cleanup], "ledger_cache_cleanup_failed"); }
    finally { connection.abort(); }
    throw failure;
  }
  try { await connection.close(); } finally { connection.abort(); }
}

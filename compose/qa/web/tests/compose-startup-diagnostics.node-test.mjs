import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { runInNewContext } from "node:vm";

const release = "0123456789abcdef0123456789abcdef01234567";
const source = readFileSync(new URL("../../../webApp/src/wasmJsMain/resources/startup.js", import.meta.url), "utf8")
  .replace("__FT_WEB_RELEASE__", release);

function start({ blocked = false, dsn = "", collectorFailure = false, wasm = true } = {}) {
  const records = new Map();
  const listeners = {};
  const consoleEvents = [];
  const consoleErrors = [];
  const scripts = [];
  const sent = [];
  let options;
  const context = {
    addEventListener(name, handler) { listeners[name] = handler; },
    localStorage: {
      getItem: (key) => records.get(key),
      setItem(key, value) {
        if (blocked) throw new Error("storage blocked");
        records.set(key, value);
      },
    },
    document: {
      createElement: () => ({ addEventListener(name, handler) { this[name] = handler; } }),
      body: { appendChild(element) {
        scripts.push(element.src);
        if (element.src === "/sentry-browser.min.js") element.load();
      } },
      getElementById: () => ({ setAttribute() {}, removeAttribute() {} }),
    },
    Sentry: {
      init(value) { options = value; },
      captureEvent(event) {
        if (collectorFailure) throw new Error("synthetic-reporting-failure");
        sent.push(options.beforeSend({ ...event, extra: { secret: "synthetic-secret" } }));
      },
    },
    console: {
      info: (_prefix, event) => consoleEvents.push(event),
      error: (_prefix, event) => { consoleErrors.push(event); consoleEvents.push(event); },
    },
    WebAssembly: wasm ? { validate: () => true, instantiateStreaming() {}, Tag() {} } : undefined,
    isSecureContext: true,
    crossOriginIsolated: false,
    SharedArrayBuffer: undefined,
    navigator: { locks: {}, storage: { getDirectory() {} } },
  };
  context.window = context;
  runInNewContext(source.replace("__FT_WEB_SENTRY_DSN__", dsn), context);
  return { context, listeners, records, consoleEvents, consoleErrors, scripts, sent, options };
}

test("startup records release and capabilities before loading Wasm", () => {
  const { consoleEvents, scripts } = start();
  const boot = consoleEvents[0];
  assert.equal(boot.kind, "startup");
  assert.equal(boot.release, release);
  assert.equal(boot.wasmSupported, true);
  assert.equal(boot.crossOriginIsolated, false);
  assert.equal(boot.sharedArrayBuffer, false);
  assert.equal(boot.opfs, true);
  assert.ok(scripts.includes("/webApp.js"));
});

test("unsupported Wasm is a handled compatibility outcome, not a console error", () => {
  const { consoleEvents, consoleErrors, scripts, sent } = start({ wasm: false, dsn: "https://public@example.test/1" });

  assert.equal(consoleErrors.length, 0);
  assert.equal(consoleEvents.length, 1);
  assert.equal(consoleEvents[0].kind, "startup");
  assert.equal(consoleEvents[0].wasmSupported, false);
  assert.ok(!scripts.includes("/webApp.js"));
  assert.ok(!scripts.includes("/cash-import-worker/client.mjs"));
  assert.ok(sent.some((event) => event.message === "finance_tracker.web.startup"
    && event.tags.reason === "wasm_unsupported"));
});

test("diagnostics are local, bounded, and only record safe fields", () => {
  const { context, listeners, records } = start();
  for (let index = 0; index < 100; index++) {
    listeners.error({ message: "synthetic-secret-message", filename: "https://example/synthetic-secret-path" });
    listeners.unhandledrejection({ reason: "synthetic-secret-reason" });
  }
  const id = "01234567-89ab-4cde-8123-456789abcdef";
  context.financeTrackerRecordAuthResponse("login", 200, id);
  context.financeTrackerRecordAuthResponse("synthetic-secret-operation", 200, id);
  context.financeTrackerRecordAuthResponse("session", 401, "synthetic-secret-id");

  const raw = records.get("finance-tracker:runtime-diagnostics");
  assert.ok(!raw.includes("synthetic-secret"));
  const entries = JSON.parse(raw);
  assert.ok(entries.length <= 32);
  assert.ok(entries.some((entry) => entry.kind === "error"));
  assert.ok(entries.some((entry) => entry.kind === "unhandled_rejection"));
  assert.equal(entries.at(-2).requestId, id);
  assert.equal(entries.at(-1).status, 401);
  assert.equal(entries.at(-1).requestId, undefined);
});

test("disabled browser storage does not interrupt the runtime gate", () => {
  const { records, consoleEvents, scripts } = start({ blocked: true });
  assert.equal(records.size, 0);
  assert.equal(consoleEvents[0].kind, "startup");
  assert.ok(scripts.includes("/webApp.js"));
});

test("Sentry only sends the Web error allowlist when configured", () => {
  const dsn = "https://publickey@sentry.example.invalid/42";
  const { context, listeners, sent, options } = start({ dsn });
  const id = "01234567-89ab-4cde-8123-456789abcdef";
  listeners.error({ message: "synthetic-secret", filename: "https://example.invalid/synthetic-secret" });
  context.financeTrackerRecordAuthResponse("login", 401, id);
  context.financeTrackerRecordAuthResponse("login", 503, id);
  context.financeTrackerReportAuthDiagnostic("1\tAUTHENTICATION\tAUTHENTICATE\tunknown_error\t\tException");
  assert.equal(sent.length, 4);
  assert.equal(sent[0].message, "finance_tracker.web.startup");
  assert.equal(sent[2].tags.request_id, id);
  assert.equal(sent[3].message, "finance_tracker.web.auth_failure");
  assert.ok(!JSON.stringify(sent).includes("synthetic-secret"));
  assert.equal(options.defaultIntegrations, false);
  assert.equal(options.dataCollection.userInfo, false);
  assert.equal(options.transportOptions.fetchOptions.referrerPolicy, "no-referrer");
  const disabled = start();
  disabled.listeners.error({ message: "synthetic-secret" });
  assert.equal(disabled.sent.length, 0);
  assert.ok(!disabled.scripts.includes("/sentry-browser.min.js"));
});


test("an SDK failure leaves local diagnostics and app startup available", () => {
  const { scripts, consoleEvents, listeners } = start({
    dsn: "https://publickey@sentry.example.invalid/42",
    collectorFailure: true,
  });
  listeners.unhandledrejection({ reason: "synthetic-secret" });
  assert.equal(consoleEvents.at(-1).kind, "unhandled_rejection");
  assert.ok(scripts.includes("/webApp.js"));
});

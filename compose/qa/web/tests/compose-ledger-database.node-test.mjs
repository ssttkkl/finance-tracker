import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import test from "node:test";
import { chromium } from "@playwright/test";

const root = new URL("../../../webApp/src/webMain/resources/", import.meta.url);
const scope = ["https://api.example.invalid", "synthetic-user", "encrypted-workspace"];

test("Encrypted OPFS database uses persistent browser keys and closes before scoped erasure", async (t) => {
  const provenance = JSON.parse(await readFile(new URL("ledger-storage/provenance.json", root), "utf8"));
  for (const [name, digest] of Object.entries(provenance.files))
    assert.equal(createHash("sha256").update(await readFile(new URL(`ledger-storage/${name}`, root))).digest("hex"), digest);
  const server = createServer(async (request, response) => {
    response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
    const path = new URL(request.url, "http://local.invalid").pathname.slice(1);
    const allowed = ["ledger-key-vault.mjs", "ledger-storage/worker.js", ...Object.keys(provenance.files).map((name) => `ledger-storage/${name}`)];
    if (!allowed.includes(path)) { response.setHeader("Content-Type", "text/html"); response.end("<!doctype html><title>Synthetic encrypted ledger</title>"); return; }
    try {
      response.setHeader("Content-Type", path.endsWith("wasm") ? "application/wasm" : "text/javascript");
      let source = await readFile(new URL(path, root));
      if (path === "ledger-storage/worker.js" && new URL(request.url, "http://local.invalid").searchParams.has("directory-once"))
        source = Buffer.concat([Buffer.from(`{ const original = navigator.storage.getDirectory.bind(navigator.storage); let first = true;
          navigator.storage.getDirectory = async () => { if (first) { first = false; throw new DOMException("Synthetic directory failure", "NotAllowedError"); } return original(); }; }
`), source]);
      if (path === "ledger-storage/worker.js" && new URL(request.url, "http://local.invalid").searchParams.has("remove-once"))
        source = Buffer.concat([Buffer.from(`{ const original = FileSystemDirectoryHandle.prototype.removeEntry; let first = true;
          FileSystemDirectoryHandle.prototype.removeEntry = function(...args) { if (first) { first = false; return Promise.reject(new DOMException("Synthetic removal failure", "NoModificationAllowedError")); } return original.apply(this, args); }; }
`), source]);
      response.end(source);
    } catch { response.writeHead(404); response.end("Missing database resource"); }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const pageErrors = []; const networkErrors = []; const consoleErrors = [];
  const profile = await mkdtemp(join(tmpdir(), "ft-encrypted-ledger-")); let context; let page;
  t.after(async () => { await context?.close(); await rm(profile, { recursive: true, force: true }); });
  const launch = async () => {
    context = await chromium.launchPersistentContext(profile, { headless: true, viewport: { width: 1280, height: 720 },
      ...(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {}) });
    page = await context.newPage();
    page.on("pageerror", (error) => pageErrors.push(error.message));
    page.on("requestfailed", (request) => networkErrors.push(`${request.url()}: ${request.failure()?.errorText}`));
    page.on("console", (message) => { if (message.type() === "error") consoleErrors.push(message.text()); });
    await page.goto(origin);
    await page.evaluate(() => {
      window.clients = new Map();
      window.client = (name, suffix = "") => {
        const worker = new Worker(`/ledger-storage/worker.js${suffix}`); let id = 0;
        const pending = new Map();
        worker.onmessage = ({ data }) => {
          const entry = pending.get(data.id); if (!entry) return;
          clearTimeout(entry.timer); pending.delete(data.id);
          if (data.error) entry.reject(Object.assign(new Error(data.error.message), data.error)); else entry.resolve(data);
        };
        worker.onerror = (event) => { for (const entry of pending.values()) { clearTimeout(entry.timer); entry.reject(new Error(event.message)); } pending.clear(); };
        const client = { worker, send(action, fields = {}) { return new Promise((resolve, reject) => {
          const current = ++id; const timer = setTimeout(() => { pending.delete(current); reject(new Error("Worker did not respond")); }, 15000);
          pending.set(current, { resolve, reject, timer }); worker.postMessage({ id: current, action, ...fields });
        }); } };
        window.clients.set(name, client);
      };
    });
  };
  await launch();
  t.diagnostic(`${await page.evaluate(() => navigator.userAgent)}; ${origin}; 1280x720`);
  const client = (name, suffix = "") => page.evaluate(([name, suffix]) => window.client(name, suffix), [name, suffix]);
  const send = (name, action, fields = {}) => page.evaluate(([name, action, fields]) => window.clients.get(name).send(action, fields), [name, action, fields]);
  const sql = async (name, query, params = []) => (await send(name, "exec", { sql: query, params })).results.values;
  const physical = () => page.evaluate(async (scope) => {
    const vault = await import("/ledger-key-vault.mjs"); const id = await vault.webLedgerKeyId(scope);
    const root = await navigator.storage.getDirectory();
    try {
      const parent = await root.getDirectoryHandle("ft-ledger-cache-v1"); const directory = await parent.getDirectoryHandle(id);
      const files = [];
      for await (const [name, handle] of directory.entries()) if (handle.kind === "file") {
        const bytes = new Uint8Array(await (await handle.getFile()).arrayBuffer()); const text = new TextDecoder().decode(bytes);
        files.push({ name, digest: Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)), (byte) => byte.toString(16).padStart(2, "0")).join(""), length: bytes.length, plainHeader: text.startsWith("SQLite format 3"), leaked: ["private-note-marker-239", "private-index-marker-441", "rolled-back-marker-775"].some((marker) => text.includes(marker)) });
      }
      return files;
    } catch (failure) { if (failure.name === "NotFoundError") return []; throw failure; }
  }, scope);

  await t.test("failed erasure acquisition closes the worker and releases its scope lease", async () => {
    const temporary = [scope[0], scope[1], "directory-failure-workspace"];
    await client("directory-failure", "?directory-once");
    await assert.rejects(send("directory-failure", "erase", { scope: temporary }), /Synthetic directory failure/);
    await assert.rejects(send("directory-failure", "open", { scope: temporary }), /ledger_cache_closed/);
    await client("after-directory-failure");
    await send("after-directory-failure", "open", { scope: temporary });
    await send("after-directory-failure", "erase");
  });

  await t.test("open verifies the encrypted engine configuration and preserves exact TEXT", async () => {
    await client("a"); const opened = await send("a", "open", { scope });
    assert.deepEqual([opened.info.sqlite, opened.info.multipleCiphers, opened.info.cipher, opened.info.hardware, opened.info.journal], ["3.54.0", "SQLite3 Multiple Ciphers 2.6.1", "chacha20", "off", "delete"]);
    assert.deepEqual(await sql("a", "PRAGMA temp_store"), [[2]]);
    await sql("a", "CREATE TABLE facts(id TEXT PRIMARY KEY, amount TEXT, note TEXT, account TEXT)");
    await sql("a", "CREATE INDEX account_idx ON facts(account)");
    await sql("a", "INSERT INTO facts VALUES(?,?,?,?)", ["9007199254740993", "-12345678901234567890.123456789012345678", "private-note-marker-239".repeat(400), "private-index-marker-441"]);
    assert.deepEqual(await sql("a", "SELECT id,amount FROM facts WHERE account=?", ["private-index-marker-441"]), [["9007199254740993", "-12345678901234567890.123456789012345678"]]);
  });

  await t.test("active DELETE journals and main database do not expose the written markers", async () => {
    await send("a", "begin_transaction");
    await sql("a", "UPDATE facts SET note=?", ["rolled-back-marker-775".repeat(400)]);
    const files = await physical();
    assert.ok(files.some((file) => file.name === "ledger.db-journal" && file.length > 0));
    assert.ok(files.every((file) => !file.plainHeader && !file.leaked));
    await send("a", "rollback_transaction");
    assert.equal((await sql("a", "SELECT note FROM facts"))[0][0], "private-note-marker-239".repeat(400));
  });

  await t.test("a second worker cannot open the same scope while the first owns its files", async () => {
    await client("busy"); await assert.rejects(send("busy", "open", { scope }), /ledger_cache_in_use/);
    await send("busy", "close"); await send("a", "close");
    await assert.rejects(sql("a", "SELECT * FROM facts"), /ledger_cache_closed/);
    const files = await physical(); assert.ok(files.some((file) => file.name === "ledger.db"));
    assert.ok(files.every((file) => !file.plainHeader && !file.leaked));
  });

  await t.test("full browser restart reads the encrypted committed facts before writing again", async () => {
    await context.close(); context = undefined; await launch();
    await client("reopened"); await send("reopened", "open", { scope });
    assert.deepEqual(await sql("reopened", "SELECT id,amount FROM facts"), [["9007199254740993", "-12345678901234567890.123456789012345678"]]);
    await send("reopened", "begin_transaction"); await sql("reopened", "INSERT INTO facts VALUES('second','0','safe','safe')"); await send("reopened", "end_transaction");
    assert.equal((await sql("reopened", "SELECT COUNT(*) FROM facts"))[0][0], 2);
    await send("reopened", "close");
  });

  await t.test("termination during a spilled transaction recovers only the committed facts", async () => {
    await client("crash"); await send("crash", "open", { scope });
    const committed = await physical();
    await sql("crash", "PRAGMA cache_size=1");
    await send("crash", "begin_transaction");
    await sql("crash", "UPDATE facts SET note=? WHERE id=?", ["rolled-back-marker-775".repeat(400), "9007199254740993"]);
    for (let i = 0; i < 8; i++) await sql("crash", "INSERT INTO facts VALUES(?,?,?,?)", [`uncommitted-${i}`, "0", "rolled-back-marker-775".repeat(400), "private-index-marker-441"]);
    const dirty = await physical();
    assert.ok(dirty.some((file) => file.name === "ledger.db-journal" && file.length > 0));
    assert.notEqual(dirty.find((file) => file.name === "ledger.db").digest, committed.find((file) => file.name === "ledger.db").digest);
    assert.ok(dirty.every((file) => !file.plainHeader && !file.leaked));
    await page.evaluate(() => window.clients.get("crash").worker.terminate());
    await page.waitForFunction(async (scope) => {
      const id = await (await import("/ledger-key-vault.mjs")).webLedgerKeyId(scope);
      return !(await navigator.locks.query()).held.some((lock) => lock.name === `ft-ledger-cache:${id}`);
    }, scope);
    await client("recovered"); await send("recovered", "open", { scope });
    assert.equal((await sql("recovered", "SELECT COUNT(*) FROM facts"))[0][0], 2);
    assert.equal((await sql("recovered", "SELECT note FROM facts WHERE id=?", ["9007199254740993"]))[0][0], "private-note-marker-239".repeat(400));
    await send("recovered", "close");
  });

  await t.test("failed file erasure fences the old connection and preserves keys for explicit retry", async () => {
    const temporary = [scope[0], scope[1], "remove-failure-workspace"];
    await client("remove-failure", "?remove-once"); await send("remove-failure", "open", { scope: temporary });
    await sql("remove-failure", "CREATE TABLE retained(value TEXT)"); await sql("remove-failure", "INSERT INTO retained VALUES('keep')");
    await assert.rejects(send("remove-failure", "erase"), /Synthetic removal failure/);
    await assert.rejects(sql("remove-failure", "SELECT * FROM retained"), /ledger_cache_closed/);
    assert.equal(await page.evaluate(async (scope) => { const raw = await (await import("/ledger-key-vault.mjs")).readWebLedgerKey(scope); const present = raw?.length === 32; raw?.fill(0); return present; }, temporary), true);
    await client("retry-erasure"); await send("retry-erasure", "open", { scope: temporary });
    assert.deepEqual(await sql("retry-erasure", "SELECT value FROM retained"), [["keep"]]);
    await send("retry-erasure", "erase");
  });

  await t.test("a different valid key rejects the existing file without modifying it", async () => {
    await page.evaluate(async (scope) => {
      const vault = await import("/ledger-key-vault.mjs"); const id = await vault.webLedgerKeyId(scope);
      const database = await new Promise((resolve, reject) => { const request = indexedDB.open("ft-ledger-cache-keys-v1"); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      try {
        window.wrappedBackup = await new Promise((resolve, reject) => { const transaction = database.transaction("keys"); const request = transaction.objectStore("keys").get(id); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      } finally { database.close(); }
      await vault.eraseWebLedgerKey(scope); const raw = await vault.createWebLedgerKey(scope); raw.fill(0);
    }, scope);
    const before = await physical();
    await client("wrong-key");
    await assert.rejects(send("wrong-key", "open", { scope }), /not a database|malformed|encrypted/i);
    assert.deepEqual(await physical(), before);
    await send("wrong-key", "close");
    await page.evaluate(async () => {
      const database = await new Promise((resolve, reject) => { const request = indexedDB.open("ft-ledger-cache-keys-v1"); request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
      try { await new Promise((resolve, reject) => { const transaction = database.transaction("keys", "readwrite"); transaction.objectStore("keys").put(window.wrappedBackup, window.wrappedBackup.keyId); transaction.oncomplete = resolve; transaction.onabort = () => reject(transaction.error); }); }
      finally { database.close(); }
      delete window.wrappedBackup;
    });
    await client("restored-key"); await send("restored-key", "open", { scope });
    assert.equal((await sql("restored-key", "SELECT COUNT(*) FROM facts"))[0][0], 2);
    await send("restored-key", "close");
  });

  await t.test("missing keys never recreate an existing encrypted database", async () => {
    await page.evaluate(async (scope) => (await import("/ledger-key-vault.mjs")).eraseWebLedgerKey(scope), scope);
    const before = await physical(); await client("missing");
    await assert.rejects(send("missing", "open", { scope }), /ledger_cache_key_missing/);
    assert.deepEqual(await physical(), before);
    assert.equal(await page.evaluate(async (scope) => (await import("/ledger-key-vault.mjs")).readWebLedgerKey(scope), scope), null);
    await send("missing", "close");
  });

  await t.test("scoped erasure is idempotent and another workspace remains readable", async () => {
    const other = [scope[0], scope[1], "other-encrypted-workspace"];
    await client("other"); await send("other", "open", { scope: other }); await sql("other", "CREATE TABLE retained(value TEXT)");
    await sql("other", "INSERT INTO retained VALUES('keep')");
    await client("eraser"); await send("eraser", "erase", { scope });
    assert.deepEqual(await physical(), []);
    assert.equal(await page.evaluate(async (scope) => (await import("/ledger-key-vault.mjs")).readWebLedgerKey(scope), scope), null);
    await client("again"); await send("again", "erase", { scope });
    assert.deepEqual(await sql("other", "SELECT value FROM retained"), [["keep"]]);
    await send("other", "erase");
    await assert.rejects(sql("other", "SELECT * FROM retained"), /ledger_cache_closed/);
  });
  assert.deepEqual(pageErrors, []);
  assert.deepEqual(networkErrors, []);
  t.diagnostic(`Console errors: ${JSON.stringify(consoleErrors)}`);
});

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { chromium } from "@playwright/test";

const asset = new URL("../../../webApp/src/webMain/resources/ledger-key-vault.mjs", import.meta.url);
const scope = ["https://api.example.invalid", "synthetic-user", "synthetic-workspace"];

test("Web ledger keys persist, isolate scopes and fail closed in real Chromium", async (t) => {
  const server = createServer(async (request, response) => {
    if (request.url === "/ledger-key-vault.mjs") {
      try {
        response.setHeader("Content-Type", "text/javascript");
        response.end(await readFile(asset));
      } catch (failure) {
        response.writeHead(404); response.end("Missing key vault asset");
      }
    } else { response.setHeader("Content-Type", "text/html"); response.end("<!doctype html><title>Synthetic ledger keys</title>"); }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const origin = `http://127.0.0.1:${server.address().port}`;
  const profile = await mkdtemp(join(tmpdir(), "ft-ledger-key-vault-"));
  const browserErrors = [];
  const watch = (page) => {
    page.on("pageerror", (error) => browserErrors.push(error.message));
    page.on("console", (message) => { if (message.type() === "error") browserErrors.push(message.text()); });
    page.on("requestfailed", (request) => browserErrors.push(`${request.url()}: ${request.failure()?.errorText}`));
  };
  let context;
  t.after(async () => { await context?.close(); await rm(profile, { recursive: true, force: true }); });
  const launch = async () => {
    context = await chromium.launchPersistentContext(profile, {
      headless: true,
      viewport: { width: 1280, height: 720 },
      ...(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {}),
    });
    const page = await context.newPage(); watch(page); await page.goto(origin);
    await page.evaluate(async () => { window.vault = await import("/ledger-key-vault.mjs"); });
    return page;
  };
  let page = await launch();
  t.diagnostic(`Browser: ${await page.evaluate(() => navigator.userAgent)}; URL: ${origin}`);
  const key = (page, input, create = false) => page.evaluate(async ([scope, create]) => {
    const bytes = await window.vault[create ? "createWebLedgerKey" : "readWebLedgerKey"](scope);
    return bytes === null ? null : Array.from(bytes);
  }, [input, create]);
  const record = async (page, input, action) => page.evaluate(async ([scope, action]) => {
    const id = await window.vault.webLedgerKeyId(scope);
    const db = await new Promise((resolve, reject) => {
      const request = indexedDB.open("ft-ledger-cache-keys-v1", 1);
      request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
    });
    try {
      const value = await new Promise((resolve, reject) => {
        const transaction = db.transaction("keys", action === "inspect" ? "readonly" : "readwrite");
        const store = transaction.objectStore("keys"); const request = store.get(id); let result;
        request.onsuccess = () => {
          const value = request.result;
          if (action === "corrupt") { new Uint8Array(value.ciphertext)[0] ^= 1; store.put(value, id); }
          result = value;
        };
        transaction.oncomplete = () => resolve(result);
        transaction.onerror = transaction.onabort = () => reject(transaction.error);
      });
      if (action !== "inspect") return true;
      let exportRejected = false;
      try { await crypto.subtle.exportKey("raw", value.wrappingKey); } catch (error) { exportRejected = error.name === "InvalidAccessError"; }
      return { fields: Object.keys(value).sort(), extractable: value.wrappingKey.extractable, exportRejected,
        ciphertext: Array.from(new Uint8Array(value.ciphertext)) };
    } finally { db.close(); }
  }, [input, action]);

  let first;
  await t.test("read does not silently create and first creation stores only wrapped material", async () => {
    assert.equal(await key(page, scope), null);
    first = await key(page, scope, true); assert.equal(first.length, 32);
    assert.deepEqual(await key(page, scope), first);
    const stored = await record(page, scope, "inspect");
    assert.deepEqual(stored.fields, ["ciphertext", "iv", "keyId", "version", "wrappingKey"]);
    assert.equal(stored.extractable, false); assert.equal(stored.exportRejected, true);
    assert.equal(stored.ciphertext.length, 48); assert.notDeepEqual(stored.ciphertext.slice(0, 32), first);
    assert.deepEqual(await page.evaluate(() => [localStorage.length, sessionStorage.length]), [0, 0]);
  });

  await t.test("a full browser process restart unlocks the same database key", async () => {
    await context.close(); context = undefined;
    page = await launch(); await context.setOffline(true);
    try { assert.deepEqual(await key(page, scope), first); } finally { await context.setOffline(false); }
  });

  await t.test("origins, users, workspaces and tuple boundaries have separate keys", async () => {
    const scopes = [scope, ["https://other.example.invalid", scope[1], scope[2]], [scope[0], "other-user", scope[2]],
      [scope[0], scope[1], "other-workspace"], ["a", "b:c", "d"], ["a:b", "c", "d"]];
    const keys = await Promise.all(scopes.map((item) => key(page, item, true)));
    assert.equal(new Set(keys.map((bytes) => JSON.stringify(bytes))).size, scopes.length);
  });

  await t.test("two tabs racing on first creation observe a single committed key", async () => {
    const other = await context.newPage(); watch(other); await other.goto(origin);
    await other.evaluate(async () => { window.vault = await import("/ledger-key-vault.mjs"); });
    try {
      const input = [scope[0], scope[1], "concurrent-workspace"];
      const keys = await Promise.all(Array.from({ length: 16 }, (_, i) => key(i % 2 ? other : page, input, true)));
      keys.forEach((value) => assert.deepEqual(value, keys[0]));
    } finally { await other.close(); }
  });

  await t.test("authenticated ciphertext corruption is rejected without rotating the key", async () => {
    const input = [scope[0], scope[1], "corrupt-workspace"]; await key(page, input, true);
    await record(page, input, "corrupt"); const before = await record(page, input, "inspect");
    for (const create of [false, true]) {
      assert.equal(await page.evaluate(async ([scope, create]) => {
        try { await window.vault[create ? "createWebLedgerKey" : "readWebLedgerKey"](scope); return "accepted"; }
        catch (failure) { return failure.name; }
      }, [input, create]), "OperationError");
    }
    assert.deepEqual(await record(page, input, "inspect"), before);
  });

  await t.test("moving a complete wrapped record to another scope fails authentication", async () => {
    const target = [scope[0], scope[1], "substitution-workspace"]; await key(page, target, true);
    await page.evaluate(async ([source, target]) => {
      const from = await window.vault.webLedgerKeyId(source); const to = await window.vault.webLedgerKeyId(target);
      const db = await new Promise((resolve, reject) => {
        const request = indexedDB.open("ft-ledger-cache-keys-v1", 1);
        request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error);
      });
      try { await new Promise((resolve, reject) => {
        const transaction = db.transaction("keys", "readwrite"); const store = transaction.objectStore("keys");
        const request = store.get(from); request.onsuccess = () => store.put({ ...request.result, keyId: to }, to);
        transaction.oncomplete = resolve; transaction.onerror = transaction.onabort = () => reject(transaction.error);
      }); } finally { db.close(); }
    }, [scope, target]);
    assert.equal(await page.evaluate(async (scope) => {
      try { await window.vault.readWebLedgerKey(scope); return "accepted"; } catch (error) { return error.name; }
    }, target), "OperationError");
    assert.deepEqual(await key(page, scope), first);
  });

  await t.test("missing cross-tab coordination refuses key creation instead of an unsafe fallback", async () => {
    assert.equal(await page.evaluate(async (scope) => {
      const descriptor = Object.getOwnPropertyDescriptor(navigator, "locks");
      Object.defineProperty(navigator, "locks", { configurable: true, value: undefined });
      try { await window.vault.createWebLedgerKey(scope); return "accepted"; }
      catch (error) { return error.message; }
      finally { if (descriptor) Object.defineProperty(navigator, "locks", descriptor); else delete navigator.locks; }
    }, [scope[0], scope[1], "no-locks"]), "ledger_cache_unavailable");
    assert.equal(await key(page, [scope[0], scope[1], "no-locks"]), null);
  });

  await t.test("storage failures preserve the original failure and never return a fallback key", async () => {
    assert.equal(await page.evaluate(async (scope) => {
      const descriptor = Object.getOwnPropertyDescriptor(window, "indexedDB");
      const failure = new DOMException("Synthetic quota failure", "QuotaExceededError");
      Object.defineProperty(window, "indexedDB", { configurable: true, value: { open() { throw failure; } } });
      try { await window.vault.createWebLedgerKey(scope); return false; }
      catch (caught) { return caught === failure; }
      finally { if (descriptor) Object.defineProperty(window, "indexedDB", descriptor); else delete window.indexedDB; }
    }, [scope[0], scope[1], "storage-failure"]), true);
    assert.equal(await key(page, [scope[0], scope[1], "storage-failure"]), null);
  });

  await t.test("scoped deletion is durable, idempotent and leaves other scopes intact", async () => {
    const otherScope = [scope[0], "other-user", scope[2]]; const other = await key(page, otherScope);
    await page.evaluate(async (scope) => { await window.vault.eraseWebLedgerKey(scope); await window.vault.eraseWebLedgerKey(scope); }, scope);
    assert.equal(await key(page, scope), null); assert.deepEqual(await key(page, otherScope), other);
    assert.notDeepEqual(await key(page, scope, true), first);
  });

  await t.test("an actual transaction abort after adding material returns no key and leaves no record", async () => {
    const input = [scope[0], scope[1], "aborted-create"];
    const result = await page.evaluate(async (scope) => {
      const original = indexedDB; const descriptor = Object.getOwnPropertyDescriptor(window, "indexedDB");
      let added = false;
      Object.defineProperty(window, "indexedDB", { configurable: true, value: {
        open(...args) {
          const request = original.open(...args);
          request.addEventListener("success", () => {
            const db = request.result; const transaction = db.transaction.bind(db);
            db.transaction = (...args) => {
              const tx = transaction(...args);
              if (args[1] === "readwrite") {
                const objectStore = tx.objectStore.bind(tx);
                tx.objectStore = (...args) => {
                  const store = objectStore(...args); const add = store.add.bind(store);
                  store.add = (...args) => {
                    const pending = add(...args);
                    pending.addEventListener("success", () => { added = true; tx.abort(); });
                    return pending;
                  };
                  return store;
                };
              }
              return tx;
            };
          });
          return request;
        },
      } });
      try { await window.vault.createWebLedgerKey(scope); return { added, error: "accepted" }; }
      catch (error) { return { added, error: error?.name ?? "missing-error" }; }
      finally { if (descriptor) Object.defineProperty(window, "indexedDB", descriptor); else delete window.indexedDB; }
    }, input);
    assert.deepEqual(result, { added: true, error: "AbortError" });
    assert.equal(await key(page, input), null);
  });
  assert.deepEqual(browserErrors, []);
});

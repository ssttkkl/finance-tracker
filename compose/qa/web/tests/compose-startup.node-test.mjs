import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { chromium, expect } from "@playwright/test";

const resources = new URL("../../../webApp/src/webMain/resources/", import.meta.url);

async function installSourcePreview(page, target = "wasmJsMain", legacyCss = false) {
  const requests = [];
  await page.route("http://startup.test/**", async (route) => {
    const name = new URL(route.request().url()).pathname.slice(1) || "index.html";
    requests.push(name);
    if (["font-mirror.js", "compose-focus.js", "webApp.js"].includes(name)) {
      return route.fulfill({ contentType: "text/javascript", body: `window.loadedScripts.push(${JSON.stringify(name)});` });
    }
    if (name === "startup.js") {
      return route.fulfill({ contentType: "text/javascript", body: await readFile(new URL(`../../../webApp/src/${target}/resources/startup.js`, import.meta.url)) });
    }
    if (!["index.html", "styles.css", "favicon.svg"].includes(name)) return route.fulfill({ status: 204 });
    const contentType = { "index.html": "text/html", "styles.css": "text/css", "favicon.svg": "image/svg+xml" }[name];
    let body = await readFile(new URL(name, resources));
    if (legacyCss && name === "styles.css") body = body.toString().replace(/inset:\s*0;/g, "").replace(/[\w-]+:\s*var\([^;]+;/g, "");
    return route.fulfill({ contentType, body });
  });
  return requests;
}

test("startup logo is centered and breathes before application JavaScript runs", async (t) => {
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {});
  t.after(() => browser.close());
  for (const { width, height, colorScheme, reducedMotion, background } of [
    { width: 1440, height: 900, colorScheme: "light", reducedMotion: "no-preference", background: "rgb(245, 245, 245)" },
    { width: 390, height: 844, colorScheme: "dark", reducedMotion: "reduce", background: "rgb(6, 6, 7)" },
  ]) {
    const context = await browser.newContext({ viewport: { width, height }, colorScheme, reducedMotion, javaScriptEnabled: false });
    const page = await context.newPage();
    await page.route("http://startup.test/**", async (route) => {
      const name = new URL(route.request().url()).pathname.slice(1) || "index.html";
      if (!["index.html", "styles.css", "favicon.svg"].includes(name)) return route.fulfill({ status: 204 });
      const contentType = { "index.html": "text/html", "styles.css": "text/css", "favicon.svg": "image/svg+xml" }[name];
      await route.fulfill({ body: await readFile(new URL(name, resources)), contentType });
    });
    await page.goto("http://startup.test/");
    const logo = page.locator("#startup-logo");
    await logo.waitFor({ state: "visible", timeout: 1500 });
    const bounds = await logo.boundingBox();
    assert.ok(Math.abs(bounds.x + bounds.width / 2 - width / 2) < 1);
    assert.ok(Math.abs(bounds.y + bounds.height / 2 - height / 2) < 1);
    assert.equal(await page.locator("#startup-screen").getAttribute("role"), "status");
    assert.equal(await page.locator("#startup-screen").getAttribute("aria-label"), "加载中...");
    assert.equal(await logo.getAttribute("src"), "/favicon.svg");
    assert.equal(await page.locator("#startup-screen").innerText(), "");
    assert.equal(await page.locator("#startup-screen").evaluate((element) => getComputedStyle(element).backgroundColor), background);
    assert.equal(await logo.evaluate((element) => element.complete && element.naturalWidth > 0), true);
    if (reducedMotion === "reduce") {
      assert.equal(await logo.evaluate((element) => getComputedStyle(element).animationName), "none");
    } else {
      const before = await logo.evaluate((element) => getComputedStyle(element).opacity);
      await expect.poll(() => logo.evaluate((element) => getComputedStyle(element).opacity), { timeout: 2000 }).not.toBe(before);
    }
    assert.equal(await page.locator("#startup-screen").evaluate((element) => element.querySelectorAll("button, a, [tabindex]").length), 0);
    await context.close();
  }
});

test("Wasm bootstrap shows upgrade requirements and requests no app when required features are missing", async (t) => {
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {});
  t.after(() => browser.close());
  for (const missing of ["wasm", "gc", "legacy-eh", "tag", "streaming", "legacy-css"]) {
    const context = await browser.newContext({ viewport: { width: 390, height: 320 } });
    const page = await context.newPage();
    const requests = await installSourcePreview(page, "wasmJsMain", missing === "legacy-css");
    await page.addInitScript((missing) => {
      window.loadedScripts = [];
      if (missing === "wasm" || missing === "legacy-css") { window.WebAssembly = undefined; return; }
      if (missing === "tag") { WebAssembly.Tag = undefined; return; }
      if (missing === "streaming") { WebAssembly.instantiateStreaming = undefined; return; }
      const validate = WebAssembly.validate.bind(WebAssembly);
      WebAssembly.validate = (buffer) => {
        const bytes = [...new Uint8Array(buffer)];
        if (missing === "gc" && bytes.includes(0x5f)) return false;
        if (missing === "legacy-eh" && bytes.includes(0x06) && bytes.includes(0x07)) return false;
        return validate(buffer);
      };
    }, missing);
    await page.goto("http://startup.test/");
    await expect(page.locator("#browser-unsupported")).toBeVisible({ timeout: 1500 });
    assert.equal((await page.locator("#browser-unsupported").boundingBox()).y, 0);
    await expect(page.locator("#startup-screen")).toBeHidden();
    const message = await page.locator("#browser-unsupported").innerText();
    for (const expected of ["Chrome", "119", "Edge", "Firefox", "120", "Safari", "18.2", "iPhone", "iPad", "iOS", "iPadOS"]) assert.ok(message.includes(expected), expected);
    assert.equal(requests.includes("webApp.js"), false, missing);
    assert.equal(requests.some((path) => path.endsWith(".wasm")), false, missing);
    assert.deepEqual(await page.evaluate(() => window.loadedScripts), []);
    await page.locator("#browser-unsupported a").focus();
    await page.keyboard.press("Tab");
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    const panel = page.locator("#browser-unsupported");
    assert.equal(await panel.evaluate((element) => element.scrollHeight > element.clientHeight), true);
    await context.close();
  }
});

test("supported Wasm and the JS target keep script order without a browser-name allowlist", async (t) => {
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {});
  t.after(() => browser.close());
  for (const target of ["wasmJsMain", "jsMain"]) {
    const context = await browser.newContext({ userAgent: "UnknownBrowser/1.0" });
    const page = await context.newPage();
    await installSourcePreview(page, target);
    await page.addInitScript((target) => {
      window.loadedScripts = [];
      WebAssembly.JSTag = undefined;
      if (target === "jsMain") WebAssembly.validate = () => false;
    }, target);
    await page.goto("http://startup.test/");
    await expect.poll(() => page.evaluate(() => window.loadedScripts)).toEqual(["font-mirror.js", "compose-focus.js", "webApp.js"]);
    await expect(page.locator("#browser-unsupported")).toBeHidden();
    await expect(page.locator("#startup-screen")).toBeVisible();
    await context.close();
  }
});

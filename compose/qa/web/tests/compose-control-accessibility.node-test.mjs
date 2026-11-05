import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { chromium } from "@playwright/test";

const source = async (platform) => readFile(new URL(`../../../shared/src/${platform}/kotlin/com/finance/tracker/core/PlatformControlAccessibility.kt`, import.meta.url), "utf8");

test("Web control semantics follow delayed shadow nodes, replacement, state updates and disposal", async (t) => {
  const js = (await source("jsMain")).match(/"""([\s\S]+?)"""/)[1];
  const wasm = (await source("wasmJsMain")).match(/"""([\s\S]+?)"""/)[1];
  assert.equal(js, wasm, "Both Web targets must use the same DOM contract");
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {});
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.setContent('<div id="host"></div>');
  await page.evaluate((body) => {
    window.register = new Function("tag", "role", "disabled", "checked", `return ${body}`);
    window.dispose = window.register("control", "radio", false, "false");
  }, js);
  // Attaching a shadow root produces no light-DOM mutation.
  await page.evaluate(() => {
    document.getElementById("host").attachShadow({ mode: "open" }).innerHTML = '<div id="control" role="button"></div>';
  });
  const state = () => page.evaluate(() => {
    const node = document.getElementById("host").shadowRoot.getElementById("control");
    return [node.getAttribute("role"), node.getAttribute("aria-disabled"), node.getAttribute("aria-checked")];
  });
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.getElementById("control").getAttribute("role") === "radio", null, { timeout: 2000 });
  assert.deepEqual(await state(), ["radio", "false", "false"]);
  await page.evaluate(() => {
    document.getElementById("host").remove();
    const host = document.createElement("div");
    host.id = "host";
    document.body.append(host);
    host.attachShadow({ mode: "open" }).innerHTML = '<div id="control" role="button"></div>';
  });
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.getElementById("control").getAttribute("role") === "radio", null, { timeout: 2000 });
  assert.deepEqual(await state(), ["radio", "false", "false"]);
  await page.evaluate(() => {
    window.dispose();
    window.dispose = window.register("control", "radio", false, "true");
  });
  assert.deepEqual(await state(), ["radio", "false", "true"]);
  await page.evaluate(() => {
    window.dispose();
    window.dispose = window.register("control", "button", true, "");
  });
  assert.deepEqual(await state(), ["button", "true", null]);
  await page.evaluate(() => window.dispose());
  assert.deepEqual(await state(), ["button", null, null]);
  await page.evaluate(() => {
    window.dispose = window.register("control", "radio", false, "true");
  });
  assert.deepEqual(await state(), ["radio", "false", "true"]);
  await page.evaluate(() => window.dispose());
  assert.deepEqual(await state(), ["button", null, null]);
});


test("Web Tab keeps keyboard delivery after backing input disposal without stealing focus", async (t) => {
  const script = await readFile(new URL("../../../webApp/src/webMain/resources/compose-focus.js", import.meta.url), "utf8");
  const browser = await chromium.launch(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {});
  t.after(() => browser.close());
  const page = await browser.newPage();
  await page.setContent('<div id="host"></div><button id="outside">Other control</button>');
  await page.addScriptTag({ content: script });
  await page.evaluate(() => {
    const root = document.getElementById("host").attachShadow({ mode: "open" });
    root.innerHTML = '<canvas tabindex="0"></canvas><input class="compose-backing-field">';
    window.delivered = 0;
    root.querySelector("canvas").addEventListener("keydown", () => window.delivered++);
    const input = root.querySelector("input");
    input.addEventListener("keydown", (event) => {
      if (event.key === "Tab") {
        event.preventDefault();
        setTimeout(() => input.remove(), 30);
      }
    });
    input.focus();
  });
  await page.keyboard.press("Tab");
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.activeElement?.tagName === "CANVAS");
  await page.keyboard.press("Space");
  assert.equal(await page.evaluate(() => window.delivered), 1);
  await page.evaluate(() => {
    const root = document.getElementById("host").shadowRoot;
    const input = document.createElement("input");
    input.className = "compose-backing-field";
    root.append(input);
    input.addEventListener("keydown", (event) => {
      event.preventDefault();
      setTimeout(() => input.remove(), 30);
    });
    input.focus();
  });
  await page.keyboard.press("Shift+Tab");
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.activeElement?.tagName === "CANVAS");
  await page.keyboard.press("Enter");
  assert.equal(await page.evaluate(() => window.delivered), 2);
  await page.evaluate(() => {
    const root = document.getElementById("host").shadowRoot;
    const input = document.createElement("input");
    input.className = "compose-backing-field";
    root.append(input);
    input.addEventListener("keydown", (event) => {
      event.preventDefault();
      document.getElementById("outside").focus();
      setTimeout(() => input.remove(), 30);
    });
    input.focus();
  });
  await page.keyboard.press("Tab");
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.querySelector("input") === null);
  assert.equal(await page.evaluate(() => document.activeElement.id), "outside");
  await page.evaluate(() => {
    const root = document.getElementById("host").shadowRoot;
    const input = document.createElement("input");
    input.className = "compose-backing-field";
    root.append(input);
    input.addEventListener("keydown", (event) => {
      event.preventDefault();
      document.dispatchEvent(new PointerEvent("pointerdown", { bubbles: true }));
      setTimeout(() => input.remove(), 30);
    });
    input.focus();
  });
  await page.keyboard.press("Shift+Tab");
  await page.waitForFunction(() => document.getElementById("host").shadowRoot.querySelector("input") === null);
  assert.equal(await page.evaluate(() => document.activeElement.tagName), "BODY");
});

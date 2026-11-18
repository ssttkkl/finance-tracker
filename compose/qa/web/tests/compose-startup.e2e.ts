import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, clickStableComposeTarget, installComposeApiFixtures } from "./compose-fixtures";

for (const variant of [
  { name: "desktop", viewport: { width: 1440, height: 900 }, colorScheme: "light", reducedMotion: "no-preference" },
  { name: "phone-reduced-motion", viewport: { width: 390, height: 844 }, colorScheme: "dark", reducedMotion: "reduce" },
] as const) {
  test(`startup logo hands over to the real Wasm app: ${variant.name}`, async ({ page }) => {
    const diagnostics = captureBrowserDiagnostics(page);
    await installComposeApiFixtures(page);
    await page.setViewportSize(variant.viewport);
    await page.emulateMedia({ colorScheme: variant.colorScheme, reducedMotion: variant.reducedMotion });
    let releaseScript!: () => void;
    let releaseWasm!: () => void;
    const scriptGate = new Promise<void>((resolve) => { releaseScript = resolve; });
    const wasmGate = new Promise<void>((resolve) => { releaseWasm = resolve; });
    let wasmRequested = false;
    await page.route("**/webApp.js", async (route) => {
      await scriptGate;
      await route.continue();
    });
    await page.route(/\.wasm(?:\?|$)/, async (route) => {
      wasmRequested = true;
      await wasmGate;
      await route.continue();
    });
    try {
      await page.goto("/", { waitUntil: "commit" });
      const startup = page.locator("#startup-screen");
      await expect(startup).toBeVisible();
      await expect(page.locator("#startup-logo")).toBeVisible();
      await page.screenshot({ path: `/tmp/finance-tracker-startup-${variant.name}.png` });
      releaseScript();
      await expect.poll(() => wasmRequested).toBe(true);
      await expect(startup).toBeVisible();
      releaseWasm();
      await expect(startup).toHaveCount(0);
      await expect(page.locator('[id="ledger.screen"]')).toBeVisible();
      await page.waitForLoadState("networkidle");
      await page.screenshot({ path: `/tmp/finance-tracker-startup-ready-${variant.name}.png` });
      await clickStableComposeTarget(page, page.locator('[id="ledger.more-filters"]'));
      await expect(page.locator('[id="ledger.filter.apply"]')).toBeVisible();
      await diagnostics.settle();
      expect(diagnostics.consoleErrors).toEqual([]);
      expect(diagnostics.failedRequests).toEqual([]);
      expect(diagnostics.staticHttpErrors).toEqual([]);
      expect(diagnostics.wasmContentTypes.length).toBeGreaterThan(0);
      expect(diagnostics.wasmContentTypes.every((type) => type.startsWith("application/wasm"))).toBe(true);
    } finally {
      releaseScript();
      releaseWasm();
    }
  });
}

for (const missing of ["wasm", "gc", "legacy-eh"] as const) {
  test(`unsupported browser shows upgrade requirements before downloading the app: ${missing}`, async ({ page }) => {
    const diagnostics = captureBrowserDiagnostics(page);
    await page.setViewportSize(missing === "gc" ? { width: 1440, height: 900 } : { width: 390, height: missing === "legacy-eh" ? 320 : 844 });
    await page.emulateMedia({ colorScheme: missing === "gc" ? "dark" : "light" });
    const appRequests: string[] = [];
    page.on("request", (request) => {
      if (/\/(webApp\.js|[^/]+\.wasm)(?:\?|$)/.test(request.url())) appRequests.push(request.url());
    });
    await page.addInitScript((missing) => {
      if (missing === "wasm") { Object.defineProperty(window, "WebAssembly", { value: undefined }); return; }
      const validate = WebAssembly.validate.bind(WebAssembly);
      WebAssembly.validate = (buffer) => {
        const bytes = [...new Uint8Array(buffer as ArrayBuffer)];
        if (missing === "gc" && bytes.includes(0x5f)) return false;
        if (missing === "legacy-eh" && bytes.includes(0x06) && bytes.includes(0x07)) return false;
        return validate(buffer);
      };
    }, missing);
    await page.goto("/");
    const panel = page.locator("#browser-unsupported");
    await expect(panel).toBeVisible();
    await expect(panel.getByRole("heading", { name: "请升级或更换浏览器" })).toBeVisible();
    await expect(page.locator("#startup-screen")).toBeHidden();
    await expect(panel).toContainText("Chrome");
    await expect(panel).toContainText("Firefox");
    await expect(panel).toContainText("iOS / iPadOS 18.2");
    await page.keyboard.press("Tab");
    await expect(panel.getByRole("link", { name: "浏览器兼容说明" })).toBeFocused();
    if (missing === "legacy-eh") {
      expect(await panel.evaluate((element) => element.scrollTop)).toBeGreaterThan(0);
      await panel.evaluate((element) => { element.scrollTop = 0; });
    }
    await page.screenshot({ path: `/tmp/finance-tracker-browser-unsupported-${missing}.png` });
    expect(appRequests).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]);
    expect(diagnostics.failedRequests).toEqual([]);
    expect(diagnostics.staticHttpErrors).toEqual([]);
  });
}

test("application download failures are not mislabeled as an incompatible browser", async ({ page }) => {
  let failed = false;
  await page.route("**/webApp.js", async (route) => { failed = true; await route.abort("failed"); });
  await page.goto("/");
  expect(failed).toBe(true);
  await expect(page.locator("#browser-unsupported")).toBeHidden();
  await expect(page.locator("#startup-screen")).toBeVisible();
});

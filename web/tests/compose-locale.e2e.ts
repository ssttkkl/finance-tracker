import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

test("Compose Resources follow browser language and fall back to Simplified Chinese", async ({ browser, baseURL }) => {
  if (!baseURL) throw new Error("Playwright baseURL is required for Compose locale checks");

  for (const [locale, expectedLabel] of [["en-US", "Sign in"], ["zh-CN", "登录"], ["fr-FR", "登录"]] as const) {
    const context = await browser.newContext({ locale, colorScheme: "light", viewport: { width: 390, height: 844 } });
    try {
      const page = await context.newPage();
      const diagnostics = captureBrowserDiagnostics(page);
      await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
      await page.goto(new URL("/", baseURL).toString());
      await expect(page.locator('[id="auth.submit"]').last()).toContainText(expectedLabel);

      await diagnostics.settle();
      expect(diagnostics.consoleErrors).toEqual([]);
      expect(diagnostics.failedRequests).toEqual([]);
      expect(diagnostics.staticHttpErrors).toEqual([]);
      expect(diagnostics.wasmContentTypes.length).toBeGreaterThan(0);
      expect(diagnostics.wasmContentTypes.every((value) => value.startsWith("application/wasm"))).toBe(true);
    } finally {
      await context.close();
    }
  }
});

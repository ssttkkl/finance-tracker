import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, typeIntoComposeInput } from "./compose-fixtures";

test("本地 Compose 注册请求经同源代理到达 FastAPI demo", async ({ page, baseURL }) => {
  if (!baseURL) throw new Error("Playwright baseURL is required for the live demo check");
  const diagnostics = captureBrowserDiagnostics(page);
  const email = `compose-kmp-qa-${Date.now()}@example.com`;
  const password = `Compose-QA-${Date.now()}-Password`;
  await page.goto(new URL("/", baseURL).toString());
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);

  const registrationResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === "/api/v1/auth/register" && response.request().method() === "POST";
  });
  await page.locator('[id="auth.toggle-mode"]').last().click({ force: true });
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', password);
  await page.locator('[id="auth.submit"]').last().click({ force: true });

  const response = await registrationResponse;
  expect(new URL(response.url()).origin).toBe(new URL(baseURL).origin);
  expect(response.status()).toBe(200);
  await expect(page.locator('[id="workspace.screen"], [id="ledger.screen"]').last()).toBeVisible({ timeout: 10_000 });

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  expect(diagnostics.wasmContentTypes.length).toBeGreaterThan(0);
  expect(diagnostics.wasmContentTypes.every((value) => value.startsWith("application/wasm"))).toBe(true);
});

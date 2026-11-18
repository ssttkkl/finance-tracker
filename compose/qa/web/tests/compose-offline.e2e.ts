import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, typeIntoComposeInput } from "./compose-fixtures";

// Requires the preview server's cross-origin isolation headers and a reachable Kotlin backend.
test("断网重启后从加密缓存浏览账本", async ({ page, baseURL }) => {
  test.setTimeout(180_000);
  const email = `offline-smoke-${Date.now()}@example.com`;
  const password = `Offline-Smoke-${Date.now()}-Password`;
  const failed: string[] = [];
  page.on("requestfailed", (request) => failed.push(`${request.method()} ${request.url()} ${request.failure()?.errorText}`));

  await page.goto(new URL("/", baseURL ?? "http://127.0.0.1:5187").toString());
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);
  await page.locator('[id="auth.toggle-mode"]').last().click({ force: true });
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', password);
  await typeIntoComposeInput(page, '[id="auth.confirm-password"]', password);
  await page.locator('[id="auth.submit"]').last().click({ force: true });
  await expect(page.locator('[id="workspace.screen"]')).toBeVisible({ timeout: 20_000 });
  await typeIntoComposeInput(page, '[id="workspace.create-name"]', "离线冒烟账本");
  await page.locator('[id="workspace.create"]').last().click({ force: true });
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible({ timeout: 20_000 });

  // An online reload restores the session and publishes the encrypted cache.
  await Promise.all([
    page.waitForResponse((response) => response.url().includes("/api/v1/ledger/version") && response.status() === 200),
    page.reload(),
  ]);
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible({ timeout: 20_000 });

  // Only API calls fail from here; the static application still loads.
  await page.route("**/api/**", (route) => route.abort("failed"));
  failed.length = 0;
  await page.reload();
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible({ timeout: 30_000 });
  await expect(page.locator('[id="auth.email"]')).toHaveCount(0);
  expect(failed.some((entry) => entry.includes("/api/v1/auth/session"))).toBe(true);
  expect(failed.every((entry) => entry.includes("/api/"))).toBe(true);
});

test("保存后刷新完整缓存，断网重启仍能看到新增收支", async ({ page, baseURL }, info) => {
  test.setTimeout(180_000);
  if (!baseURL) throw new Error("A local backend preview is required");
  await page.setViewportSize({ width: 1440, height: 1600 });
  const errors: string[] = [];
  const failed: string[] = [];
  const diagnostics = captureBrowserDiagnostics(page);
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("requestfailed", (request) => failed.push(`${new URL(request.url()).pathname}: ${request.failure()?.errorText}`));
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, {
    data: { email: `offline-write-${crypto.randomUUID()}@example.invalid`, password: `Offline-${crypto.randomUUID()}` },
  });
  expect(authentication.status()).toBe(200);
  const { access_token: token } = await authentication.json();
  const headers = { Authorization: `Bearer ${token}` };
  const workspace = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers, data: { name: "保存后离线核对" },
  });
  expect(workspace.status()).toBe(200);
  const account = await page.request.post(`${baseURL}/api/v1/accounts`, {
    headers: { ...headers, "Idempotency-Key": `offline-account-${crypto.randomUUID()}` },
    data: { name: "离线核对现金", type: "cash", currencies: ["CNY"] },
  });
  expect(account.status()).toBe(201);
  await page.addInitScript((value) => localStorage.setItem("finance-tracker:session-token", value), token);
  await page.goto(baseURL);
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible({ timeout: 30_000 });
  const initialVersion = await page.request.get(`${baseURL}/api/v1/ledger/version`, { headers });
  const { ledger_seq: before } = await initialVersion.json();
  const press = async (id: string) => {
    const target = page.locator(`[id="${id}"]:visible`).last();
    await target.click({ force: true });
  };
  await press("ledger.actions-fab");
  await press("ledger.add");
  await expect(page.locator('[id="cash.create.form"]')).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash.create.amount"]', "12.50");
  const refreshed = page.waitForResponse((response) => response.url().includes("/api/v1/ledger/snapshot") && response.status() === 200);
  await press("cash.create.save");
  const snapshot = await (await refreshed).json();
  expect(BigInt(snapshot.ledger_seq)).toBeGreaterThan(BigInt(before));
  expect(snapshot.facts.cash_transactions).toHaveLength(1);
  expect(snapshot.facts.cash_transactions[0].amount).toBe("-12.50");
  // Closing a Compose dialog can leave its exported DOM mirror stale. Check the rendered
  // result here and assert the actual saved row on the new offline page instance below.
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  await page.screenshot({ path: info.outputPath("saved-record-online.png") });
  await page.route("**/api/**", (route) => route.abort("failed"));
  await page.reload();
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible({ timeout: 30_000 });
  const row = page.locator('[id^="ledger.record-cash-entry:"]:visible');
  await expect(row.last()).toContainText("-12.5");
  await expect(row.last()).toContainText("CNY");
  await expect(row).toHaveCount(1);
  await page.screenshot({ path: info.outputPath("saved-record-offline.png") });
  expect(errors).toEqual([]);
  expect(failed.length).toBeGreaterThan(0);
  expect(failed.every((entry) => entry.startsWith("/api/v1/auth/session:"))).toBe(true);
  await info.attach("offline-write-network", { body: JSON.stringify({ failed, pageErrors: errors }, null, 2), contentType: "application/json" });
});

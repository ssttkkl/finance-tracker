import { expect, test } from "@playwright/test";
import { typeIntoComposeInput } from "./compose-fixtures";

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
    page.waitForResponse((response) => response.url().includes("/api/v1/ledger/snapshot") && response.status() === 200),
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

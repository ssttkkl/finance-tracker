import { test, expect } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics, clickStableComposeTarget } from "./compose-fixtures";

test("local ledger renders the committed snapshot expense and opens its detail", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  await page.goto("/w/workspace-1/");
  await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible();
  const row = page.locator('[id="ledger.record-cash-entry:2001"]:visible').last();
  await expect(row).toBeVisible();
  await expect(row).toContainText("咖啡店");
  await expect(row).toContainText("-28.5 CNY");
  await clickStableComposeTarget(page, row);
  const detail = page.locator('[id="cash.current.detail"]:visible').last();
  await expect(detail).toBeVisible();
  await expect(detail).toContainText("拿铁");
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

import { test, expect } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics, clickStableComposeTarget, buildLedgerSnapshot } from "./compose-fixtures";

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

test("local ledger filters pending drafts", async ({ page }) => {
  const snapshot = buildLedgerSnapshot("workspace-1", [
    { transactionId: "11", entryId: "1101", occurredAt: null, amount: null, currency: null,
      counterparty: "待补充的账单", note: "待核对金额与日期", state: "draft",
      issues: [{ code: "amount_unreadable", field_path: "amount" }] },
    { transactionId: "13", entryId: "1301", occurredAt: "2026-10-01T02:00:00Z", amount: "-20.00", currency: "CNY",
      counterparty: "已确认消费", note: "已核对的消费" },
  ]);
  await installComposeApiFixtures(page, { snapshot });
  await page.goto("/w/workspace-1/");
  await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible();
  const pending = page.locator('[id="ledger.record-cash-entry:1101"]:visible').last();
  await expect(pending).toBeVisible();
  await expect(page.locator('[id="ledger.record-cash-entry:1301"]:visible')).toHaveCount(1);
  await clickStableComposeTarget(page, page.locator('[id="ledger.filter.pending"]:visible').last());
  await expect(page.locator('[id="ledger.record-cash-entry:1301"]:visible')).toHaveCount(0);
  await expect(page.locator('[id="ledger.record-cash-entry:1101"]:visible').last()).toBeVisible();
});

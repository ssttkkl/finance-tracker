import { test, expect, type Page } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics, typeIntoComposeInput } from "./compose-fixtures";

async function press(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

test("imported entry edits only common fields and saves once in the current detail", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  const contextId = "a".repeat(43);
  const planId = `mp_${contextId}.${"b".repeat(16)}`;
  const source = { amount: "-12.5000", currency: "CNY", occurred_at: "2026-10-24T04:00:00Z", note: "原始备注" };
  let saved = false;
  await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
    items: [{ projection_id: "entry-11", record_id: "source-11", transaction_id: "11", ...source,
      economic_type: "expense", state: "posted", statistics_eligible: true, confirmation_status: "confirmed", attention_count: 0 }],
    start_index: 0, total_count: 1, items_before: 0, items_after: 0, month_positions: [],
    next_cursor: null, projection_version: saved ? 10 : 9, pending_item_count: 0,
    page_size: 20, filters: {}, filter_options: {}, monthly_summaries: [],
  } }));
  await page.route(/\/api\/v1\/cash-records\/11$/, route => route.fulfill({ json: {
    transaction_id: "11", state: "posted", row_revision: 3, record_origin: "import", confirmation_status: "confirmed",
    fields: source, editable_fields: [], issues: [], relations: [],
    entry: { entry_id: "public-11", revision: saved ? 8 : 7, fields: { note: saved ? "本次备注" : "公共备注" },
      manual_fields: ["note"], editable_fields: ["note"], supports_transfer_edit: false },
  } }));
  const previews: Record<string, unknown>[] = [];
  await page.route(/\/api\/v1\/cash-mutations\/plans$/, async route => {
    previews.push(route.request().postDataJSON());
    await route.fulfill({ json: { plan_id: planId, edit_context_id: contextId, plan_revision: 1,
      expires_at: "2099-01-01T00:00:00Z", summary: {}, item_counts: {}, can_commit: true,
      items: [], pages: { items: null }, invalidated_choices: [], additional_effects: [] } });
  });
  let commits = 0;
  await page.route(/\/api\/v1\/cash-mutations\/plans\/mp_[^/]+\/commit$/, async route => {
    commits++; saved = true;
    await route.fulfill({ json: { receipt_id: "receipt-entry", plan_id: planId, operation_kind: "edit_entry",
      committed: true, created_at: "2026-10-24T04:00:00Z", status: "completed", summary: {}, item_counts: {},
      retained_transaction_id: "11", pages: { items: null, source_issues: null, account_results: null } } });
  });
  await page.goto("/w/workspace-1/");
  await press(page, "ledger.record-entry-11"); await press(page, "cash.current.edit");
  await expect(page.locator('[id="cash.entry.editor"]:visible')).toHaveCount(1);
  for (const field of ["amount", "currency", "account_id", "occurred_at", "components", "record_type"]) {
    await expect(page.locator(`[id="cash.entry.${field}"]:visible`)).toHaveCount(0);
  }
  await typeIntoComposeInput(page, '[id="cash.entry.note"]', "本次备注", { replace: true });
  await page.mouse.click(1100, 43);
  await page.waitForTimeout(200);
  await expect(page.locator('[id="cash.entry.note"]:visible')).toContainText("本次备注");
  await press(page, "cash.entry.save");
  await expect(page.locator('[id="cash.entry.editor"]:visible')).toHaveCount(0);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("本次备注");
  expect(previews).toHaveLength(1); expect(commits).toBe(1);
  expect(previews[0]).toMatchObject({ intent: "edit_entry", transaction_id: "11", expected_revision: 3,
    entry_id: "public-11", expected_entry_revision: 7, patch: { note: "本次备注" } });
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]); expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

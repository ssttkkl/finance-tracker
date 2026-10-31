import { test, expect, type Page } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { installComposeApiFixtures, captureBrowserDiagnostics } from "./compose-fixtures";

async function press(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}
for (const width of [390, 768, 1440]) {
  test(`merged entry processes a source issue on another member at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : width === 768 ? 1024 : 900 });
    const diagnostics = captureBrowserDiagnostics(page);
    await installComposeApiFixtures(page);
    let committed = false;
    const projection = { projection_id: "group-one", record_id: "provider-11", transaction_id: "11", occurred_at: "2026-10-01T00:00:00Z",
      amount: "-10.00", currency: "CNY", economic_type: "expense", state: "posted", statistics_eligible: true,
      confirmation_status: "pending", attention_count: 1, member_count: 2, counterparty: "合并消费", note: "共同备注" };
    await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
      items: [{ ...projection, confirmation_status: committed ? "confirmed" : "pending", attention_count: committed ? 0 : 1 }],
      next_cursor: null, projection_version: committed ? 10 : 9, pending_item_count: committed ? 0 : 1,
      page_size: 20, start_index: 0, total_count: 1, filters: {}, filter_options: {}, monthly_summaries: [],
    } }));
    await page.route(/\/api\/v1\/cash-projections\/group-one$/, route => route.fulfill({ json: {
      projection_version: committed ? 10 : 9,
      members: [{ transaction_id: "11", row_revision: 3, confirmation_status: "confirmed" }, { transaction_id: "12", row_revision: 7, confirmation_status: committed ? "confirmed" : "pending" }],
    } }));
    await page.route(/\/api\/v1\/cash-records\/(11|12)$/, route => {
      const id = new URL(route.request().url()).pathname.split("/").at(-1)!;
      return route.fulfill({ json: { transaction_id: id, state: "posted", row_revision: id === "12" ? 7 : 3,
        record_origin: "import", confirmation_status: id === "12" && !committed ? "pending" : "confirmed",
        fields: { amount: "-10.00", currency: "CNY", occurred_at: "2026-10-01T00:00:00Z", counterparty: id === "12" ? "银行来源" : "合并消费", note: "来源备注" },
        entry: { entry_id: "entry-one", revision: 4, fields: { counterparty: "合并消费", note: "共同备注" }, manual_fields: [], editable_fields: ["note"], supports_transfer_edit: false },
        editable_fields: ["note"], relations: [], issues: id === "12" && !committed ? [{ issue_id: "31", revision: 4, code: "source_update_pending", message: "来源账单备注有更新",
          field_path: "note", blocking_scope: "posting", next_action: "open_record", allowed_resolutions: ["keep_existing"], pending_fields: { note: "账单新备注" }, pending_source_payload: null }] : [],
        projection_ref: { transaction_id: id, projection_id: "group-one", attention_count: committed ? 0 : 1, projection_version: committed ? 10 : 9 } } });
    });
    await page.route(/\/api\/v1\/evidence\/cash-projections\/group-one$/, route => route.fulfill({ json: { projection_version: 10, projection, members: [] } }));
    const contextId = "c".repeat(43); const planId = `mp_${contextId}.${"d".repeat(16)}`;
    let request: Record<string, unknown> | undefined; let commits = 0;
    await page.route(/\/api\/v1\/cash-mutations\/plans$/, async route => {
      request = route.request().postDataJSON();
      await route.fulfill({ json: { plan_id: planId, edit_context_id: contextId, plan_revision: 1, expires_at: "2099-01-01T00:00:00Z",
        summary: {}, item_counts: {}, can_commit: true, items: [], pages: { items: null }, invalidated_choices: [], additional_effects: [] } });
    });
    await page.route(/\/api\/v1\/cash-mutations\/plans\/mp_[^/]+\/commit$/, async route => {
      committed = true; commits++;
      await route.fulfill({ json: { receipt_id: "receipt-member", plan_id: planId, operation_kind: "resolve_issue", committed: true,
        created_at: "2026-10-24T00:00:00Z", status: "completed", summary: {}, retained_transaction_id: "12" } });
    });
    await page.goto("/w/workspace-1/");
    await press(page, "ledger.record-group-one");
    await expect(page.locator('[id="cash.current.detail"]:visible').last()).toContainText("来源账单备注有更新");
    await press(page, "cash.current.process-member-31");
    await expect(page.locator('[id="cash.issue.treatment-31"]:visible').last()).toContainText("账单新备注");
    await mkdir("/tmp/ft-compose-cash-evidence", { recursive: true });
    await page.screenshot({ path: `/tmp/ft-compose-cash-evidence/member-issue-review-${width}.png`, fullPage: true });
    await press(page, "cash.issue.keep_existing");
    await expect.poll(() => commits).toBe(1);
    expect(request?.intent).toBe("resolve_issue"); expect(request?.transaction_id).toBe("12"); expect(request?.expected_revision).toBe(7);
    expect(request?.issue_resolutions).toEqual([{ issue_id: "31", expected_issue_revision: 4, resolution: "keep_existing" }]);
    await expect(page.locator('[id="cash.current.pending-member-31"]:visible')).toHaveCount(0);
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]); expect(diagnostics.failedRequests).toEqual([]); expect(diagnostics.staticHttpErrors).toEqual([]);
    await mkdir("/tmp/ft-compose-cash-evidence", { recursive: true });
    await page.screenshot({ path: `/tmp/ft-compose-cash-evidence/member-issue-saved-${width}.png`, fullPage: true });
  });
}

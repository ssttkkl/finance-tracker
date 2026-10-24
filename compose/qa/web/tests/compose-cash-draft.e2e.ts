import { test, expect, type Page } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { installComposeApiFixtures, captureBrowserDiagnostics, typeIntoComposeInput } from "./compose-fixtures";

async function press(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

const contextId = "a".repeat(43);
const planId = `mp_${contextId}.${"b".repeat(16)}`;

for (const width of [390, 768, 1440]) {
  test(`draft save rejects stale data without replay or unknown-result UI at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : width === 768 ? 1024 : 900 });
    const diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [409] });
    await installComposeApiFixtures(page);
    const row = { projection_id: "draft-11", record_id: "source-11", transaction_id: "11", occurred_at: null, amount: "-10.00", currency: "CNY",
      economic_type: null, state: "draft", statistics_eligible: false, confirmation_status: "pending", attention_count: 1,
      counterparty: "待补充账单", note: "当前备注" };
    await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
      items: [row], next_cursor: null, projection_version: 9, pending_item_count: 1,
      page_size: 20, filters: {}, filter_options: {}, monthly_summaries: [],
    } }));
    let recordReads = 0;
    await page.route(/\/api\/v1\/cash-records\/11$/, route => { recordReads++; return route.fulfill({ json: {
      transaction_id: "11", state: "draft", row_revision: recordReads === 1 ? 3 : 4, record_origin: "import", confirmation_status: "pending",
      fields: { amount: "-10.00", currency: "CNY", occurred_at: null, note: recordReads === 1 ? "当前备注" : "更新后的备注" },
      editable_fields: ["amount", "note"], issues: [], relations: [],
    } }); });
    const previews: Record<string, unknown>[] = [];
    await page.route(/\/api\/v1\/cash-mutations\/plans$/, async route => {
      previews.push(route.request().postDataJSON());
      await route.fulfill({ json: { plan_id: planId, edit_context_id: contextId, plan_revision: 1,
        expires_at: "2099-01-01T00:00:00Z", summary: {}, item_counts: {}, can_commit: true,
        items: [], pages: { items: null }, invalidated_choices: [], additional_effects: [],
      } });
    });
    let commits = 0;
    let resolutions = 0;
    await page.route(/\/api\/v1\/cash-mutations\/plans\/mp_[^/]+\/commit$/, async route => {
      commits++;
      await route.fulfill({ status: 409, json: { error: { code: "record_changed", message: "流水已变化，请刷新后重试。" } } });
    });
    await page.route(/\/api\/v1\/cash-mutations\/resolve$/, async route => {
      resolutions++;
      await route.fulfill({ json: { status: "not_committed" } });
    });
    await page.goto("/w/workspace-1/");
    await press(page, width === 390 ? "ledger.record-draft-11" : "ledger.open-record");
    await press(page, "cash.current.process-draft");
    await expect(page.locator('[id="cash.draft.editor"]:visible').last()).toBeVisible();
    await typeIntoComposeInput(page, '[id="cash.draft.note"]', "本次备注", { replace: true });
    // Reopening the same warning must preserve the form, rather than unmount it.
    await press(page, "cash.current.process-draft");
    await press(page, "cash.draft.save");
    await expect.poll(() => commits).toBe(1);
    await expect(page.locator('[id="cash.current.error"]:visible').last()).toContainText("重试");
    await expect(page.locator('[id="cash.current.resolve"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="cash.current.retry-original"]:visible')).toHaveCount(0);
    expect(previews).toHaveLength(1);
    expect(previews[0].intent).toBe("save_draft");
    expect(previews[0].patch).toEqual({ note: "本次备注" });
    await page.waitForTimeout(200);
    expect(commits).toBe(1);
    expect(resolutions).toBe(0);
    await mkdir("/tmp/ft-compose-cash-evidence", { recursive: true });
    await page.screenshot({ path: `/tmp/ft-compose-cash-evidence/draft-rejected-${width}.png`, fullPage: true });
    const retry = page.getByRole("button", { name: "重试", exact: true });
    const retryBox = await retry.boundingBox();
    if (!retryBox) throw new Error("Missing retry button");
    await page.mouse.click(retryBox.x + retryBox.width / 2, retryBox.y + retryBox.height / 2);
    await expect.poll(() => recordReads).toBe(2);
    await expect(page.locator('[id="cash.current.error"]:visible')).toHaveCount(0);
    await page.waitForTimeout(300);
    await page.screenshot({ path: `/tmp/ft-compose-cash-evidence/draft-refreshed-${width}.png`, fullPage: true });
    await writeFile(`/tmp/ft-compose-cash-evidence/draft-refreshed-semantics-${width}.txt`, await page.locator("body").ariaSnapshot());
    // The actual disabled control must not submit even if its accessibility state is missing.
    await press(page, "cash.draft.save");
    await page.waitForTimeout(200);
    expect(commits).toBe(1);
    expect(previews).toHaveLength(1);
    expect(resolutions).toBe(0);
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]);
    expect(diagnostics.failedRequests).toEqual([]);
    expect(diagnostics.staticHttpErrors).toEqual([]);
    // Keep the disabled-state accessibility regression visible.
    await expect(page.locator('[id="cash.draft.save"]:visible').last()).toBeDisabled();
  });
}

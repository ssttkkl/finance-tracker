import { test, expect, type Page } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics } from "./compose-fixtures";

async function press(page: Page, id: string) {
  await page.mouse.move(1340, 650); await page.mouse.wheel(0, -5000); await page.waitForTimeout(200);
  const target = page.locator(`[id="${id}"]:visible`).last();
  for (let attempt = 0; attempt < 24; attempt++) {
    const box = await target.boundingBox({ timeout: 200 }).catch(() => null);
    if (box && box.y > 64 && box.y + box.height < 880) {
      await page.waitForTimeout(200);
      await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2); return;
    }
    await page.mouse.move(1340, 650); await page.mouse.wheel(0, box && box.y < 64 ? -320 : 320); await page.waitForTimeout(150);
  }
  throw new Error(`Missing visible ${id}`);
}

test("single relation candidate shows every actual member and invalidated choices require reinspection", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  const contextId = "a".repeat(43);
  const planId = `mp_${contextId}.${"b".repeat(16)}`;
  const fields = (id: string) => ({ amount: "-100.00", currency: "CNY", account_id: "1", occurred_at: "2026-10-24T04:00:00Z",
    counterparty: id === "11" ? "本笔" : `候选成员${id}`, note: "" });
  let reads = 0;
  await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
    items: [{ projection_id: "relation-11", transaction_id: "11", record_id: "11", ...fields("11"), economic_type: "expense",
      state: "posted", confirmation_status: "pending", statistics_eligible: true, attention_count: 1 }],
    start_index: 0, total_count: 1, items_before: 0, items_after: 0, month_positions: [],
    next_cursor: null, projection_version: 9, pending_item_count: 1, page_size: 20, filters: {}, filter_options: {}, monthly_summaries: [],
  } }));
  await page.route(/\/api\/v1\/cash-records\/(11|21|22)$/, route => {
    const id = new URL(route.request().url()).pathname.split("/").at(-1)!;
    if (id === "11") reads++;
    return route.fulfill({ json: { transaction_id: id, state: "posted", row_revision: 3, record_origin: "import",
      confirmation_status: id === "11" ? "pending" : "confirmed", fields: fields(id), editable_fields: [], issues: [],
      relations: id === "11" ? [{ relation_id: "7", kind: "payment_mirror", state: "draft", active: true, row_revision: 1 }] : [] } });
  });
  const previews: Record<string, unknown>[] = [];
  await page.route(/\/api\/v1\/cash-mutations\/plans$/, async route => {
    const input = route.request().postDataJSON(); previews.push(input);
    const invalidated = previews.length === 2;
    await route.fulfill({ json: { plan_id: planId, edit_context_id: contextId, plan_revision: previews.length,
      expires_at: "2099-01-01T00:00:00Z", summary: {}, item_counts: {}, can_commit: true,
      items: [{ preview_item_id: "one", confirmation_status: "pending", anticipated_state: "posted", display: { ...fields("11"), accounts: ["日常账户"] },
        statistics_eligible: true, member_refs: [{ transaction_id: "11" }], attention_reasons: [], detail_available: true }],
      pages: { items: null }, additional_effects: [], invalidated_choices: invalidated ? [{ kind: "relation", reference: "group", code: "choice_invalid" }] : [] } });
  });
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/items\/one$/, route => route.fulfill({ json: {
    preview_item_id: "one", members: [{ transaction_id: "11", planned_action: "unchanged", fields: fields("11"), editable_fields: [], allowed_actions: [], row_revision: 3 }],
    field_diffs: [], issues: [], relation_effects: [], relation_choices: [{ suggestion_id: "group", kind: "payment_mirror", action: "defer",
      member_refs: [{ transaction_id: "11" }], candidates: [{ candidate_id: "candidate", member_refs: [{ transaction_id: "21" }, { transaction_id: "22" }] }],
      allowed_actions: ["accept", "defer", "ignore"], common_fields: {}, common_field_conflicts: {} }],
  } }));
  let commits = 0;
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/commit$/, async route => { commits++; await route.abort(); });
  await page.goto("/w/workspace-1/");
  await press(page, "ledger.open-record"); await press(page, "cash.current.process-relations");
  await expect(page.locator('[id="cash.relations.treatment"]:visible')).toHaveCount(1);
  await press(page, "cash.relation.action-group-accept");
  for (const id of ["21", "22"]) {
    const member = page.locator(`[id="cash.relation.member-candidate-${id}"]:visible`);
    await expect(member).toContainText(`候选成员${id}`); await expect(member).toContainText("日常账户"); await expect(member).toContainText("-100.00 CNY");
  }
  await press(page, "cash.relations.save");
  await expect(page.locator('[id="cash.current.error"]:visible')).toHaveCount(1);
  await expect(page.locator('[id="cash.relations.treatment"]:visible')).toHaveCount(0);
  expect(commits).toBe(0);
  expect(previews[1].relation_choices).toEqual([{ suggestion_id: "group", action: "accept", candidate_id: "candidate" }]);
  const retry = page.locator('[id="cash.current.error"]').getByRole("button");
  const box = await retry.boundingBox(); if (!box) throw new Error("Missing retry");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await expect.poll(() => reads).toBe(2);
  await press(page, "cash.current.process-relations");
  await expect(page.locator('[id="cash.relations.treatment"]:visible')).toHaveCount(1);
  expect(previews[2].relation_choices).toEqual([]);
  expect(previews[2]).not.toHaveProperty("previous_plan_id");
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]); expect(diagnostics.failedRequests).toEqual([]); expect(diagnostics.staticHttpErrors).toEqual([]);
});

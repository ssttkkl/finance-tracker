import { test, expect, type Page, type Locator } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics } from "./compose-fixtures";

const byId = (page: Page, id: string) => page.locator(`[id="${id}"]:visible`).last();
async function press(page: Page, locator: Locator) {
  await expect(locator).toBeVisible();
  for (let i = 0; i < 20; i++) {
    const box = await locator.boundingBox();
    if (box && box.y >= 0 && box.y + box.height < (page.viewportSize()?.height ?? 1000) - 1) {
      await page.waitForTimeout(150);
      const settled = await locator.boundingBox();
      if (!settled || Math.abs(settled.y - box.y) > 1 || Math.abs(settled.x - box.x) > 1) continue;
      await page.mouse.click(settled.x + settled.width / 2, settled.y + settled.height / 2);
      await page.waitForTimeout(350);
      return;
    }
    await page.mouse.move(Math.min(1100, (page.viewportSize()?.width ?? 1440) - 30), 720); await page.mouse.wheel(0, 280); await page.waitForTimeout(100);
  }
  throw new Error("Control is outside the scrollable viewport");
}

for (const { count, width } of [{ count: 0, width: 1440 }, { count: 0, width: 390 }, { count: 1, width: 1440 }, { count: 2, width: 1440 }, { count: 1, width: 390 }]) {
  test(`F-04 关联建议 ${count} 个备选 ${width}：方案单选、按需搜索和返回`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1000 });
    await installComposeApiFixtures(page);
    const current = record("record-1", "1001", "当前商户");
    const relationKind = count === 0 && width === 390 ? "transfer_pair" : "refund_offset";
    current.fields.record_type = relationKind === "transfer_pair" ? "transfer_out" : "refund";
    current.relations = [{ relation_id: "draft", kind: relationKind, state: "draft", active: true, row_revision: 1 }];
    const targets = [record("record-2", "1002", "建议商户一"), record("record-3", "1003", "建议商户二")];
    Object.assign(targets[0].fields, { source_type: "alipay", counterparty_account: "merchant@example.test", counterparty_account_attrs: ["full"] });
    const other = record("record-4", "1004", "备选外商户");
    const records = [current, ...targets, other];
    await page.route(/\/api\/v1\/cash-records\/record-[1-4]$/, route => route.fulfill({ json: records.find(item => route.request().url().endsWith(item.transaction_id)) }));
    let searches = 0;
    await page.route(/\/api\/v1\/cash-records\/record-1\/association-targets(?:\?.*)?$/, route => {
      searches++;
      return route.fulfill({ json: { items: [{ ...other, component_choices: [{anchor_component_id:"1001",target_component_id:"1004"}], unavailable_reason:null }], next_cursor: null } });
    });
    let conflict = false;
    let resolved = false;
    const planId = `mp_${"a".repeat(43)}.${"b".repeat(16)}`;
    await page.route(/\/api\/v1\/cash-mutations\/plans$/, route => {
      const choice = route.request().postDataJSON()?.relation_choices?.find((item: { action: string }) => item.action === "accept");
      resolved = choice?.common_fields?.note === "";
      conflict = !!choice && !resolved;
      return route.fulfill({ json: {
      plan_id: planId, edit_context_id: "a".repeat(43), plan_revision: 1, expires_at: "2099-01-01T00:00:00Z",
      summary: {}, item_counts: {}, can_commit: !conflict, additional_effects: resolved ? [{ kind: "relation_changed", reference: "fixture" }] : [], invalidated_choices: [], pages: { items: null },
      items: [{ preview_item_id: "one", confirmation_status: "pending", anticipated_state: "posted", display: { ...current.fields, accounts: ["日常账户"] },
        statistics_eligible: true, member_refs: [{ transaction_id: "record-1" }], attention_reasons: [], detail_available: true }],
    } }); });
    await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/items\/one$/, route => route.fulfill({ json: {
      preview_item_id: "one", members: [{ transaction_id: "record-1", planned_action: "unchanged", fields: current.fields, editable_fields: [], allowed_actions: [], row_revision: 1 }],
      field_diffs: [], issues: [], relation_effects: [], relation_choices: [{ suggestion_id: "suggestion", kind: relationKind, action: resolved ? "accept" : "defer",
        member_refs: [{ transaction_id: "record-1" }], allowed_actions: count > 0 ? ["accept", "ignore", "defer"] : ["ignore", "defer"],
        candidates: targets.slice(0, count).map(target => ({ candidate_id: target.transaction_id, member_refs: [{ transaction_id: "record-1" }, { transaction_id: target.transaction_id }] })),
        relations: count === 2 ? [{ kind: "refund_offset", member_refs: [{ transaction_id: "record-1" }, { transaction_id: "record-2" }] }, { kind: "payment_mirror", member_refs: [{ transaction_id: "record-1" }, { transaction_id: "record-3" }] }] : [],
        common_fields: resolved ? { note: "" } : {}, common_field_conflicts: conflict ? { note: ["人工甲", "人工乙"] } : {} }],
    } }));
    await page.goto("/w/workspace-1/");
    const row = byId(page, "ledger.record-projection-1");
    await press(page, row);
    const banner = byId(page, "cash.current.pending-relations");
    await expect(banner).toContainText("关系待确认");
    await expect(banner).toContainText("这笔收支有等待确认的关联");
    const suggestion = byId(page, "cash.current.suggestion-suggestion");
    await expect(suggestion).toBeVisible();
    await expect(banner.getByRole("button")).toHaveCount(0);
    await expect(banner).not.toContainText("查看关系");
    await page.screenshot({ path: info.outputPath("pending-relations.png") });

    if (count === 0) await expect(suggestion).toContainText("请搜索");
    if (count === 1) {
      await expect(suggestion).toContainText("建议商户一");
      await expect(suggestion).toContainText("28.50 CNY");
      await expect(suggestion).toContainText("日常账户");
      await expect(suggestion).toContainText("消费");
      await expect(suggestion).toContainText("收支分类");
      await expect(suggestion).toContainText("餐饮 / 咖啡");
      await expect(suggestion).toContainText("支付宝");
      await expect(suggestion).toContainText("merchant@example.test");
      await expect(suggestion).toContainText("备注");
      await expect(suggestion).toContainText("合成账单");
    }
    if (count === 2) {
      await expect(suggestion).toContainText("有 2 套备选方案");
      await expect(suggestion).toContainText("同笔支付");
    }
    await page.screenshot({ path: info.outputPath(`suggestions-${count}-actions.png`) });
    const title = count === 1 ? "接受建议" : "核对建议";
    const rejectBox = await suggestion.getByText("拒绝建议", { exact: true }).boundingBox();
    const acceptBox = await suggestion.getByText(title, { exact: true }).boundingBox();
    expect(rejectBox!.x + rejectBox!.width).toBeLessThan(acceptBox!.x);
    expect(Math.abs(rejectBox!.y - acceptBox!.y)).toBeLessThan(2);
    const reopen = acceptBox;
    await press(page, suggestion.getByText(title, { exact: true }));
    const dialog = byId(page, "cash.association.dialog");
    await expect(dialog.getByText(title, { exact: true })).toBeVisible();
    if (count > 0) {
      await expect(byId(page, "cash.association.search")).toHaveCount(0);
      await expect(dialog).not.toContainText("备选外商户");
      await expect(dialog.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(0);
      if (count === 1) { await expect(byId(page, "cash.association.single-selected")).toBeVisible(); await expect(dialog.locator('[id^="cash.association.option-"]')).toHaveCount(0); }
      for (const target of targets.slice(0, count)) await expect(dialog).toContainText(target.fields.counterparty);
      await page.waitForTimeout(400);
      expect(searches).toBe(0);
      await page.screenshot({ path: info.outputPath("suggestions-only.png") });
      if (count === 2) await press(page, byId(page, "cash.association.option-record-3"));
      await press(page, byId(page, "cash.association.choose-other"));
    } else {
      await expect(dialog).toContainText("请搜索");
      await expect(byId(page, "cash.association.back-suggestions")).toHaveCount(0);
    }
    await expect(byId(page, "cash.association.search")).toBeVisible();
    await expect(dialog).toContainText("备选外商户");
    await expect.poll(() => searches).toBeGreaterThan(0);
    for (const target of targets.slice(0, count)) await expect(dialog).not.toContainText(target.fields.counterparty);
    await press(page, byId(page, "ledger.record-radio-record-4"));
    // Compose's selected state is painted asynchronously and is not exported
    // as aria-checked; let the selection frame render before absence checks.
    await page.waitForTimeout(350);
    for (const label of ["同笔支付", "退款冲销", "个人转账", "普通转账", "信用账户还款", "换汇", "跨币种汇款"]) await expect(dialog.getByText(label, { exact: true })).toHaveCount(0);
    if (count === 0) {
      const added = page.waitForRequest(request => request.method() === "POST" && request.url().endsWith("/cash-mutations/plans") && request.postDataJSON()?.relation_additions?.length > 0);
      await press(page, byId(page, "cash.association.continue"));
      expect((await added).postDataJSON().relation_additions[0]).toMatchObject({ target_transaction_id: "record-4", kind: relationKind });
      return;
    }
    if (count > 0) {
      await page.screenshot({ path: info.outputPath("manual-search.png") });
      await press(page, byId(page, "cash.association.back-suggestions"));
      await expect(byId(page, "cash.association.search")).toHaveCount(0);
      await expect(dialog).not.toContainText("备选外商户");
    }
    await page.screenshot({ path: info.outputPath(`suggestions-${count}.png`) });
    if (count === 2) {
      const accepted = page.waitForRequest(request => request.method() === "POST" && request.url().endsWith("/cash-mutations/plans") && request.postDataJSON()?.relation_choices?.some((item: { action: string }) => item.action === "accept"));
      await press(page, byId(page, "cash.association.continue"));
      const body = (await accepted).postDataJSON();
      expect(body.relation_choices[0].candidate_id).toBe("record-3");
      expect(body.relation_additions ?? []).toEqual([]);
      return;
    }
    if (count === 1 && width === 1440) {
      await expect(byId(page, "cash.association.continue")).toBeEnabled();
      const acceptedRequest = page.waitForRequest(request => request.method() === "POST" && request.url().endsWith("/cash-mutations/plans") && request.postDataJSON()?.relation_choices?.some((item: { action: string }) => item.action === "accept"));
      await press(page, byId(page, "cash.association.continue"));
      await acceptedRequest;
      await expect(byId(page, "cash.association.dialog")).toContainText("选择关联后保留的信息");
      await expect(byId(page, "cash.association.search")).toHaveCount(0);
      const premature: string[] = [];
      const trackPreview = (request: import("@playwright/test").Request) => { if (request.method() === "POST" && request.url().endsWith("/cash-mutations/plans")) premature.push(request.url()); };
      page.on("request", trackPreview);
      await press(page, byId(page, "cash.association.continue"));
      await page.waitForTimeout(200);
      expect(premature).toEqual([]);
      page.off("request", trackPreview);
      await press(page, byId(page, "cash.association.common-note-clear"));
      const resolvedRequest = page.waitForRequest(request => request.method() === "POST" && request.url().endsWith("/cash-mutations/plans") && request.postDataJSON()?.relation_choices?.some((item: { common_fields?: { note: string } }) => item.common_fields?.note === ""));
      await page.screenshot({ path: info.outputPath("common-conflict-clear.png") });
      await press(page, byId(page, "cash.association.continue"));
      const body = (await resolvedRequest).postDataJSON();
      expect(body.relation_choices[0]).toMatchObject({ action: "accept", candidate_id: "record-2", common_fields: { note: "" } });
      await expect(byId(page, "cash.association.continue")).toContainText("确认保存");
      await expect(byId(page, "cash.association.continue")).toBeEnabled();
      await expect(byId(page, "cash.association.dialog")).toContainText("保存后");
      await press(page, byId(page, "cash.association.back-preview"));
      await expect(byId(page, "cash.association.choose-other")).toBeVisible();
      await expect(byId(page, "cash.association.single-selected")).toBeVisible();
      await page.screenshot({ path: info.outputPath("conflict-return-restored.png") });
      return;
    }
    await press(page, dialog.getByText("取消", { exact: true }));
    // Compose Web retains the dismissed Dialog's semantics until the next
    // overlay update. Verify the exposed underlying action by a real click.
    await page.waitForTimeout(300);
    await page.screenshot({ path: info.outputPath(`suggestions-${count}-cancelled.png`) });
    await page.mouse.click(reopen!.x + reopen!.width / 2, reopen!.y + reopen!.height / 2);
    await expect(byId(page, "cash.association.dialog").getByText(title, { exact: true })).toBeVisible();

  });
}

test("F-04 多成员原文：卡头同行标签、完整原文和仅对侧关联", async ({ page }, info) => {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await installComposeApiFixtures(page, { cashProjectionOverrides: { member_count: 2, projection_version: 1 } });
  const records = [record("record-1", "1001", "当前商户"), record("record-2", "1002", "关联商户")].map(item => ({
    ...item, relations: [{ relation_id: "relation", kind: "payment_mirror", state: "confirmed", active: true, row_revision: 1 }],
    projection_ref: { transaction_id: item.transaction_id, projection_id: "projection-1", projection_version: 1, attention_count: 0 },
  }));
  await page.route(/\/api\/v1\/cash-records\/record-[12]$/, route => route.fulfill({ json: records.find(item => route.request().url().endsWith(item.transaction_id)) }));
  await page.route(/\/api\/v1\/cash-projections\/projection-1$/, route => route.fulfill({ json: { projection_version: 1, members: records.map(item => ({ transaction_id: item.transaction_id, row_revision: 1, confirmation_status: "confirmed" })) } }));
  await page.route(/\/api\/v1\/evidence\/cash-projections\/projection-1$/, route => route.fulfill({ json: {
    projection_version: 1, projection: { projection_id: "projection-1", record_id: "record-1", transaction_id: "record-1", occurred_at: records[0].fields.occurred_at,
      amount: "-28.50", currency: "CNY", economic_type: "expense", counterparty: "当前商户", member_count: 2, projection_version: 1 },
    members: records.map(item => ({ id: item.transaction_id, row_revision: 1, ...item.fields, components: [] })),
  } }));
  await page.goto("/w/workspace-1/");
  await press(page, byId(page, "ledger.record-projection-1"));
  const body = byId(page, "cash.current.source-body-record-1");
  await expect(body).toBeVisible();
  await expect(byId(page, "cash.current.pending-relations")).toHaveCount(0);
  await expect(byId(page, "cash.current.associations")).not.toContainText("当前商户");
  await expect(byId(page, "cash.current.associations")).toContainText("关联商户");
  await expect(byId(page, "cash.current.associated-record-2")).toContainText("备注");
  const name = await body.getByText("当前商户", { exact: true }).first().boundingBox();
  const tag = await body.getByText("当前流水", { exact: true }).boundingBox();
  expect(Math.abs(name!.y - tag!.y)).toBeLessThan(12);
  await expect(body).toContainText("fixture-order");
  await expect(byId(page, "cash.current.detail")).toContainText(/关联商户\s*同笔支付/);
  await page.screenshot({ path: info.outputPath("source-headers.png") });
});
function record(id: string, component: string, counterparty: string) {
  return { transaction_id: id, record_origin: "import", state: "posted", row_revision: 1, confirmation_status: "confirmed",
    fields: { amount: "-28.50", currency: "CNY", occurred_at: "2026-09-24T10:30:00+08:00", account_id: "1", account_name: "日常账户",
      counterparty, note: "合成账单", record_type: "consumption", category_id: "coffee", source_payload: { 交易对方: counterparty, 原始单号: "fixture-order" },
      components: [{ component_id: component, amount: "-28.50", currency: "CNY", account_id: "1", ordinal: 0 }] },
    entry: { entry_id: "entry-" + id, revision: 1, fields: {}, manual_fields: [], editable_fields: ["note"], supports_transfer_edit: false },
    editable_fields: [], issues: [], relations: [], projection_ref: null };
}
for (const width of [1440, 390]) {
  test(`F-04 新详情 ${width}：正文原文及复用流水选择弹窗`, async ({ page }, info) => {
    const diagnostics = captureBrowserDiagnostics(page);
    await installComposeApiFixtures(page);
    const current = record("record-1", "1001", "咖啡店");
    const target = record("record-2", "1002", "备选外咖啡店");
    await page.route(/\/api\/v1\/cash-records\/record-1$/, r => r.fulfill({ json: current }));
    await page.route(/\/api\/v1\/cash-records\/record-1\/association-targets(?:\?.*)?$/, r => r.fulfill({ json: { items: [{ ...target, component_choices: [{anchor_component_id:"1001",target_component_id:"1002"}], unavailable_reason:null }], next_cursor: null } }));
    await page.route(/\/api\/v1\/cash-mutations\/plans$/, r => r.fulfill({ json: {
      plan_id: `mp_${"a".repeat(43)}.${"b".repeat(16)}`, edit_context_id: "a".repeat(43), plan_revision: 1, expires_at: "2099-01-01T00:00:00Z",
      summary: {}, item_counts: { all: 0, confirmed: 0, pending: 0 }, can_commit: true, items: [], pages: { items: null }, invalidated_choices: [],
      additional_effects: r.request().postDataJSON()?.relation_additions?.length ? [{ kind: "relation_changed", reference: "added-relation" }] : [],
    } }));
    await page.setViewportSize({ width, height: 1000 });
    await page.goto("/w/workspace-1/");
    const row = byId(page, "ledger.record-projection-1");
    await expect(row).toBeVisible();
    await press(page, row);
    await expect(byId(page, "cash.current.pending-relations")).toHaveCount(0);
    const detail = byId(page, "cash.current.detail");
    await expect(detail).toContainText("账单原文");
    await expect(detail).toContainText("fixture-order");
    await expect(page.locator('[id="cash.current.sources-toggle"]')).toHaveCount(0);
    await expect(page.locator('[id="cash.current.associations-toggle"]')).toHaveCount(0);
    await expect(byId(page, "cash.current.add-association")).toContainText("查找流水并关联");
    const reopen = await byId(page, "cash.current.add-association").boundingBox();
    await press(page, byId(page, "cash.current.add-association"));
    const dialog = byId(page, "cash.association.dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("关联流水", { exact: true })).toBeVisible();
    const relationTypes = byId(page, "cash.association.relation-types");
    await expect(relationTypes).toBeVisible();
    const typeBounds = await relationTypes.boundingBox();
    const searchBounds = await byId(page, "cash.association.search").boundingBox();
    expect(typeBounds!.y + typeBounds!.height).toBeLessThanOrEqual(searchBounds!.y);
    for (const label of ["同笔支付", "退款冲销", "个人转账"]) await expect(relationTypes.getByText(label, { exact: true })).toBeVisible();
    const chosenKind = width < 600 ? "transfer_pair" : "refund_offset";
    await press(page, byId(page, `cash.association.kind-${chosenKind}`));
    await expect(dialog).toContainText("备选外咖啡店");
    await expect(byId(page, "ledger.record-record-2")).toBeVisible();
    const bounds = await dialog.boundingBox();
    expect(bounds).not.toBeNull();
    if (width >= 600) {
      await expect(dialog.locator('[id="ledger.table-header"]')).toBeVisible();
      expect(bounds!.width).toBeGreaterThan(900);
      const filters = await Promise.all(["account", "amount", "date"].map(key => byId(page, `cash.association.filter-${key}`).boundingBox()));
      expect(Math.max(...filters.map(box => box!.y)) - Math.min(...filters.map(box => box!.y))).toBeLessThan(2);
      await expect(byId(page, "ledger.record-record-2")).toContainText("-28.50 CNY");
    } else {
      await expect(dialog.locator('[id="ledger.table-header"]')).toHaveCount(0);
      expect(bounds!.y).toBeLessThanOrEqual(1);
      expect(bounds!.height).toBeGreaterThanOrEqual(999);
    }
    await page.screenshot({ path: info.outputPath(`association-picker-${width}.png`) });
    await press(page, byId(page, "ledger.record-radio-record-2"));
    await expect(byId(page, "cash.association.continue")).toBeEnabled();
    // Compose Web does not expose SegmentedButton's selected property in its
    // DOM accessibility tree. Verify the real selection in the preview input.
    const submitted = page.waitForRequest(request => request.method() === "POST" && request.url().endsWith("/cash-mutations/plans") && request.postDataJSON()?.relation_additions?.length > 0);
    await press(page, byId(page, "cash.association.continue"));
    expect((await submitted).postDataJSON().relation_additions[0].kind).toBe(chosenKind);
    await page.waitForTimeout(300);
    await press(page, dialog.getByText("取消", { exact: true }));
    await page.waitForTimeout(300);
    await page.screenshot({ path: info.outputPath(`association-picker-${width}-cancelled.png`) });
    await page.mouse.click(reopen!.x + reopen!.width / 2, reopen!.y + reopen!.height / 2);
    await expect(byId(page, "cash.association.dialog").getByText("关联流水", { exact: true })).toBeVisible();
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]);
    expect(diagnostics.failedRequests).toEqual([]);
  });
}

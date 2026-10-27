import { expect, test, type Page, type Locator } from "@playwright/test";
import { activeSession, captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

const byId = (page: Page, id: string) => page.locator(`[id="${id}"]:visible`).last();
async function press(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  const bounds = await target.boundingBox();
  if (!bounds) throw new Error("Missing current cash control bounds");
  await page.waitForTimeout(200);
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
}
async function openDetail(page: Page, width: number) {
  await page.goto("/w/workspace-1/");
  const row = byId(page, "ledger.record-projection-1");
  await expect(row).toBeVisible();
  await press(page, width < 600 ? row : row.locator('[id="ledger.open-record"]').last());
  await expect(byId(page, "cash.current.detail")).toBeVisible();
}
function currentRecord(note = "拿铁", counterparty = "咖啡店") {
  return { transaction_id: "record-1", state: "posted", row_revision: 1, record_origin: "import", confirmation_status: "confirmed",
    fields: { amount: "-28.50", currency: "CNY", occurred_at: "2026-09-24T10:30:00+08:00", record_type: "consumption", account_id: "1", category_id: "coffee", note, counterparty },
    editable_fields: [], issues: [], relations: [], entry: { entry_id: "entry-1", revision: 1,
      fields: { note, counterparty }, manual_fields: [], editable_fields: ["note", "counterparty"], supports_transfer_edit: false } };
}
for (const width of [390, 768, 1440]) {
  test(`F-04 当前详情 ${width}：摘要、只读信息及同容器编辑`, async ({ page }, info) => {
    const diagnostics = captureBrowserDiagnostics(page);
    const api = await installComposeApiFixtures(page);
    await page.route(/\/api\/v1\/cash-records\/record-1$/, route => route.fulfill({ json: currentRecord() }));
    await page.setViewportSize({ width, height: 1000 });
    await openDetail(page, width);
    const panel = byId(page, "ledger.record-panel");
    const bounds = await panel.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(0);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width);
    const detail = byId(page, "cash.current.detail");
    await expect(detail.getByText("流水信息", { exact: true })).toHaveCount(0);
    await expect(detail).toContainText("账户");
    await expect(detail).toContainText("分类");
    await expect(detail).toContainText("拿铁");
    await expect(detail.locator('[id="cash.current.associations"]')).toHaveCount(0);
    await expect(byId(page, "cash.current.amount")).toContainText("28.50 CNY");
    await page.screenshot({ path: info.outputPath(`current-detail-${width}.png`) });
    await press(page, byId(page, "cash.current.edit"));
    await expect(byId(page, "cash.entry.editor")).toBeVisible();
    await expect(page.locator('[id="ledger.record-panel"]:visible')).toHaveCount(1);
    await expect(page.locator('[id="cash.entry.amount"]:visible')).toHaveCount(0);
    await expect(byId(page, "cash.entry.note")).toContainText("拿铁");
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]); expect(diagnostics.failedRequests).toEqual([]);
    expect(diagnostics.staticHttpErrors).toEqual([]);
    expect(api.calls.filter(call => call.method !== "GET")).toEqual([]);
  });
}

test("F-04 当前草稿详情：橙色提示包含标题和缺项，原文默认折叠", async ({ page }, info) => {
  await installComposeApiFixtures(page);
  const draft = currentRecord();
  draft.state = "draft";
  draft.confirmation_status = "pending";
  draft.fields.amount = null;
  draft.issues = [{ issue_id: "draft-incomplete", revision: 1, code: "incomplete_record", message: "流水信息尚未补齐",
    field_path: "amount", blocking_scope: "row", next_action: "edit", allowed_resolutions: [], pending_fields: {} }];
  await page.route(/\/api\/v1\/cash-records\/record-1$/, route => route.fulfill({ json: draft }));
  await page.setViewportSize({ width: 390, height: 1000 });
  await openDetail(page, 390);
  const detail = byId(page, "cash.current.detail");
  const status = byId(page, "cash.current.pending-draft");
  await expect(status).toContainText("草稿");
  await expect(status).toContainText("金额信息尚未补齐");
  await expect(status).not.toContainText("当前不参与收支统计");
  await expect(detail.getByText("流水信息", { exact: true })).toHaveCount(0);
  const sources = byId(page, "cash.current.sources-toggle");
  await expect(sources).toContainText("账单原文");
  await expect(sources).toContainText("查看");
  await expect(detail).not.toContainText("来源商户");
  await page.screenshot({ path: info.outputPath("current-draft-detail-390.png") });
});

test("F-04 当前详情：关联流水默认展开并排在默认折叠的账单原文之前", async ({ page }, info) => {
  await installComposeApiFixtures(page, { cashProjectionOverrides: { projection_version: 1,
    member_count: 2, accepted_relation_summary: [{ kind: "payment_mirror", count: 2 }] } });
  const record = {
    ...currentRecord(),
    projection_ref: { transaction_id: "record-1", projection_id: "projection-1", attention_count: 0, projection_version: 1 },
    relations: [{ relation_id: "relation-1", kind: "payment_mirror", subtype: "", state: "confirmed", active: true,
      row_revision: 1, decision_group_id: "group-1" }],
  };
  await page.route(/\/api\/v1\/cash-records\/record-(?:1|2)$/, route => {
    const transactionId = new URL(route.request().url()).pathname.endsWith("record-2") ? "record-2" : "record-1";
    return route.fulfill({ json: { ...record, transaction_id: transactionId,
      fields: { ...record.fields, source_payload: { "原始字段": `来自${transactionId}` } },
      projection_ref: { ...record.projection_ref, transaction_id: transactionId } } });
  });
  await page.route(/\/api\/v1\/cash-projections\/projection-1$/, route => route.fulfill({ json: { projection_version: 1, members: [
    { transaction_id: "record-1", row_revision: 1, confirmation_status: "confirmed" },
    { transaction_id: "record-2", row_revision: 1, confirmation_status: "confirmed" },
  ] } }));
  let evidenceReads = 0;
  await page.route(/\/api\/v1\/evidence\/cash-projections\/projection-1$/, route => { evidenceReads += 1; return route.fulfill({ json: {
    projection_version: 1,
    projection: { projection_id: "projection-1", record_id: "source-1", transaction_id: "record-1",
      occurred_at: "2026-09-24T10:30:00+08:00", amount: "-28.50", currency: "CNY", economic_type: "expense",
      counterparty: "平台合并收支", member_count: 2, accepted_relation_summary: [{ kind: "payment_mirror", count: 2 }] },
    members: [
      { id: "record-1", row_revision: 1, occurred_at: "2026-09-24T10:30:00+08:00", amount: "-28.50", currency: "CNY",
        counterparty: "当前来源商户", note: "当前来源备注", account_name: "日常账户", components: [] },
      { id: "record-2", row_revision: 1, occurred_at: "2026-09-24T10:31:00+08:00", amount: "-28.50", currency: "CNY",
        counterparty: "关联来源商户", note: "关联来源备注", account_name: "微信支付", components: [] },
    ],
  } }); });
  await page.setViewportSize({ width: 390, height: 1000 });
  await openDetail(page, 390);

  const detail = byId(page, "cash.current.detail");
  const associations = byId(page, "cash.current.associations");
  const sources = byId(page, "cash.current.sources-toggle");
  await expect(associations).toBeVisible();
  await expect(associations).toContainText("关联来源商户");
  await expect(associations.locator('[id="cash.current.associations-loading"]')).toHaveCount(0);
  expect(evidenceReads).toBe(1);
  await expect(sources).toContainText("账单原文");
  await expect(sources).toContainText("查看");
  await expect(detail).not.toContainText("来自record-1");
  const associationBounds = await associations.boundingBox();
  const sourceBounds = await sources.boundingBox();
  expect(associationBounds).not.toBeNull();
  expect(sourceBounds).not.toBeNull();
  expect(associationBounds!.y).toBeLessThan(sourceBounds!.y);

  await page.screenshot({ path: info.outputPath("current-detail-associations-default-390.png") });
  await sources.scrollIntoViewIfNeeded();
  await press(page, sources);
  await expect(detail).toContainText("来自record-1");
  await page.screenshot({ path: info.outputPath("current-detail-sources-expanded-390.png") });
});

test("F-04 当前详情：关联成员读取失败可原位重试", async ({ page }) => {
  await installComposeApiFixtures(page, { cashProjectionOverrides: { projection_version: 1, member_count: 2 } });
  await page.route(/\/api\/v1\/cash-records\/record-(?:1|2)$/, route => route.fulfill({ json: {
    ...currentRecord(), transaction_id: new URL(route.request().url()).pathname.endsWith("record-2") ? "record-2" : "record-1",
    projection_ref: { transaction_id: new URL(route.request().url()).pathname.endsWith("record-2") ? "record-2" : "record-1",
      projection_id: "projection-1", attention_count: 0, projection_version: 1 },
    relations: [{ relation_id: "relation-1", kind: "payment_mirror", subtype: "", state: "confirmed", active: true,
      row_revision: 1, decision_group_id: "group-1" }],
  } }));
  await page.route(/\/api\/v1\/cash-projections\/projection-1$/, route => route.fulfill({ json: { projection_version: 1, members: [
    { transaction_id: "record-1", row_revision: 1, confirmation_status: "confirmed" },
    { transaction_id: "record-2", row_revision: 1, confirmation_status: "confirmed" },
  ] } }));
  let evidenceReads = 0;
  await page.route(/\/api\/v1\/evidence\/cash-projections\/projection-1$/, route => {
    evidenceReads += 1;
    return evidenceReads === 1
      ? route.fulfill({ status: 503, json: { error: { code: "temporarily_unavailable" } } })
      : route.fulfill({ json: { projection_version: 1,
          projection: { projection_id: "projection-1", record_id: "record-1", transaction_id: "record-1",
            occurred_at: "2026-09-24T10:30:00+08:00", amount: "-28.50", currency: "CNY", economic_type: "expense", member_count: 2 },
          members: [
            { id: "record-1", row_revision: 1, amount: "-28.50", currency: "CNY", counterparty: "当前流水", components: [] },
            { id: "record-2", row_revision: 1, amount: "-28.50", currency: "CNY", counterparty: "另一笔流水", components: [] },
          ] } });
  });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openDetail(page, 1440);
  const associations = byId(page, "cash.current.associations");
  const retry = byId(page, "cash.current.associations-retry");
  await expect(associations).toContainText("暂时无法读取关联流水。");
  await expect(retry).toBeVisible();
  await expect(associations.locator('[id="cash.current.associations-loading"]')).toHaveCount(0);
  await press(page, retry);
  await expect(associations).toContainText("另一笔流水");
  expect(evidenceReads).toBe(2);
});

test("F-04 当前详情：候选嵌入关联区并可打开整组处理", async ({ page }, info) => {
  await installComposeApiFixtures(page);
  const current = {
    ...currentRecord(),
    relations: [{ relation_id: "relation-draft", kind: "payment_mirror", subtype: "", state: "draft", active: true,
      row_revision: 1, decision_group_id: "decision-group-1" }],
  };
  await page.route(/\/api\/v1\/cash-records\/record-(?:1|2)$/, route => {
    const transactionId = new URL(route.request().url()).pathname.endsWith("record-2") ? "record-2" : "record-1";
    return route.fulfill({ json: { ...current, transaction_id: transactionId,
      fields: transactionId === "record-2" ? { ...current.fields, counterparty: "另一来源商户", note: "候选备注" } : current.fields } });
  });
  await page.route(/\/api\/v1\/cash-mutations\/plans$/, route => route.fulfill({ json: {
    plan_id: "relation-plan", edit_context_id: "relation-context", plan_revision: 1, expires_at: "2099-01-01T00:00:00Z",
    summary: {}, item_counts: {}, can_commit: true,
    items: [{ preview_item_id: "relation-item", confirmation_status: "pending", anticipated_state: "posted",
      display: { occurred_at: "2026-09-24T10:30:00+08:00", amount: "-28.50", currency: "CNY", counterparty: "咖啡店",
        note: "拿铁", accounts: [] }, statistics_eligible: true,
      member_refs: [{ transaction_id: "record-1" }], attention_reasons: [], detail_available: true }],
    pages: { items: null }, invalidated_choices: [], additional_effects: [],
  } }));
  await page.route(/\/api\/v1\/cash-mutations\/plans\/relation-plan\/items\/relation-item$/, route => route.fulfill({ json: {
    preview_item_id: "relation-item",
    members: [{ transaction_id: "record-1", row_revision: 1, planned_action: "skip", fields: current.fields,
      editable_fields: [], allowed_actions: [] }],
    field_diffs: [], issues: [],
    relation_choices: [{ suggestion_id: "suggestion-1", kind: "payment_mirror", subtype: "", action: "defer", code: "insufficient_evidence",
      candidates: [{ candidate_id: "candidate-1", code: "insufficient_evidence", display: { occurred_at: "2026-09-24T10:31:00+08:00", amount: "-28.50",
        currency: "CNY", counterparty: "另一来源商户", note: "候选备注", accounts: [] },
        member_refs: [{ transaction_id: "record-2" }], code: null }],
      member_refs: [{ transaction_id: "record-1" }], allowed_actions: ["accept", "defer", "ignore"], code: null }],
    relation_effects: [],
  } }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openDetail(page, 1440);

  const detail = byId(page, "cash.current.detail");
  const associations = byId(page, "cash.current.associations");
  await expect(associations).toContainText("候选");
  await expect(associations).toContainText("另一来源商户");
  await expect(associations).toContainText("候选备注");
  await expect(detail).not.toContainText("暂时无法完成导入，请稍后重试。");
  await page.screenshot({ path: info.outputPath("current-detail-relation-candidate-1440.png") });
  const sources = byId(page, "cash.current.sources-toggle");
  await expect(sources).toContainText("账单原文");
  await expect(sources).toContainText("查看");
  const action = byId(page, "cash.current.process-relations");
  await action.scrollIntoViewIfNeeded();
  await press(page, action);
  const treatment = byId(page, "cash.relations.treatment");
  await expect(treatment.getByText("拒绝配对", { exact: true })).toBeVisible();
  await expect(byId(page, "cash.relation.action-suggestion-1-accept")).toBeVisible();
  await expect(byId(page, "cash.relation.action-suggestion-1-ignore")).toBeVisible();
  await press(page, byId(page, "cash.relation.action-suggestion-1-accept"));
  await expect(byId(page, "cash.relation.candidate-suggestion-1-candidate-1")).toBeVisible();
  await expect(treatment.getByRole("button", { name: "保存", exact: true })).toBeEnabled();
});

test("F-04 当前详情：长字段换行且来源成员保持只读", async ({ page }, info) => {
  await installComposeApiFixtures(page);
  const note = "这是一段需要完整阅读的备注。".repeat(24);
  const merchant = "这是很长的交易对方名称".repeat(8);
  await page.route(/\/api\/v1\/cash-records\/record-1$/, route => route.fulfill({ json: { ...currentRecord(note, merchant), projection_ref: { projection_id: "projection-1", projection_version: 1, transaction_id: "record-1", attention_count: 0 } } }));
  await page.route(/\/api\/v1\/cash-projections\/projection-1$/, route => route.fulfill({ json: { projection_version: 1, members: [
    { transaction_id: "record-1", row_revision: 1, confirmation_status: "confirmed" },
  ] } }));
  await page.route(/\/api\/v1\/evidence\/cash-projections\/projection-1$/, route => route.fulfill({ json: {
    projection_version: 1, projection: { projection_id: "projection-1", record_id: "source-1", transaction_id: "record-1", occurred_at: "2026-09-24T10:30:00+08:00", amount: "-28.50", currency: "CNY", economic_type: "expense", counterparty: merchant },
    members: [{ id: "record-1", row_revision: 1, amount: "-28.50", currency: "CNY", counterparty: "来源商户", note: "来源备注", components: [] }],
  } }));
  await page.setViewportSize({ width: 390, height: 1000 });
  await openDetail(page, 390);
  await expect(byId(page, "cash.current.detail")).toContainText(note);
  const panel = await byId(page, "ledger.record-panel").boundingBox();
  for (const value of [note, merchant]) {
    const bounds = await byId(page, "cash.current.detail").getByText(value, { exact: true }).boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x).toBeGreaterThanOrEqual(panel!.x);
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(panel!.x + panel!.width);
  }
  await page.mouse.move(190, 550); await page.mouse.wheel(0, 1800); await page.waitForTimeout(300);
  const sourcesButton = byId(page, "cash.current.sources-toggle");
  await expect(sourcesButton).toContainText("账单原文");
  await expect(sourcesButton).toContainText("查看");
  await press(page, sourcesButton);
  await expect(byId(page, "cash.current.detail")).toContainText("来源商户");
  await expect(page.locator('[id^="record.related-edit"]')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("current-detail-long-source.png") });
});

test("F-04 当前详情：读取失败可在原容器重试", async ({ page }) => {
  await installComposeApiFixtures(page);
  let reads = 0;
  await page.route(/\/api\/v1\/cash-records\/record-1$/, route => ++reads === 1
    ? route.fulfill({ status: 503, json: { error: { code: "temporarily_unavailable" } } })
    : route.fulfill({ json: currentRecord() }));
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openDetail(page, 1440);
  const error = byId(page, "cash.current.error");
  await expect(error).toBeVisible(); await press(page, error.getByRole("button"));
  await expect(byId(page, "cash.current.amount")).toContainText("28.50 CNY");
  expect(reads).toBe(2);
});

test("F-04 当前详情：只读成员没有编辑入口", async ({ page }) => {
  const initialSession = { ...activeSession, workspaces: activeSession.workspaces.map(row => ({ ...row, role: "viewer" })) };
  await installComposeApiFixtures(page, { initialSession });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await openDetail(page, 1440);
  await expect(byId(page, "cash.current.detail")).toContainText("咖啡店");
  await expect(page.locator('[id="cash.current.edit"]:visible')).toHaveCount(0);
});

import { expect, test, type Locator, type Page } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

async function expectReadonlyImportDetail(detail: Locator) {
  await expect(detail.getByRole("textbox")).toHaveCount(0);
  await expect(detail.getByRole("radio")).toHaveCount(0);
  await expect(detail.getByRole("button", { name: /^(保存|编辑收支|应用更新|保留现有|确认配对|稍后处理)$/ })).toHaveCount(0);
  await expect(detail.locator('[id="cash.import.edit-entry"], [id^="cash.import.edit-member-"], [id^="cash.import.relation-action-"], [id^="cash.import.relation-save-"], [id^="cash.import.common-value-"]')).toHaveCount(0);
}

async function press(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  await page.waitForTimeout(200);
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

async function clickLocator(page: Page, target: Locator, xRatio = 0.5, yRatio = 0.5) {
  await expect(target).toBeVisible();
  await target.scrollIntoViewIfNeeded();
  await page.waitForTimeout(200);
  const box = await target.boundingBox();
  if (!box) throw new Error("Missing action bounds");
  await page.mouse.click(box.x + box.width * xRatio, box.y + box.height * yRatio);
}

async function chooseAccountMenuOption(page: Page) {
  const menu = page.locator('[id="choice-overlay-menu"]:visible').last();
  await expect(menu).toBeVisible();
  const option = menu.getByText("＋新建账户", { exact: true }).last();
  await expect(option).toBeVisible();
  await clickLocator(page, option);
}

async function setup(page: Page, unknown = false, unexpected = false, uploadDelayMs = 0) {
  const fixtures = await installComposeApiFixtures(page);
  const session = "i".repeat(43), planId = `ip_${session}.${"p".repeat(16)}`;
  const display = { occurred_at: "2026-10-24T04:00:00Z", amount: "-12.5000", currency: "CNY", counterparty: "待核对商户", note: "待核对备注", accounts: [] };
  const pending = { preview_item_id: "pending-item", confirmation_status: "pending", anticipated_state: "draft", display,
    statistics_eligible: false, member_refs: [{ row_ref: "row-pending" }], attention_reasons: [], detail_available: true };
  const confirmed = { ...pending, preview_item_id: "confirmed-item", confirmation_status: "confirmed", anticipated_state: "posted",
    display: { ...display, counterparty: "正常商户" }, statistics_eligible: true, member_refs: [{ row_ref: "row-confirmed" }] };
  const context = { session_id: session, session_revision: 1, expires_at: "2099-01-01T00:00:00Z", state: "open",
    files: [{ file_ref: "file", client_file_ref: "client", display_name: "synthetic.csv", source_type: "alipay", source_scope_label: null,
      status: "ready", recognized_row_count: 2, issues: [], can_retry: false }], source_accounts: [] as Array<Record<string, unknown>>, last_choices: null, last_plan_id: null,
    can_preview: true, receipt_id: null };
  const plan = { plan_id: planId, session_id: session, session_revision: 2, expires_at: context.expires_at,
    summary: { create_posted: 1, create_draft: 1 }, item_counts: { all: 2, confirmed: 1, pending: 1 }, can_commit: true,
    items: [confirmed, pending], file_results: context.files, pages: { items: null, processing_details: null }, invalidated_choices: [] };
  const receipt = { receipt_id: "import-receipt", plan_id: planId, operation_kind: "import", committed: true,
    created_at: "2026-10-24T04:00:00Z", status: unexpected ? "partial" : "completed", summary: { created_posted: 1, saved_draft: 1 },
    pending_item_count: 1, needs_attention_count: 1, projection_version: 2,
    commit_outcome: { has_new_failures: unexpected, has_business_writes: true, reason_codes: unexpected ? ["account_changed"] : [] }, pages: {} };
  const pages: string[] = [], previews: Array<Record<string, unknown>> = [], commits: string[] = [], resolves: string[] = [], removals: string[] = [];
  await page.route(/\/api\/v1\/cash-import\/sessions$/, async route => {
    if (uploadDelayMs > 0) await page.waitForTimeout(uploadDelayMs);
    const body = route.request().postDataBuffer()?.toString("utf8") ?? "";
    const uploadedRef = body.match(/\"client_file_ref\"\s*:\s*\"([A-Za-z0-9_-]+)\"/)?.[1];
    if (uploadedRef) context.files[0].client_file_ref = uploadedRef;
    return route.fulfill({ status: 201, json: context });
  });
  await page.route(new RegExp(`/api/v1/cash-import/sessions/${session}/files/[^/?]+$`), route => {
    if (route.request().method() !== "DELETE") return route.fallback();
    removals.push(route.request().method());
    context.files = [];
    context.source_accounts = [];
    context.can_preview = false;
    context.session_revision = 2;
    return route.fulfill({ json: context });
  });
  await page.route(new RegExp(`/api/v1/cash-import/sessions/${session}/plans$`), route => {
    previews.push(route.request().postDataJSON());
    return route.fulfill({ json: plan });
  });
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items(?:\?|$)/, route => {
    const filter = new URL(route.request().url()).searchParams.get("confirmation_status") ?? "all";
    pages.push(filter);
    return route.fulfill({ json: { items: filter === "pending" ? [pending] : [confirmed, pending], next_cursor: null } });
  });
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items\/[^/?]+$/, route => {
    const id = new URL(route.request().url()).pathname.split("/").at(-1);
    const row = id === "pending-item" ? pending : confirmed;
    return route.fulfill({ json: { preview_item_id: id, members: [{ row_ref: row.member_refs[0].row_ref,
      planned_action: row.anticipated_state === "draft" ? "create_draft" : "create_posted", fields: row.display,
      editable_fields: [], allowed_actions: [] }], field_diffs: [], issues: [], relation_choices: [], relation_effects: [], relation_members: [] } });
  });
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/commit$/, route => {
    commits.push(route.request().headers()["idempotency-key"]);
    return route.fulfill(unknown ? { status: 503, json: { error: { code: "commit_state_unknown", message: "保存结果待确认", field_errors: [], retryable: false }, commit_state: "unknown" } } : { json: receipt });
  });
  await page.route(/\/api\/v1\/cash-mutations\/resolve$/, route => {
    resolves.push(route.request().postDataJSON().idempotency_key);
    return route.fulfill({ json: { status: "committed", receipt } });
  });
  return { fixtures, pages, previews, commits, resolves, removals, context };
}

async function enterAccountMapping(page: Page) {
  await expect(page.locator('[id^="cash.import.file-uploading-"]:visible')).toHaveCount(0);
  const next = page.locator('[id="cash.import.to-accounts"]:visible');
  await expect(next).toBeEnabled();
  await press(page, "cash.import.to-accounts");
  await expect(page.locator('[id="cash.import.mapping-step"]:visible')).toHaveCount(1);
}

async function uploadAndReview(page: Page, fromLedger = false, prepareOnly = false) {
  await page.goto(fromLedger ? "/w/workspace-1/" : "/w/workspace-1/cash-import");
  if (fromLedger) {
    await page.waitForLoadState("networkidle");
    await press(page, "ledger.actions-fab"); await press(page, "ledger.import");
  }
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1);
  await page.waitForLoadState("networkidle");
  await page.waitForTimeout(400);
  let pickerOpened = false;
  const chooser = page.waitForEvent("filechooser", { timeout: 10_000 }).then(event => { pickerOpened = true; return event; });
  // The semantics mirror can expose the button before the task restoration
  // enables its physical action. Retrying this picker action cannot submit data.
  for (let attempt = 0; attempt < 4 && !pickerOpened; attempt++) {
    await press(page, "cash.import.add-files");
    await page.waitForTimeout(250);
  }
  await (await chooser).setFiles({ name: "synthetic.csv", mimeType: "text/csv", buffer: Buffer.from("synthetic statement") });
  await page.waitForTimeout(300);
  await expect(page.locator('[id="cash.import.files-step"]:visible')).toHaveCount(1);
  await expect(page.locator('[id="cash.import.mapping-step"]:visible')).toHaveCount(0);
  const task = page.locator('[id="cash.import.task"]:visible');
  await expect(task).toContainText("1  选择文件");
  await expect(task).toContainText("添加本次要导入的账单文件。");
  await expect(task).not.toContainText("指定账户映射");
  await expect(task).not.toContainText("核对预计结果");
  if (prepareOnly) return;
  await enterAccountMapping(page);
  await expect(page.locator('[id="cash.import.mapping-step"]:visible')).toHaveCount(1);
  await expect(task).toContainText("2  账户映射");
  await expect(task).toContainText("为账单中的账户选择对应的账本账户。");
  await expect(task).not.toContainText("指定账户映射");
  await expect(task).not.toContainText("核对预计结果");
  await press(page, "cash.import.review");
  await expect(page.locator('[id="cash.import.items"]:visible')).toContainText("待核对商户");
  await expect(task).toContainText("3  核对");
  await expect(task).toContainText("核对本次将导入的收支和待确认内容。");
  await expect(task).not.toContainText("指定账户映射");
  await expect(task).not.toContainText("核对预计结果");
}

for (const width of [390, 768, 1440]) {
  test(`import account task keeps responsive actions and readable fields ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const calls = await setup(page);
    await page.route(/\/api\/v1\/accounts\/by-id\/1$/, route => route.fulfill({ json: {
      account_id: "1", name: "日常账户", type: "cash", active: true, currencies: ["CNY"], account_revision: 1, deleted: false,
    } }));
    calls.context.source_accounts.push({ source_account_ref: "source-account", source_type: "alipay",
      display_name: "浦发信用卡", currencies: ["CNY", "USD"], row_count: 2,
      suggestion: { account_id: "1", expected_account_revision: 1, basis: "confirmed_mapping" },
      selection: { account_id: "1", expected_account_revision: 1 } });
    await uploadAndReview(page, false, true);
    await enterAccountMapping(page);
    await expect(page.locator('[id="cash.import.create-account"]:visible')).toHaveCount(0);
    await expect(page.getByRole("button", { name: "编辑账户", exact: true })).toHaveCount(0);
    await expect(page.locator('[id="cash.import.account-source-account"]:visible')).toBeEnabled();
    await expect(page.locator('[id="cash.import.account-currency-missing-source-account"]:visible')).toContainText("USD");
    await press(page, "cash.import.account-source-account");
    await chooseAccountMenuOption(page);
    const footer = page.locator('[id="ledger.record-panel.footer"]:visible');
    const save = await page.locator('[id="cash.import.account-form.save"]:visible').boundingBox();
    const cancel = await footer.getByRole("button", { name: "取消", exact: true }).boundingBox();
    const field = await page.locator('[id="cash.import.account-form.name"]:visible').boundingBox();
    const area = await footer.boundingBox();
    expect(save).not.toBeNull(); expect(cancel).not.toBeNull(); expect(field).not.toBeNull(); expect(area).not.toBeNull();
    expect(field!.width).toBeLessThanOrEqual(720);
    expect(area!.height).toBe(width < 600 ? 56 : 112);
    if (width < 600) expect(cancel!.x).toBeLessThan(save!.x);
    else expect(save!.x).toBeLessThan(cancel!.x);
    await expect(page.locator('[id="cash.import.account-form"]:visible').getByRole("textbox")).toHaveCount(3);
    await page.screenshot({ path: info.outputPath(`account-task-${width}.png`) });
    expect(calls.fixtures.calls.filter(call => call.method === "POST" && call.path === "/api/v1/accounts")).toHaveLength(0);
  });
}

test("creating an account from its mapping menu returns to the same source with it selected", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const calls = await setup(page);
  await page.route(/\/api\/v1\/accounts\/by-id\/1$/, route => route.fulfill({ json: {
    account_id: "1", name: "日常账户", type: "cash", active: true, currencies: ["CNY"], account_revision: 1, deleted: false,
  } }));
  calls.context.source_accounts.push({ source_account_ref: "source-account", source_type: "alipay",
    display_name: "浦发信用卡", currencies: ["CNY", "USD"], row_count: 2,
    suggestion: { account_id: "1", expected_account_revision: 1, basis: "confirmed_mapping" },
    selection: { account_id: "1", expected_account_revision: 1 } });
  const createdAccount = { account_id: "42", name: "浦发信用卡", type: "loan", active: true,
    currencies: ["CNY", "USD"], account_revision: 1, deleted: false,
    capabilities: { can_update: true, can_delete: false }, receipt_id: "account-create-receipt", committed: true };
  let createBody: Record<string, unknown> | undefined;
  await page.route(/\/api\/v1\/accounts$/, async route => {
    if (route.request().method() !== "POST") return route.fallback();
    createBody = route.request().postDataJSON();
    return route.fulfill({ json: createdAccount });
  });
  await page.route(/\/api\/v1\/accounts\/by-id\/42$/, route => route.fulfill({ json: createdAccount }));

  await uploadAndReview(page, false, true);
  await enterAccountMapping(page);
  await expect(page.locator('[id="cash.import.account-source-account"]:visible')).toBeEnabled();
  await press(page, "cash.import.account-source-account");
  await chooseAccountMenuOption(page);
  await expect(page.locator('[id="cash.import.account-form"]:visible')).toBeVisible();
  await expect(page.locator('[id="cash.import.account-form.save"]:visible')).toBeEnabled();
  await page.screenshot({ path: "/tmp/cash-import-account-form-prefill.png" });
  const accountReload = page.waitForResponse(response => response.request().method() === "GET"
    && new URL(response.url()).pathname === "/api/v1/accounts/by-id/42");
  await press(page, "cash.import.account-form.save");
  await expect.poll(() => createBody).toEqual({ name: "浦发信用卡", type: "loan", currencies: ["CNY", "USD"] });
  // CMP-10623: after nested Popup/Dialog disposal the mirrored semantics can
  // remain on the form. Wait for the actual account reload, then use the fixed
  // primary action's real screen position to continue this 1440px scenario.
  await accountReload;
  await page.waitForTimeout(300);
  await page.screenshot({ path: "/tmp/cash-import-account-after-save.png" });
  await page.mouse.click(1357, 872);
  await expect.poll(() => calls.previews.length).toBe(1);
  expect(calls.previews.at(-1)?.account_selections).toEqual([
    { source_account_ref: "source-account", account_id: "42", expected_account_revision: 1 },
  ]);
});

test("cash import reviews all items with drafts first in the main task and shows only the actual result summary", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  const calls = await setup(page);
  await uploadAndReview(page);
  await expect(page.locator('[id="cash.import.items"]:visible')).toContainText("正常商户");
  expect(calls.pages).toEqual([]);
  await press(page, "cash.import.filter-draft");
  await expect(page.locator('[id="cash.import.items"]:visible')).not.toContainText("正常商户");
  await press(page, "cash.import.filter-all");
  await expect(page.locator('[id="cash.import.items"]:visible')).toContainText("正常商户");
  await press(page, "cash.import.confirm");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("已处理 2 条账单明细");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("待确认 1");
  expect(calls.commits).toHaveLength(1);
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

test("file parsing shows row progress and removes a file immediately from the right action area", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  const calls = await setup(page, false, false, 5000);
  await uploadAndReview(page, false, true);
  const pending = page.locator('[id^="cash.import.file-uploading-"]:visible');
  await expect(pending).toBeVisible();
  await expect(page.locator('[id^="cash.import.file-loading-"]:visible')).toBeVisible();
  await expect(page.locator('[id^="cash.import.remove-pending-"]:visible')).toBeEnabled();
  await expect(page.getByText("已解析", { exact: true })).toHaveCount(0);
  await expect(page.locator('[id="cash.import.file-file"]:visible')).toBeVisible({ timeout: 10_000 });
  const remove = page.getByRole("button", { name: "移除文件 synthetic.csv" });
  await expect(remove).toBeVisible();
  await expect(page.getByText("移除“synthetic.csv”？", { exact: true })).toHaveCount(0);
  await clickLocator(page, remove);
  await expect.poll(() => calls.removals.length).toBe(1);
  await expect(page.locator('[id="cash.import.file-file"]:visible')).toHaveCount(0);
  await expect(page.getByText("移除“synthetic.csv”？", { exact: true })).toHaveCount(0);
});

test("removing a file during parsing hides it immediately and removes it from the session when upload settles", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 900 });
  const calls = await setup(page, false, false, 5000);
  await uploadAndReview(page, false, true);
  const pending = page.locator('[id^="cash.import.file-uploading-"]:visible');
  await expect(pending).toBeVisible();
  await clickLocator(page, page.locator('[id^="cash.import.remove-pending-"]:visible').last());
  await expect(pending).toHaveCount(0);
  await expect(page.getByText("移除“synthetic.csv”？", { exact: true })).toHaveCount(0);
  await expect.poll(() => calls.removals.length, { timeout: 15_000 }).toBe(1);
  await expect(page.locator('[id^="cash.import.file-"]:visible')).toHaveCount(0);
});

test("an unknown import result resolves the original command without another submission", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  const calls = await setup(page, true);
  await uploadAndReview(page);
  await press(page, "cash.import.confirm");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("正在确认导入结果");
  await press(page, "cash.import.resolve");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("导入完成");
  expect(calls.commits).toHaveLength(1);
  expect(calls.resolves).toEqual(calls.commits);
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

for (const width of [390, 768, 1440]) {
  test(`import review keeps every member readonly and retains only the account selection ${width}`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 900 });
    const calls = await setup(page);
    calls.context.source_accounts.push({ source_account_ref: "source-account", source_type: "alipay",
      display_name: "账单账户", currencies: ["CNY"], row_count: 2, suggestion: { basis: "none" },
      selection: { account_id: "1", expected_account_revision: 1 } });
    const members = [
      { row_ref: "row-a", counterparty: "待补齐成员甲", amount: "-12.50" },
      { row_ref: "row-b", counterparty: "待补齐成员乙", amount: "-8.00" },
    ].map(member => ({ row_ref: member.row_ref, planned_action: "create_draft",
      fields: { occurred_at: "2026-10-24T04:00:00Z", amount: member.amount, currency: "CNY", account_id: "1", counterparty: member.counterparty },
      editable_fields: ["amount"], allowed_actions: ["apply", "defer"] }));
    await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items\/pending-item$/, route => route.fulfill({ json: {
      preview_item_id: "pending-item", entry: { edit_ref: "entry-pending", fields: { note: "共同备注" }, editable_fields: ["note"] }, members,
      field_diffs: [], issues: [{ code: "incomplete_record", message: "补充缺失金额", field_path: "amount", blocking_scope: "row", next_action: "edit_record" }],
      relation_choices: [{ suggestion_id: "relation", kind: "payment_mirror", action: "accept", code: "relation_context_changed",
        member_refs: [{ row_ref: "row-a" }], allowed_actions: ["accept", "defer"], candidates: [
          { candidate_id: "candidate-a", member_refs: [{ row_ref: "row-a" }] },
          { candidate_id: "candidate-b", member_refs: [{ row_ref: "row-b" }] },
        ] }],
      relation_effects: [], relation_members: members,
    } }));
    await uploadAndReview(page);
    await page.screenshot({ path: info.outputPath(`import-review-${width}.png`) });
    await expect(page.locator('[id="cash.import.items"]:visible')).toHaveCount(1);
    await expect(page.locator('[id="ledger.table-header"]:visible')).toHaveCount(width < 600 ? 0 : 1);
    const confirm = await page.locator('[id="cash.import.confirm"]:visible').boundingBox();
    if (!confirm) throw new Error("Missing import confirmation");
    await press(page, width < 600 ? "ledger.record-import-pending-item" : "ledger.open-record");
    const detail = page.locator('[id="cash.import.detail"]:visible');
    const panel = page.locator('[id="ledger.record-panel"]:visible');
    await expect(detail).toBeVisible();
    await page.screenshot({ path: info.outputPath(`import-detail-${width}.png`) });
    const panelBox = await panel.boundingBox();
    if (!panelBox) throw new Error("Missing import detail panel");
    if (width < 600) expect(panelBox.width).toBe(width);
    else {
      expect(panelBox.x).toBeGreaterThan(0);
      expect(panelBox.width).toBeLessThan(width);
    }
    await expect(detail).toContainText("补充缺失金额");
    await press(page, "cash.import.relation-relation");
    await expect(detail).toContainText("待补齐成员甲");
    await expect(detail).toContainText("待补齐成员乙");
    await expectReadonlyImportDetail(detail);
    await press(page, "ledger.record-panel.close");
    // CMP-10623: the mirrored DOM can remain stale after Popup disposal.
    await page.waitForTimeout(700);
    await page.mouse.click(confirm.x + confirm.width / 2, confirm.y + confirm.height / 2);
    await expect.poll(() => calls.commits.length).toBe(1);
    await page.screenshot({ path: info.outputPath(`import-after-confirm-${width}.png`) });
    await expect.poll(() => calls.commits.length).toBe(1);
    expect(calls.previews).toHaveLength(1);
    expect(calls.previews[0]).toMatchObject({
      account_selections: [{ source_account_ref: "source-account", account_id: "1", expected_account_revision: 1 }],
      row_choices: [], relation_choices: [], entry_choices: [],
    });
    expect(calls.commits).toHaveLength(1);
  });
}


test("a completed import reloads the cached ledger even after visiting another page", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const calls = await setup(page);
  await uploadAndReview(page, true);
  const reads = () => calls.fixtures.calls.filter(call => call.method === "GET" && call.path.split("?")[0] === "/api/v1/cash-projections").length;
  const before = reads();
  expect(before).toBeGreaterThan(0);
  await press(page, "cash.import.confirm");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("导入完成");
  await press(page, "cash.import.return");
  await press(page, "navigation-item-workspace-management");
  await expect(page).toHaveURL(/workspace-management$/);
  await press(page, "navigation-item-cash-ledger");
  await expect(page).toHaveURL(/workspace-1\/$/);
  await expect.poll(reads).toBeGreaterThan(before);
});

test("import opens a full-screen dialog over the ledger and closes through its own action", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page);
  await page.goto("/w/workspace-1/");
  await page.waitForLoadState("networkidle");
  await press(page, "ledger.actions-fab"); await press(page, "ledger.import");
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1);
  await expect(page.getByText("导入账单", { exact: true })).toHaveCount(1);
  await expect(page.locator('[id="secondary-page-modal"]:visible')).toHaveCount(0);
  const dialog = page.locator('[id="cash.import.dialog"]:visible');
  await expect(dialog).toHaveCount(1);
  const box = await dialog.boundingBox();
  expect(box).toMatchObject({ x: 0, y: 0, width: 1440, height: 900 });
  const header = await page.locator('[id="cash.import.header"]:visible').boundingBox();
  expect(header?.height).toBe(56);
  await page.waitForTimeout(300);
  await press(page, "cash.import.close");
  await expect(page).toHaveURL(/workspace-1\/$/);
});


test("unexpected commit failures show one generic error and return without row actions", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const calls = await setup(page, false, true);
  await uploadAndReview(page);
  await press(page, "cash.import.confirm");
  const result = page.locator('[id="cash.import.result"]:visible');
  await expect(result).toContainText("导入出错");
  await expect(result).toContainText("部分内容已保存");
  await expect(result).toContainText("账户已变化");
  await expect(result.getByRole("button")).toHaveCount(1);
  await expect(page.locator('[id="cash.import.resolve"]:visible')).toHaveCount(0);
  await expect(page.locator('[id="cash.import.processing"]:visible')).toHaveCount(0);
  expect(calls.commits).toHaveLength(1);
  await press(page, "cash.import.return");
  await expect(page).toHaveURL(/workspace-1\/$/);
});

test("an authoritative uncommitted refusal offers return without replaying the command", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const calls = await setup(page);
  let commits = 0;
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/commit$/, route => {
    commits++;
    return route.fulfill({ status: 409, json: { error: { code: "entry_choice_invalid", message: "重新核对", field_errors: [], retryable: false }, commit_state: "not_committed" } });
  });
  await uploadAndReview(page);
  await press(page, "cash.import.confirm");
  const result = page.locator('[id="cash.import.result"]:visible');
  await expect(result).toContainText("导入出错");
  await expect(result.getByRole("button")).toHaveCount(1);
  await expect(page.locator('[id="cash.import.resolve"]:visible')).toHaveCount(0);
  await page.waitForTimeout(200);
  expect(commits).toBe(1);
  expect(calls.resolves).toEqual([]);
});


test("import account preparation keeps the single form within the desktop width limit", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const { context } = await setup(page);
  context.source_accounts.push({ source_account_ref: "account-group", source_type: "alipay", display_name: "对应账户",
    currencies: ["CNY"], row_count: 2, suggestion: { basis: "none" }, selection: null });
  context.files.push({ ...context.files[0], file_ref: "locked-file", client_file_ref: "locked", display_name: "locked.pdf",
    source_type: "ccb", status: "password_required", recognized_row_count: 0, can_retry: true });
  await uploadAndReview(page, false, true);
  const password = page.locator('[id="cash.import.password-locked-file"]:visible').last();
  await expect(password).toBeVisible();
  expect((await password.boundingBox())?.width ?? Infinity).toBeLessThanOrEqual(720);
  await enterAccountMapping(page);
  const account = page.locator('[id="cash.import.account-account-group"]:visible').last();
  await expect(account).toBeVisible();
  const box = await account.boundingBox();
  expect(box?.width ?? Infinity).toBeLessThanOrEqual(720);
  await page.screenshot({ path: "/tmp/ft-compose-cash-evidence/import-account-width-1440.png" });
});


test("import source differences show both statement bodies as readonly content", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page);
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items\/pending-item$/, route => route.fulfill({ json: {
    preview_item_id: "pending-item", members: [{ row_ref: "row-pending", transaction_id: "11", planned_action: "update_posted",
      fields: { amount: "-12.5000", currency: "CNY", source_payload: { receipt: "新凭证正文" } },
      editable_fields: [], allowed_actions: ["apply", "keep_existing", "defer"], row_revision: 3 }],
    field_diffs: [{ row_ref: "row-pending", transaction_id: "11", field: "source_payload", current: { receipt: "原始凭证正文" },
      incoming: { receipt: "新凭证正文" }, effective: { receipt: "新凭证正文" }, resolution: "apply" }],
    issues: [], relation_choices: [], relation_effects: [], relation_members: [],
  } }));
  await uploadAndReview(page);
  await press(page, "ledger.open-record");
  const changed = page.getByRole("button", { name: /账单内容有变化/ });
  const box = await changed.boundingBox();
  if (!box) throw new Error("Missing source difference card");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  const detail = page.locator('[id="cash.import.detail"]:visible');
  await expect(detail).toContainText("原始凭证正文");
  await expect(detail).toContainText("新凭证正文");
  await expectReadonlyImportDetail(detail);
});

test("import empty incoming allocations remain explicitly visible and readonly", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page);
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items\/pending-item$/, route => route.fulfill({ json: {
    preview_item_id: "pending-item", members: [{ row_ref: "row-pending", transaction_id: "11", planned_action: "update_posted",
      fields: { amount: "-12.5000", currency: "CNY", source_payload: { receipt: "新凭证正文" } },
      editable_fields: [], allowed_actions: ["apply", "keep_existing", "defer"], row_revision: 3 }],
    field_diffs: [{ row_ref: "row-pending", transaction_id: "11", field: "components", current: [{ account_id: "1", amount: "-12.5000", currency: "CNY" }],
      incoming: [], effective: [], resolution: "apply" }],
    issues: [], relation_choices: [], relation_effects: [], relation_members: [],
  } }));
  await uploadAndReview(page);
  await press(page, "ledger.open-record");
  const changed = page.getByRole("button", { name: /账单内容有变化/ });
  const box = await changed.boundingBox();
  if (!box) throw new Error("Missing source difference card");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  const detail = page.locator('[id="cash.import.detail"]:visible');
  await expect(detail).toContainText("日常账户 · -12.5000 · CNY");
  await expect(detail).toContainText(/账单信息\s*未提供/);
  await expectReadonlyImportDetail(detail);
});

async function countDiagnosticFocusPixels(page: Page, target: Locator): Promise<number> {
  const bounds = await target.boundingBox();
  if (!bounds) return 0;
  const screenshot = await page.screenshot();
  return page.evaluate(async ({ pngBase64, bounds }) => {
    const binary = atob(pngBase64);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: "image/png" }));
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Canvas 2D is unavailable for screenshot inspection");
    context.drawImage(bitmap, 0, 0);

    const { data, width, height } = context.getImageData(0, 0, bitmap.width, bitmap.height);
    const { x, y, width: targetWidth, height: targetHeight } = bounds;
    const left = Math.max(0, Math.floor(x - 6));
    const right = Math.min(width, Math.ceil(x + targetWidth + 6));
    const top = Math.max(0, Math.floor(y - 6));
    const bottom = Math.min(height, Math.ceil(y + targetHeight + 6));
    let count = 0;
    for (let py = top; py < bottom; py += 1) {
      for (let px = left; px < right; px += 1) {
        if (px < x || px >= x + targetWidth || py < y || py >= y + targetHeight) continue;
        const offset = (py * width + px) * 4;
        const [red, green, blue] = data.subarray(offset, offset + 3);
        if (red > 150 && blue > red + 5 && green > red + 2) count += 1;
      }
    }
    bitmap.close();
    return count;
  }, { pngBase64: screenshot.toString("base64"), bounds });
}


for (const multi of [false, true]) {
  test(`import diagnostics show a readonly ${multi ? "related range" : "exact record"} without row recovery`, async ({ page }, info) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    const browserDiagnostics = captureBrowserDiagnostics(page);
    const calls = await setup(page);
    await page.route(/\/api\/v1\/cash-import\/sessions\/[^/]+$/, route =>
      route.request().method() === "DELETE" ? route.fulfill({ json: { ...calls.context, state: "cancelled", session_revision: 3 } }) : route.fallback());
    const diagnostic = { error_id: "a".repeat(32), stage: multi ? "relation_save" : "row_write", scope: multi ? "related" : "row",
      locations: [8, ...(multi ? [17] : [])].map(row => ({ file_ref: "file", display_name: "定位账单.csv", row_ref: `file:r${row}`,
        row_number: row, position_kind: "csv_record" })), total_locations: multi ? 2 : 1 };
    await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/commit$/, route => route.fulfill({ status: 503,
      json: { error: { code: "commit_failed", message: "private server exception", field_errors: [], retryable: true, diagnostic }, commit_state: "not_committed" } }));
    await uploadAndReview(page);
    await press(page, "cash.import.confirm");
    const result = page.locator('[id="cash.import.result"]:visible');
    await expect(result).toContainText("导入出错");
    await expect(result).toContainText("出错环节");
    await expect(result).toContainText("a".repeat(32));
    await expect(result).not.toContainText("private server exception");
    if (multi) {
      await expect(result).toContainText("无法确定具体哪一条记录");
      await expect(result).not.toContainText("定位账单.csv");
      const toggle = page.locator('[id="cash.import.diagnostic-toggle"]:visible');
      await page.mouse.click(1320, 800);
      let focusPixels = 0;
      for (let attempt = 0; attempt < 24 && focusPixels <= 500; attempt++) {
        await page.keyboard.press("Tab");
        focusPixels = await countDiagnosticFocusPixels(page, toggle);
      }
      expect(focusPixels).toBeGreaterThan(500);
      await page.keyboard.press("Enter");
      await expect(result).toContainText("第 17 条记录");
      await page.screenshot({ path: info.outputPath("diagnostic-expanded.png") });
      await expect(result.getByRole("button")).toHaveCount(2);
      await press(page, "cash.import.diagnostic-toggle");
      await expect(result).not.toContainText("第 17 条记录");
    } else {
      await expect(result).toContainText("定位账单.csv · 第 8 条记录");
      await expect(result.getByRole("button")).toHaveCount(1);
    }
    await page.screenshot({ path: info.outputPath("diagnostic.png") });
    expect(calls.resolves).toEqual([]);
    await press(page, "cash.import.return");
    await expect(page).toHaveURL(/workspace-1\/$/);
    await browserDiagnostics.settle();
    expect(browserDiagnostics.consoleErrors).toEqual([]);
    expect(browserDiagnostics.failedRequests).toEqual([]);
    expect(browserDiagnostics.staticHttpErrors).toEqual([]);
    expect(browserDiagnostics.expectedApiErrors).toHaveLength(1);
  });
}

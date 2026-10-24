import { expect, test, type Page } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

async function press(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  await page.waitForTimeout(200);
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing ${id}`);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}

async function setup(page: Page, unknown = false, unexpected = false) {
  const fixtures = await installComposeApiFixtures(page);
  const session = "i".repeat(43), planId = `ip_${session}.${"p".repeat(16)}`;
  const display = { occurred_at: "2026-10-24T04:00:00Z", amount: "-12.5000", currency: "CNY", counterparty: "待核对商户", note: "待核对备注", accounts: [] };
  const pending = { preview_item_id: "pending-item", confirmation_status: "pending", anticipated_state: "draft", display,
    statistics_eligible: false, member_refs: [{ row_ref: "row-pending" }], attention_reasons: [], detail_available: true };
  const confirmed = { ...pending, preview_item_id: "confirmed-item", confirmation_status: "confirmed", anticipated_state: "posted",
    display: { ...display, counterparty: "正常商户" }, statistics_eligible: true, member_refs: [{ row_ref: "row-confirmed" }] };
  const context = { session_id: session, session_revision: 1, expires_at: "2099-01-01T00:00:00Z", state: "open",
    files: [{ file_ref: "file", client_file_ref: "client", display_name: "synthetic.csv", source_type: "alipay", source_scope_label: null,
      status: "ready", recognized_row_count: 2, issues: [], can_retry: false }], source_accounts: [], last_choices: null, last_plan_id: null,
    can_preview: true, receipt_id: null };
  const plan = { plan_id: planId, session_id: session, session_revision: 2, expires_at: context.expires_at,
    summary: { create_posted: 1, create_draft: 1 }, item_counts: { confirmed: 1, pending: 1 }, can_commit: true,
    items: [confirmed], file_results: context.files, pages: { items: null, processing_details: null }, invalidated_choices: [] };
  const receipt = { receipt_id: "import-receipt", plan_id: planId, operation_kind: "import", committed: true,
    created_at: "2026-10-24T04:00:00Z", status: unexpected ? "partial" : "completed", summary: { created_posted: 1, saved_draft: 1 },
    pending_item_count: 1, needs_attention_count: 1, projection_version: 2,
    commit_outcome: { has_new_failures: unexpected, has_business_writes: true, reason_codes: unexpected ? ["account_changed"] : [] }, pages: {} };
  const pages: string[] = [], commits: string[] = [], resolves: string[] = [];
  await page.route(/\/api\/v1\/cash-import\/sessions$/, route => route.fulfill({ status: 201, json: context }));
  await page.route(new RegExp(`/api/v1/cash-import/sessions/${session}/plans$`), route => route.fulfill({ json: plan }));
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items(?:\?|$)/, route => {
    const filter = new URL(route.request().url()).searchParams.get("confirmation_status") ?? "all";
    pages.push(filter);
    return route.fulfill({ json: { items: filter === "pending" ? [pending] : [confirmed, pending], next_cursor: null } });
  });
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/commit$/, route => {
    commits.push(route.request().headers()["idempotency-key"]);
    return route.fulfill(unknown ? { status: 503, json: { error: { code: "commit_state_unknown", message: "保存结果待确认", field_errors: [], retryable: false }, commit_state: "unknown" } } : { json: receipt });
  });
  await page.route(/\/api\/v1\/cash-mutations\/resolve$/, route => {
    resolves.push(route.request().postDataJSON().idempotency_key);
    return route.fulfill({ json: { status: "committed", receipt } });
  });
  return { fixtures, pages, commits, resolves };
}

async function uploadAndReview(page: Page, fromLedger = false) {
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
  await press(page, "cash.import.review");
  await expect(page.locator('[id="cash.import.items"]:visible')).toContainText("待核对商户");
}

test("cash import reviews pending pages in the main task and shows only the actual result summary", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const diagnostics = captureBrowserDiagnostics(page);
  const calls = await setup(page);
  await uploadAndReview(page);
  await expect(page.locator('[id="cash.import.items"]:visible')).not.toContainText("正常商户");
  expect(calls.pages).toEqual(["pending"]);
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

test("switching incomplete members never drops another member's unsaved fields", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page);
  const fields = { occurred_at: "2026-10-24T04:00:00Z", amount: "-12.50", currency: "CNY", counterparty: "待补齐成员" };
  await page.route(/\/api\/v1\/cash-import\/plans\/[^/]+\/items\/pending-item$/, route => route.fulfill({ json: {
    preview_item_id: "pending-item", members: ["row-a", "row-b"].map(row => ({ row_ref: row, planned_action: "create_draft",
      fields, editable_fields: ["amount"], allowed_actions: ["apply", "defer"] })),
    field_diffs: [], issues: [{ code: "incomplete_record", message: "补充缺失金额", field_path: "amount", blocking_scope: "row", next_action: "edit_record" }],
    relation_choices: [], relation_effects: [], relation_members: [],
  } }));
  await uploadAndReview(page);
  await press(page, "ledger.open-record");
  const warning = page.getByRole("button", { name: /补充缺失金额/ });
  await expect(warning).toBeVisible();
  const box = await warning.boundingBox();
  if (!box) throw new Error("Missing issue card");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await press(page, "cash.import.edit-member-row-a");
  await expect(page.locator('[id="cash.import.row-row-a.amount"]:visible')).toHaveCount(1);
  await expect(page.locator('[id="cash.import.row-row-b.amount"]:visible')).toHaveCount(0);
  const amount = page.locator('[id="cash.import.row-row-a.amount"]:visible').last();
  const amountBox = await amount.boundingBox();
  if (!amountBox) throw new Error("Missing member amount");
  await page.mouse.click(amountBox.x + amountBox.width / 2, amountBox.y + amountBox.height / 2);
  await page.keyboard.press("ControlOrMeta+A"); await page.keyboard.press("Backspace"); await page.keyboard.type("-15.00", { delay: 60 });
  await page.waitForTimeout(200);
  const saveBox = await page.locator('[id="cash.import.row-row-a.save"]:visible').last().boundingBox();
  if (!saveBox) throw new Error("Missing member save");
  await press(page, "cash.import.edit-member-row-b");
  await expect(page.getByText("放弃本次修改？", { exact: true })).toBeVisible();
  const keep = page.getByRole("button", { name: "继续编辑", exact: true });
  const keepBox = await keep.boundingBox();
  if (!keepBox) throw new Error("Missing continue action");
  await page.mouse.click(keepBox.x + keepBox.width / 2, keepBox.y + keepBox.height / 2);
  // Compose 1.12.1 can freeze its semantics after closing a dialog. Verify the
  // retained canvas form through the original physical action and outgoing patch.
  await page.waitForTimeout(250);
  const saved = page.waitForRequest(request => request.method() === "POST" && /\/plans$/.test(new URL(request.url()).pathname));
  await page.mouse.click(saveBox.x + saveBox.width / 2, saveBox.y + saveBox.height / 2);
  expect((await saved).postDataJSON().row_choices).toEqual([{ row_ref: "row-a", action: "apply", patch: { amount: "-15.00" } }]);
});


test("a completed import reloads the cached ledger even after visiting another page", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const calls = await setup(page);
  await uploadAndReview(page, true);
  const reads = () => calls.fixtures.calls.filter(call => call.method === "GET" && call.path.split("?")[0] === "/api/v1/cash-projections").length;
  const before = reads();
  expect(before).toBeGreaterThan(0);
  await press(page, "cash.import.confirm");
  await expect(page.locator('[id="cash.import.result"]:visible')).toContainText("导入完成");
  await press(page, "navigation-item-cash-categories");
  await expect(page).toHaveURL(/cash-categories$/);
  await press(page, "navigation-item-cash-ledger");
  await expect(page).toHaveURL(/workspace-1\/$/);
  await expect.poll(reads).toBeGreaterThan(before);
});

test("import is a full main-content task with its own title and a return action", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await setup(page);
  await page.goto("/w/workspace-1/");
  await page.waitForLoadState("networkidle");
  await press(page, "ledger.actions-fab"); await press(page, "ledger.import");
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1);
  await expect(page.getByText("导入账单", { exact: true })).toHaveCount(1);
  await expect(page.locator('[id="secondary-page-modal"]:visible')).toHaveCount(0);
  const box = await page.locator('[id="cash.import.task"]:visible').boundingBox();
  expect(box?.width ?? 0).toBeGreaterThan(1000);
  await page.waitForTimeout(300);
  await press(page, "cash.import.return");
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

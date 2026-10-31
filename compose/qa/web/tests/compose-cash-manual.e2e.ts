import { expect, test, type Page, type Locator } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures, typeIntoComposeInput } from "./compose-fixtures";

const diagnostics = new WeakMap<Page, ReturnType<typeof captureBrowserDiagnostics>>();
test.beforeEach(async ({ page }) => { diagnostics.set(page, captureBrowserDiagnostics(page, { expectedErrorStatuses: [409] })); });
test.afterEach(async ({ page }, info) => {
  if (info.status !== "passed") return;
  const observed = diagnostics.get(page)!; await observed.settle();
  expect(observed.consoleErrors).toEqual([]); expect(observed.failedRequests).toEqual([]);
  expect(observed.staticHttpErrors).toEqual([]);
});

async function bounds(page: Page, target: Locator) {
  const preferUp = /cash\.(?:entry|create)\.(?:amount|mode-|direction-)/.test(target.toString());
  for (let attempt = 0; attempt < 24; attempt++) {
    const box = await target.boundingBox({ timeout: 150 }).catch(() => null);
    if (box && box.y > 55 && box.y + box.height <= 900) {
      await page.waitForTimeout(250);
      const settled = await target.boundingBox({ timeout: 150 }).catch(() => null);
      if (!settled) continue;
      return settled;
    }
    await page.mouse.move(1320, 650);
    await page.mouse.wheel(0, (box ? box.y < 55 : preferUp) ? -300 : 300);
    await page.waitForTimeout(250);
  }
  await page.screenshot({ path: "/tmp/ft-compose-cash-evidence/manual-control-unreachable.png" });
  throw new Error(`Control was not reachable: ${target}; bounds ${JSON.stringify(await target.boundingBox({ timeout: 150 }).catch(() => null))}`);
}
async function click(page: Page, target: Locator) {
  const box = await bounds(page, target);
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
}
async function enter(page: Page, id: string, value: string) {
  await bounds(page, page.locator(`[id="${id}"]:visible`).last());
  await typeIntoComposeInput(page, `[id="${id}"]`, value, { replace: true });
}
async function press(page: Page, id: string) {
  if (id.startsWith("cash.create.mode-") || id.startsWith("cash.create.direction-")) {
    await page.mouse.click(1100, 43);
    await page.mouse.move(1320, 650); await page.mouse.wheel(0, -1500); await page.waitForTimeout(300);
  }
  await click(page, page.locator(`[id="${id}"]:visible`).last());
}

async function currentManual(page: Page, withEffects = false, accountAttributes: string[] = []) {
  const base = await installComposeApiFixtures(page);
  const fields: Record<string, unknown> = { amount: "-12.5000", currency: "CNY", occurred_at: "2026-10-24T04:00:00Z", account_id: "1",
    counterparty: "现金商户", counterparty_account: "", note: "原始备注", category_id: null, record_type: "consumption", record_subtype: "not_applicable" };
  const common: Record<string, unknown> = { note: "公共备注", counterparty: "现金商户", counterparty_account: "", category_id: null, counterparty_account_attrs: accountAttributes };
  const publicKeys = new Set(Object.keys(common));
  const editable = [...Object.keys(fields), "counterparty_account_attrs"];
  const context = "m".repeat(43), planId = `mp_${context}.${"p".repeat(16)}`;
  const requests: Array<Record<string, any>> = [];
  let commits = 0, saved = false, release: (() => void) | undefined;
  let hold: Promise<void> | undefined;
  const display = () => ({ occurred_at: fields.occurred_at, amount: fields.amount, currency: fields.currency,
    counterparty: common.counterparty, note: common.note, accounts: ["日常账户"] });
  await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, route => route.fulfill({ json: {
    items: [{ projection_id: "manual-11", record_id: "source-11", transaction_id: "11", ...display(), account: { id: 1, name: "日常账户", type: "cash" },
      economic_type: "expense", state: "posted", statistics_eligible: true, confirmation_status: "confirmed", attention_count: 0 }],
    start_index: 0, total_count: 1, items_before: 0, items_after: 0, month_positions: [],
    next_cursor: null, projection_version: saved ? 10 : 9, pending_item_count: 0, page_size: 20, filters: {}, filter_options: {}, monthly_summaries: [],
  } }));
  await page.route(/\/api\/v1\/cash-records\/11$/, route => route.fulfill({ json: {
    transaction_id: "11", state: "posted", row_revision: saved ? 4 : 3, record_origin: "manual", confirmation_status: "confirmed",
    fields, editable_fields: editable, issues: [], relations: [], entry: { entry_id: "entry-11", revision: saved ? 8 : 7,
      fields: common, manual_fields: [], editable_fields: editable, supports_transfer_edit: false },
  } }));
  await page.route(/\/api\/v1\/cash-mutations\/plans$/, route => {
    requests.push(route.request().postDataJSON());
    return route.fulfill({ json: { plan_id: planId, edit_context_id: context, plan_revision: 1, expires_at: "2099-01-01T00:00:00Z",
      summary: {}, item_counts: { confirmed: 1, pending: 0 }, can_commit: true, pages: { items: null }, invalidated_choices: [],
      items: withEffects ? [{ preview_item_id: "changed-11", anticipated_state: "posted", confirmation_status: "confirmed", display: display(),
        statistics_eligible: true, member_refs: [{ transaction_id: "11" }], attention_reasons: [], detail_available: true }] : [],
      additional_effects: withEffects ? [{ kind: "field_value_changed", reference: "11", field: "note" }] : [],
    } });
  });
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/items\/changed-11$/, route => route.fulfill({ json: {
    preview_item_id: "changed-11", members: [{ transaction_id: "11", row_revision: 3, planned_action: "update_posted",
      fields: { ...fields, ...requests.at(-1)?.patch }, editable_fields: [], allowed_actions: [] }], field_diffs: [{ transaction_id: "11", field: "note",
      current: common.note, incoming: requests.at(-1)?.patch.note, effective: requests.at(-1)?.patch.note, resolution: "apply" }],
    issues: [], relation_choices: [], relation_effects: [],
  } }));
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/commit$/, async route => {
    commits++;
    await hold;
    const patch = requests.at(-1)?.patch ?? {};
    for (const [key, value] of Object.entries(patch)) (publicKeys.has(key) ? common : fields)[key] = value;
    saved = true;
    await route.fulfill({ json: { receipt_id: "manual-receipt", plan_id: planId, operation_kind: "edit_entry", committed: true,
      created_at: "2026-10-25T00:00:00Z", status: "completed", summary: { updated_posted: 1 }, retained_transaction_id: "11" } });
  });
  return { base, requests, commits: () => commits,
    holdCommit: () => { hold = new Promise<void>(resolve => { release = resolve; }); }, release: () => release?.() };
}

async function openManual(page: Page) {
  await page.goto("/w/workspace-1/");
  await press(page, "ledger.record-manual-11");
  await expect(page.locator('[id="cash.current.detail"]:visible')).toBeVisible();
}

test("ordinary creation retains its input across modes and saves only the active direction", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page);
  await page.goto("/w/workspace-1/");
  await press(page, "ledger.actions-fab"); await press(page, "ledger.add");
  await enter(page, "cash.create.amount", "12.50");
  await press(page, "cash.create.mode-transfer"); await press(page, "cash.create.mode-ordinary");
  await expect(page.locator('[id="cash.create.amount"]:visible')).toContainText("12.50");
  await press(page, "cash.create.direction-income");
  await press(page, "cash.create.save");
  await expect.poll(() => api.base.state.recordWrites.length).toBe(1);
  expect(api.base.state.recordWrites[0]).toMatchObject({ amount: "12.50", account_name: "日常账户", record_type: "income" });
});

test("manual editor keeps amount with currency and actions visible while the full form scrolls", async ({ page }, info) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page, false, ["tail"]); await openManual(page);
  await expect(page.getByText("流水信息", { exact: true })).toHaveCount(0);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("账户");
  await expect(page.getByText("账号尾号", { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath("detail-account-attributes.png") });
  await press(page, "cash.current.edit");
  const amount = await bounds(page, page.locator('[id="cash.entry.amount"]:visible'));
  const currency = await page.locator('[id="cash.entry.currency"]:visible').boundingBox();
  expect(currency).not.toBeNull();
  expect(Math.abs(amount.y - currency!.y)).toBeLessThan(12);
  await enter(page, "cash.entry.amount", "invalid");
  await page.screenshot({ path: info.outputPath("invalid-amount.png") });
  await info.attach("amount-semantics", { body: await page.locator('[id="cash.entry.amount"]:visible').evaluate(node => node.outerHTML), contentType: "text/html" });
  expect((await page.locator('[id="cash.entry.amount"]:visible').boundingBox())!.height).toBeGreaterThan(amount.height);
  await press(page, "cash.entry.save");
  await bounds(page, page.locator('[id="cash.entry.error"]:visible'));
  await expect(page.locator('[id="cash.entry.error"]:visible')).toContainText("金额格式无效。");
  expect(api.requests).toHaveLength(0);
  await enter(page, "cash.entry.amount", "-12.5000");
  const save = page.locator('[id="cash.entry.save"]:visible');
  const before = await save.boundingBox();
  expect(before).not.toBeNull(); expect(before!.y + before!.height).toBeLessThanOrEqual(900);
  await enter(page, "cash.entry.counterparty_account", "visible-expanded-account");
  const after = await save.boundingBox();
  expect(after).not.toBeNull(); expect(Math.abs(before!.y - after!.y)).toBeLessThan(2);
  await press(page, "cash.entry.save");
  await expect.poll(api.commits).toBe(1);
  expect(api.requests[0].patch).toMatchObject({ counterparty_account: "visible-expanded-account" });
  expect(api.requests[0].patch).not.toHaveProperty("counterparty_account_attrs");
});

test("manual financial editing uses the current plan and never the retired PUT", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page); await openManual(page);
  await press(page, "cash.current.edit");
  await enter(page, "cash.entry.amount", "-23.7500");
  await press(page, "cash.entry.save");
  await expect.poll(api.commits).toBe(1);
  expect(api.requests[0]).toMatchObject({ intent: "edit_entry", transaction_id: "11", expected_revision: 3, entry_id: "entry-11",
    expected_entry_revision: 7, patch: { amount: "-23.7500" } });
  expect(api.base.calls.some(call => call.method === "PUT" && /cash-records/.test(call.path))).toBe(false);
});

test("current entry editing keeps one panel and retains unsaved input when continuing", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page); await openManual(page);
  const panel = page.locator('[id="ledger.record-panel"]:visible');
  const before = await panel.boundingBox();
  await press(page, "cash.current.edit");
  await expect(panel).toHaveCount(1);
  await expect.poll(() => panel.boundingBox()).toEqual(before);
  await expect(page.locator('[id="ledger.record-panel.title"]:visible')).toContainText("编辑收支");
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await enter(page, "cash.entry.note", "保留本次备注");
  await press(page, "cash.entry.save");
  // Save the physical position after scrolling, then reopen a dirty edit to exercise cancellation.
  await expect.poll(api.commits).toBe(1);
  await press(page, "cash.current.edit");
  await enter(page, "cash.entry.note", "继续编辑的备注");
  const save = page.locator('[id="cash.entry.save"]:visible').last();
  const saveBox = await bounds(page, save);
  await press(page, "cash.entry.cancel");
  await expect(page.getByText("放弃未保存的修改？", { exact: true })).toBeVisible();
  const keep = page.getByRole("button", { name: "继续编辑", exact: true });
  // Capture canvas form position before closing the confirmation freezes its semantics mirror.
  await click(page, keep);
  if (!saveBox) throw new Error("Missing retained form action");
  await page.waitForTimeout(250);
  await page.mouse.click(saveBox.x + saveBox.width / 2, saveBox.y + saveBox.height / 2);
  await expect.poll(api.commits).toBe(2);
  expect(api.requests[1].patch).toEqual({ note: "继续编辑的备注" });
});

test("actual additional effects require confirmation and an in-flight save cannot be repeated or closed", async ({ page }, info) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page, true); api.holdCommit();
  try {
    await openManual(page); await press(page, "cash.current.edit");
    await enter(page, "cash.entry.note", "确认后的备注");
    await press(page, "cash.entry.save");
    await expect(page.locator('[id="cash.entry.save"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="cash.entry.review"]:visible')).toBeVisible();
    await expect(page.locator('[id="cash.entry.review"]:visible').getByText("现金商户", { exact: true })).toHaveCount(1);
    await press(page, "cash.maintenance.cancel");
    await expect(page.locator('[id="cash.entry.note"]:visible')).toContainText("确认后的备注");
    await press(page, "cash.entry.save");
    await expect(page.locator('[id="cash.entry.review"]:visible')).toBeVisible();
    const confirmation = await bounds(page, page.locator('[id="cash.maintenance.confirm"]:visible').last());
    await page.screenshot({ path: info.outputPath("additional-effects-confirmation.png") });
    const close = await page.locator('[id="ledger.record-panel.close"]:visible').last().boundingBox();
    await page.mouse.click(confirmation.x + confirmation.width / 2, confirmation.y + confirmation.height / 2);
    await expect.poll(api.commits).toBe(1);
    await page.mouse.click(confirmation.x + confirmation.width / 2, confirmation.y + confirmation.height / 2);
    if (!close) throw new Error("Missing panel close");
    await page.mouse.click(close.x + close.width / 2, close.y + close.height / 2);
    await page.waitForTimeout(250);
    expect(api.commits()).toBe(1);
    await expect(page.locator('[id="ledger.record-panel"]:visible')).toHaveCount(1);
    api.release();
    await expect(page.locator('[id="cash.entry.editor"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("确认后的备注");
  } finally { api.release(); }
});

for (const dismissal of ["backdrop", "drag"] as const) {
  test(`current detail ${dismissal} restores the ledger canvas`, async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await currentManual(page);
    await page.goto("/w/workspace-1/");
    await expect(page.locator('[id="ledger.record-manual-11"]:visible')).toBeVisible();
    await page.mouse.move(300, 230); await page.waitForTimeout(500);
    const ledgerCanvas = await page.screenshot();
    await press(page, "ledger.record-manual-11");
    await expect(page.locator('[id="cash.current.detail"]:visible')).toBeVisible();
    if (dismissal === "backdrop") await page.mouse.click(300, 230);
    else {
      const panel = page.locator('[id="ledger.record-panel"]:visible');
      const before = await panel.boundingBox();
      await page.mouse.move(970, 180); await page.mouse.down(); await page.mouse.move(1020, 180, { steps: 8 }); await page.mouse.up();
      await page.waitForTimeout(300);
      expect(Math.abs((await panel.boundingBox())!.x - before!.x)).toBeLessThan(2);
      await page.mouse.move(970, 180); await page.mouse.down(); await page.mouse.move(1170, 180, { steps: 16 }); await page.mouse.up();
    }
    await page.mouse.move(300, 230); await page.waitForTimeout(500);
    expect((await page.screenshot()).equals(ledgerCanvas)).toBe(true);
    await page.screenshot({ path: `/tmp/ft-compose-cash-evidence/current-${dismissal}-closed-1440.png` });
    // The visual equality assertion is authoritative for canvas closure. Accessibility
    // after popup dismissal is separately deferred for the retained Compose version.
  });
}

test("discarding unsaved manual changes restores the detail without a write", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page); await openManual(page);
  await page.mouse.move(300, 230); await page.waitForTimeout(500);
  const originalDetail = await page.screenshot();
  await press(page, "cash.current.edit");
  await enter(page, "cash.entry.note", "放弃这次输入");
  await press(page, "cash.entry.cancel");
  await click(page, page.getByRole("button", { name: "放弃修改", exact: true }));
  await page.mouse.move(300, 230); await page.waitForTimeout(500);
  expect((await page.screenshot()).equals(originalDetail)).toBe(true);
  expect(api.requests).toHaveLength(0); expect(api.commits()).toBe(0);
});

test("a known uncommitted save failure retains input and only retries on a new save", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  const api = await currentManual(page);
  let attempts = 0;
  await page.route(/\/api\/v1\/cash-mutations\/plans\/[^/]+\/commit$/, route => {
    attempts++;
    return attempts === 1 ? route.fulfill({ status: 409, json: {
      error: { code: "record_changed", message: "记录已变化", field_errors: [], retryable: false }, commit_state: "not_committed",
    } }) : route.fallback();
  });
  await openManual(page); await press(page, "cash.current.edit");
  await enter(page, "cash.entry.note", "失败后保留的备注");
  await press(page, "cash.entry.save");
  await expect.poll(() => attempts).toBe(1);
  await page.mouse.move(1320, 650); await page.mouse.wheel(0, -1500); await page.waitForTimeout(300);
  await expect(page.locator('[id="cash.current.error"]:visible')).toBeVisible();
  await bounds(page, page.locator('[id="cash.entry.note"]:visible').last());
  await expect(page.locator('[id="cash.entry.note"]:visible')).toContainText("失败后保留的备注");
  expect(attempts).toBe(1); expect(api.commits()).toBe(0);
  await press(page, "cash.entry.save");
  await expect.poll(api.commits).toBe(1);
  expect(attempts).toBe(2); expect(api.requests).toHaveLength(2);
  expect(api.requests[1].patch).toEqual({ note: "失败后保留的备注" });
});

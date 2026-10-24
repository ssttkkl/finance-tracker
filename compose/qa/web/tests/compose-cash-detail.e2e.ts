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
    await expect(byId(page, "cash.current.detail")).toContainText("流水信息");
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
  await press(page, byId(page, "cash.current.sources-toggle"));
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

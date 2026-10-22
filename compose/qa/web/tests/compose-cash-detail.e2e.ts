import { expect, test, type Locator, type Page } from "@playwright/test";
import path from "node:path";
import { mkdir } from "node:fs/promises";
import { activeSession, captureBrowserDiagnostics, installComposeApiFixtures, type BrowserDiagnostics } from "./compose-fixtures";

const evidence = path.resolve("test-results/evidence/cash-detail-typography");
const byId = (page: Page, id: string) => page.locator(`[id="${id}"]`).last();
const diagnostics = new WeakMap<Page, BrowserDiagnostics>();

test.beforeEach(({ page }) => { diagnostics.set(page, captureBrowserDiagnostics(page)); });
test.afterEach(async ({ page }) => {
  const result = diagnostics.get(page)!;
  await result.settle();
  expect(result.consoleErrors).toEqual([]);
  expect(result.failedRequests).toEqual([]);
  expect(result.staticHttpErrors).toEqual([]);
});

async function click(page: Page, target: Locator) {
  await target.waitFor({ state: "visible" });
  const rendered: { box: Awaited<ReturnType<Locator["boundingBox"]>> } = { box: null };
  await expect.poll(async () => {
    rendered.box = await target.boundingBox();
    return rendered.box != null;
  }).toBe(true);
  let box = rendered.box;
  if (!box) throw new Error("Compose target has no rendered bounds");
  for (let attempt = 0; attempt < 4; attempt++) {
    await page.waitForTimeout(350);
    const next = await target.boundingBox();
    if (!next) continue;
    const stable = Math.abs(next.x - box.x) < 1 && Math.abs(next.y - box.y) < 1 && Math.abs(next.height - box.height) < 1;
    box = next;
    if (stable) break;
  }
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(350);
  return box;
}

async function openDetail(page: Page, width: number) {
  await page.goto("/w/workspace-1/");
  const row = byId(page, "ledger.record-projection-1");
  await row.waitFor({ state: "visible" });
  const target = width < 600 ? row : row.locator('[id="ledger.open-record"]').last();
  const entryBounds = await click(page, target);
  await byId(page, "ledger.record-panel").waitFor({ state: "visible" });
  return entryBounds!;
}

async function capture(page: Page, name: string) {
  await mkdir(evidence, { recursive: true });
  await page.waitForTimeout(350);
  await page.screenshot({ path: path.join(evidence, `${name}.png`) });
}

async function changedPixels(page: Page, before: Buffer, after: Buffer) {
  return page.evaluate(async ({ a, b }) => {
    const load = async (data: string) => {
      const image = new Image();
      image.src = `data:image/png;base64,${data}`;
      await image.decode();
      return image;
    };
    const [first, second] = await Promise.all([load(a), load(b)]);
    const canvas = document.createElement("canvas");
    canvas.width = first.naturalWidth;
    canvas.height = first.naturalHeight;
    const context = canvas.getContext("2d")!;
    context.drawImage(first, 0, 0);
    const previous = context.getImageData(0, 0, canvas.width, canvas.height).data;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(second, 0, 0);
    const current = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let changed = 0, samples = 0;
    for (let y = 12; y < canvas.height; y += 24) for (let x = 12; x < canvas.width; x += 24) {
      const offset = (y * canvas.width + x) * 4;
      const difference = Math.abs(current[offset] - previous[offset]) + Math.abs(current[offset + 1] - previous[offset + 1]) + Math.abs(current[offset + 2] - previous[offset + 2]);
      if (difference > 72) changed++;
      samples++;
    }
    return changed / samples;
  }, { a: before.toString("base64"), b: after.toString("base64") });
}

for (const width of [390, 768, 1440]) {
  test(`F-04 详情排版 ${width}：时间进入平铺字段且摘要只有金额与对方`, async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", error => errors.push(error.message));
    const api = await installComposeApiFixtures(page);
    await page.setViewportSize({ width, height: 1000 });
    const entry = await openDetail(page, width);
    const panel = byId(page, "ledger.record-panel");
    await expect(panel.getByText("发生时间", { exact: true })).toBeVisible();
    const summary = byId(page, "record.detail.summary");
    const facts = byId(page, "record.detail.facts");
    await expect(summary).toContainText("咖啡店");
    await expect(summary).not.toContainText("2026-09-24");
    await expect(facts).toContainText("2026-09-24");
    await expect(facts).toContainText("拿铁");
    const summaryBox = await summary.boundingBox();
    const headerBox = await byId(page, "ledger.record-panel.header").boundingBox();
    expect(summaryBox!.y - headerBox!.y - headerBox!.height).toBeCloseTo(25, 0);
    const factsBox = await facts.boundingBox();
    const panelBox = await panel.boundingBox();
    expect(factsBox!.x - panelBox!.x).toBeCloseTo(24, 0);
    expect(panelBox!.x + panelBox!.width - factsBox!.x - factsBox!.width).toBeCloseTo(24, 0);
    await expect(byId(page, "record.related.toggle")).toHaveCount(0);
    await capture(page, `single-${width}`);
    await click(page, byId(page, "record.detail.edit"));
    await expect(byId(page, "record.amount")).toBeVisible();
    await click(page, byId(page, "record.cancel"));
    await expect(summary).toContainText("咖啡店");
    await page.waitForTimeout(350);
    const beforeEscape = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
    // CMP-10623 can retain a closed Dialog's mirrored DOM. Verify the rendered
    // scene and a fresh API-backed interaction with the revealed ledger.
    expect(await changedPixels(page, beforeEscape, await page.screenshot())).toBeGreaterThan(0.02);
    const readsBefore = api.calls.filter(call => call.path === "/api/v1/cash-records/1001" && call.method === "GET").length;
    await page.mouse.click(entry.x + entry.width / 2, entry.y + entry.height / 2);
    await expect.poll(() => api.calls.filter(call => call.path === "/api/v1/cash-records/1001" && call.method === "GET").length).toBe(readsBefore + 1);
    expect(errors).toEqual([]);
  });

  test(`F-04 详情排版 ${width}：关联展开仍在同一面板且保留管理操作`, async ({ page }) => {
    await installComposeApiFixtures(page, { withAcceptedCashRelation: true });
    await page.setViewportSize({ width, height: 1000 });
    await openDetail(page, width);
    const content = byId(page, "record.related.content");
    await expect(content).toHaveCount(0);
    await click(page, byId(page, "record.related.toggle"));
    await expect(content).toContainText("当前流水");
    await expect(content).toContainText("便利店");
    await expect(content).toContainText("超市");
    await expect(page.locator('[id="ledger.record-panel"]')).toHaveCount(1);
    await capture(page, `related-${width}`);
    const panelBox = await byId(page, "ledger.record-panel").boundingBox();
    await page.mouse.move(panelBox!.x + panelBox!.width / 2, 600);
    await page.mouse.wheel(0, 1200);
    await page.waitForTimeout(250);
    await expect(byId(page, "record.related.kind.edit-trigger")).toBeVisible();
    await expect(byId(page, "record.related.unlink-trigger")).toBeVisible();
    await expect(byId(page, "record.detail.edit")).toBeVisible();
    await capture(page, `related-scrolled-${width}`);
  });

  test(`F-04 详情排版 ${width}：长字段与关联金额可完整阅读`, async ({ page }) => {
    await installComposeApiFixtures(page);
    await page.setViewportSize({ width, height: 1100 });
    await page.emulateMedia({ colorScheme: width === 768 ? "dark" : "light" });
    const note = "跨地区服务及账单核对备注。".repeat(8);
    const merchant = "长名称商户与跨地区业务服务中心";
    const amount = "12345678901234567890.123456789";
    const record = {
      id: 1001, occurred_at: "2026-09-24T10:30:00+08:00", amount: "28.50", currency: "CNY",
      counterparty: merchant, counterparty_account: "BANK-123456789012345678901234567890",
      note, record_type: "expense", record_subtype: "not_applicable",
      account_name: "日常账户与跨地区结算账户", account_id: 1, account_type: "cash",
      category_id: "coffee", category: { id: "coffee", name: "咖啡与早餐", path: [{ id: "food", name: "日常生活与家庭消费" }, { id: "dining", name: "餐饮及聚会" }] },
    };
    await page.route("**/api/v1/cash-records/1001", async route => {
      if (route.request().method() !== "GET") return route.fallback();
      await route.fulfill({ json: {
        record,
        relations: [{ id: "relation-1", kind: "payment_mirror", label: "同笔支付", status: "accepted", primary_record: record, secondary_record: { ...record, id: 1002, amount, note: "" } }],
        options: {},
      } });
    });
    await openDetail(page, width);
    const noteValue = byId(page, "record.detail.fact.note.value");
    await expect(noteValue).toContainText(note);
    const facts = await byId(page, "record.detail.facts").boundingBox();
    const value = await noteValue.boundingBox();
    expect(value!.x).toBeGreaterThanOrEqual(facts!.x);
    expect(value!.x + value!.width).toBeLessThanOrEqual(facts!.x + facts!.width + 1);
    const panel = await byId(page, "ledger.record-panel").boundingBox();
    await page.mouse.move(panel!.x + panel!.width / 2, 700);
    await page.mouse.wheel(0, 500);
    await page.waitForTimeout(200);
    await capture(page, `long-fields-${width}`);
    await click(page, byId(page, "record.related.toggle"));
    const related = byId(page, "record.related.content");
    await expect(related).toContainText(`${amount} CNY`);
    await page.mouse.move(panel!.x + panel!.width / 2, 700);
    await page.mouse.wheel(0, 1800);
    await page.waitForTimeout(250);
    await capture(page, `long-related-${width}`);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(byId(page, "record.detail.edit")).toBeVisible();
  });

  test(`F-04 详情排版 ${width}：更多菜单与删除确认仍可取消`, async ({ page }) => {
    const api = await installComposeApiFixtures(page, { withAcceptedCashRelation: true });
    await page.setViewportSize({ width, height: 1000 });
    await openDetail(page, width);
    await click(page, byId(page, "record.detail.more-actions"));
    await expect(byId(page, "record.detail.action.associate")).toBeVisible();
    await expect(byId(page, "record.detail.action.dissolve")).toBeVisible();
    await capture(page, `more-actions-${width}`);
    await click(page, byId(page, "record.detail.action.delete"));
    await expect(byId(page, "ledger.record.delete-dialog")).toBeVisible();
    await capture(page, `delete-confirm-${width}`);
    await click(page, byId(page, "record.delete.cancel"));
    await expect(byId(page, "record.detail.summary")).toContainText("咖啡店");
    expect(api.calls.filter(call => call.method === "DELETE")).toEqual([]);
  });
}

test("F-04 详情排版：加载失败可重试", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 900 });
  let release!: () => void;
  let first = true;
  const held = new Promise<void>(resolve => { release = resolve; });
  await page.route("**/api/v1/cash-records/1001", async route => {
    if (!first) return route.fallback();
    first = false;
    await held;
    await route.fulfill({ status: 503, json: { detail: "temporarily unavailable" } });
  });
  try {
    await openDetail(page, 390);
    await expect(byId(page, "record.detail.summary")).toHaveCount(0);
    await capture(page, "loading-390");
    release();
    const retry = byId(page, "ledger.record-panel").getByRole("button", { name: "重试", exact: true });
    await expect(retry).toBeVisible();
    await capture(page, "error-390");
    await click(page, retry);
    await expect(byId(page, "record.detail.facts")).toContainText("发生时间");
    await capture(page, "retried-390");
  } finally { release(); }
});

test("F-04 详情排版：只读成员能查看且没有写操作", async ({ page }) => {
  await installComposeApiFixtures(page, { initialSession: { ...activeSession, workspaces: [{ ...activeSession.workspaces[0], role: "viewer" }] } });
  await page.setViewportSize({ width: 768, height: 1000 });
  await openDetail(page, 768);
  await expect(byId(page, "record.detail.facts")).toContainText("发生时间");
  await expect(byId(page, "record.detail.edit")).toHaveCount(0);
  await expect(byId(page, "record.detail.more-actions")).toHaveCount(0);
  await capture(page, "readonly-768");
});

for (const label of ["消费", "收入"]) {
  test(`F-04 详情排版：类型显示${label === "消费" ? "相同去重" : "不同保留"}`, async ({ page }) => {
    await installComposeApiFixtures(page);
    if (label === "收入") await page.route("**/api/v1/cash-records/1001", route => route.fulfill({ json: {
      record: { id: 1001, occurred_at: "2026-09-24T10:30:00+08:00", amount: "28.50", currency: "CNY", counterparty: "咖啡店", counterparty_account: "", note: "拿铁", category: null, category_id: null, record_type: "income", record_subtype: "not_applicable", account_name: "日常账户", account_id: 1, account_type: "cash" },
      relations: [], options: {},
    } }));
    await openDetail(page, 390);
    const facts = byId(page, "record.detail.facts");
    await expect(facts.getByText("消费", { exact: true })).toHaveCount(1);
    await expect(facts.getByText("流水类型", { exact: true })).toHaveCount(1);
    await expect(facts.getByText("收支类型", { exact: true })).toHaveCount(label === "消费" ? 0 : 1);
    if (label === "收入") await expect(facts.getByText("收入", { exact: true })).toHaveCount(1);
  });
}

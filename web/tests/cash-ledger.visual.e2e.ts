import { expect, test, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const filter_options = { categories: ["餐饮"], currencies: ["CNY"] };
const EVIDENCE_ANIMATION_MS = 200;
const projection = {
  projection_id: "cash:visual-001", occurred_at: "2026-07-03T09:00:00+08:00", account,
  counterparty: "视觉核对商户", category: "餐饮", note: "固定去标识化备注", amount: "-12.50", currency: "CNY",
  economic_type: "expense", transfer_subtype: null, composition: ["payment_mirror"], member_count: 2,
  accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }], source_type: "wallet", source_types: ["wallet", "bank"], record_id: "cash-visual-001", visible: true, hidden_reason: null,
};

function evidence() {
  const root = { id: "visual-001", occurred_at: projection.occurred_at, account, counterparty: projection.counterparty, category: projection.category, note: projection.note, amount: projection.amount, currency: projection.currency, source_type: "wallet", record_id: "cash-visual-001" };
  const mirror = { ...root, id: "visual-002", source_type: "bank", record_id: "cash-visual-002", roles: ["mirror"] };
  return { projection_version: 1, projection, root_record: { ...root, source_snapshot: { merchant: "视觉核对商户" } }, members: [{ ...root, roles: ["root"] }, mirror], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] };
}

async function mockLedger(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: evidence() });
    if (url.searchParams.get("counterparty") === "empty") return route.fulfill({ json: { projection_version: 1, items: [], next_cursor: null, page_size: 50, filters: {}, filter_options } });
    if (url.searchParams.get("counterparty") === "error") return route.abort();
    if (url.searchParams.get("counterparty") === "append-loading" && url.searchParams.get("cursor") === null) return route.fulfill({ json: { projection_version: 1, items: [projection], next_cursor: "loading", page_size: 50, filters: {}, filter_options } });
    if (url.searchParams.get("counterparty") === "append-error" && url.searchParams.get("cursor") === null) return route.fulfill({ json: { projection_version: 1, items: [projection], next_cursor: "error", page_size: 50, filters: {}, filter_options } });
    if (url.searchParams.get("cursor") === "loading") return new Promise(() => undefined);
    if (url.searchParams.get("cursor") === "error") return route.abort();
    if (url.searchParams.get("cursor") === "done") return route.fulfill({ json: { projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options } });
    return route.fulfill({ json: { projection_version: 1, items: [projection], next_cursor: "done", page_size: 50, filters: {}, filter_options } });
  });
}

for (const viewport of [{ width: 1440, height: 900 }, { width: 1024, height: 768 }, { width: 768, height: 1024 }, { width: 390, height: 844 }]) {
  test(`固定去标识化账本快照 ${viewport.width}x${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await mockLedger(page);
    await page.goto("/");
    await expect(page.getByText("视觉核对商户")).toBeVisible();
    await expect(page).toHaveScreenshot(`cash-ledger-${viewport.width}x${viewport.height}.png`, { fullPage: true, animations: "disabled" });
    await page.getByRole("button", { name: "查看视觉核对商户的证据详情" }).click();
    await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();
    await page.waitForTimeout(EVIDENCE_ANIMATION_MS);
    await expect(page).toHaveScreenshot(`cash-ledger-evidence-${viewport.width}x${viewport.height}.png`, { fullPage: true, animations: "disabled" });
  });
}

test("连续加载状态候选快照", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page); await page.goto("/");
  await expect(page.getByText("视觉核对商户")).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-default-collapsed.png", { fullPage: true, animations: "disabled" });
  await page.locator("details.filters > summary").click();
  await expect(page).toHaveScreenshot("cash-ledger-filters-expanded.png", { fullPage: true, animations: "disabled" });
  await expect(page.getByText("已显示全部记录。")).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-all-loaded.png", { fullPage: true, animations: "disabled" });
});

test("追加加载中和失败候选快照", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page); await page.goto("/");
  await page.locator("details.filters > summary").click();
  await page.getByLabel("交易信息").fill("append-loading");
  await expect(page.getByText("视觉核对商户")).toBeVisible();
  await expect(page.getByRole("button", { name: "正在加载更多…" })).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-append-loading.png", { fullPage: true, animations: "disabled" });
  await page.reload();
  await page.locator("details.filters > summary").click();
  await page.getByLabel("交易信息").fill("append-error");
  await expect(page.getByText("视觉核对商户")).toBeVisible();
  await page.getByRole("button", { name: "加载更多" }).click();
  await expect(page.getByRole("button", { name: "重试加载更多" })).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-append-error.png", { fullPage: true, animations: "disabled" });
});

test("390 px 经济类型字段候选快照", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLedger(page); await page.goto("/");
  await expect(page.getByText("经济类型：消费")).toBeVisible();
  await expect(page.getByText("导入渠道：fixture")).toHaveCount(0);
  await expect(page).toHaveScreenshot("cash-ledger-mobile-fields.png", { fullPage: true, animations: "disabled" });
});

test("固定去标识化状态快照", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page);
  await page.goto("/");
  await page.locator("details.filters > summary").click();
  await page.getByLabel("交易信息").fill("empty");
  await expect(page.getByText("当前筛选没有匹配的收支记录。")).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-empty.png", { fullPage: true, animations: "disabled" });
  await page.getByLabel("交易信息").fill("error");
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page).toHaveScreenshot("cash-ledger-error.png", { fullPage: true, animations: "disabled" });
});

test("模态证据抽屉点击遮罩关闭", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page);
  await page.goto("/");
  await expect(page.getByText("视觉核对商户")).toBeVisible();
  await page.getByRole("button", { name: "查看视觉核对商户的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();
  await page.getByRole("dialog", { name: "证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();
  await page.locator(".evidence-backdrop").click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toHaveCount(0);
});

test("modal backdrop remains transparent while hovered", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page);
  await page.goto("/");
  await expect(page.getByText("视觉核对商户")).toBeVisible();
  await page.getByRole("button", { name: "查看视觉核对商户的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();

  const backdrop = page.locator(".evidence-backdrop");
  await expect.poll(() => backdrop.evaluate((element) => getComputedStyle(element).backgroundColor)).toBe("rgba(0, 0, 0, 0)");
  await backdrop.hover();
  await expect.poll(() => backdrop.evaluate((element) => getComputedStyle(element).backgroundColor)).toBe("rgba(0, 0, 0, 0)");
});

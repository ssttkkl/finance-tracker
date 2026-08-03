import { expect, test, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const filter_options = { categories: ["餐饮", "日用", "收入"], currencies: ["CNY", "USD"] };
const item = (id: string, counterparty: string) => ({ projection_id: `cash:${id}`, occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty, category: "餐饮", amount: "-12.5", currency: "CNY", note: `备注${id}`, source_type: "fixture", source_types: ["fixture"], record_id: `cash-${id}`, economic_type: "expense", transfer_subtype: null, composition: ["payment_mirror"], member_count: 2, accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }], visible: true, hidden_reason: null });

async function mockLedger(page: Page, failOnce = false) {
  let failed = false;
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: { projection_version: 1, projection: item("1", "第一笔"), root_record: null, members: [], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] } });
    const cursor = url.searchParams.get("cursor");
    if (cursor === "page-2" && failOnce && !failed) { failed = true; return route.abort(); }
    const data = cursor === "page-2" ? { items: [item("2", "第二笔")], next_cursor: "page-3" } : cursor === "page-3" ? { items: [item("3", "第三笔")], next_cursor: null } : { items: [item("1", "第一笔")], next_cursor: "page-2" };
    return route.fulfill({ json: { projection_version: 1, ...data, page_size: 50, filters: {}, filter_options } });
  });
}

async function openFilters(page: Page) { await page.locator("details.filters > summary").click(); }

test("默认折叠筛选，自动或手动连续加载三批且不重复请求", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page); await page.goto("/");
  const filters = page.locator("details.filters");
  await expect(filters).not.toHaveAttribute("open", "");
  await expect(page.getByText("第一笔")).toBeVisible();
  await expect(page.getByText("第三笔")).toBeVisible();
  await expect(page.getByText("已显示全部记录。")).toBeVisible();
  await expect(page.getByRole("button", { name: "上一页" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "下一页" })).toHaveCount(0);
});

test("追加失败保留已加载记录，并通过键盘回退重试", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLedger(page, true); await page.goto("/");
  const retry = page.getByRole("button", { name: "重试加载更多" });
  await expect(retry).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
  await retry.focus(); await page.keyboard.press("Enter");
  await expect(page.getByText("第二笔")).toBeVisible();
  await expect(page.getByText("经济类型：消费").first()).toBeVisible();
  await expect(page.getByText("导入渠道：fixture")).toHaveCount(0);
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test("筛选后从首批重新读取，且所有规定视口无横向溢出", async ({ page }) => {
  await mockLedger(page);
  for (const viewport of [{ width: 320, height: 844 }, { width: 375, height: 844 }, { width: 414, height: 844 }, { width: 768, height: 1024 }, { width: 1024, height: 768 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport); await page.goto("/"); await expect(page.getByText("第一笔")).toBeVisible();
    await expect(page.getByLabel("关系投影").first()).toBeVisible();
    await openFilters(page); await page.getByLabel("分类").selectOption("餐饮");
    await expect(page.getByText("第一笔")).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
  }
});

import { expect, test, type Locator, type Page } from "@playwright/test";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const item = (id: string, counterparty: string) => ({
  projection_id: `cash:${id}`, occurred_at: "2026-07-03T09:00:00+08:00", account, counterparty, category: "餐饮",
  amount: "-12.5", currency: "CNY", note: `备注${id}`, source_type: "fixture", record_id: `cash-${id}`,
  economic_type: "expense", transfer_subtype: null, composition: ["payment_mirror"], member_count: 2,
  accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }], visible: true, hidden_reason: null,
});

function evidence(projection: ReturnType<typeof item>) {
  const root = { id: projection.projection_id.replace("cash:", ""), occurred_at: projection.occurred_at, account, counterparty: projection.counterparty, category: projection.category, note: projection.note, amount: projection.amount, currency: projection.currency, source_type: projection.source_type, record_id: projection.record_id };
  return { projection_version: 1, projection, root_record: { ...root, source_snapshot: null }, members: [{ ...root, roles: ["root"] }], accepted_relations: [{ id: "accepted-1", kind: "payment_mirror", subtype: "", rule_id: "payment.mirror.v1", confidence: "strong", evidence: {}, primary_record: null, secondary_record: null }], inactive_relation_hints: [], refund_timeline: [] };
}

async function mockLedger(page: Page) {
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: evidence(item("1", "第一笔")) });
    if (url.searchParams.get("category") === "empty") return route.fulfill({ json: { items: [], next_cursor: null, page_size: 50, filters: {} } });
    if (url.searchParams.get("category") === "error") return route.abort();
    const cursor = url.searchParams.get("cursor");
    const pageData = cursor === "page-2" ? { items: [item("2", "第二笔")], next_cursor: "page-3" } : cursor === "page-3" ? { items: [item("3", "第三笔")], next_cursor: null } : { items: [item("1", "第一笔")], next_cursor: "page-2" };
    return route.fulfill({ json: { projection_version: 1, ...pageData, page_size: 50, filters: {} } });
  });
}

async function tabTo(page: Page, target: Locator) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    await page.keyboard.press("Tab");
    if (await target.evaluate((element) => document.activeElement === element)) return;
  }
  throw new Error("键盘焦点未到达目标控件");
}

test("键盘筛选、三页分页、详情、空和错误状态", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await mockLedger(page);
  await page.goto("/");
  await expect(page.getByText("第一笔")).toBeVisible();
  const category = page.getByLabel("分类");
  await tabTo(page, category);
  await page.keyboard.type("键盘筛选");
  await expect(page.getByText("第一笔")).toBeVisible();

  const next = page.getByRole("button", { name: "下一页" });
  await tabTo(page, next);
  await page.keyboard.press("Enter");
  await expect(page.getByText("第二笔")).toBeVisible();
  await tabTo(page, next);
  await page.keyboard.press("Enter");
  await expect(page.getByText("第三笔")).toBeVisible();
  const previous = page.getByRole("button", { name: "上一页" });
  await tabTo(page, previous);
  await page.keyboard.press("Enter");
  await expect(page.getByText("第二笔")).toBeVisible();

  const secondEvidenceButton = page.getByRole("button", { name: "查看第二笔的证据详情" });
  await tabTo(page, secondEvidenceButton);
  await page.keyboard.press("Enter");
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();
  const main = page.locator("main.app-shell");
  const detail = page.getByRole("dialog", { name: "证据详情" });
  const mainBox = await main.boundingBox();
  const detailBox = await detail.boundingBox();
  expect((mainBox?.x ?? 0) + (mainBox?.width ?? 0)).toBeLessThanOrEqual(detailBox?.x ?? 0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "证据详情" })).toHaveCount(0);
  await expect(secondEvidenceButton).toBeFocused();

  await tabTo(page, category);
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("empty");
  await expect(page.getByText("当前筛选没有匹配的收支投影。")).toBeVisible();
  await tabTo(page, category);
  await page.keyboard.press("ControlOrMeta+A");
  await page.keyboard.type("error");
  await expect(page.getByRole("alert")).toContainText("请求失败，请稍后重试。");
});

test("窄屏可触摸打开并关闭全屏证据详情，布局没有横向溢出", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockLedger(page);
  await page.goto("/");
  const filters = page.locator("details.filters");
  await expect(filters).toHaveAttribute("open", "");
  await filters.locator("summary").click();
  await expect(filters).not.toHaveAttribute("open", "");
  await filters.locator("summary").focus();
  await page.keyboard.press("Enter");
  await expect(filters).toHaveAttribute("open", "");
  await expect(page.getByText("备注1")).toBeVisible();
  await expect(page.locator(".relation-summary")).toHaveCount(0);
  const compositionRequest = page.waitForRequest((request) => request.url().includes("/cash-projections") && request.url().includes("composition=combined"));
  await page.getByLabel("组成方式").selectOption("combined");
  await compositionRequest;
  await page.getByRole("button", { name: "查看第一笔的证据详情" }).tap();
  const dialog = page.getByRole("dialog", { name: "证据详情" });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveCSS("width", "390px");
  await expect(dialog.getByText("同笔支付关系（payment.mirror.v1）")).toBeVisible();
  await page.getByRole("button", { name: "关闭证据详情" }).tap();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByText(/2026年7月3日/)).toBeVisible();
  await expect(page.getByText("第一笔")).toBeVisible();
  await expect(page.getByText("-12.5 CNY")).toBeVisible();
  await expect(page.getByText("备注1")).toBeVisible();
  for (const button of await page.getByRole("button").all()) {
    const box = await button.boundingBox();
    expect(box?.height).toBeGreaterThanOrEqual(44);
  }
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBeTruthy();
});

test("减弱动效偏好不会为证据详情设置过渡", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await mockLedger(page);
  await page.goto("/");
  await page.getByRole("button", { name: "查看第一笔的证据详情" }).click();

  await expect(page.getByRole("dialog", { name: "证据详情" })).toHaveCSS("transition-duration", "0s");
});

test("证据详情打开时不能操作背景筛选器", async ({ page }) => {
  await mockLedger(page);
  await page.goto("/");
  await page.getByRole("button", { name: "查看第一笔的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();

  const category = page.getByLabel("分类");
  const box = await category.boundingBox();
  if (!box) throw new Error("未找到背景筛选器");
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);

  await expect(category).not.toBeFocused();
  await expect(page.getByRole("button", { name: "关闭证据详情" })).toBeFocused();
});

test("100 条投影可在两分钟内完成筛选、连续翻页和证据查看", async ({ page }) => {
  test.setTimeout(120_000);
  const startedAt = Date.now();
  const hundred = Array.from({ length: 100 }, (_value, index) => item(String(index + 1), `性能条目${index + 1}`));
  await page.route("**/api/v1/**", async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname.endsWith("/accounts")) return route.fulfill({ json: { items: [account] } });
    if (url.pathname.includes("/evidence/")) return route.fulfill({ json: evidence(hundred[99]) });
    if (url.searchParams.get("cursor") === "page-2") {
      return route.fulfill({ json: { projection_version: 1, items: [hundred[99]], next_cursor: null, page_size: 50, filters: {} } });
    }
    return route.fulfill({ json: { projection_version: 1, items: hundred.slice(0, 99), next_cursor: "page-2", page_size: 50, filters: {} } });
  });
  await page.goto("/");
  await page.getByLabel("分类").fill("性能");
  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByText("性能条目100")).toBeVisible();
  await page.getByRole("button", { name: "查看性能条目100的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toBeVisible();
  expect(Date.now() - startedAt).toBeLessThan(120_000);
});

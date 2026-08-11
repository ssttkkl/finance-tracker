import { expect, test } from "@playwright/test";

test("生产预览读取自包含 API 的账户和收支投影", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("暂时无法连接账本，请稍后重试。")).toHaveCount(0);
  await expect(page.getByRole("option", { name: "预览账户" })).toHaveCount(1);
  await expect(page.getByText("自包含预览投影")).toBeVisible();
  await expect(page.getByRole("cell", { name: "银证转账", exact: true })).toBeVisible();
  await expect(page.getByLabel("银证转账").first()).toBeVisible();
  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  await expect(page.getByRole("option", { name: "银证转账", exact: true })).toHaveAttribute("value", "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}");
  await page.getByRole("button", { name: "查看自包含预览投影的详情" }).click();
  await expect(page.getByRole("dialog", { name: "记录详情" })).toContainText("收支详情");
  await expect(page.getByText("无法读取详情。")).toHaveCount(0);
  await page.getByRole("button", { name: "关闭详情", exact: true }).click();
  await page.getByRole("button", { name: "查看Charles Schwab的详情" }).click();
  const bankSecurityDetail = page.getByRole("dialog", { name: "记录详情" });
  await expect(bankSecurityDetail).toContainText("银证转账");
  await expect(bankSecurityDetail.getByText("银证转账", { exact: true })).toHaveCount(2);
});

test("生产预览在窄屏保持银证转账双端金额可见", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
});

test("生产预览读取投资事件和关系证据", async ({ page }) => {
  await page.goto("/#investment-events");

  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByText("预览买入")).toBeVisible();
  await expect(page.getByText("-10,000 USD")).toBeVisible();
  await expect(page.getByText("+100 AAPL.US")).toBeVisible();
  await expect(page.getByText("价格不完整", { exact: true })).toHaveCount(0);
  await expect(page.locator(".evidence-trigger .ui-icon")).toBeVisible();
  await page.getByRole("button", { name: "查看预览买入的详情" }).click();
  const detail = page.getByRole("dialog", { name: "买入" });
  await expect(detail).toContainText("资产变动");
  await expect(detail).toContainText("-10,000 USD");
  await expect.poll(async () => detail.evaluate((node) => node.getBoundingClientRect().left)).toBeLessThan(await page.evaluate(() => window.innerWidth));
  await expect(detail).not.toContainText("资金流向");
  await page.getByRole("button", { name: "关闭", exact: true }).click();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  const firstRow = page.locator(".investment-table tbody tr.investment-row").first();
  await expect(firstRow).toBeVisible();
  await expect(firstRow.locator(".evidence-trigger .ui-icon")).toBeHidden();
  await firstRow.click({ position: { x: 180, y: 50 } });
  await expect(page.getByRole("dialog", { name: "买入" })).toBeVisible();
});

test("生产预览的三个账本路由共用同一棵侧边导航", async ({ page }) => {
  await page.goto("/#cash-ledger");
  const navigation = page.getByRole("navigation", { name: "主要导航" });
  await expect(navigation.getByRole("link")).toHaveText(["收支账本", "投资账本", "当前持仓", "投资事件"]);
  await expect(navigation.getByRole("link", { name: "收支账本" })).toHaveAttribute("aria-current", "page");
  await expect(navigation.getByRole("link", { name: "当前持仓" })).not.toHaveAttribute("aria-current");

  await navigation.getByRole("link", { name: "投资事件" }).click();
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主要导航" }).getByRole("link")).toHaveText(["收支账本", "投资账本", "当前持仓", "投资事件"]);
  await expect(page.getByRole("navigation", { name: "主要导航" }).getByRole("link", { name: "投资事件" })).toHaveAttribute("aria-current", "page");

  await page.getByRole("link", { name: "收支账本" }).click();
  await expect(page.getByRole("heading", { name: "收支账本", level: 1 })).toBeVisible();
});

test("生产预览移动端折叠菜单可展开并在选路由后收起", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#cash-ledger");

  const menu = page.getByRole("button", { name: "打开菜单" });
  await expect(menu).toBeVisible();
  await menu.click();
  await expect(page.getByRole("button", { name: "关闭菜单" })).toBeVisible();
  await expect(page.getByRole("link", { name: "投资事件" })).toBeVisible();

  await page.getByRole("link", { name: "投资事件" }).click();
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByRole("button", { name: "打开菜单" })).toBeVisible();
});

test("生产预览在 320/375/414/768 px 无页面级横向滚动", async ({ page }) => {
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/#investment-holdings");
    await expect(page.getByRole("heading", { name: "当前持仓", level: 1 })).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

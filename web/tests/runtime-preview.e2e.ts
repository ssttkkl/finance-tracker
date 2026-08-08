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
  await expect(page.getByText("10000.000000000000000001 USD")).toBeVisible();
  await expect(page.getByText("价格不完整", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "查看预览买入的详情" }).click();
  await expect(page.getByRole("dialog", { name: "投资详情" })).toContainText("资金流向");
  await page.getByRole("button", { name: "关闭", exact: true }).click();
});

test("生产预览在 320/375/414/768 px 无页面级横向滚动", async ({ page }) => {
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/#investment-holdings");
    await expect(page.getByRole("heading", { name: "当前持仓", level: 1 })).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

import { expect, test } from "@playwright/test";

test("生产预览读取自包含 API 的账户和收支投影", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("账本暂不可用，请稍后重试。")).toHaveCount(0);
  await expect(page.getByRole("option", { name: "预览账户" })).toHaveCount(1);
  await expect(page.getByText("示例商户")).toBeVisible();
  await expect(page.getByRole("cell", { name: "银证转账", exact: true })).toBeVisible();
  await expect(page.getByLabel("银证转账")).toBeVisible();
  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  await expect(page.getByRole("option", { name: "银证转账", exact: true })).toHaveAttribute("value", "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}");
  await page.getByRole("button", { name: "查看示例商户的收支详情" }).click();
  await expect(page.getByRole("dialog", { name: "收支详情" })).toContainText("收支详情");
  await expect(page.getByText("无法读取收支详情。")).toHaveCount(0);
  await page.getByRole("button", { name: "关闭收支详情", exact: true }).click();
  await page.getByRole("button", { name: "查看Charles Schwab的收支详情" }).click();
  const bankSecurityDetail = page.getByRole("dialog", { name: "收支详情" });
  await expect(bankSecurityDetail).toContainText("银证转账");
  await expect(bankSecurityDetail).toContainText("银证转账");
});

test("生产预览在窄屏保持银证转账双端金额可见", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
});

test("生产预览可打开流水编辑和独立导入处理页面", async ({ page }) => {
  await page.goto("/");

  await page.getByRole("button", { name: "新建流水" }).click();
  const recordDrawer = page.getByRole("dialog", { name: "新建流水" });
  await expect(recordDrawer.getByLabel("币种")).toHaveValue("CNY");
  await expect(recordDrawer.getByLabel("流水类型")).toHaveValue("consumption");
  await expect(recordDrawer.getByLabel("收入支出")).toHaveCount(0);
  await recordDrawer.locator("header button").click();

  await page.getByRole("button", { name: "导入账单" }).click();
  await expect(page).toHaveURL(/\/cash-import$/);
  await expect(page.getByRole("heading", { name: "选择账单文件" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "导入预览" })).toHaveCount(0);
});

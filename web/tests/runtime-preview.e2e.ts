import { expect, test } from "@playwright/test";

test("生产预览读取自包含 API 的账户和收支投影", async ({ page }) => {
  await page.goto("/");

  await expect(page.getByText("前端 API 地址无效。请设置 VITE_FT_API_ORIGIN 后重启。")).toHaveCount(0);
  await expect(page.getByRole("option", { name: "预览账户" })).toHaveCount(1);
  await expect(page.getByText("自包含预览投影")).toBeVisible();
  await expect(page.getByRole("cell", { name: "银证转账", exact: true })).toBeVisible();
  await expect(page.getByLabel("银证转账关系")).toBeVisible();
  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  await expect(page.getByRole("option", { name: "银证转账", exact: true })).toHaveAttribute("value", "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}");
  await page.getByRole("button", { name: "查看自包含预览投影的证据详情" }).click();
  await expect(page.getByRole("dialog", { name: "证据详情" })).toContainText("收支详情");
  await expect(page.getByText("无法读取证据详情。")).toHaveCount(0);
  await page.getByRole("button", { name: "关闭证据详情", exact: true }).click();
  await page.getByRole("button", { name: "查看Charles Schwab的证据详情" }).click();
  const bankSecurityDetail = page.getByRole("dialog", { name: "证据详情" });
  await expect(bankSecurityDetail).toContainText("银证转账");
  await expect(bankSecurityDetail).toContainText("银证转账关系");
});

test("生产预览在窄屏保持银证转账双端金额可见", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("row", { name: /预览账户 → 预览投资账户/ })).toContainText("10000 HKD → 1275.5 USD");
  expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
});

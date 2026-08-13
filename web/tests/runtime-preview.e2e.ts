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

test("生产预览可打开流水编辑和六渠道导入入口", async ({ page }) => {
  await page.goto("/");

  await page.getByRole("button", { name: "新建流水" }).click();
  const recordDrawer = page.getByRole("dialog", { name: "新建流水" });
  await expect(recordDrawer.getByLabel("币种")).toHaveValue("CNY");
  await expect(recordDrawer.getByLabel("流水类型")).toHaveValue("consumption");
  await expect(recordDrawer.getByLabel("收入支出")).toHaveCount(0);
  await recordDrawer.locator("header button").click();

  await page.getByRole("button", { name: "导入账单" }).click();
  const importDrawer = page.getByRole("dialog", { name: "导入账单" });
  await expect(importDrawer.getByLabel("账单渠道").locator("option")).toHaveCount(6);
});

test("生产预览完成分类创建和批量分类流程", async ({ page }) => {
  await page.goto("/cash-categories");

  const tree = page.getByRole("tree", { name: "收支分类目录" });
  await expect(tree.locator(":scope > li").last()).toContainText("新建一级分类");
  await tree.getByRole("button", { name: "新建一级分类" }).click();
  const categoryEditor = page.getByRole("region", { name: "分类编辑" });
  await categoryEditor.getByLabel("分类名称").fill("预览分类");
  await categoryEditor.getByRole("button", { name: "创建分类" }).click();
  await expect(tree).toContainText("预览分类");
  await expect(tree.locator(":scope > li").last()).toContainText("新建一级分类");

  await page.goto("/");
  await page.getByLabel("选择示例商户").check();
  await page.getByLabel("选择Charles Schwab").check();
  const toolbar = page.getByRole("toolbar", { name: "批量操作" });
  await expect(toolbar).toContainText("已选 2 项");
  await toolbar.getByRole("button", { name: "修改分类" }).click();
  const batch = page.getByRole("dialog", { name: "修改分类" });
  await batch.getByLabel("分类", { exact: true }).selectOption("preview-created");
  await batch.getByRole("button", { name: "保存" }).click();
  await expect(page.getByRole("toolbar", { name: "批量操作" })).toHaveCount(0);
  await expect(page.getByRole("row", { name: /示例商户/ })).toContainText("预览分类");
  await expect(page.getByRole("row", { name: /Charles Schwab/ })).toContainText("预览分类");
});

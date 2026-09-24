import path from "node:path";
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

test("生产预览在桌面和移动宽度保留禁止缩放 viewport", async ({ page }) => {
  const consoleErrors: string[] = [];
  const requestFailures: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") consoleErrors.push(message.text());
  });
  page.on("requestfailed", (request) => {
    requestFailures.push(`${request.method()} ${request.url()}: ${request.failure()?.errorText ?? "unknown"}`);
  });

  for (const [width, height] of [[390, 844], [1440, 900]]) {
    await page.setViewportSize({ width, height });
    await page.goto("/");
    await expect(page.locator('meta[name="viewport"]')).toHaveAttribute(
      "content",
      "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no",
    );
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  }

  expect(consoleErrors).toEqual([]);
  expect(requestFailures).toEqual([]);
});

test("生产预览在性能预算内分阶段展示当前持仓", async ({ page }) => {
  const started = Date.now();
  await page.goto("/investment-holdings");

  await expect(page.getByRole("cell", { name: "AAPL.US" })).toBeVisible({ timeout: 1_000 });
  const holdingsElapsed = Date.now() - started;
  await expect(page.getByRole("cell", { name: "101.25 USD" })).toBeVisible({ timeout: 2_000 });
  await expect(page.getByText("当前总市值").locator("..").getByText("1,012.50 USD")).toBeVisible();
  await expect(page.getByText("近 24 小时浮盈亏").locator("..").getByText("+8.04 USD")).toBeVisible();
  await expect(page.getByRole("cell", { name: "+8.04 USD" })).toBeVisible();
  const valuationElapsed = Date.now() - started;

  expect(holdingsElapsed).toBeLessThan(1_000);
  expect(valuationElapsed).toBeLessThan(2_000);
  await page.getByRole("button", { name: "刷新持仓" }).click();
  await expect(page.getByRole("cell", { name: "101.25 USD" })).toBeVisible();
});

test("当前持仓在目标响应式宽度保持可见且无横向溢出", async ({ page }) => {
  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/investment-holdings");
    await expect(page.getByText("AAPL.US", { exact: true })).toBeVisible();
    expect(await page.locator("body").evaluate((body) => body.scrollWidth <= window.innerWidth)).toBe(true);
  }
});

test("生产预览使用标的片段筛选投资事件", async ({ page }) => {
  await page.goto("/investment-events");

  await expect(page.getByText("预览买入")).toBeVisible();
  const filtered = page.waitForRequest((request) => request.url().includes("/api/v1/investment-events") && request.url().includes("ticker=apl"));
  await page.getByLabel("标的").fill("apl");
  await filtered;
  await expect(page.getByText("+10 AAPL.US", { exact: true })).toBeVisible();
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
  await expect(page).toHaveURL(/\/w\/preview-workspace\/cash-import$/);
  await expect(page.getByRole("heading", { name: "选择文件" })).toBeVisible();
  await expect(page.getByRole("button", { name: "下一步", exact: true })).toBeDisabled();
  await expect(page.getByRole("heading", { name: "核对流水" })).toHaveCount(0);
});

test("生产预览支持混合渠道批量选择、统一预览和提交", async ({ page }) => {
  await page.goto("/cash-import");

  const cashFile = path.resolve(process.cwd(), "../tests/fixtures/cash_import_browser_refund.csv");
  const brokerFile = path.resolve(process.cwd(), "../tests/fixtures/ibkr/transactions_1y_sample.csv");
  const fileInput = page.locator('input[type="file"]');
  const scanRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/cash-import/scan")) scanRequests.push(request.url());
  });

  await fileInput.setInputFiles([cashFile, cashFile]);
  await expect(page.getByText(/已选择 1\/20/)).toBeVisible();
  await expect(page.getByText("cash_import_browser_refund.csv", { exact: true })).toHaveCount(1);

  await fileInput.setInputFiles(brokerFile);
  await expect(page.getByText(/已选择 2\/20/)).toBeVisible();
  await expect(page.getByText("transactions_1y_sample.csv", { exact: true })).toHaveCount(1);
  expect(scanRequests).toEqual([]);

  await page.getByRole("button", { name: "删除" }).nth(1).click();
  await expect(page.getByText(/已选择 1\/20/)).toBeVisible();
  await fileInput.setInputFiles(brokerFile);
  await expect(page.getByText(/已选择 2\/20/)).toBeVisible();

  await page.getByRole("button", { name: "下一步" }).click();
  await expect(page.getByRole("heading", { name: "映射账户" })).toBeVisible();
  await expect(page.getByText("多渠道", { exact: true })).toBeVisible();
  await expect(page.getByText("预览现金账户", { exact: true })).toBeVisible();
  await expect(page.getByText("预览券商账户", { exact: true })).toBeVisible();

  await page.getByRole("button", { name: "确认映射" }).click();
  await expect(page.getByRole("heading", { name: "核对流水" })).toBeVisible();
  await expect(page.getByRole("row", { name: /预览现金记录/ })).toBeVisible();
  await expect(page.getByRole("row", { name: /预览券商记录/ })).toBeVisible();

  await page.getByRole("button", { name: "下一步" }).click();
  await expect(page.getByRole("heading", { name: "配对" })).toBeVisible();
  await page.getByRole("button", { name: "确认导入" }).click();
  const completedRegion = page.getByRole("region", { name: "导入完成" });
  await expect(completedRegion).toBeVisible();
  await expect(completedRegion.getByText("2", { exact: true })).toHaveCount(1);
});

test("生产预览在批量导入中逐文件处理密码并阻止解析失败文件继续", async ({ page }) => {
  await page.goto("/cash-import");
  const fileInput = page.locator('input[type="file"]');
  await fileInput.setInputFiles({ name: "locked.pdf", mimeType: "application/pdf", buffer: Buffer.from("encrypted") });
  await page.getByRole("button", { name: "下一步", exact: true }).click();

  const passwordInput = page.getByTestId("import.file-password.0");
  await expect(passwordInput).toBeVisible();
  await expect(page.getByRole("button", { name: "下一步", exact: true })).toBeDisabled();
  await passwordInput.fill("preview-password");
  await page.getByRole("button", { name: "下一步", exact: true }).click();
  await expect(page.getByRole("heading", { name: "映射账户" })).toBeVisible();

  await page.getByRole("button", { name: "1 选择文件" }).click();
  await page.getByRole("button", { name: "删除", exact: true }).click();
  await fileInput.setInputFiles({ name: "broken.pdf", mimeType: "application/pdf", buffer: Buffer.from("broken") });
  await page.getByRole("button", { name: "下一步", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("broken.pdf");
  await expect(page.getByRole("button", { name: "下一步", exact: true })).toBeDisabled();
});

test("生产预览提交失败时保留批量确认上下文并提示重试", async ({ page }) => {
  await page.goto("/cash-import");
  const fileInput = page.locator('input[type="file"]');
  await fileInput.setInputFiles({ name: "commit-failure.pdf", mimeType: "application/pdf", buffer: Buffer.from("commit failure") });
  await page.getByRole("button", { name: "下一步", exact: true }).click();
  await expect(page.getByRole("heading", { name: "映射账户" })).toBeVisible();
  await page.getByRole("button", { name: "确认映射" }).click();
  await expect(page.getByRole("heading", { name: "核对流水" })).toBeVisible();
  await page.getByRole("button", { name: "下一步", exact: true }).click();
  await expect(page.getByRole("heading", { name: "配对" })).toBeVisible();
  await page.getByRole("button", { name: "确认导入" }).click();
  await expect(page.getByRole("alert")).toContainText("确认导入失败，请重试");
  await expect(page.getByRole("heading", { name: "配对" })).toBeVisible();
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

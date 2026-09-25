import { expect, test, type Locator, type Page } from "@playwright/test";
import path from "node:path";
import {
  activeSession,
  cashAccount,
  captureBrowserDiagnostics,
  installComposeApiFixtures,
  noWorkspaceSession,
  typeIntoComposeInput,
  type BrowserDiagnostics,
} from "./compose-fixtures";

const workspaceRoot = "/w/workspace-1";
const responsiveWidths = [320, 375, 390, 414, 768, 1440];
const browserDiagnostics = new WeakMap<import("@playwright/test").Page, BrowserDiagnostics>();

async function clickComposeTarget(page: Page, locator: Locator, xRatio = 0.5, yRatio = 0.5): Promise<void> {
  const bounds = await locator.boundingBox();
  if (!bounds) throw new Error("Missing Compose target bounds");
  await page.mouse.click(bounds.x + bounds.width * xRatio, bounds.y + bounds.height * yRatio);
  await page.waitForTimeout(60);
}

test.beforeEach(({ page }) => {
  browserDiagnostics.set(page, captureBrowserDiagnostics(page));
});

test.afterEach(async ({ page }, testInfo) => {
  const diagnostics = browserDiagnostics.get(page);
  await diagnostics?.settle();
  if (diagnostics) {
    testInfo.annotations.push({
      type: "browser-diagnostics",
      description: JSON.stringify({
        consoleErrors: diagnostics.consoleErrors,
        failedRequests: diagnostics.failedRequests,
        expectedBrowserMessages: diagnostics.expectedBrowserMessages,
        expectedApiErrors: diagnostics.expectedApiErrors,
        staticHttpErrors: diagnostics.staticHttpErrors,
        wasmContentTypes: diagnostics.wasmContentTypes,
      }),
    });
  }
  if (testInfo.status === "passed" && diagnostics) {
    expect(diagnostics.consoleErrors).toEqual([]);
    expect(diagnostics.failedRequests).toEqual([]);
    expect(diagnostics.staticHttpErrors).toEqual([]);
    expect(diagnostics.wasmContentTypes.length).toBeGreaterThan(0);
    expect(diagnostics.wasmContentTypes.every((value) => value.startsWith("application/wasm"))).toBe(true);
  }
});

test("F-01 登录成功后打开当前工作区", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null, loginSession: activeSession });
  await page.goto("/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "owner@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });

  await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
});

test("F-02 可创建工作区并进入账本", async ({ page }) => {
  await installComposeApiFixtures(page, { initialSession: noWorkspaceSession });
  await page.goto("/");
  await expect(page.locator('[id="workspace.screen"]').last()).toContainText("创建工作区");

  await typeIntoComposeInput(page, '[id="workspace.create-name"]', "旅行账本");
  await page.locator('[id="workspace.create"]').last().click({ force: true });

  await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-created\/$/);
});

test("响应式导航在 regular 与 wide 宽度保留可见内容区", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/`);

  for (const width of [600, 768, 1023, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const ledger = page.locator('[id="ledger.screen"]').last();
    await expect(ledger).toBeVisible();
    const bounds = await ledger.boundingBox();
    expect(bounds?.width ?? 0).toBeGreaterThan(320);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

test("工作区主导航保留路径上下文并支持浏览器前进后退", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);

  const destinations = [
    { id: "cash-import", screen: "import.screen", path: "cash-import" },
    { id: "cash-categories", screen: "cash-categories.screen", path: "cash-categories" },
    { id: "investment-holdings", screen: "investment-holdings.screen", path: "investment-holdings" },
    { id: "investment-events", screen: "investment-events.screen", path: "investment-events" },
    { id: "workspace-management", screen: "workspace-management.screen", path: "workspace-management" },
  ];
  const visited = [{ url: `${workspaceRoot}/`, screen: "ledger.screen" }];

  for (const destination of destinations) {
    await page.locator('[id="navigation-open"]').last().click({ force: true });
    await page.waitForTimeout(350);
    await page.locator(`[id="navigation-item-${destination.id}"]`).last().dispatchEvent("click");
    const url = `${workspaceRoot}/${destination.path}`;
    visited.push({ url, screen: destination.screen });
    await expect(page).toHaveURL(url);
    await expect(page.locator(`[id="${destination.screen}"]`).last()).toBeVisible();
  }

  for (const route of visited.slice(0, -1).reverse()) {
    await page.goBack();
    await expect(page).toHaveURL(route.url);
    await expect(page.locator(`[id="${route.screen}"]`).last()).toBeVisible();
  }
  for (const route of visited.slice(1)) {
    await page.goForward();
    await expect(page).toHaveURL(route.url);
    await expect(page.locator(`[id="${route.screen}"]`).last()).toBeVisible();
  }
});

test("F-03 账本显示本地月份摘要并按账户筛选", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.summary"]').last()).toContainText("支出");

  await page.getByRole("button", { name: "账户 全部账户", exact: true }).last().click({ force: true });
  await page.getByRole("button", { name: "日常账户", exact: true }).last().click({ force: true });

  await expect.poll(() => api.calls.some((call) => call.path.includes("account_id=1"))).toBe(true);
  await page.mouse.click(380, 1700);
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.waitForTimeout(300);
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    const themeSuffix = process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "-dark" : "";
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForTimeout(3000);
    await page.screenshot({
      path: path.resolve(process.cwd(), `../openspec/changes/compose-multiplatform-client/screenshots/web-final-390${themeSuffix}.png`),
    });

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.waitForTimeout(500);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({
      path: path.resolve(process.cwd(), `../openspec/changes/compose-multiplatform-client/screenshots/web-final-1440${themeSuffix}.png`),
    });
  }
});

test("F-03 账本请求失败后可重试，空结果显示空态", async ({ page }) => {
  const api = await installComposeApiFixtures(page, { cashPageFailures: 1 });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  await page.mouse.move(220, 740);
  await page.mouse.wheel(0, 1200);
  await page.waitForTimeout(250);
  const retry = page.locator('[id="ledger.retry"]');
  await expect(retry).toContainText("重试");
  await page.mouse.click(50, 800);
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toContainText("咖啡店");
  expect(api.calls.filter((call) => call.path.startsWith("/api/v1/cash-projections")).length).toBe(2);
});

test("F-03 账本没有记录时显示空态", async ({ page }) => {
  await installComposeApiFixtures(page, { cashPageEmpty: true });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.empty"]')).toContainText("没有匹配的收支记录。");
});

test("F-04 可新建并编辑流水，保留账户和金额字段", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.goto(`${workspaceRoot}/`);
  await expect.poll(() => api.calls.some((call) => call.path.startsWith("/api/v1/cash-ledger/options"))).toBe(true);
  await page.locator('[id="ledger.add"]').last().click({ force: true });
  await expect(page.getByText("记一笔", { exact: true })).toBeVisible();
  await expect(page.locator('[id="record.type"]').last()).toContainText("支出");
  await typeIntoComposeInput(page, '[id="record.amount"]', "12.50");
  await typeIntoComposeInput(page, '[id="record.counterparty"]', "午餐店");
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByText("记一笔", { exact: true })).toBeVisible();
    await expect(page.locator('[id="record.amount"]').last()).toBeAttached();
  }
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.waitForTimeout(300);
  await page.locator('[id="record.save"]').last().click({ force: true });

  await expect.poll(() => api.state.recordWrites.length).toBe(1);
  expect(api.state.recordWrites[0]).toMatchObject({ amount: "12.50", account_name: "日常账户", record_type: "expense" });
  await expect.poll(() => api.calls.filter((call) => call.path.startsWith("/api/v1/cash-projections")).length).toBe(2);
  await page.reload();
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toContainText("咖啡店");

  await page.locator('[id="ledger.open-record"]').last().click({ force: true });
  await expect.poll(() => api.calls.some((call) => call.path === "/api/v1/evidence/cash-projections/projection-1")).toBe(true);
  await expect(page.getByText("流水详情", { exact: true }).last()).toContainText("流水详情");
  await page.getByRole("button", { name: "编辑", exact: true }).last().click({ force: true });
  const amount = page.locator('[id="record.amount"]').last();
  const amountBox = await amount.boundingBox();
  if (!amountBox) throw new Error("Missing Compose amount input bounds");
  await page.mouse.click(amountBox.x + amountBox.width / 2, amountBox.y + amountBox.height / 2);
  await page.keyboard.press("Meta+A");
  await page.keyboard.type("30.00", { delay: 60 });

  await page.mouse.move(200, 1050);
  await page.mouse.wheel(0, 700);
  await page.waitForTimeout(250);
  const note = page.locator('[id="record.note"]').last();
  const noteBox = await note.boundingBox();
  if (!noteBox) throw new Error("Missing Compose note input bounds");
  await page.mouse.click(noteBox.x + noteBox.width / 2, noteBox.y + noteBox.height / 2);
  await page.keyboard.press("Meta+A");
  await page.keyboard.type("已修改", { delay: 120 });
  await page.waitForTimeout(300);
  await page.locator('[id="record.save"]').last().click({ force: true });

  await expect.poll(() => api.state.recordWrites.length).toBe(2);
  expect(api.state.recordWrites[1]).toMatchObject({ amount: "30.00", note: "已修改" });
  expect(api.calls.some((call) => call.method === "PUT" && call.path === "/api/v1/cash-records/record-1")).toBe(true);
});

test("F-05 导入可完成选择、扫描、预览、确认和结果摘要", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/cash-import`);
  const chooserPromise = page.waitForEvent("filechooser");
  await page.locator('[id="import.choose-file"]').click({ force: true });
  const chooser = await chooserPromise;
  await chooser.setFiles({
    name: "statement.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
  });
  await expect(page.getByText("statement.csv", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').click({ force: true });
  await expect(page.getByText("账户映射", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').click({ force: true });

  await expect(page.getByText("咖啡店", { exact: true })).toBeVisible();
  await expect(page.getByText("分类不会展示", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "继续", exact: true }).click({ force: true });
  await page.locator('[id="import.confirm"]').click({ force: true });

  await expect(page.getByText("导入完成", { exact: true })).toBeVisible();
  await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-import/scan").length).toBe(1);
  await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-import/preview").length).toBe(1);
  await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-import/commit").length).toBe(1);
});

async function openBatchRelationFilter(page: Page) {
  await installComposeApiFixtures(page);
  const importFiles = [
    { index: 0, name: "bank.csv", filename: "bank.csv", digest: "bank-digest", size: 42, channel: "bank", channel_label: "银行账单", row_count: 1, status: "ready" },
    { index: 1, name: "wallet.csv", filename: "wallet.csv", digest: "wallet-digest", size: 44, channel: "wallet", channel_label: "钱包账单", row_count: 1, status: "ready" },
  ];
  const primaryRecord = {
    record_id: "bank-row",
    occurred_at: "2026-09-24T10:30:00+08:00",
    amount: "28.50",
    currency: "CNY",
    account_name: "日常账户",
    counterparty: "银行咖啡店",
    record_type: "expense",
    record_subtype: "not_applicable",
    channel: "bank",
    status: "new",
    preview: true,
  };
  const candidateRecord = {
    ...primaryRecord,
    record_id: "wallet-row",
    counterparty: "钱包咖啡店",
    channel: "wallet",
  };
  const commitAttempts: Array<{ body: Record<string, unknown>; idempotencyKey: string | undefined }> = [];
  await page.route(/\/api\/v1\/cash-import\/(scan|preview|commit)$/, async (route) => {
    const request = route.request();
    const pathname = new URL(request.url()).pathname;
    const respond = (value: unknown, status = 200) => route.fulfill({
      status,
      contentType: "application/json",
      body: JSON.stringify(value),
    });
    if (pathname.endsWith("/scan")) {
      const body = request.postDataJSON() as { files?: Array<{ filename: string; content_base64: string }> };
      expect(body.files).toHaveLength(2);
      expect(body.files?.map((file) => file.filename)).toEqual(["bank.csv", "wallet.csv"]);
      expect(body.files?.every((file) => file.content_base64.length > 0)).toBe(true);
      return respond({
        import_token: "import-batch-e2e-token",
        contract: "cash-import-v1",
        channel: "mixed",
        channel_label: "跨渠道账单",
        ready: true,
        batch_digest: "batch-digest",
        channels: ["bank", "wallet"],
        file: { name: "bank.csv", digest: "batch-digest" },
        digest: "batch-digest",
        files: importFiles,
        accounts: [cashAccount],
        groups: [
          { group_id: "bank-account", source_type: "bank", channel: "bank", channel_label: "银行账单", display_name: "银行卡", masked_evidence: "尾号 1234", currencies: ["CNY"], row_count: 1, suggestion: { account_id: 1, account: cashAccount, missing_currencies: [], mapping_revision: 1 } },
          { group_id: "wallet-account", source_type: "wallet", channel: "wallet", channel_label: "钱包账单", display_name: "钱包账户", masked_evidence: "已验证账户", currencies: ["CNY"], row_count: 1, suggestion: { account_id: 1, account: cashAccount, missing_currencies: [], mapping_revision: 1 } },
        ],
      });
    }
    if (pathname.endsWith("/preview")) {
      const body = request.postDataJSON() as { batch?: boolean; mapping?: unknown[] };
      expect(body.batch).toBe(true);
      expect(body.mapping).toHaveLength(2);
      return respond({
        import_token: "import-batch-e2e-token",
        channel: "mixed",
        channel_label: "跨渠道账单",
        file: { name: "bank.csv", digest: "batch-digest" },
        batch_digest: "batch-digest",
        channels: ["bank", "wallet"],
        files: importFiles,
        relation_digest: "relations-batch-e2e",
        items: [
          { ...primaryRecord, category: "", note: "银行记录" },
          { ...candidateRecord, category: "", note: "钱包记录" },
        ],
        summary: { total: 2, new: 2, existing: 0, unsupported: 0, unresolved: 0, requires_allocation: 0 },
        mapping: [],
        relations: [
          {
            id: "automatic-relation",
            kind: "payment_mirror",
            label: "自动匹配记录",
            subtype: "payment_mirror",
            status: "accepted",
            automatic: true,
            rule_id: "rule-automatic",
            reason: "同渠道确定性匹配",
            primary: primaryRecord,
            secondary: candidateRecord,
            candidates: [],
          },
          {
            id: "pending-relation",
            kind: "payment_mirror",
            label: "待确认的跨渠道候选",
            subtype: "payment_mirror",
            status: "pending",
            automatic: false,
            rule_id: "rule-pending",
            reason: "需要用户确认",
            primary: primaryRecord,
            candidates: [candidateRecord],
          },
        ],
      });
    }

    const body = request.postDataJSON() as Record<string, unknown>;
    commitAttempts.push({ body, idempotencyKey: request.headers()["idempotency-key"] });
    if (commitAttempts.length === 1) return respond({ error: { code: "temporarily_unavailable" } }, 503);
    return respond({ message: "ok", new_rows: 2, updated_rows: 0, skipped_rows: 0, channel: "mixed", digest: "batch-digest", batch_digest: "batch-digest", channels: ["bank", "wallet"], files: importFiles });
  });

  await page.goto(`${workspaceRoot}/cash-import`);
  const chooserPromise = page.waitForEvent("filechooser");
  await page.locator('[id="import.choose-file"]').click({ force: true });
  const chooser = await chooserPromise;
  await chooser.setFiles([
    { name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
    { name: "wallet.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
  ]);
  await expect(page.getByText("bank.csv", { exact: true })).toBeVisible();
  await expect(page.getByText("wallet.csv", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').last().click({ force: true });
  await expect(page.getByText("跨渠道账单 · 2 个来源账户", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').last().click({ force: true });
  await expect(page.getByText("银行咖啡店", { exact: true })).toBeVisible();
  await expect(page.getByText("钱包咖啡店", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "继续", exact: true }).last());
  await expect(page.getByText("确认配对", { exact: true })).toBeVisible();

  const relationFilter = page.getByRole("button", { name: "查看 全部 2", exact: true });
  await expect(relationFilter).toBeVisible();
  await clickComposeTarget(page, relationFilter, 0.1, 0.25);
  await expect(page.locator('[id="choice-option-automatic"]')).toBeVisible();
  return commitAttempts;
}

test("F-05 跨渠道多文件会聚合关系候选，提交重试复用幂等键", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 2000 });
  const commitAttempts = await openBatchRelationFilter(page);
  await expect(page.getByText(/银行咖啡店[\s\S]*与[\s\S]*钱包咖啡店/).last()).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "关闭", exact: true }).last());
  await expect(page.getByRole("button", { name: "查看 全部 2", exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "查看 全部 2", exact: true }), 0.1, 0.25);
  const automaticOption = page.locator('[id="choice-option-automatic"]');
  await expect(automaticOption).toBeVisible();
  await clickComposeTarget(page, automaticOption);
  await expect(page.getByText("自动配对 · 自动匹配记录", { exact: true })).toBeVisible();
  await expect(page.getByText("待处理 · 待确认的跨渠道候选", { exact: true })).toHaveCount(0);
  const pendingFilter = page.getByRole("button", { name: "查看 自动 1", exact: true });
  await clickComposeTarget(page, pendingFilter, 0.1, 0.25);
  const pendingOption = page.locator('[id="choice-option-pending"]');
  await expect(pendingOption).toBeVisible();
  await clickComposeTarget(page, pendingOption);
  await expect(page.getByText("待处理 · 待确认的跨渠道候选", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: /对侧流水/ }).last(), 0.1, 0.25);
  await expect(page.getByText(/钱包咖啡店 · 28\.50 CNY/).last()).toBeVisible();
  const walletCandidateOption = page.locator('[id="choice-option-wallet-row"]');
  await expect(walletCandidateOption).toBeVisible();
  await walletCandidateOption.click({ force: true });
  const emptyPendingFilter = page.getByRole("button", { name: "查看 待处理 0", exact: true });
  await expect(emptyPendingFilter).toBeVisible();
  await clickComposeTarget(page, emptyPendingFilter, 0.1, 0.25);
  const allRelationsOption = page.locator('[id="choice-option-all"]');
  await expect(allRelationsOption).toBeVisible();
  await clickComposeTarget(page, allRelationsOption);
  await expect(page.getByText("已确认 · 待确认的跨渠道候选", { exact: true })).toBeVisible();
  await expect(page.getByText(/钱包咖啡店 · 28\.50 CNY/).last()).toBeVisible();

  await clickComposeTarget(page, page.locator('[id="import.confirm"]').last());
  await expect(page.getByText("暂时无法完成操作，请稍后重试。", { exact: true })).toBeVisible();
  await expect(page.locator('[id="import.confirm"]').last()).toBeEnabled();
  await clickComposeTarget(page, page.locator('[id="import.confirm"]').last());
  await expect(page.getByText("导入完成", { exact: true })).toBeVisible();
  expect(commitAttempts).toHaveLength(2);
  expect(commitAttempts[0].idempotencyKey).toBeTruthy();
  expect(commitAttempts[1].idempotencyKey).toBe(commitAttempts[0].idempotencyKey);
  expect(commitAttempts[1].body).toMatchObject({ batch: true });
  expect(commitAttempts[1].body.relations).toEqual(expect.arrayContaining([
    expect.objectContaining({ proposal_key: "automatic-relation", status: "accepted" }),
    expect.objectContaining({ proposal_key: "pending-relation", status: "accepted", secondary_record_id: "wallet-row" }),
  ]));
});

test("F-05 关系筛选卡片覆盖 compact 和 wide 宽度", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 1800 });
  await openBatchRelationFilter(page);
  const screenshotDirectory = path.resolve(process.cwd(), "../openspec/changes/compose-multiplatform-client/screenshots");
  const themeSuffix = process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "-dark" : "";
  await page.screenshot({ path: path.join(screenshotDirectory, `web-choice-card-390${themeSuffix}.png`) });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.locator('[id="choice-option-automatic"]')).toBeVisible();
  await page.screenshot({ path: path.join(screenshotDirectory, `web-choice-card-1440${themeSuffix}.png`) });
});
test("F-06 可预览并接受邀请，随后返回原工作区页面", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/workspace-management?invite=invite-e2e-token`);
  await expect(page.getByText("加入「共享账本」", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "接受邀请", exact: true }).click({ force: true });

  await expect(page.locator('[id="workspace-management.screen"]')).toBeVisible();
  await expect(page).toHaveURL(`${workspaceRoot}/workspace-management`);
  expect(api.calls.some((call) => call.method === "POST" && call.path.endsWith("/invite-e2e-token/accept"))).toBe(true);
});

test("F-07 分类目录按上级路径搜索并可新增分类", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/cash-categories`);
  await expect(page.getByText("咖啡", { exact: true })).toBeVisible();

  await typeIntoComposeInput(page, '[id="cash-category.search"]', "餐饮", { replace: true });
  await expect(page.getByText("咖啡", { exact: true })).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash-category.search"]', "咖啡", { replace: true });
  await expect(page.getByText("没有匹配的分类。", { exact: true })).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash-category.search"]', "", { replace: true });
  await typeIntoComposeInput(page, '[id="cash-category.search"]', " ", { replace: true });

  await page.locator('[id="cash-category-create"]').last().click({ force: true });
  await typeIntoComposeInput(page, '[id="cash-category.name"]', "外卖");
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByText("新增一级分类", { exact: true })).toBeVisible();
    await expect(page.locator('[id="cash-category.name"]').last()).toBeAttached();
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  await page.mouse.move(200, 700);
  await page.mouse.wheel(0, 1200);
  await page.waitForTimeout(200);
  await page.locator('[id="cash-category.save"]').last().click({ force: true });
  await expect.poll(() => api.state.categoryItems.some((item) => item.name === "外卖")).toBe(true);
  await page.reload();
  await expect(page.getByText("外卖", { exact: true }).last()).toContainText("外卖");
});

test("F-08 持仓可查看估值详情", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/investment-holdings`);
  await expect(page.getByText("Apple", { exact: true }).last()).toContainText("Apple");
  await expect(page.getByText("当前总市值", { exact: true })).toBeVisible();
  await expect(page.getByText("+10%", { exact: true })).toHaveCount(2);
  await expect(page.getByText(/null%/)).toHaveCount(0);

  await page.mouse.move(220, 740);
  await page.mouse.wheel(0, 700);
  await expect(page.getByText("Apple", { exact: true }).last()).toBeVisible();
  await page.getByRole("button", { name: "查看估值详情", exact: true }).last().click({ force: true });
  await expect(page.getByText("估值状态", { exact: true }).last()).toContainText("估值状态");
  await expect(page.getByText("行情完整", { exact: true }).last()).toContainText("行情完整");
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await page.getByRole("button", { name: "关闭", exact: true }).click({ force: true });
    await page.evaluate(async () => { await document.fonts.ready; window.scrollTo(0, 0); });
    await page.waitForTimeout(3000);
    const evidenceDirectory = path.resolve(process.cwd(), "../openspec/changes/compose-multiplatform-client/screenshots");
    const themeSuffix = process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "-dark" : "";
    await page.setViewportSize({ width: 390, height: 1800 });
    await page.screenshot({ path: path.join(evidenceDirectory, `web-holdings-390${themeSuffix}.png`) });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(evidenceDirectory, `web-holdings-1440${themeSuffix}.png`) });
  }
});

test("F-09 投资事件可加载下一页并查看证据", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/investment-events`);
  await expect(page.getByText("买入苹果", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.locator('[id="investment-events.screen"]').last()).toBeVisible();
  await page.getByRole("button", { name: "查看详情", exact: true }).last().click({ force: true });
  await expect(page.getByText("发生时间", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "关闭", exact: true }).last().click({ force: true });
  await expect(page.locator('[id="investment.event.detail"]')).toHaveCount(0);
  // Reload after the canvas dialog closes so the accessibility proxy is rebuilt before nav actions.
  await page.reload();
  await expect(page.locator('[id="investment-events.screen"]').last()).toBeVisible();

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.waitForTimeout(300);
  await page.locator('[id="navigation-item-investment-holdings"]').last().click({ force: true });
  await expect(page).toHaveURL(`${workspaceRoot}/investment-holdings`);
  await page.goBack();
  await expect(page).toHaveURL(`${workspaceRoot}/investment-events`);
  await page.goForward();
  await expect(page).toHaveURL(`${workspaceRoot}/investment-holdings`);
  await page.goBack();
  await expect(page.locator('[id="investment-events.screen"]').last()).toBeVisible();

  await page.setViewportSize({ width: 390, height: 2000 });
  await page.waitForTimeout(300);
  await page.mouse.move(220, 740);
  await page.mouse.wheel(0, 1200);
  // The load-more action is painted on the Wasm canvas below the event scroll region.
  await page.mouse.click(40, 766);
  await expect.poll(() => api.calls.some((call) => call.path.includes("cursor=event-page-2"))).toBe(true);
});

test("F-10 工作区管理员可修改名称并创建邀请链接", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/workspace-management`);
  await expect(page.getByText("成员", { exact: true }).last()).toContainText("成员");
  await typeIntoComposeInput(page, '[id="workspace-management.name"]', "新的家庭账本", { replace: true });
  await page.getByRole("button", { name: "保存", exact: true }).last().click({ force: true });
  await expect(page.getByText("已保存。", { exact: true }).last()).toContainText("已保存。");

  await page.mouse.move(220, 740);
  await page.mouse.wheel(0, 1400);
  await page.locator('[id="workspace.management.invite"]').last().click({ force: true });
  await expect(page.locator('[id="workspace-management.invite-link"]').last()).toContainText(/\?invite=invite-e2e-token$/);
  expect(api.calls.some((call) => call.method === "PUT" && call.path === "/api/v1/auth/workspace")).toBe(true);
  expect(api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/auth/invitations")).toBe(true);
});

test("F-10 工作区成员邮箱在 regular 平板宽度保持可读", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 834, height: 1210 });
  await page.goto(`${workspaceRoot}/workspace-management`);

  const memberEmail = page.getByText("member@example.com", { exact: true }).last();
  await expect(memberEmail).toBeVisible();
  const bounds = await memberEmail.boundingBox();
  expect(bounds?.width ?? 0).toBeGreaterThan(120);
});

test("查看者在账本、导入和分类页不能执行写入操作", async ({ page }) => {
  const viewerSession = {
    ...activeSession,
    workspaces: [{ ...activeSession.workspaces[0], role: "viewer" as const }],
  };
  const api = await installComposeApiFixtures(page, { initialSession: viewerSession });
  await page.goto(`${workspaceRoot}/`);
  await page.locator('[id="ledger.add"]').last().click({ force: true });
  await expect(page.locator('[id="record.screen"]')).toHaveCount(0);
  await expect.poll(() => api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-records")).toBe(false);

  await page.goto(`${workspaceRoot}/cash-import`);
  await expect(page.getByText("当前工作区仅可查看。", { exact: true })).toBeVisible();
  await expect(page.locator('[id="import.choose-file"]')).toHaveCount(0);

  await page.goto(`${workspaceRoot}/cash-categories`);
  await page.locator('[id="cash-category-create"]').last().click({ force: true });
  await expect(page.locator('[id="cash-category.name"]')).toHaveCount(0);
  expect(api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-categories")).toBe(false);
});

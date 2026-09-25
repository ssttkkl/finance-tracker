import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures, typeIntoComposeInput, type BrowserDiagnostics } from "./compose-fixtures";

const browserDiagnostics = new WeakMap<import("@playwright/test").Page, BrowserDiagnostics>();

test.beforeEach(({ page }, testInfo) => {
  const expectedErrorStatuses = testInfo.title.startsWith("viewer 登录遇到不可访问的 URL 工作区") ? [403] : [];
  browserDiagnostics.set(page, captureBrowserDiagnostics(page, { expectedErrorStatuses }));
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

const activeInputMode = async (page: import("@playwright/test").Page) => page.evaluate(() => {
  let active = document.activeElement as HTMLElement | null;
  while (active) {
    const inputMode = active.getAttribute("inputmode");
    if (inputMode) return inputMode;
    active = active.shadowRoot?.activeElement as HTMLElement | null;
  }
  return null;
});

test("Compose 登录表单按 Tab 键顺序聚焦", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  await page.locator('[id="auth.email"]').last().click({ force: true });
  await expect.poll(() => activeInputMode(page)).toBe("email");
  await page.keyboard.press("Tab");
  await expect.poll(() => activeInputMode(page)).toBe("password");
});

test("Compose 登录输入框向辅助技术公开邮箱和密码标签", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "邮箱" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "密码" })).toBeVisible();
});

test("Compose Web 中文字体从同源资源加载", async ({ page }) => {
  const fontResponses: Array<{ url: string; status: number }> = [];
  const mirroredFallbackResponses: Array<{ url: string; status: number }> = [];
  const externalFallbackRequests: string[] = [];
  page.on("response", (response) => {
    if (/\/font\/noto_sans_sc\.woff2(?:\?|$)/.test(response.url())) {
      fontResponses.push({ url: response.url(), status: response.status() });
    }
    if (/\/fonts\/notosanssc\/v40\/[^/]+\.woff2(?:\?|$)/.test(response.url())) {
      mirroredFallbackResponses.push({ url: response.url(), status: response.status() });
    }
  });
  page.on("request", (request) => {
    if (/fonts\.gstatic\.com\/s\/notosanssc\//.test(request.url())) {
      externalFallbackRequests.push(request.url());
    }
  });

  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "邮箱" })).toBeVisible();
  await expect.poll(() => fontResponses.length).toBeGreaterThan(0);
  expect(fontResponses.every((response) => response.status === 200)).toBe(true);
  await expect.poll(() => mirroredFallbackResponses.length).toBeGreaterThan(0);
  expect(mirroredFallbackResponses.every((response) => response.status === 200)).toBe(true);
  expect(externalFallbackRequests).toEqual([]);
});

test("Compose 登录显示提交错误并可切换注册", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null, loginError: true });
  await page.goto("/");
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);

  await typeIntoComposeInput(page, '[id="auth.email"]', "owner@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });
  await expect(page.locator('[id="auth.error"]')).toContainText("邮箱或密码不正确。请检查后重试。", { timeout: 10_000 });

  await page.locator('[id="auth.toggle-mode"]').last().click({ force: true });
  await expect(page.getByText("创建你的账户", { exact: true })).toBeVisible();
  await expect(page.locator('[id="auth.toggle-mode"]')).toContainText("已有账户？登录");
});

test("viewer 登录遇到不可访问的 URL 工作区后仍可选择有权限的工作区", async ({ page }) => {
  const viewerSession = {
    user: { email: "viewer@example.com" },
    active_workspace_id: "workspace-1",
    workspaces: [{ id: "workspace-1", name: "家庭账本", role: "viewer" as const }],
  };
  const fixture = await installComposeApiFixtures(page, {
    seedToken: false,
    initialSession: null,
    loginSession: viewerSession,
    inaccessibleWorkspaceIds: ["workspace-created"],
  });
  await page.goto("/w/workspace-created/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "viewer@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });

  await expect(page.locator('[id="workspace.screen"]').last()).toBeVisible();
  await expect(page.getByText("无法打开该工作区，请检查权限后重试。", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: /家庭账本/ }).last().click({ force: true });

  await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
  await page.locator('[id="ledger.add"]').last().click({ force: true });
  await expect(page.locator('[id="record.screen"]')).toHaveCount(0);
  expect(fixture.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-records")).toBe(false);
  await page.goto("/w/workspace-1/cash-import");
  await expect(page.getByText("当前工作区仅可查看。", { exact: true })).toBeVisible();
  await expect(page.locator('[id="import.choose-file"]')).toHaveCount(0);
  expect(fixture.calls.filter((call) => call.path.endsWith("/select")).map((call) => call.path)).toEqual([
    "/api/v1/auth/workspaces/workspace-created/select",
    "/api/v1/auth/workspaces/workspace-1/select",
  ]);
});

test("Compose 访问页在目标窗口宽度无横向溢出", async ({ page }) => {
  await page.goto("/");
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);

  for (const width of [320, 375, 390, 414, 768, 1440]) {
    await page.setViewportSize({ width, height: width >= 1024 ? 1000 : 844 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

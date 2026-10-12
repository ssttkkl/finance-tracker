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
        diagnosticLogs: diagnostics.diagnosticLogs,
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
    for (const line of diagnostics.diagnosticLogs) {
      expect(line).toMatch(/^\[Finance Tracker diagnostic\] \d+\t[A-Z_]+\t[A-Z_]+\t[a-z0-9_]+\t(?:\d+)?\t[A-Za-z0-9]+$/);
      expect(line).not.toContain("@");
    }
  }
});

const expectAuthOutlineTopRelativeTo = async (
  element: import("@playwright/test").Locator,
  container: import("@playwright/test").Locator,
  expectedTop: number,
) => {
  const elementBox = await element.boundingBox();
  const containerBox = await container.boundingBox();
  expect(elementBox).not.toBeNull();
  expect(containerBox).not.toBeNull();
  // M3's labeled field adds 8 dp above the visible 56 dp outline.
  expect(Math.abs((elementBox?.y ?? 0) + 8 - (containerBox?.y ?? 0) - expectedTop)).toBeLessThanOrEqual(2);
};

const expectTopRelativeTo = async (
  element: import("@playwright/test").Locator,
  container: import("@playwright/test").Locator,
  expectedTop: number,
) => {
  const elementBox = await element.boundingBox();
  const containerBox = await container.boundingBox();
  expect(elementBox).not.toBeNull();
  expect(containerBox).not.toBeNull();
  expect(Math.abs((elementBox?.y ?? 0) - (containerBox?.y ?? 0) - expectedTop)).toBeLessThanOrEqual(2);
};

test("Compose 登录输入框向辅助技术公开邮箱和密码标签", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  await expect(page.getByRole("textbox", { name: "邮箱" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "密码" })).toBeVisible();
});

test("Compose 认证页面匹配确认稿的布局、标题与校验", async ({ page }, testInfo) => {
  const fixtures = await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  await page.evaluate(() => document.fonts.ready);

  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const card = page.locator('[id="auth.card"]').last();
    await expect(page.locator('[id="auth.title"]').last()).toHaveText("登录");
    await expect(page.getByText("Finance Tracker", { exact: true })).toHaveCount(0);
    await expect(page.getByText("工作区访问", { exact: true })).toHaveCount(0);
    await expect(page.getByText("至少 12 个字符", { exact: true })).toHaveCount(0);
    await expect(page.getByRole("textbox", { name: "邮箱" })).toBeVisible();
    await expect(page.locator('[id="auth.email"]')).not.toHaveAttribute("placeholder", /.+/);
    const loginEmailBox = await page.locator('[id="auth.email.control"]').last().boundingBox();
    expect(loginEmailBox).not.toBeNull();

    if (width === 390) {
      await expect(card).toHaveCount(0);
      expect(Math.abs((loginEmailBox?.y ?? 0) + 8 - 118)).toBeLessThanOrEqual(2);
      const titleBox = await page.locator('[id="auth.title"]').last().boundingBox();
      expect(titleBox).not.toBeNull();
      expect(titleBox?.y ?? 999).toBeLessThan(120);
    } else {
      await expect(page.locator('[id="auth.card"]')).toHaveCount(1);
      await expect.poll(async () => (await card.boundingBox())?.width).toBeCloseTo(440, 0);
      await expect.poll(async () => {
        const box = await card.boundingBox();
        return box ? Math.abs(box.x - (width - box.width) / 2) : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(2);
      const cardBox = await card.boundingBox();
      expect(cardBox).not.toBeNull();
      expect(Math.abs((cardBox?.x ?? 0) - (width - (cardBox?.width ?? 0)) / 2)).toBeLessThanOrEqual(2);
      await expectAuthOutlineTopRelativeTo(page.locator('[id="auth.email.control"]').last(), card, 86);
      expect(Math.abs((cardBox?.height ?? 0) - 378)).toBeLessThanOrEqual(2);
    }
    await page.screenshot({ path: testInfo.outputPath(`login-default-${width}.png`) });
  }

  await page.setViewportSize({ width: 390, height: 900 });
  const modeToggle = page.locator('[id="auth.toggle-mode"]').last();
  await expect(page.locator('[id="auth.card"]')).toHaveCount(0);
  await expect(modeToggle).toBeInViewport();
  await modeToggle.scrollIntoViewIfNeeded();
  await modeToggle.click({ force: true });
  await expect(page.locator('[id="auth.title"]').last()).toHaveText("创建账户");
  await expect(page.getByRole("button", { name: "返回登录" })).toBeVisible();
  await expect(page.getByRole("button", { name: "注册" })).toBeVisible();
  await expect(page.locator('[id="auth.password.validation"]').last()).toHaveAttribute("aria-label", "至少 12 个字符");
  await expect(page.getByRole("textbox", { name: "确认密码" })).toBeVisible();
  await expect(page.locator('[id="auth.email.validation"]')).toHaveCount(0);
  await expect(page.locator('[id="auth.confirm-password.validation"]')).toHaveCount(0);
  await expect(page.locator('[id="auth.toggle-mode"]')).toHaveCount(0);
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(overflow, `注册默认态在 ${width}px 下不应横向溢出`).toBe(false);
    const emailBox = await page.locator('[id="auth.email.control"]').last().boundingBox();
    expect(emailBox).not.toBeNull();
    if (width === 390) {
      await expect(page.locator('[id="auth.card"]')).toHaveCount(0);
      expect(Math.abs((emailBox?.y ?? 0) + 8 - 118)).toBeLessThanOrEqual(2);
    } else {
      const card = page.locator('[id="auth.card"]').last();
      await expect(page.locator('[id="auth.card"]')).toHaveCount(1);
      await expect.poll(async () => (await card.boundingBox())?.width).toBeCloseTo(440, 0);
      await expect.poll(async () => {
        const box = await card.boundingBox();
        return box ? Math.abs(box.x - (width - box.width) / 2) : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(2);
      const cardBox = await card.boundingBox();
      expect(Math.abs((cardBox?.height ?? 0) - 414)).toBeLessThanOrEqual(2);
      await expectAuthOutlineTopRelativeTo(page.locator('[id="auth.email.control"]').last(), card, 86);
      await expectAuthOutlineTopRelativeTo(page.locator('[id="auth.password.control"]').last(), card, 166);
      await expectAuthOutlineTopRelativeTo(page.locator('[id="auth.confirm-password.control"]').last(), card, 262);
      await expectTopRelativeTo(page.locator('[id="auth.submit"]').last(), card, 342);
      const backBox = await page.locator('[id="auth.back"]').last().boundingBox();
      expect(backBox).not.toBeNull();
      expect(Math.abs((backBox?.width ?? 0) - 44)).toBeLessThanOrEqual(2);
      expect(Math.abs((backBox?.height ?? 0) - 44)).toBeLessThanOrEqual(2);
      expect(Math.abs((backBox?.x ?? 0) - ((cardBox?.x ?? 0) + 32))).toBeLessThanOrEqual(2);
      await expect(page.locator('[id="auth.password.validation"]').last()).toHaveAttribute("aria-label", "至少 12 个字符");
      for (const control of [
        page.locator('[id="auth.email.control"]').last(),
        page.locator('[id="auth.password.control"]').last(),
        page.locator('[id="auth.confirm-password.control"]').last(),
        page.locator('[id="auth.submit"]').last(),
      ]) {
        const controlBox = await control.boundingBox();
        expect(Math.abs((controlBox?.width ?? 0) - 376)).toBeLessThanOrEqual(2);
        expect(Math.abs((controlBox?.x ?? 0) - ((cardBox?.x ?? 0) + 32))).toBeLessThanOrEqual(2);
        expect(Math.abs(((controlBox?.x ?? 0) + (controlBox?.width ?? 0)) - ((cardBox?.x ?? 0) + (cardBox?.width ?? 0) - 32))).toBeLessThanOrEqual(2);
      }
    }
    if (width === 390) {
      const backBox = await page.locator('[id="auth.back"]').last().boundingBox();
      expect(backBox).not.toBeNull();
      expect(Math.abs((backBox?.x ?? 0) - 16)).toBeLessThanOrEqual(2);
      expect(Math.abs((backBox?.width ?? 0) - 44)).toBeLessThanOrEqual(2);
      expect(Math.abs((backBox?.height ?? 0) - 44)).toBeLessThanOrEqual(2);
    }
    await page.screenshot({ path: testInfo.outputPath(`register-default-${width}.png`) });
  }
  await page.setViewportSize({ width: 390, height: 900 });
  await expect(page.locator('[id="auth.card"]')).toHaveCount(0);
  await expect(page.locator('[id="auth.email"]').last()).toBeInViewport();
  const backButton = page.getByRole("button", { name: "返回登录" });
  await typeIntoComposeInput(page, '[id="auth.email"]', "not-an-email");
  await expect(page.locator('[id="auth.email.validation"]').last()).toHaveAttribute("aria-label", "✕ 邮箱格式不正确");
  await typeIntoComposeInput(page, '[id="auth.password"]', "short", { replace: true });
  await expect(page.locator('[id="auth.password.validation"]').last()).toHaveAttribute("aria-label", "✕ 至少 12 个字符");
  await typeIntoComposeInput(page, '[id="auth.confirm-password"]', "different");
  await expect(page.locator('[id="auth.confirm-password.validation"]').last()).toHaveAttribute("aria-label", "✕ 两次密码不一致");
  await typeIntoComposeInput(page, '[id="auth.email"]', "qa@example.com", { replace: true });
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123", { replace: true });
  await page.getByRole("button", { name: "注册" }).click({ force: true });
  await expect(page.locator('[id="auth.error"]')).toContainText("请确认两次输入的密码一致。");
  await page.screenshot({ path: testInfo.outputPath("register-submit-error-390.png") });
  expect(fixtures.calls.some((call) => call.method === "POST" && call.path === "/api/v1/auth/register")).toBe(false);

  for (const width of [320, 375, 390, 414, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(overflow, `注册页面在 ${width}px 下不应横向溢出`).toBe(false);
    if (width < 600) {
      await expect(page.locator('[id="auth.card"]')).toHaveCount(0);
    } else {
      const card = page.locator('[id="auth.card"]').last();
      await expect(page.locator('[id="auth.card"]')).toHaveCount(1);
      await expect.poll(async () => (await card.boundingBox())?.width).toBeCloseTo(440, 0);
      await expect.poll(async () => {
        const box = await card.boundingBox();
        return box ? Math.abs(box.x - (width - box.width) / 2) : Number.POSITIVE_INFINITY;
      }).toBeLessThanOrEqual(2);
    }
    if (width === 390 || width === 768 || width === 1440) {
      await page.screenshot({ path: testInfo.outputPath(`register-validation-${width}.png`) });
    }
  }

  await page.setViewportSize({ width: 390, height: 900 });
  await expect(page.locator('[id="auth.card"]')).toHaveCount(0);
  await backButton.click({ force: true });
  await expect(page.locator('[id="auth.title"]').last()).toHaveText("登录");
});

test("Compose 注册成功并且请求不包含确认密码", async ({ page }) => {
  const fixtures = await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  await page.locator('[id="auth.toggle-mode"]').last().click({ force: true });
  await typeIntoComposeInput(page, '[id="auth.email"]', "qa@example.com", { replace: true });
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123", { replace: true });
  await typeIntoComposeInput(page, '[id="auth.confirm-password"]', "Sample-password-123", { replace: true });
  await expect(page.locator('[id="auth.confirm-password.validation"]').last()).toHaveAttribute("aria-label", "✓ 两次密码一致");
  await page.getByRole("button", { name: "注册" }).click({ force: true });
  await expect(page.locator('[id="ledger.screen"]')).toBeVisible();
  const registrationRequest = fixtures.calls.find((call) => call.method === "POST" && call.path === "/api/v1/auth/register");
  expect(registrationRequest).toBeDefined();
  expect(JSON.parse(registrationRequest?.body ?? "{}")).not.toHaveProperty("confirm_password");
  expect(JSON.parse(registrationRequest?.body ?? "{}")).not.toHaveProperty("confirmPassword");
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

test("Compose 登录显示提交错误并可切换注册", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null, loginError: true });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);

  await typeIntoComposeInput(page, '[id="auth.email"]', "owner@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });
  await expect(page.locator('[id="auth.error"]')).toContainText("邮箱或密码不正确。请检查后重试。", { timeout: 10_000 });
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? ""))
    .toContain("AUTHENTICATION\tAUTHENTICATE\tinvalid_credentials\t401\tDomainFailure");
  const diagnosticLog = await page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? "");
  expect(diagnosticLog).not.toContain("owner@example.com");
  expect(diagnosticLog).not.toContain("Sample-password-123");
  expect(diagnosticLog).not.toContain("access-token");
  expect(browserDiagnostics.get(page)?.diagnosticLogs.join("\n")).toContain("AUTHENTICATION\tAUTHENTICATE\tinvalid_credentials\t401\tDomainFailure");
  await page.screenshot({ path: testInfo.outputPath("login-request-error-390.png") });
  await page.locator('[id="auth.toggle-mode"]').last().click({ force: true });
  await expect(page.locator('[id="auth.title"]').last()).toHaveText("创建账户");
  await expect(page.getByRole("button", { name: "返回登录" })).toBeVisible();
  await expect(page.locator('[id="auth.toggle-mode"]')).toHaveCount(0);
});

test("Compose 登录请求错误适配三类宽度", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null, loginError: true });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "owner@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });
  await expect(page.locator('[id="auth.error"]')).toContainText("邮箱或密码不正确。请检查后重试。", { timeout: 10_000 });
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ path: testInfo.outputPath(`login-request-error-${width}.png`) });
  }
});

test("Compose 登录忙碌态禁止重复提交并锁定输入", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.route("**/api/v1/auth/login", async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1_000));
    await route.fulfill({ status: 401, contentType: "application/json", body: JSON.stringify({ error: { code: "invalid_credentials" } }) });
  });
  await page.goto("/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "owner@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  let loginRequests = 0;
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/v1/auth/login") loginRequests += 1;
  });
  const response = page.waitForResponse((value) => new URL(value.url()).pathname === "/api/v1/auth/login");
  await page.locator('[id="auth.submit"]').last().click({ force: true });

  await page.waitForTimeout(100);
  await page.screenshot({ path: testInfo.outputPath("login-busy-390.png") });
  const submitBox = await page.locator('[id="auth.submit"]').last().boundingBox();
  expect(submitBox).not.toBeNull();
  await page.mouse.click((submitBox?.x ?? 0) + (submitBox?.width ?? 0) / 2, (submitBox?.y ?? 0) + (submitBox?.height ?? 0) / 2);
  await typeIntoComposeInput(page, '[id="auth.email"]', "x", { replace: true });
  expect(await page.locator('[id="auth.email"]').last().innerText()).toBe("owner@example.com");
  expect(loginRequests).toBe(1);
  await response;
  await expect(page.locator('[id="auth.error"]')).toContainText("邮箱或密码不正确。请检查后重试。");
});

test("Compose 登录键盘操作从邮箱进入密码", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.setViewportSize({ width: 390, height: 900 });
  await page.goto("/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "qa@example.com");
  await page.keyboard.press("Enter");
  await page.waitForTimeout(250);
  await expect(page.locator('[id="auth.error"]')).toHaveCount(0);
  await expect(page.locator('[id="auth.password"]')).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath("login-keyboard-next-password-390.png") });
});

test("Compose 登录字段获得焦点后显示浮动标签", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await typeIntoComposeInput(page, '[id="auth.email"]', "");
    await page.screenshot({ path: testInfo.outputPath(`login-email-focused-${width}.png`) });
  }
});

test("Compose 认证必填校验写入不含输入值的诊断日志", async ({ page }) => {
  await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.goto("/");
  const submitButton = page.locator('[id="auth.submit"]').last();
  await expect(submitButton).toBeVisible();
  await expect(submitButton).toBeEnabled();
  await submitButton.click({ force: true });
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? ""))
    .toContain("AUTHENTICATION\tVALIDATE_INPUT\tauth_input_invalid_emailrequired\t\tInputValidationFailure");
  await expect(page.locator('[id="auth.error"]')).toContainText("请输入邮箱。");
  const diagnosticLog = await page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? "");
  expect(diagnosticLog).not.toContain("@");
  expect(browserDiagnostics.get(page)?.diagnosticLogs.join("\n"))
    .toContain("AUTHENTICATION\tVALIDATE_INPUT\tauth_input_invalid_emailrequired\t\tInputValidationFailure");
});

test("Compose 工作区名称校验写入固定字段的诊断日志", async ({ page }) => {
  await installComposeApiFixtures(page, {
    seedToken: false,
    initialSession: null,
    loginSession: { user: { email: "qa@example.com" }, active_workspace_id: null, workspaces: [] },
  });
  await page.goto("/");
  await typeIntoComposeInput(page, '[id="auth.email"]', "qa@example.com");
  await typeIntoComposeInput(page, '[id="auth.password"]', "Sample-password-123");
  await page.locator('[id="auth.submit"]').last().click({ force: true });
  await expect(page.locator('[id="workspace.screen"]')).toBeVisible();
  await page.locator('[id="workspace.create"]').last().click({ force: true });

  await expect(page.getByText("请输入不超过 255 个字符的名称。", { exact: true })).toBeVisible();
  await expect.poll(() => page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? ""))
    .toContain("WORKSPACE\tVALIDATE_INPUT\tinvalid_workspace_name\t\tInputValidationFailure");
  const diagnosticLog = await page.evaluate(() => window.localStorage.getItem("finance-tracker:diagnostics") ?? "");
  expect(diagnosticLog).not.toContain("qa@example.com");
  expect(diagnosticLog).not.toContain("Sample-password-123");
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
  await page.locator('[id="ledger.add"]').last().dispatchEvent("click");
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

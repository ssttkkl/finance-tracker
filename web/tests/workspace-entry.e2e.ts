import { expect, test, type Page } from "@playwright/test";

const workspace = { id: "workspace-1", name: "家庭账本", role: "editor" as const };
type AccessSession = {
  user: { email: string };
  active_workspace_id: string | null;
  workspaces: typeof workspace[];
};
const activeSession: AccessSession = {
  user: { email: "member@example.com" },
  active_workspace_id: workspace.id,
  workspaces: [workspace],
};

function installAccessFixture(page: Page, session: AccessSession | null, loginSession: AccessSession | null = session) {
  const consoleErrors: string[] = [];
  const requestFailures: string[] = [];
  page.on("console", message => {
    if (message.type() === "error" && !message.text().includes("401 (Unauthorized)")) consoleErrors.push(message.text());
  });
  page.on("requestfailed", request => { requestFailures.push(`${request.method()} ${request.url()}: ${request.failure()?.errorText ?? "failed"}`); });
  void page.route("**/api/v1/**", async route => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (value: unknown, status = 200) => route.fulfill({ status, json: value });
    if (url.pathname.endsWith("/auth/session")) {
      return session
        ? json(session)
        : json({ error: { code: "authentication_required" } }, 401);
    }
    if (url.pathname.endsWith("/auth/login") && request.method() === "POST") {
      return loginSession ? json({ ...loginSession, access_token: "e2e-token" }) : json({ error: { code: "invalid_credentials" } }, 400);
    }
    if (url.pathname.endsWith(`/auth/workspaces/${workspace.id}/select`) && request.method() === "POST") return json(activeSession);
    if (url.pathname.endsWith("/accounts")) return json({ items: [] });
    return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [], currencies: [], economic_types: [] } });
  });
  return { consoleErrors, requestFailures };
}

test("登录已有工作区后进入账本并规范化工作区 URL", async ({ page }) => {
  const errors = installAccessFixture(page, null, { ...activeSession, active_workspace_id: null });
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");
  await page.getByLabel("邮箱").fill("member@example.com");
  await page.getByLabel("密码").fill("a-secure-password");
  await page.getByRole("button", { name: "登录" }).click();

  await expect(page.getByRole("heading", { name: "收支账本" })).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
  expect(errors.consoleErrors).toEqual([]);
  expect(errors.requestFailures).toEqual([]);
  await page.screenshot({ path: "/tmp/fix-login-workspace-entry-1440.png", fullPage: true });
});

test("没有工作区时只显示创建页且 390 px 无横向溢出", async ({ page }) => {
  const errors = installAccessFixture(page, { user: { email: "new@example.com" }, active_workspace_id: null, workspaces: [] });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByRole("heading", { name: "创建工作区" })).toBeVisible();
  await expect(page.getByRole("button", { name: /^返回$/ })).toHaveCount(0);
  expect(await page.locator("body").evaluate(body => body.scrollWidth <= window.innerWidth)).toBe(true);
  expect(errors.consoleErrors).toEqual([]);
  expect(errors.requestFailures).toEqual([]);
  await page.screenshot({ path: "/tmp/fix-login-workspace-entry-390.png", fullPage: true });
});

test("已有工作区主动创建时返回按钮回到账本", async ({ page }) => {
  const errors = installAccessFixture(page, activeSession);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/");

  await page.getByRole("combobox", { name: "当前工作区" }).selectOption("__create__");
  await expect(page.getByRole("heading", { name: "创建工作区" })).toBeVisible();
  await page.getByRole("button", { name: /^返回$/ }).click();
  await expect(page.getByRole("heading", { name: "收支账本" })).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
  expect(errors.consoleErrors).toEqual([]);
  expect(errors.requestFailures).toEqual([]);
});

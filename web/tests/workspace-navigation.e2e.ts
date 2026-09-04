import { expect, test } from "@playwright/test";

test("直接打开工作区根路径时加载对应账本并保留 URL", async ({ page }) => {
  const consoleErrors: string[] = [];
  const requestFailures: string[] = [];
  page.on("console", message => { if (message.type() === "error") consoleErrors.push(message.text()); });
  page.on("requestfailed", request => { requestFailures.push(`${request.method()} ${request.url()}: ${request.failure()?.errorText ?? "failed"}`); });
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/auth/session")) {
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: "workspace-2",
        workspaces: [
          { id: "workspace-1", name: "家庭账本", role: "editor" },
          { id: "workspace-2", name: "旅行账本", role: "editor" },
        ],
      } });
    }
    if (path.endsWith("/auth/workspaces/workspace-1/select") && request.method() === "POST") {
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: "workspace-1",
        workspaces: [
          { id: "workspace-1", name: "家庭账本", role: "editor" },
          { id: "workspace-2", name: "旅行账本", role: "editor" },
        ],
      } });
    }
    if (path.endsWith("/cash-categories")) return route.fulfill({ json: { items: [], revision: 0 } });
    if (path.endsWith("/accounts")) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.setViewportSize({ width: 1440, height: 900 });
  const response = await page.goto("/w/workspace-1/");
  expect(response?.status()).toBe(200);
  await expect(page.getByRole("heading", { name: "收支账本", level: 1 })).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
  await page.getByRole("link", { name: "分类管理" }).focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("heading", { name: "分类管理", level: 1 })).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-1\/cash-categories$/);
  expect(consoleErrors).toEqual([]);
  expect(requestFailures).toEqual([]);
  await page.screenshot({ path: "/tmp/workspace-entry-1440.png", fullPage: true });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/w/workspace-1/");
  await expect(page.getByRole("heading", { name: "收支账本", level: 1 })).toBeVisible();
  expect(await page.locator("body").evaluate(body => body.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: "/tmp/workspace-entry-390.png", fullPage: true });
});

test("登录后的工作区使用统一侧栏路由打开分类与投资事件", async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path.endsWith("/auth/session")) {
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: "workspace-1",
        workspaces: [{ id: "workspace-1", name: "家庭账本", role: "editor" }],
      } });
    }
    if (path.endsWith("/cash-categories")) return route.fulfill({ json: { items: [], revision: 0 } });
    if (path.endsWith("/accounts")) return route.fulfill({ json: { items: [] } });
    if (path.endsWith("/investment-events")) return route.fulfill({ json: { items: [], next_cursor: null } });
    if (path.endsWith("/investment-portfolio")) return route.fulfill({ json: { accounts: [], total_market_value: null, total_profit: null, total_profit_rate: null, period_profit: null, period_profit_rate: null } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.goto("/");
  await page.getByRole("link", { name: "分类管理" }).click();
  await expect(page.getByRole("heading", { name: "分类管理", level: 1 })).toBeVisible();

  await page.getByRole("link", { name: "投资事件" }).click();
  await expect(page).toHaveURL(/\/investment-events$/);
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
});

test("管理员在桌面与移动视口确认工作区名称后删除工作区", async ({ page }) => {
  let deleted = false;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/auth/session")) {
      return route.fulfill({ json: {
        user: { email: "admin@example.com" }, active_workspace_id: deleted ? "workspace-2" : "workspace-1",
        workspaces: deleted
          ? [{ id: "workspace-2", name: "另一个账本", role: "admin" }]
          : [{ id: "workspace-1", name: "家庭账本", role: "admin" }, { id: "workspace-2", name: "另一个账本", role: "admin" }],
      } });
    }
    if (path.endsWith("/auth/workspace") && request.method() === "GET") {
      return route.fulfill({ json: {
        workspace: { id: "workspace-1", name: "家庭账本" },
        members: [{ user_id: "admin-1", email: "admin@example.com", role: "admin", is_self: true }],
      } });
    }
    if (path.endsWith("/auth/workspace") && request.method() === "DELETE") {
      expect(JSON.parse(request.postData() ?? "{}" )).toEqual({ name: "家庭账本" });
      deleted = true;
      return route.fulfill({ json: {
        user: { email: "admin@example.com" }, active_workspace_id: "workspace-2",
        workspaces: [{ id: "workspace-2", name: "另一个账本", role: "admin" }],
      } });
    }
    if (path.endsWith("/accounts")) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.goto("/workspace-management");
  await expect(page.getByRole("heading", { name: "工作区管理", level: 1 })).toBeVisible();
  const workspacePage = page.locator("main.workspace-management-page");
  await expect(workspacePage.locator(":scope > section h2")).toHaveText(["工作区信息", "成员", "邀请成员", "删除工作区"]);
  const dangerSection = workspacePage.locator('[aria-labelledby="workspace-delete-title"]');
  const dangerTitleBox = await dangerSection.getByRole("heading", { name: "删除工作区" }).boundingBox();
  const dangerButtonBox = await dangerSection.getByRole("button", { name: "删除工作区" }).boundingBox();
  if (!dangerTitleBox || !dangerButtonBox) throw new Error("删除工作区标题或按钮未渲染");
  expect(dangerButtonBox.y).toBeGreaterThan(dangerTitleBox.y);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1440);
  await page.screenshot({ path: "/tmp/workspace-management-delete-desktop.png", fullPage: true });

  for (const width of [320, 375, 414, 768]) {
    await page.setViewportSize({ width, height: 900 });
    await page.reload();
    await expect(page.locator("main.workspace-management-page > section h2")).toHaveText(["工作区信息", "成员", "邀请成员", "删除工作区"]);
    if (width === 768) {
      const nameBox = await page.locator(".workspace-identity-fields .workspace-field").nth(0).boundingBox();
      const idBox = await page.locator(".workspace-identity-fields .workspace-field").nth(1).boundingBox();
      if (!nameBox || !idBox) throw new Error("工作区信息字段未渲染");
      expect(idBox.y).toBeGreaterThan(nameBox.y);
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.getByRole("heading", { name: "工作区管理", level: 1 })).toBeVisible();
  await expect(page.locator("main.workspace-management-page > section h2")).toHaveText(["工作区信息", "成员", "邀请成员", "删除工作区"]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
  await page.screenshot({ path: "/tmp/workspace-management-layout-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "删除工作区" }).click();
  await expect(page.getByRole("alertdialog", { name: "删除工作区？" })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "输入工作区名称" })).toBeFocused();
  await page.screenshot({ path: "/tmp/workspace-management-delete-mobile.png", fullPage: true });
  await page.getByRole("button", { name: "取消" }).click();

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole("button", { name: "删除工作区" }).click();
  const dialog = page.getByRole("alertdialog", { name: "删除工作区？" });
  await dialog.getByRole("textbox", { name: "输入工作区名称" }).fill("家庭账本");
  await dialog.getByRole("button", { name: "删除工作区" }).click();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("link", { name: "工作区管理" })).not.toHaveAttribute("aria-current", "page");
});

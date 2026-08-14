import { expect, test } from "@playwright/test";

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
  await expect(page).toHaveURL(/\/w\/workspace-1\/cash-categories$/);

  await page.getByRole("link", { name: "投资事件" }).click();
  await expect(page).toHaveURL(/\/w\/workspace-1\/investment-events$/);
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
});

test("左下角切换工作区后立即更新 URL 与收支列表", async ({ page }) => {
  let activeWorkspace = "workspace-1";
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/auth/session")) {
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: activeWorkspace,
        workspaces: [
          { id: "workspace-1", name: "家庭账本", role: "editor" },
          { id: "workspace-2", name: "旅行账本", role: "editor" },
        ],
      } });
    }
    if (path.endsWith("/auth/workspaces/workspace-2/select")) {
      activeWorkspace = "workspace-2";
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: activeWorkspace,
        workspaces: [
          { id: "workspace-1", name: "家庭账本", role: "editor" },
          { id: "workspace-2", name: "旅行账本", role: "editor" },
        ],
      } });
    }
    if (path.endsWith("/cash-projections")) {
      return route.fulfill({ json: {
        items: [{
          projection_id: `projection-${activeWorkspace}`, occurred_at: "2026-07-03T09:00:00+08:00",
          account: { id: 101, name: "日常账户", type: "cash", active: true },
          counterparty: activeWorkspace === "workspace-1" ? "家庭流水" : "旅行流水", category: null,
          note: "", amount: "-12.50", currency: "CNY", economic_type: "expense", transfer_subtype: null,
          composition: [], member_count: 1, accepted_relation_summary: [], source_type: "wechat",
          source_types: ["wechat"], record_id: `record-${activeWorkspace}`, visible: true, hidden_reason: null,
        }], projection_version: activeWorkspace === "workspace-1" ? 1 : 2, next_cursor: null,
        page_size: 50, filters: {}, filter_options: { categories: [], currencies: [], economic_types: [] }, monthly_summaries: [],
      } });
    }
    if (path.endsWith("/accounts")) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.goto("/w/workspace-1/");
  await expect(page.getByText("家庭流水")).toBeVisible();
  await page.getByRole("combobox", { name: "当前工作区" }).selectOption("workspace-2");
  await expect(page).toHaveURL(/\/w\/workspace-2\/$/);
  await expect(page.getByText("旅行流水")).toBeVisible();
  await expect(page.getByText("家庭流水")).not.toBeVisible();
});

test("工作区深链接先验证成员关系再打开目标页面", async ({ page }) => {
  let selected = false;
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/auth/session")) return route.fulfill({ json: {
      user: { email: "member@example.com" }, active_workspace_id: "workspace-1",
      workspaces: [
        { id: "workspace-1", name: "家庭账本", role: "editor" },
        { id: "workspace-2", name: "旅行账本", role: "editor" },
      ],
    } });
    if (path.endsWith("/auth/workspaces/workspace-2/select")) {
      selected = true;
      return route.fulfill({ json: {
        user: { email: "member@example.com" }, active_workspace_id: "workspace-2",
        workspaces: [
          { id: "workspace-1", name: "家庭账本", role: "editor" },
          { id: "workspace-2", name: "旅行账本", role: "editor" },
        ],
      } });
    }
    if (path.endsWith("/cash-categories")) return route.fulfill({ json: { items: [], revision: 0 } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.goto("/w/workspace-2/cash-categories");
  await expect(page.getByRole("heading", { name: "分类管理", level: 1 })).toBeVisible();
  expect(selected).toBe(true);
  await expect(page).toHaveURL(/\/w\/workspace-2\/cash-categories$/);
});

test("工作区切换失败时保留旧页面、旧 URL 和选择值", async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (path.endsWith("/auth/session")) return route.fulfill({ json: {
      user: { email: "member@example.com" }, active_workspace_id: "workspace-1",
      workspaces: [
        { id: "workspace-1", name: "家庭账本", role: "editor" },
        { id: "workspace-2", name: "旅行账本", role: "editor" },
      ],
    } });
    if (path.endsWith("/auth/workspaces/workspace-2/select")) return route.fulfill({ status: 503, json: { error: { code: "storage.busy" } } });
    if (path.endsWith("/cash-projections")) return route.fulfill({ json: {
      items: [{
        projection_id: "projection-workspace-1", occurred_at: "2026-07-03T09:00:00+08:00",
        account: { id: 101, name: "日常账户", type: "cash", active: true }, counterparty: "家庭流水",
        category: null, note: "", amount: "-12.50", currency: "CNY", economic_type: "expense",
        transfer_subtype: null, composition: [], member_count: 1, accepted_relation_summary: [],
        source_type: "wechat", source_types: ["wechat"], record_id: "record-workspace-1", visible: true, hidden_reason: null,
      }], projection_version: 1, next_cursor: null, page_size: 50, filters: {},
      filter_options: { categories: [], currencies: [], economic_types: [] }, monthly_summaries: [],
    } });
    if (path.endsWith("/accounts")) return route.fulfill({ json: { items: [] } });
    return route.fulfill({ json: { items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} } });
  });

  await page.goto("/w/workspace-1/");
  await expect(page.getByText("家庭流水")).toBeVisible();
  const switcher = page.getByRole("combobox", { name: "当前工作区" });
  await switcher.selectOption("workspace-2");
  await expect(page.getByRole("alert")).toHaveText("无法切换工作区，请稍后重试。");
  await expect(switcher).toHaveValue("workspace-1");
  await expect(page).toHaveURL(/\/w\/workspace-1\/$/);
  await expect(page.getByText("家庭流水")).toBeVisible();
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
  await expect(page).toHaveURL(/\/w\/workspace-1\/workspace-management$/);
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
  await expect(page).toHaveURL(/\/w\/workspace-2\/$/);
  await expect(page.getByRole("link", { name: "工作区管理" })).not.toHaveAttribute("aria-current", "page");
});

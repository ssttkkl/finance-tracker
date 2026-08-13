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

  await page.getByRole("link", { name: "投资事件" }).click();
  await expect(page.getByRole("heading", { name: "投资事件", level: 1 })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主要导航" })).toBeVisible();
});

import { expect, test } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

test("desktop rail and account panel follow pointer entry and exit", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);

  for (const width of [600, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");

    const content = page.locator('[id="ledger.screen"]');
    const accountPanel = page.locator('[id="account.menu"]');
    await expect(content).toBeVisible();
    const collapsedContentX = (await content.boundingBox())?.x;
    expect(collapsedContentX).toBeDefined();

    // The pointer is over empty rail space, away from the toggle and destination buttons.
    await page.mouse.move(32, 500);
    await expect.poll(async () => (await content.boundingBox())?.x ?? 0).toBeGreaterThan((collapsedContentX ?? 0) + 100);

    await page.mouse.move(width - 80, 500);
    await expect.poll(async () => (await content.boundingBox())?.x ?? 0).toBeCloseTo(collapsedContentX ?? 0, 0);

    // Use viewport pixels: Compose's accessibility mirror bounding boxes are logical dp at WIDE.
    await page.mouse.move(32, 860);
    await expect(accountPanel).toBeVisible();
    await page.waitForTimeout(700);
    await page.screenshot({ path: `/tmp/compose-app-shell-account-trigger-${width}.png` });
    await page.mouse.move(20, 520);
    await expect(accountPanel).toBeVisible();
    await page.waitForTimeout(250);
    const menuHoverScreenshot = await page.screenshot({ path: `/tmp/compose-app-shell-account-hover-${width}.png` });

    await page.mouse.move(width - 4, 4);
    await page.waitForTimeout(300);
    const afterExitScreenshot = await page.screenshot({ path: `/tmp/compose-app-shell-after-exit-${width}.png` });
    expect(menuHoverScreenshot.equals(afterExitScreenshot)).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  }

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

test("desktop rail expands on keyboard focus and Escape closes the account panel", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto("/");
  await page.mouse.move(700, 500);

  const content = page.locator('[id="ledger.screen"]');
  const collapsedContentX = (await content.boundingBox())?.x;
  expect(collapsedContentX).toBeDefined();
  const accountTrigger = page.locator('[id="workspace.switcher"]').last();
  const accountPanel = page.locator('[id="account.menu"]');
  await page.keyboard.press("Tab");
  await page.keyboard.press("Tab");
  await expect.poll(async () => (await content.boundingBox())?.x ?? 0)
    .toBeGreaterThan((collapsedContentX ?? 0) + 100);

  const triggerBox = await accountTrigger.boundingBox();
  expect(triggerBox).not.toBeNull();
  await page.mouse.move(
    (triggerBox?.x ?? 0) + (triggerBox?.width ?? 0) / 2,
    (triggerBox?.y ?? 0) + (triggerBox?.height ?? 0) / 2,
  );
  await expect(accountPanel).toBeVisible();
  const menuRegion = { x: 0, y: 480, width: 360, height: 400 };
  const openMenuScreenshot = await page.screenshot({ clip: menuRegion });
  await page.keyboard.press("Escape");
  await page.waitForTimeout(200);
  const closedMenuScreenshot = await page.screenshot({ clip: menuRegion });
  expect(closedMenuScreenshot.equals(openMenuScreenshot)).toBe(false);
  await page.keyboard.press("Enter");
  await expect(accountPanel).toBeVisible();
  const reopenedMenuScreenshot = await page.screenshot({ clip: menuRegion });
  expect(reopenedMenuScreenshot.equals(closedMenuScreenshot)).toBe(false);
  await page.screenshot({ path: "/tmp/compose-app-shell-account-escape-768.png" });
});

test("compact shell keeps drawer and account sheet click-driven", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  for (const width of [320, 375, 390, 414]) {
    await page.setViewportSize({ width, height: 844 });
    await page.goto("/");

    await page.locator('[id="navigation-open"]').click({ force: true });
    await expect(page.getByRole("button", { name: "工作区管理" })).toBeVisible();
    const drawerScreenshot = await page.screenshot();
    await page.mouse.click(152, 804);
    await page.waitForTimeout(300);
    const accountSheetScreenshot = await page.screenshot();
    expect(accountSheetScreenshot.equals(drawerScreenshot)).toBe(false);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    if (width === 390) await page.screenshot({ path: "/tmp/compose-app-shell-compact-390.png" });
  }

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

test("account workspace access errors keep retry available", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [403] });
  const api = await installComposeApiFixtures(page, {
    initialSession: {
      user: { email: "owner@example.com" },
      active_workspace_id: "workspace-1",
      workspaces: [
        { id: "workspace-1", name: "家庭账本", role: "admin" },
        { id: "workspace-2", name: "共享账本", role: "editor" },
      ],
    },
    inaccessibleWorkspaceIds: ["workspace-2"],
    workspaceSelectDelayMs: 1200,
  });
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto("/");
  await page.locator('[id="workspace.switcher"]').click({ force: true });
  await expect(page.locator('[id="account.menu"]')).toBeVisible();

  const targetWorkspace = page.getByRole("button", { name: /共享账本/ });
  await targetWorkspace.click({ force: true });
  await expect(page.getByText("正在切换工作区")).toBeVisible();
  await page.screenshot({ path: "/tmp/compose-app-shell-workspace-loading-768.png" });
  await targetWorkspace.click({ force: true });
  const selectionRequests = () => api.calls.filter((call) =>
    call.method === "POST" && call.path.endsWith("/auth/workspaces/workspace-2/select"),
  );
  await expect.poll(() => selectionRequests().length).toBe(1);

  await expect(page.getByText("无法打开该工作区，请检查权限后重试。")).toBeVisible();
  await expect(page.locator('[id="account.workspace.retry"]')).toBeVisible();
  await page.locator('[id="account.workspace.retry"]').click({ force: true });
  await expect.poll(() => selectionRequests().length).toBe(2);
  await expect(page.getByText("无法打开该工作区，请检查权限后重试。")).toBeVisible();

  await page.screenshot({ path: "/tmp/compose-app-shell-workspace-error-768.png" });
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

test("account panel switches workspace, opens management, and signs out", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  const api = await installComposeApiFixtures(page, {
    initialSession: {
      user: { email: "owner@example.com" },
      active_workspace_id: "workspace-1",
      workspaces: [
        { id: "workspace-1", name: "家庭账本", role: "admin" },
        { id: "workspace-2", name: "共享账本", role: "editor" },
      ],
    },
  });
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto("/");

  const accountTrigger = page.locator('[id="workspace.switcher"]').last();
  await accountTrigger.click({ force: true });
  await expect(page.locator('[id="account.menu"]')).toBeVisible();
  await page.waitForTimeout(250);
  await expect(page.getByRole("button", { name: /家庭账本.*管理员.*当前/ })).toBeVisible();
  await expect(page.getByRole("button", { name: /共享账本.*可编辑/ })).toBeVisible();

  await page.getByRole("button", { name: /共享账本.*可编辑/ }).click({ force: true });
  await expect(page.getByText("工作区已切换")).toBeVisible();
  await expect(page.getByRole("button", { name: /共享账本.*可编辑.*当前/ })).toBeVisible();

  const beforeManagementScreenshot = await page.screenshot();
  await page.locator('[id="account.workspace.manage"]').click({ force: true });
  await expect(page).toHaveURL(/\/w\/workspace-2\/workspace-management$/);
  await page.waitForTimeout(600);
  const managementScreenshot = await page.screenshot();
  expect(managementScreenshot.equals(beforeManagementScreenshot)).toBe(false);
  await page.screenshot({ path: "/tmp/compose-app-shell-workspace-management-768.png" });

  await page.mouse.move(38, 860);
  await expect(page.locator('[id="account.menu"]')).toBeVisible();
  await page.locator('[id="workspace.logout"]').click({ force: true });
  await page.waitForTimeout(600);
  const signedOutScreenshot = await page.screenshot();
  expect(signedOutScreenshot.equals(managementScreenshot)).toBe(false);
  await page.screenshot({ path: "/tmp/compose-app-shell-signed-out-768.png" });
  expect(api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/auth/logout")).toBe(true);

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

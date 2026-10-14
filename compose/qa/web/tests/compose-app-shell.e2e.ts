import { expect, test, type Locator, type Page } from "@playwright/test";
import { captureBrowserDiagnostics, installComposeApiFixtures } from "./compose-fixtures";

async function clickComposeTarget(page: Page, locator: Locator): Promise<void> {
  await locator.scrollIntoViewIfNeeded();
  const bounds = await locator.boundingBox();
  if (!bounds) throw new Error("Missing Compose target bounds");
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.waitForTimeout(60);
}

async function countNavigationFocusRingPixels(page: Page, target: Locator): Promise<number> {
  const bounds = await target.boundingBox();
  if (!bounds) return 0;
  const screenshot = await page.screenshot();
  return page.evaluate(async ({ pngBase64, bounds }) => {
    const binary = atob(pngBase64);
    const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: "image/png" }));
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Canvas 2D is unavailable for screenshot inspection");
    context.drawImage(bitmap, 0, 0);

    const { data, width, height } = context.getImageData(0, 0, bitmap.width, bitmap.height);
    const { x, y, width: targetWidth, height: targetHeight } = bounds;
    const left = Math.max(0, Math.floor(x - 6));
    const right = Math.min(width, Math.ceil(x + targetWidth + 6));
    const top = Math.max(0, Math.floor(y - 6));
    const bottom = Math.min(height, Math.ceil(y + targetHeight + 6));
    let count = 0;
    for (let py = top; py < bottom; py += 1) {
      for (let px = left; px < right; px += 1) {
        const outsideTargetBounds = px < x || px >= x + targetWidth || py < y || py >= y + targetHeight;
        if (!outsideTargetBounds) continue;
        const offset = (py * width + px) * 4;
        const [red, green, blue] = data.subarray(offset, offset + 3);
        if (red < 48 && green > 72 && green < 190 && blue > 175) count += 1;
      }
    }
    bitmap.close();
    return count;
  }, { pngBase64: screenshot.toString("base64"), bounds });
}

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

test("desktop account panel remains open while the pointer rests on the avatar", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);

  const width = 1045;
  await page.setViewportSize({ width, height: 1000 });
  await page.goto("/");

  const accountPanel = page.locator('[id="account.menu"]');
  const expandControl = page.getByRole("button", { name: "展开导航" });
  await page.mouse.move(width - 40, 500);
  await expect(expandControl).toBeVisible();
  await page.mouse.move(32, 500);
  await expect(page.getByRole("button", { name: "收起导航" })).toBeVisible();
  await page.waitForTimeout(220);

  const panelRegion = { x: 10, y: 600, width: 320, height: 350 };
  const expandedRailScreenshot = await page.screenshot({ clip: panelRegion });
  await page.mouse.move(32, 960);
  await expect(accountPanel).toBeVisible();
  await page.waitForTimeout(220);
  const openedPanelScreenshot = await page.screenshot({ clip: panelRegion });
  expect(openedPanelScreenshot.equals(expandedRailScreenshot)).toBe(false);
  await page.screenshot({ path: "/tmp/compose-app-shell-account-hover-stable-1045.png" });

  for (let sample = 0; sample < 20; sample += 1) {
    await page.waitForTimeout(75);
    const screenshot = await page.screenshot({ clip: panelRegion });
    expect(screenshot.equals(openedPanelScreenshot)).toBe(true);
  }

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

test("desktop rail collapses after mouse navigation leaves focus behind", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);

  for (const width of [768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");

    const collapseControl = page.getByRole("button", { name: "收起导航" });

    await page.locator('[id="navigation-item-cash-categories"]').click({ force: true });
    await expect(page).toHaveURL(/\/w\/workspace-1\/cash-categories$/);
    await expect(collapseControl).toBeVisible();
    await page.mouse.move(width - 40, 500);
    await expect(page.getByRole("button", { name: "展开导航" })).toBeVisible();
    await page.screenshot({ path: `/tmp/compose-app-shell-after-mouse-navigation-${width}.png` });
  }

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

test("desktop hover-expanded rail control collapses then resumes hover after exit", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);

  for (const width of [768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");

    const collapseControl = page.getByRole("button", { name: "收起导航" });
    const expandControl = page.getByRole("button", { name: "展开导航" });

    await page.mouse.move(32, 500);
    await expect(collapseControl).toBeVisible();

    await collapseControl.click({ force: true });
    await expect(expandControl).toBeVisible();
    await page.screenshot({ path: `/tmp/compose-app-shell-toggle-collapsed-${width}.png` });
    await page.mouse.move(width - 40, 500);
    await expect(expandControl).toBeVisible();
    await page.mouse.move(32, 500);
    await expect(collapseControl).toBeVisible();
    await page.mouse.move(width - 40, 500);
    await expect(expandControl).toBeVisible();
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
  const accountPanel = page.locator('[id="account.menu"]');
  await page.keyboard.press("Tab");
  await page.keyboard.press("Tab");
  await expect.poll(async () => (await content.boundingBox())?.x ?? 0)
    .toBeGreaterThan((collapsedContentX ?? 0) + 100);

  await page.mouse.move(32, 860);
  await expect(accountPanel).toBeVisible();
  const openMenuScreenshot = await page.screenshot();
  await page.keyboard.press("Escape");
  await page.waitForTimeout(200);
  const closedAfterHoverEscape = await page.screenshot();
  expect(closedAfterHoverEscape.equals(openMenuScreenshot)).toBe(false);
  await page.mouse.move(700, 500);
  await page.waitForTimeout(200);
  const closedMenuScreenshot = await page.screenshot();
  await page.keyboard.press("Space");
  await expect.poll(async () => !(await page.screenshot()).equals(closedMenuScreenshot)).toBe(true);
  const reopenedMenuScreenshot = await page.screenshot();
  expect(reopenedMenuScreenshot.equals(closedMenuScreenshot)).toBe(false);
  await page.keyboard.press("Escape");
  await expect.poll(async () => (await page.screenshot()).equals(closedMenuScreenshot)).toBe(true);
  await page.keyboard.press("Enter");
  await expect.poll(async () => !(await page.screenshot()).equals(closedMenuScreenshot)).toBe(true);
  await page.screenshot({ path: "/tmp/compose-app-shell-account-escape-768.png" });
});

test("desktop navigation focus ring follows keyboard focus, not pointer clicks", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);

  for (const width of [768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/");

    const selectedItem = page.locator('[id="navigation-item-cash-ledger"]');
    await page.mouse.move(width - 32, 500);
    let keyboardFocusPixels = 0;
    for (let tabCount = 0; tabCount < 24; tabCount += 1) {
      await page.keyboard.press("Tab");
      keyboardFocusPixels = await countNavigationFocusRingPixels(page, selectedItem);
      if (keyboardFocusPixels > 20) break;
    }
    expect(keyboardFocusPixels).toBeGreaterThan(20);
    await page.screenshot({ path: `/tmp/compose-navigation-keyboard-focus-${width}.png` });

    const categoriesItem = page.locator('[id="navigation-item-cash-categories"]');
    await page.keyboard.press("Tab");
    await expect.poll(async () => countNavigationFocusRingPixels(page, categoriesItem)).toBeGreaterThan(20);
    await page.keyboard.press("Enter");
    await expect(page).toHaveURL(/\/cash-categories$/);

    const holdingsItem = page.locator('[id="navigation-item-investment-holdings"]');
    await page.keyboard.press("Tab");
    await expect.poll(async () => countNavigationFocusRingPixels(page, holdingsItem)).toBeGreaterThan(20);
    await page.keyboard.press("Space");
    await expect(page).toHaveURL(/\/investment-holdings$/);

    await holdingsItem.click({ force: true });
    await expect.poll(async () => countNavigationFocusRingPixels(page, holdingsItem)).toBe(0);
    await page.mouse.move(32, 500);
    await expect(page.getByRole("button", { name: "收起导航" })).toBeVisible();
    await expect.poll(async () => countNavigationFocusRingPixels(page, selectedItem)).toBe(0);
    await page.screenshot({ path: `/tmp/compose-navigation-pointer-focus-expanded-${width}.png` });

    await page.mouse.move(width - 32, 500);
    await expect(page.getByRole("button", { name: "展开导航" })).toBeVisible();
    await expect.poll(async () => countNavigationFocusRingPixels(page, selectedItem)).toBe(0);
    await page.screenshot({ path: `/tmp/compose-navigation-pointer-focus-collapsed-${width}.png` });
  }

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
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
  await page.waitForTimeout(250);

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
  await clickComposeTarget(page, page.getByRole("button", { name: "退出登录", exact: true }));
  await expect.poll(() => api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/auth/logout")).toBe(true);
  const signedOutScreenshot = await page.screenshot();
  expect(signedOutScreenshot.equals(managementScreenshot)).toBe(false);
  await page.screenshot({ path: "/tmp/compose-app-shell-signed-out-768.png" });

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

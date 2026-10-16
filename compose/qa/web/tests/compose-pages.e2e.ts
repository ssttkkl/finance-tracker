import { expect, test, type Locator, type Page } from "@playwright/test";
import path from "node:path";
import { mkdir } from "node:fs/promises";
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
const screenshotDir = path.resolve(process.cwd(), "test-results/evidence");
const changeScreenshotDir = path.resolve(process.cwd(), "../openspec/changes/compose-cash-ledger-redesign/screenshots/current-review-20261015");
const browserDiagnostics = new WeakMap<import("@playwright/test").Page, BrowserDiagnostics>();

async function clickComposeTarget(page: Page, locator: Locator, xRatio = 0.5, yRatio = 0.5): Promise<void> {
  await locator.scrollIntoViewIfNeeded();
  const bounds = await locator.boundingBox();
  if (!bounds) throw new Error("Missing Compose target bounds");
  await page.mouse.click(bounds.x + bounds.width * xRatio, bounds.y + bounds.height * yRatio);
  await page.waitForTimeout(60);
}

async function clickComposeUntil(page: Page, locator: Locator, isDone: () => Promise<boolean>): Promise<void> {
  let lastError: unknown;
  for (let attempt = 0; attempt < 4; attempt += 1) {
    try {
      await expect(locator).toBeVisible();
      await clickComposeTarget(page, locator);
      await expect.poll(isDone, { timeout: 1_500 }).toBe(true);
      return;
    } catch (error) {
      lastError = error;
      if (attempt < 3) await page.waitForTimeout(100);
    }
  }
  if (lastError instanceof Error) throw lastError;
  throw new Error("Compose target did not produce the expected state");
}

async function hasRenderedComposeBounds(locator: Locator): Promise<boolean> {
  const bounds = await locator.boundingBox();
  return !!bounds && bounds.width > 0 && bounds.height > 0;
}

async function clickVisibleComposeTargetUntil(page: Page, locator: Locator, isDone: () => Promise<boolean>): Promise<void> {
  const viewport = page.viewportSize();
  if (!viewport) throw new Error("Missing Playwright viewport");
  let lastError: unknown;
  for (let attempt = 0; attempt < 6; attempt += 1) {
    for (let index = 0; index < await locator.count(); index += 1) {
      const candidate = locator.nth(index);
      const bounds = await candidate.boundingBox();
      if (!bounds || bounds.x < 0 || bounds.y < 0 || bounds.x + bounds.width > viewport.width || bounds.y + bounds.height > viewport.height) {
        continue;
      }
      try {
        await clickComposeTarget(page, candidate);
        await expect.poll(isDone, { timeout: 1_500 }).toBe(true);
        return;
      } catch (error) {
        lastError = error;
        break;
      }
    }
    if (attempt < 5) await page.waitForTimeout(120);
  }
  if (lastError instanceof Error) throw lastError;
  throw new Error("No in-viewport Compose target became available");
}

async function getLedgerFab(page: Page): Promise<Locator> {
  const taggedFab = page.locator('[id="ledger.actions-fab"]').last();
  if (await taggedFab.count() && await taggedFab.isVisible().catch(() => false)) return taggedFab;
  return page.getByRole("button", { name: "账单操作" }).last();
}

async function clickFabMenuItemFromBottom(page: Page, itemIndexFromBottom: number): Promise<void> {
  const menuItem = page.locator(`[id="${itemIndexFromBottom === 0 ? "ledger.import" : "ledger.add"}"]`).last();
  // Compose Web draws the menu on its canvas and keeps an invisible semantics
  // mirror in the DOM; use the mirrored bounds to click the rendered action.
  await expect.poll(() => hasRenderedComposeBounds(menuItem), { timeout: 2_500 }).toBe(true);
  await clickComposeTarget(page, menuItem);
  await expect.poll(() => page.url().endsWith("/cash-import"), { timeout: 1_500 }).toBe(true);
}

async function captureLedgerScene(page: Page, name: string, width: number, height: number): Promise<void> {
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS !== "1") return;
  const screenshotDirectory = process.env.FT_COMPOSE_COLOR_SCHEME === "dark"
    ? path.join(changeScreenshotDir, "dark")
    : changeScreenshotDir;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.setViewportSize({ width, height });
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(screenshotDirectory, `${name}-${width}.png`) });
}

async function screenshotDifferenceRatio(page: Page, before: Buffer, after: Buffer): Promise<number> {
  return page.evaluate(async ({ beforeBase64, afterBase64 }) => {
    const decode = async (data: string): Promise<HTMLImageElement> => {
      const image = new Image();
      image.src = `data:image/png;base64,${data}`;
      await image.decode();
      return image;
    };
    const [beforeImage, afterImage] = await Promise.all([decode(beforeBase64), decode(afterBase64)]);
    const canvas = document.createElement("canvas");
    canvas.width = beforeImage.naturalWidth;
    canvas.height = beforeImage.naturalHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Unable to compare Compose screenshots");
    context.drawImage(beforeImage, 0, 0);
    const beforePixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(afterImage, 0, 0);
    const afterPixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    let changed = 0;
    let samples = 0;
    for (let y = 12; y < canvas.height; y += 24) {
      for (let x = 12; x < canvas.width; x += 24) {
        const offset = (y * canvas.width + x) * 4;
        const difference = Math.abs(beforePixels[offset] - afterPixels[offset]) +
          Math.abs(beforePixels[offset + 1] - afterPixels[offset + 1]) +
          Math.abs(beforePixels[offset + 2] - afterPixels[offset + 2]);
        if (difference > 72) changed += 1;
        samples += 1;
      }
    }
    return samples === 0 ? 0 : changed / samples;
  }, { beforeBase64: before.toString("base64"), afterBase64: after.toString("base64") });
}

async function selectFirstLedgerRecord(page: Page, width: number): Promise<void> {
  const row = page.locator('[id="ledger.record-projection-1"]').last();
  if (width < 600) {
    const bounds = await row.boundingBox();
    expect(bounds).not.toBeNull();
    await page.mouse.move(bounds!.x + bounds!.width * 0.45, bounds!.y + bounds!.height * 0.5);
    await page.mouse.down();
    await page.waitForTimeout(800);
    await page.mouse.up();
  } else {
    await clickComposeTarget(page, page.locator('[id="ledger.record-checkbox-projection-1"]').last());
  }
}

async function openLedgerActions(page: Page): Promise<void> {
  const fab = await getLedgerFab(page);
  const addAction = page.locator('[id="ledger.add"]').last();
  await clickComposeUntil(page, fab, async () => hasRenderedComposeBounds(addAction));
}

async function chooseComposeFiles(page: Page, files: Array<{ name: string; mimeType: string; buffer: Buffer }>): Promise<void> {
  const target = page.locator('[id="import.choose-file"]');
  for (let attempt = 0; attempt < 4; attempt += 1) {
    await expect(target).toBeVisible();
    const chooserPromise = page.waitForEvent("filechooser", { timeout: 2_500 }).catch(() => null);
    try {
      await clickComposeTarget(page, target);
    } catch {
      await page.waitForTimeout(250);
    }
    const chooser = await chooserPromise;
    if (chooser) {
      await chooser.setFiles(files);
      return;
    }
  }
  throw new Error("Compose file button did not open the chooser");
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

test("F-02 可创建工作区并进入账本", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page, { initialSession: noWorkspaceSession });
  await page.goto("/");
  await expect(page.locator('[id="workspace.screen"]').last()).toContainText("创建工作区");
  await expect(page.locator('[id="auth.card"]')).toHaveCount(1);
  await expect(page.getByText("新工作区", { exact: true })).toBeVisible();

  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const card = page.locator('[id="auth.card"]').last();
    const expectedWidth = Math.min(width - 32, 440);
    await expect.poll(async () => (await card.boundingBox())?.width ?? 0).toBeCloseTo(expectedWidth, 0);
    await expect.poll(async () => {
      const bounds = await card.boundingBox();
      return bounds ? Math.abs(bounds.x - (width - bounds.width) / 2) : Number.POSITIVE_INFINITY;
    }).toBeLessThanOrEqual(2);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: testInfo.outputPath(`workspace-access-${width}.png`) });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(100);
  await expect.poll(async () => (await page.locator('[id="auth.card"]').last().boundingBox())?.width ?? 0).toBeCloseTo(358, 0);

  await typeIntoComposeInput(page, '[id="workspace.create-name"]', "旅行账本");
  await page.locator('[id="workspace.create"]').last().click({ force: true });

  await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
  await expect(page).toHaveURL(/\/w\/workspace-created\/$/);
});

test("响应式导航在 regular 与 wide 宽度保留可见内容区", async ({ page }, testInfo) => {
  await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/`);

  for (const width of [600, 768, 1023, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    const ledger = page.locator('[id="ledger.screen"]').last();
    await expect(ledger).toBeVisible();
    await expect(page.locator('[id="ledger.summary"]').last()).toBeVisible();
    await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText("收支账本");
    await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
    const bounds = await ledger.boundingBox();
    expect(bounds?.width ?? 0).toBeGreaterThan(320);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 768 || width === 1440) {
      await page.screenshot({ path: testInfo.outputPath(`app-${width}.png`) });
    }
  }
});

test("工作区主导航保留路径上下文并支持浏览器前进后退", async ({ page }, testInfo) => {
  test.setTimeout(120_000);
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.summary"]').last()).toBeVisible();
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toBeVisible();
  await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText("收支账本");
  await page.evaluate(async () => { await document.fonts.ready; });
  await page.screenshot({ path: testInfo.outputPath("app-390.png") });

  const destinations = [
    { id: "cash-categories", screen: "cash-categories.screen", path: "cash-categories", title: "收支分类" },
    { id: "investment-holdings", screen: "investment-holdings.screen", path: "investment-holdings", title: "当前持仓" },
    { id: "investment-events", screen: "investment-events.screen", path: "investment-events", title: "投资事件" },
    { id: "workspace-management", screen: "workspace-management.screen", path: "workspace-management", title: "工作区管理" },
  ];
  const visited = [{ url: `${workspaceRoot}/`, screen: "ledger.screen", title: "收支账本" }];

  for (const destination of destinations) {
    const openNavigation = page.locator('[id="navigation-open"]').last();
    await clickComposeUntil(page, openNavigation, async () => page.locator(`[id="navigation-item-${destination.id}"]`).isVisible());
    const url = `${workspaceRoot}/${destination.path}`;
    await clickVisibleComposeTargetUntil(
      page,
      page.locator(`[id="navigation-item-${destination.id}"]`),
      async () => page.url().endsWith(url),
    );
    visited.push({ url, screen: destination.screen, title: destination.title });
    await expect(page).toHaveURL(url);
    await expect(page.locator(`[id="${destination.screen}"]`).last()).toBeVisible();
    await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText(destination.title);
    await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
    for (const width of [390, 600, 768, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(100);
      await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText(destination.title);
      await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      if (width === 600 && destination.id === "investment-events") {
        await page.screenshot({ path: "/tmp/compose-investment-events-600.png" });
      }
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.waitForTimeout(100);
  }

  for (const route of visited.slice(0, -1).reverse()) {
    await page.goBack();
    await expect(page).toHaveURL(route.url);
    await expect(page.locator(`[id="${route.screen}"]`).last()).toBeVisible();
    await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText(route.title);
    await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
  }
  for (const route of visited.slice(1)) {
    await page.goForward();
    await expect(page).toHaveURL(route.url);
    await expect(page.locator(`[id="${route.screen}"]`).last()).toBeVisible();
    await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText(route.title);
    await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
  }
});

test("F-05 账单导入从账本以响应式二级页面打开并可关闭", async ({ page }, testInfo) => {
  test.setTimeout(150_000);
  await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/`);
  await mkdir(screenshotDir, { recursive: true });

  for (const width of [320, 375, 390, 414, 768, 1440]) {
    const height = width === 390 ? 844 : width === 768 ? 1024 : 900;
    await page.setViewportSize({ width, height });
    // Resize before navigation so Compose Web builds its semantics tree for
    // this viewport instead of reusing bounds captured at the previous width.
    await page.goto(`${workspaceRoot}/`);
    await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await openLedgerActions(page);
    if (width === 320) await captureLedgerScene(page, "fab-idle-compact-boundary", width, height);
    const importUrl = `${workspaceRoot}/cash-import`;
    await clickFabMenuItemFromBottom(page, 0);
    await expect.poll(() => page.url().endsWith("/cash-import"), { timeout: 1_500 }).toBe(true);
    await expect(page).toHaveURL(importUrl);

    if (width < 600) {
      const fullscreen = page.locator('[id="secondary-page-fullscreen"]').last();
      await expect(fullscreen).toBeVisible();
      await expect(page.locator('[id="secondary-page-modal"]')).toHaveCount(0);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const bounds = await fullscreen.boundingBox();
      expect(bounds?.x ?? 1).toBeCloseTo(0, 0);
      expect(bounds?.y ?? 1).toBeCloseTo(0, 0);
      expect(bounds?.width ?? 0).toBeCloseTo(width, 0);
      expect(bounds?.height ?? 0).toBeCloseTo(height, 0);
      if (width === 390) {
        await page.screenshot({ path: path.join(screenshotDir, "compose-secondary-page-390.png") });
      }
      await clickComposeTarget(page, page.locator('[id="secondary-page-back"]').last());
    } else {
      const modal = page.locator('[id="secondary-page-modal"]').last();
      await expect(modal).toBeVisible();
      await expect(page.locator('[id="secondary-page-fullscreen"]')).toHaveCount(0);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const bounds = await modal.boundingBox();
      expect(bounds?.width ?? 0).toBeCloseTo(width * 0.9, 0);
      expect(bounds?.height ?? 0).toBeCloseTo(height * 0.9, 0);
      if (width === 1440) await page.screenshot({ path: path.join(screenshotDir, "compose-secondary-page-1440.png") });
      if (width === 768) {
        await clickComposeTarget(page, page.locator('[id="secondary-page-close"]').last());
      } else {
        await page.keyboard.press("Escape");
      }
    }

    await expect(page).toHaveURL(`${workspaceRoot}/`);
    await page.waitForTimeout(350);
  }

  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  await openLedgerActions(page);
  const importButton = page.getByText("导入账单", { exact: true }).last();
  await clickComposeUntil(page, importButton, async () => page.url().endsWith("/cash-import"));
  await chooseComposeFiles(page, [{
    name: "discard-me.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
  }]);
  await expect(page.getByText("discard-me.csv", { exact: true }).first()).toBeVisible();
  await clickComposeTarget(page, page.locator('[id="secondary-page-back"]').last());
  await expect(page).toHaveURL(`${workspaceRoot}/`);
  await openLedgerActions(page);
  await clickComposeUntil(page, importButton, async () => page.url().endsWith("/cash-import"));
  await expect(page.getByText("discard-me.csv", { exact: true })).toHaveCount(0);
  await clickComposeTarget(page, page.locator('[id="secondary-page-back"]').last());
});

test("F-03 账本显示本地月份摘要并按账户筛选", async ({ page }) => {
  test.setTimeout(60_000);
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.summary"]').last()).toContainText("支出");
  await expect(page.locator('[id="ledger.filter.quick-type-all"]').last()).toBeVisible();
  await expect(page.locator('[id="ledger.filter.quick-type-expense"]').last()).toBeVisible();
  await expect(page.locator('[id="ledger.filter.quick-type-income"]').last()).toBeVisible();
  await expect(page.locator('[id="ledger.filter.quick-type-internal_transfer"]').last()).toBeVisible();

  const moreFilters = page.locator('[id="ledger.more-filters"]').last();
  await clickComposeUntil(page, moreFilters, async () => page.getByRole("button", { name: "账户 全部账户", exact: true }).isVisible());
  await expect(page.getByRole("textbox", { name: "最低金额" })).toBeVisible();
  const accountFilter = page.getByRole("button", { name: "账户 全部账户", exact: true }).last();
  await clickComposeUntil(page, accountFilter, async () => page.getByRole("button", { name: "日常账户", exact: true }).last().isVisible());
  const dailyAccount = page.getByRole("button", { name: "日常账户", exact: true }).last();
  await clickComposeUntil(page, dailyAccount, async () => api.calls.some((call) => call.path.includes("account_id=1")));

  await expect.poll(() => api.calls.some((call) => call.path.includes("account_id=1"))).toBe(true);
  const filteredLedgerPath = api.calls.find((call) => call.path.includes("account_id=1"))?.path.split("?")[0];
  expect(filteredLedgerPath).toBeTruthy();
  const callsBeforeReset = api.calls.length;
  await clickComposeUntil(
    page,
    page.getByRole("button", { name: "清除", exact: true }).last(),
    async () => page.getByRole("button", { name: "账户 全部账户", exact: true }).isVisible(),
  );
  await expect.poll(() => api.calls.slice(callsBeforeReset).some((call) => call.path.startsWith(filteredLedgerPath!))).toBe(true);
  const resetLedgerCalls = api.calls.slice(callsBeforeReset).filter((call) => call.path.startsWith(filteredLedgerPath!));
  expect(resetLedgerCalls[resetLedgerCalls.length - 1].path).not.toContain("account_id=1");
  await page.keyboard.press("Escape");
  await page.waitForTimeout(250);
  await page.reload();
  await expect(page.locator('[id="ledger.filters"]').last()).toBeVisible();
  await expect(page.getByRole("textbox", { name: "最低金额" })).toHaveCount(0);
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width >= 768) {
      await expect(page.locator('[id="ledger.table-header"]')).toContainText("日期");
      await expect(page.locator('[id="ledger.record-projection-1"]')).toContainText("-28.50 CNY");
    } else {
      await expect(page.locator('[id="ledger.table-header"]')).toHaveCount(0);
      await expect(page.locator('[id="ledger.record-projection-1"]')).toBeVisible();
    }
  }
  await page.goto(`${workspaceRoot}/`);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await expect(page.locator('[id="ledger.record-projection-1"]').last()).toBeVisible();
    await captureLedgerScene(page, `list-${sizeName}`, width, height);
  }
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.waitForTimeout(300);
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await mkdir(screenshotDir, { recursive: true });
    const themeSuffix = process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "-dark" : "";
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForTimeout(3000);
    await page.screenshot({
      path: path.join(screenshotDir, `web-final-390${themeSuffix}.png`),
    });

    await page.setViewportSize({ width: 768, height: 1024 });
    await page.waitForTimeout(500);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({
      path: path.join(screenshotDir, `web-final-768${themeSuffix}.png`),
    });

    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.waitForTimeout(500);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({
      path: path.join(screenshotDir, `web-final-1440${themeSuffix}.png`),
    });
  }
});

test("F-03 Compact 默认隐藏复选框，长按选中并在取消最后一笔后退出", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toBeVisible();
  await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(0);

  const row = page.locator('[id="ledger.record-projection-1"]').last();
  const bounds = await row.boundingBox();
  expect(bounds).not.toBeNull();
  await page.mouse.move(bounds!.x + bounds!.width * 0.45, bounds!.y + bounds!.height * 0.5);
  await page.mouse.down();
  await page.waitForTimeout(800);
  await page.mouse.up();

  await expect(page.locator('[id="ledger.selected-count"]')).toContainText("1");
  await expect(page.locator('[id="ledger.record-checkbox-projection-1"]')).toBeVisible();
  await captureLedgerScene(page, "batch-selection-toolbar-compact", 390, 844);
  await row.click({ force: true });
  await expect(page.locator('[id="ledger.selected-count"]')).toHaveCount(0);
  await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(0);
});

test("F-03 各窗口通过同一 FAB 打开新增与导入菜单", async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const [width, height] of [[390, 844], [768, 1024], [1440, 1000]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const fab = await getLedgerFab(page);
    await clickComposeUntil(page, fab, async () => page.locator('[id="ledger.add"]:visible').last().isVisible());
    await expect(page.locator('[id="ledger.import"]:visible').last()).toBeVisible();
    await expect(page.locator('[id="ledger.add"]:visible').last()).toBeVisible();
    await captureLedgerScene(page, `fab-idle-${width === 390 ? "compact" : width === 768 ? "regular" : "wide"}`, width, height);
  }
});

test("F-03 Regular/Wide 保留行复选框、移除全选并通过菜单取消选择", async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const width of [768, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(`${workspaceRoot}/`);
    const checkbox = page.locator('[id="ledger.record-checkbox-projection-1"]').last();
    await expect(checkbox).toBeVisible();
    await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(1);
    await clickComposeUntil(page, checkbox, async () => page.locator('[id="ledger.selected-count"]').isVisible());
    await expect(page.locator('[id="ledger.selected-count"]')).toContainText("1");
    const sizeName = width === 768 ? "regular" : "wide";
    await captureLedgerScene(page, `batch-selection-toolbar-${sizeName}`, width, 1000);
    const fab = await getLedgerFab(page);
    await clickComposeUntil(page, fab, async () => page.locator('[id="ledger.batch-category"]').last().isVisible());
    await expect(page.locator('[id="ledger.batch-delete"]')).toBeVisible();
    await expect(page.locator('[id="ledger.cancel-selection"]')).toBeVisible();
    await captureLedgerScene(page, `fab-batch-${sizeName}`, width, 1000);
    await page.locator('[id="ledger.cancel-selection"]').last().click({ force: true });
    await expect(page.locator('[id="ledger.selected-count"]')).toHaveCount(0);
    await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(1);
  }
});

test("F-03 批量分类取消不写入", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await selectFirstLedgerRecord(page, width);
    await expect(page.locator('[id="ledger.selected-count"]')).toContainText("1");
    await captureLedgerScene(page, `batch-selection-toolbar-${sizeName}`, width, height);

    await clickComposeUntil(page, await getLedgerFab(page), async () => page.locator('[id="ledger.batch-category"]').last().isVisible());
    await captureLedgerScene(page, `fab-batch-${sizeName}`, width, height);
    const dialog = page.locator('[id="ledger.category-dialog"]').last();
    await page.locator('[id="ledger.batch-category"]').last().click({ force: true });
    await expect(dialog).toBeVisible();
    await captureLedgerScene(page, `batch-category-dialog-${sizeName}`, width, height);
    await dialog.locator('[id="ledger.category-picker"]').click({ force: true });
    await expect(page.locator('[id="choice-option-coffee"]').last()).toBeVisible();
    await captureLedgerScene(page, `batch-category-options-${sizeName}`, width, height);
    await page.locator('[id="choice-option-coffee"]').last().click({ force: true });
    await expect(dialog.locator('[id="ledger.category.apply"]')).toBeEnabled();
    await captureLedgerScene(page, `batch-classify-ready-${sizeName}`, width, height);
    const writesBeforeCancel = api.state.classificationWrites.length;
    await dialog.locator('[id="ledger.category.cancel"]').click({ force: true });
    await captureLedgerScene(page, `batch-classify-cancelled-${sizeName}`, width, height);
    expect(api.state.classificationWrites).toHaveLength(writesBeforeCancel);
  }
});

test("F-03 批量分类应用时沿用现有写入", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await selectFirstLedgerRecord(page, width);
    await clickComposeUntil(page, await getLedgerFab(page), async () => page.locator('[id="ledger.batch-category"]').last().isVisible());
    await page.locator('[id="ledger.batch-category"]').last().click({ force: true });
    const dialog = page.locator('[id="ledger.category-dialog"]:visible').last();
    await expect(dialog).toBeVisible();
    const categoryPicker = dialog.getByRole("button", { name: "分类 选择分类", exact: true }).last();
    await clickComposeUntil(page, categoryPicker, async () => page.getByRole("button", { name: "餐饮 / 咖啡", exact: true }).last().isVisible());
    const coffeeOption = page.getByRole("button", { name: "餐饮 / 咖啡", exact: true }).last();
    await clickComposeUntil(page, coffeeOption, async () => dialog.getByRole("button", { name: "分类 餐饮 / 咖啡", exact: true }).last().isVisible());
    await captureLedgerScene(page, `batch-classify-ready-${sizeName}`, width, height);
    const writesBeforeApply = api.state.classificationWrites.length;
    const applyButton = dialog.getByRole("button", { name: "应用", exact: true }).last();
    await clickComposeUntil(page, applyButton, async () => api.state.classificationWrites.length === writesBeforeApply + 1);
    expect(api.state.classificationWrites.at(-1)).toMatchObject({ projection_ids: ["projection-1"], projection_version: 1, category_id: "coffee" });
    await captureLedgerScene(page, `batch-classify-applied-${sizeName}`, width, height);
  }
});

test("F-03 批量删除仍要求确认", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await selectFirstLedgerRecord(page, width);
    const deleteCallsBefore = api.calls.filter((call) => call.path === "/api/v1/cash-projections/delete-impact" && call.method === "POST").length;
    await clickComposeUntil(page, await getLedgerFab(page), async () => page.locator('[id="ledger.batch-delete"]').last().isVisible());
    await page.locator('[id="ledger.batch-delete"]').last().click({ force: true });
    await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-projections/delete-impact" && call.method === "POST").length).toBe(deleteCallsBefore + 1);
    await expect(page.getByRole("button", { name: "取消", exact: true }).last()).toBeVisible();
    await captureLedgerScene(page, `batch-delete-confirm-${sizeName}`, width, height);
    await page.getByRole("button", { name: "取消", exact: true }).last().click({ force: true });
    expect(api.state.projectionDeletes).toHaveLength(0);
    await captureLedgerScene(page, `batch-delete-cancelled-${sizeName}`, width, height);
  }
});

test("F-03 账本请求失败后可重试，空结果显示空态", async ({ page }) => {
  const api = await installComposeApiFixtures(page, { cashPageFailures: 1 });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  const retry = page.locator('[id="ledger.retry"]');
  await expect(retry).toContainText("重试");
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    for (const [name, width, height] of [["compact", 390, 844], ["regular", 768, 1024], ["wide", 1440, 1000]] as const) {
      await captureLedgerScene(page, `ledger-api-error-${name}`, width, height);
    }
    await page.setViewportSize({ width: 390, height: 844 });
  }
  await clickComposeUntil(page, retry, async () => page.locator('[id="ledger.record-projection-1"]').last().isVisible());
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toContainText("咖啡店");
  expect(api.calls.filter((call) => call.path.startsWith("/api/v1/cash-projections")).length).toBe(2);
});

test("F-03 桌面详细筛选弹窗按内容高度展开", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 768, height: 1024 });
  await page.goto(`${workspaceRoot}/`);
  const moreFilters = page.locator('[id="ledger.more-filters"]').last();
  const details = page.locator('[id="ledger.filter.details"]').last();
  await clickComposeUntil(page, moreFilters, async () => details.isVisible());
  let bounds = await details.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds?.height ?? 1000).toBeLessThan(600);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.waitForTimeout(150);
  bounds = await details.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds?.width ?? 1000).toBeLessThanOrEqual(720);
});

test("F-03 详细筛选在三类窗口展开并可用 Escape 关闭", async ({ page }) => {
  await installComposeApiFixtures(page);
  const sizes = [
    { name: "compact", width: 390, height: 844 },
    { name: "regular", width: 768, height: 1024 },
    { name: "wide", width: 1440, height: 1000 },
  ] as const;
  for (const size of sizes) {
    await page.setViewportSize({ width: size.width, height: size.height });
    await page.goto(`${workspaceRoot}/`);
    const moreFilters = page.locator('[id="ledger.more-filters"]').last();
    const details = page.locator('[id="ledger.filter.details"]').last();
    await clickComposeUntil(page, moreFilters, async () => details.isVisible());
    await expect(page.getByRole("textbox", { name: "最低金额" })).toBeVisible();
    if (size.name === "compact") {
      const amountBounds = await page.getByRole("textbox", { name: "最低金额" }).boundingBox();
      expect(amountBounds).not.toBeNull();
      expect(amountBounds?.width ?? 0).toBeGreaterThan(320);
    }
    await captureLedgerScene(page, `filters-open-${size.name}`, size.width, size.height);
    let openScreenshot = await page.screenshot();

    const doneButton = page.getByRole("button", { name: "完成", exact: true }).last();
    await clickComposeTarget(page, doneButton);
    await page.waitForTimeout(300);
    const doneClosedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, openScreenshot, doneClosedScreenshot)).toBeGreaterThan(0.03);
    await captureLedgerScene(page, `filters-done-closed-${size.name}`, size.width, size.height);

    await page.goto(`${workspaceRoot}/`);
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.more-filters"]').last(),
      async () => page.getByRole("textbox", { name: "最低金额" }).isVisible(),
    );
    openScreenshot = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
    const closedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, openScreenshot, closedScreenshot)).toBeGreaterThan(0.03);
    await captureLedgerScene(page, `filters-escape-closed-${size.name}`, size.width, size.height);
  }
});

test("F-03 账本没有记录时显示空态", async ({ page }) => {
  await installComposeApiFixtures(page, { cashPageEmpty: true });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.empty"]')).toContainText("没有匹配的收支记录。");
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    for (const [name, width, height] of [["compact", 390, 844], ["regular", 768, 1024], ["wide", 1440, 1000]] as const) {
      await captureLedgerScene(page, `ledger-api-empty-${name}`, width, height);
    }
  }
});

test("F-04 可新建并编辑流水，保留账户和金额字段", async ({ page }) => {
  test.setTimeout(120_000);
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.goto(`${workspaceRoot}/`);
  await expect.poll(() => api.calls.some((call) => call.path.startsWith("/api/v1/cash-ledger/options"))).toBe(true);
  await openLedgerActions(page);
  const panel = page.locator('[id="ledger.record-panel"]');
  await clickComposeUntil(page, page.locator('[id="ledger.add"]:visible').last(), async () => panel.isVisible());
  await expect(page.getByText("记一笔", { exact: true })).toBeVisible();
  await expect(page.locator('[id="record.type"]').last()).toContainText("支出");
  await typeIntoComposeInput(page, '[id="record.amount"]', "12.50");
  await expect(page.getByText("12.50", { exact: true })).toBeVisible();
  await typeIntoComposeInput(page, '[id="record.counterparty"]', "午餐店");
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const fieldIds = ["record.amount", "record.counterparty", "record.counterparty-account", "record.occurred-at"];
  const readFieldBounds = () => Promise.all(
    fieldIds.map(async (id) => page.locator(`[id="${id}"]`).last().boundingBox()),
  );
  await expect.poll(async () => (await readFieldBounds()).every(Boolean)).toBe(true);
  const fields = await readFieldBounds();
  expect(fields.every(Boolean)).toBe(true);
  expect(fields[0]!.y).toBeLessThan(fields[1]!.y);
  expect(fields[1]!.y).toBeLessThan(fields[2]!.y);
  expect(fields[2]!.y).toBeLessThan(fields[3]!.y);
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await mkdir(screenshotDir, { recursive: true });
    await page.screenshot({ path: path.join(screenshotDir, "cash-record-create-390.png") });
  }
  await page.waitForTimeout(300);
  await clickComposeUntil(page, page.locator('[id="record.save"]:visible').last(), async () => api.state.recordWrites.length === 1);

  await expect.poll(() => api.state.recordWrites.length).toBe(1);
  expect(api.state.recordWrites[0]).toMatchObject({ amount: "12.50", account_name: "日常账户", record_type: "expense" });
  await expect.poll(() => api.calls.filter((call) => call.path.startsWith("/api/v1/cash-projections")).length).toBe(2);
  await page.reload();
  await expect(page.locator('[id="ledger.record-projection-1"]').last()).toContainText("咖啡店");

  await clickComposeUntil(page, page.locator('[id="ledger.record-projection-1"]').last(), async () => api.calls.some((call) => call.path === "/api/v1/evidence/cash-projections/projection-1"));
  await expect.poll(() => api.calls.some((call) => call.path === "/api/v1/evidence/cash-projections/projection-1")).toBe(true);
  await expect(page.getByText("流水详情", { exact: true }).last()).toContainText("流水详情");
  await page.getByRole("button", { name: "编辑", exact: true }).last().click({ force: true });
  await typeIntoComposeInput(page, '[id="record.amount"]', "30.00", { replace: true });
  await expect(page.getByText("30.00", { exact: true })).toBeVisible();

  await page.mouse.move(200, 1050);
  await page.mouse.wheel(0, 700);
  await page.waitForTimeout(250);
  await typeIntoComposeInput(page, '[id="record.note"]', "已修改", { replace: true });
  await page.waitForTimeout(300);
  await clickComposeUntil(page, page.locator('[id="record.save"]:visible').last(), async () => api.state.recordWrites.length === 2);

  await expect.poll(() => api.state.recordWrites.length).toBe(2);
  expect(api.state.recordWrites[1]).toMatchObject({ amount: "30.00", note: "已修改" });
  expect(api.calls.some((call) => call.method === "PUT" && call.path === "/api/v1/cash-records/record-1")).toBe(true);
});

test("F-04 编辑流水在详情面板内切换，不叠加第二个模态层", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 768, height: 1000 });
  await page.goto(`${workspaceRoot}/`);

  const openDetail = page.locator('[id="ledger.open-record"]').last();
  const panel = page.locator('[id="ledger.record-panel"]');
  await openDetail.scrollIntoViewIfNeeded();
  await clickComposeUntil(page, openDetail, async () => page.getByText("流水详情", { exact: true }).last().isVisible());
  await expect(panel).toHaveCount(1);
  await expect(panel).toBeVisible();
  await expect(page.getByRole("button", { name: "解散关联", exact: true })).toHaveCount(0);

  await page.getByRole("button", { name: "编辑", exact: true }).last().click({ force: true });
  await expect(page.locator('[id="record.amount"]').last()).toBeVisible();
  await expect(panel).toHaveCount(1);

  await page.keyboard.press("Escape");
  await expect(page.locator('[id="record.amount"]')).toHaveCount(0);
  await expect(panel).toHaveCount(1);
  await expect(page.getByText("流水详情", { exact: true }).last()).toBeVisible();
  await page.waitForTimeout(250);
  const detailScreenshot = await page.screenshot();
  await page.keyboard.press("Escape");
  await page.waitForTimeout(250);
  const listScreenshot = await page.screenshot();
  expect(await screenshotDifferenceRatio(page, detailScreenshot, listScreenshot)).toBeGreaterThan(0.05);
});

test("F-04 新增、详情和编辑面板适配三类窗口", async ({ page }) => {
  await installComposeApiFixtures(page);
  const panel = page.locator('[id="ledger.record-panel"]');
  const expectPanelBounds = async (width: number, height: number) => {
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const bounds = await panel.boundingBox();
    expect(bounds).not.toBeNull();
    if (width < 600) {
      expect(bounds!.x).toBeCloseTo(0, 0);
      expect(bounds!.y).toBeCloseTo(0, 0);
      expect(bounds!.width).toBeCloseTo(width, 0);
      expect(bounds!.height).toBeCloseTo(height, 0);
    } else {
      expect(bounds!.width).toBeLessThanOrEqual(560);
      expect(bounds!.x).toBeGreaterThan(width * 0.25);
      expect(bounds!.x + bounds!.width).toBeCloseTo(width - 16, 0);
      expect(bounds!.y).toBeCloseTo(16, 0);
      expect(bounds!.y + bounds!.height).toBeCloseTo(height - 16, 0);
    }
  };

  for (const width of [390, 768, 1440]) {
    const height = width === 390 ? 844 : width === 768 ? 1024 : 1000;
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await openLedgerActions(page);
    await clickComposeUntil(page, page.locator('[id="ledger.add"]:visible').last(), async () => panel.isVisible());
    await expect(panel).toBeVisible();
    await expect(page.getByText("记一笔", { exact: true })).toBeVisible();
    await expectPanelBounds(width, height);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
      await mkdir(screenshotDir, { recursive: true });
      await page.screenshot({ path: path.join(screenshotDir, `cash-record-create-${width}.png`) });
    }
    await captureLedgerScene(page, `record-create-${width === 390 ? "compact" : width === 768 ? "regular" : "wide"}`, width, height);
    await page.goto(`${workspaceRoot}/`);
    const openDetail = width < 600
      ? page.locator('[id="ledger.record-projection-1"]').last()
      : page.locator('[id="ledger.open-record"]:visible').last();
    await openDetail.scrollIntoViewIfNeeded();
    await clickComposeUntil(page, openDetail, async () => page.getByText("流水详情", { exact: true }).last().isVisible());
    await expect(panel).toBeVisible();
    await expectPanelBounds(width, height);
    await expect(page.locator('[id="record.related.toggle"]')).toHaveCount(0);
    await expect(page.getByText("来源：-", { exact: true })).toHaveCount(0);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
      await page.screenshot({ path: path.join(screenshotDir, `cash-record-detail-${width}.png`) });
    }
    await captureLedgerScene(page, `record-detail-${width === 390 ? "compact" : width === 768 ? "regular" : "wide"}`, width, height);

    await page.getByRole("button", { name: "编辑", exact: true }).last().click({ force: true });
    await expect(page.locator('[id="record.amount"]').last()).toBeVisible();
    await expect(panel).toHaveCount(1);
    await expectPanelBounds(width, height);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
      await page.screenshot({ path: path.join(screenshotDir, `cash-record-edit-${width}.png`) });
    }
    await captureLedgerScene(page, `record-edit-${width === 390 ? "compact" : width === 768 ? "regular" : "wide"}`, width, height);
    await page.keyboard.press("Escape");
    await expect(page.locator('[id="record.amount"]')).toHaveCount(0);
    await expect(page.getByText("流水详情", { exact: true }).last()).toBeVisible();
    await page.waitForTimeout(250);
    const detailScreenshot = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(250);
    const listScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, detailScreenshot, listScreenshot)).toBeGreaterThan(0.05);
  }
});

test("F-04 流水删除确认在三类窗口保留危险操作样式", async ({ page }) => {
  test.setTimeout(90_000);
  await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const openDetail = width < 600
      ? page.locator('[id="ledger.record-projection-1"]').last()
      : page.locator('[id="ledger.open-record"]:visible').last();
    await clickComposeUntil(page, openDetail, async () => page.locator('[id="ledger.record-panel"]').isVisible());
    await expect(page.locator('[id="ledger.record-panel"]')).toBeVisible();
    await page.getByRole("button", { name: "删除", exact: true }).last().click({ force: true });
    await expect(page.getByText("删除流水", { exact: true }).last()).toBeVisible();
    await expect(page.getByRole("button", { name: "取消", exact: true }).last()).toBeVisible();
    await expect(page.getByRole("button", { name: "确认删除", exact: true }).last()).toBeVisible();
    await captureLedgerScene(page, `record-delete-confirm-${sizeName}`, width, height);
  }
});

test("F-04 已关联流水的解散关联与删除选项使用危险色", async ({ page }) => {
  test.setTimeout(150_000);
  await installComposeApiFixtures(page, { withAcceptedCashRelation: true });
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const openDetail = width < 600
      ? page.locator('[id="ledger.record-projection-1"]').last()
      : page.locator('[id="ledger.open-record"]:visible').last();
    await clickComposeUntil(page, openDetail, async () => page.locator('[id="ledger.record-panel"]').isVisible());
    await expect(page.getByRole("button", { name: "解散关联", exact: true }).last()).toBeVisible();
    const relatedToggle = page.locator('[id="record.related.toggle"]').last();
    await expect(relatedToggle).toBeVisible();
    await expect(page.locator('[id="record.related.content"]')).toHaveCount(0);
    await captureLedgerScene(page, `related-detail-${sizeName}`, width, height);
    await relatedToggle.click({ force: true });
    const relatedContent = page.locator('[id="record.related.content"]').last();
    await expect(relatedContent).toBeVisible();
    await expect(relatedContent).toContainText("当前流水");
    await expect(relatedContent).toContainText("其他关联流水");
    await expect(relatedContent).toContainText("咖啡店");
    await expect(relatedContent).toContainText("便利店");
    await expect(relatedContent).toContainText("超市");
    await expect(page.getByRole("button", { name: "编辑流水", exact: true })).toHaveCount(2);
    await expect(page.getByRole("button", { name: "更改类型", exact: true })).toHaveCount(2);
    await page.getByRole("button", { name: "新增关联", exact: true }).last().click({ force: true });
    await page.mouse.move(width < 600 ? width / 2 : width - 80, height / 2);
    await page.mouse.wheel(0, 1_600);
    await page.waitForTimeout(220);
    const relationComposerTitle = page.getByText("关联另一笔流水", { exact: true });
    await relationComposerTitle.scrollIntoViewIfNeeded();
    await expect(relationComposerTitle).toBeVisible();
    const relationCandidate = page.getByText("待关联流水", { exact: true });
    await relationCandidate.scrollIntoViewIfNeeded();
    await expect(relationCandidate).toBeVisible();
    await captureLedgerScene(page, `relation-composer-${sizeName}`, width, height);
    await page.getByRole("button", { name: "取消", exact: true }).last().click({ force: true });
    await expect(page.getByText("关联另一笔流水", { exact: true })).toHaveCount(0);
    await captureLedgerScene(page, `related-detail-expanded-${sizeName}`, width, height);
    await page.mouse.move(width < 600 ? width / 2 : width - 80, height - 300);
    await page.mouse.wheel(0, 900);
    await page.waitForTimeout(180);
    await captureLedgerScene(page, `related-detail-expanded-scrolled-${sizeName}`, width, height);
    await page.getByRole("button", { name: "删除", exact: true }).last().click({ force: true });
    await expect(page.getByText("删除流水", { exact: true }).last()).toBeVisible();
    await expect(page.getByRole("button", { name: "仅删除此流水", exact: true }).last()).toBeVisible();
    await expect(page.getByRole("button", { name: "删除整笔收支", exact: true }).last()).toBeVisible();
    await captureLedgerScene(page, `related-record-delete-confirm-${sizeName}`, width, height);
    await page.getByRole("button", { name: "取消", exact: true }).last().click({ force: true });
  }

  await page.goto(`${workspaceRoot}/`);
  const relationOpenDetail = page.locator('[id="ledger.open-record"]:visible').last();
  await clickComposeUntil(page, relationOpenDetail, async () => page.locator('[id="ledger.record-panel"]').isVisible());
  await page.locator('[id="record.related.toggle"]').last().click({ force: true });
  await page.mouse.move(1360, 700);
  await page.mouse.wheel(0, 900);
  await page.waitForTimeout(180);
  await clickComposeTarget(page, page.getByRole("button", { name: "更改类型", exact: true }).first());
  await clickComposeTarget(page, page.getByRole("button", { name: /关联类型/ }).last());
  const refundOffsetOption = page.getByText("退款冲销", { exact: true }).last();
  await expect(refundOffsetOption).toBeVisible();
  await clickComposeTarget(page, refundOffsetOption);
  const saveRelationType = page.getByRole("button", { name: "保存", exact: true }).last();
  await page.mouse.move(1360, 700);
  await page.mouse.wheel(0, 800);
  await page.waitForTimeout(180);
  await expect.poll(async () => {
    const bounds = await saveRelationType.boundingBox();
    return !!bounds && bounds.x >= 0 && bounds.y >= 0 && bounds.x + bounds.width <= 1440 && bounds.y + bounds.height <= 1000;
  }).toBe(true);
  await clickComposeTarget(page, saveRelationType);
  await expect(page.getByRole("button", { name: "保存", exact: true })).toHaveCount(0);
  await expect(page.locator('[id="record.related.content"]').last()).toContainText("退款冲销");

  await page.getByRole("button", { name: "编辑流水", exact: true }).first().click({ force: true });
  await expect(page.locator('[id="record.counterparty"]').last()).toBeVisible();
  await expect(page.getByText("便利店", { exact: true }).last()).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByText("流水详情", { exact: true }).last()).toBeVisible();
  await page.locator('[id="record.related.toggle"]').last().click({ force: true });

  await page.mouse.move(1360, 700);
  await page.mouse.wheel(0, 900);
  await page.waitForTimeout(180);
  await clickComposeTarget(page, page.getByRole("button", { name: "取消关联", exact: true }).first());
  const remainingRelatedContent = page.locator('[id="record.related.content"]').last();
  await expect(remainingRelatedContent).toContainText("超市");
  await expect(remainingRelatedContent).not.toContainText("便利店");
});

test("F-05 导入可完成选择、扫描、预览、确认和结果摘要", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/cash-import`);
  await expect(page.locator('[id="import.choose-file"]')).toBeVisible();
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await page.screenshot({ path: "/tmp/finance-compose-cash-import-390.png" });
  }
  await chooseComposeFiles(page, [{
    name: "statement.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
  }]);
  await expect(page.getByText("statement.csv", { exact: true }).first()).toBeVisible();
  await page.locator('[id="import.next"]').dispatchEvent("click");
  await expect(page.getByText("选择目标账户", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').dispatchEvent("click");

  await expect(page.getByText("咖啡店", { exact: true })).toBeVisible();
  await expect(page.getByText("分类不会展示", { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: "继续", exact: true }).click({ force: true });
  await page.mouse.move(220, 740);
  await page.mouse.wheel(0, 1400);
  await clickComposeTarget(page, page.locator('[id="import.confirm"]').last());

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
  await expect(page.locator('[id="import.choose-file"]')).toBeVisible();
  await chooseComposeFiles(page, [
    { name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
    { name: "wallet.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-17.25\n") },
  ]);
  await expect(page.getByText("bank.csv", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("wallet.csv", { exact: true }).first()).toBeVisible();
  await page.locator('[id="import.next"]').last().click({ force: true });
  await expect(page.getByText("跨渠道账单 · 2 个来源账户", { exact: true })).toBeVisible();
  await page.locator('[id="import.next"]').last().click({ force: true });
  await expect(page.getByText("银行咖啡店", { exact: true })).toBeVisible();
  await expect(page.getByText("钱包咖啡店", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "继续", exact: true }).last());
  await expect(page.getByText("关系配对", { exact: true })).toBeVisible();
  await expect(page.getByText(/共\s*\d+\s*条流水/)).toHaveCount(0);
  await expect(page.getByText(/将相同流水关联/)).toHaveCount(0);
  await expect(page.getByText(/自动匹配\s*\d+\s*条/)).toHaveCount(0);
  await expect(page.getByText(/待确认\s*\d+\s*条/)).toHaveCount(0);
  await expect(page.getByText(/尚未选择关联流水/)).toHaveCount(0);
  await expect(page.getByText(/选择一条已有账单/)).toHaveCount(0);
  await expect(page.getByRole("button", { name: /选择流水/ })).toHaveCount(1);

  const relationFilter = page.getByRole("button", { name: "全部 2", exact: true });
  await expect(relationFilter).toBeVisible();
  await expect(page.getByRole("button", { name: "已匹配 1", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "待确认 1", exact: true })).toBeVisible();
  await clickComposeTarget(page, relationFilter, 0.1, 0.25);
  return commitAttempts;
}

test("F-05 cash-import matches the approved secondary-page hierarchy", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${workspaceRoot}/`);
  await expect(page.locator('[id="ledger.screen"]').last()).toBeVisible();
  const railToggleBounds = await page.locator('[id="navigation.rail.toggle"]').last().boundingBox();
  expect(railToggleBounds?.width ?? 0).toBeGreaterThanOrEqual(52);
  await page.goto(`${workspaceRoot}/cash-import`);

  const modal = page.locator('[id="secondary-page-modal"]').last();
  const title = page.locator('[id="secondary-page-title"]').last();
  const close = page.locator('[id="secondary-page-close"]').last();
  const header = page.locator('[id="secondary-page-header"]').last();
  const footer = page.locator('[id="secondary-page-footer"]').last();
  await mkdir(screenshotDir, { recursive: true });
  await expect(modal).toBeVisible();
  await expect(page.locator('[id="secondary-page-footer-divider"]')).toHaveCount(0);
  await expect(title).toHaveText("导入账单");
  await expect(page.locator('[id="import.stepper"]').last()).toBeVisible();
  await expect(page.getByRole("button", { name: /文件/ }).last()).toBeVisible();
  await expect(page.locator('[id="import.dropzone"]').last()).toBeVisible();
  await expect(page.getByText("添加账单文件", { exact: true })).toBeVisible();
  await expect(page.getByText("CSV · XLSX · PDF", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "选择文件", exact: true }).last()).toBeVisible();
  await expect(page.getByText("单个文件不超过 100 MB", { exact: true })).toHaveCount(0);
  await expect(page.locator('[id="import.previous"]')).toHaveCount(0);
  await expect(page.getByText("离开后将丢弃未完成导入", { exact: true })).toBeVisible();
  const [titleBounds, closeBounds, formBounds, stepperBounds] = await Promise.all([
    title.boundingBox(),
    close.boundingBox(),
    page.locator('[id="import.stage-content"]').last().boundingBox(),
    page.locator('[id="import.stepper"]').last().boundingBox(),
  ]);
  expect(titleBounds).not.toBeNull();
  expect(closeBounds).not.toBeNull();
  expect(formBounds).not.toBeNull();
  expect(stepperBounds).not.toBeNull();
  expect((titleBounds?.x ?? Number.POSITIVE_INFINITY) + (titleBounds?.width ?? 0)).toBeLessThan(closeBounds?.x ?? 0);
  const modalBounds = await modal.boundingBox();
  expect(modalBounds).not.toBeNull();
  expect(Math.abs((titleBounds?.x ?? 0) - (modalBounds?.x ?? 0) - 32)).toBeLessThanOrEqual(2);
  expect(formBounds?.width ?? Number.POSITIVE_INFINITY).toBeLessThanOrEqual(720);
  expect(stepperBounds?.width ?? 0).toBeGreaterThan((formBounds?.width ?? Number.POSITIVE_INFINITY) * 1.5);
  const footerBounds = await footer.boundingBox();
  expect(footerBounds).not.toBeNull();
  expect(Math.abs((modalBounds?.y ?? 0) + (modalBounds?.height ?? 0) - (footerBounds?.y ?? 0) - (footerBounds?.height ?? 0) - 24)).toBeLessThanOrEqual(2);
  const dropzoneBounds = await page.locator('[id="import.dropzone"]').last().boundingBox();
  expect(dropzoneBounds?.height ?? 0).toBeGreaterThanOrEqual(249);
  expect(dropzoneBounds?.height ?? 0).toBeLessThanOrEqual(256);
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(500);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-file-1440.png") });

  await page.setViewportSize({ width: 768, height: 1024 });
  await expect(modal).toBeVisible();
  await expect.poll(async () => (await modal.boundingBox())?.width ?? 0).toBeCloseTo(691, 0);
  const [regularModalBounds, regularTitleBounds, regularStageTitleBounds] = await Promise.all([
    modal.boundingBox(),
    title.boundingBox(),
    page.getByText("选择账单文件", { exact: true }).boundingBox(),
  ]);
  const regularHeaderBounds = await header.boundingBox();
  expect(regularModalBounds).not.toBeNull();
  expect(regularTitleBounds).not.toBeNull();
  expect(regularStageTitleBounds).not.toBeNull();
  expect(regularHeaderBounds?.height).toBeCloseTo(78, 0);
  await expect(page.locator('[id="secondary-page-footer-divider"]')).toHaveCount(0);
  expect(Math.abs((regularTitleBounds?.x ?? 0) - (regularModalBounds?.x ?? 0) - 25)).toBeLessThanOrEqual(2);
  expect(Math.abs((regularStageTitleBounds?.x ?? 0) - (regularModalBounds?.x ?? 0) - 25)).toBeLessThanOrEqual(2);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-file-768.png") });

  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await page.evaluate(() => document.fonts.ready);
  await page.waitForTimeout(500);
  const back = page.locator('[id="secondary-page-back"]').last();
  const compactFooter = page.locator('[id="secondary-page-footer"]').last();
  const compactNote = page.getByText("离开后将丢弃未完成导入", { exact: true }).last();
  await expect(back).toBeVisible();
  await expect(title).toHaveText("导入账单");
  await expect(page.locator('[id="secondary-page-fullscreen"]').last()).toBeVisible();
  await expect(page.locator('[id="secondary-page-footer-divider"]')).toHaveCount(1);
  const compactFormBounds = await page.locator('[id="import.stage-content"]').last().boundingBox();
  expect(compactFormBounds).not.toBeNull();
  expect(compactFormBounds?.x).toBeCloseTo(16, 0);
  expect(compactFormBounds?.width ?? 0).toBeGreaterThanOrEqual(350);
  const [compactFooterBounds, compactNoteBounds, compactNextBounds] = await Promise.all([
    compactFooter.boundingBox(),
    compactNote.boundingBox(),
    page.locator('[id="import.next"]').last().boundingBox(),
  ]);
  expect(compactFooterBounds).not.toBeNull();
  expect(compactNoteBounds).not.toBeNull();
  expect(compactNextBounds).not.toBeNull();
  expect(Math.abs(844 - ((compactFooterBounds?.y ?? 0) + (compactFooterBounds?.height ?? 0)) - 16)).toBeLessThanOrEqual(2);
  expect((compactNextBounds?.y ?? 0)).toBeGreaterThan((compactNoteBounds?.y ?? 0) + (compactNoteBounds?.height ?? 0));
  await page.evaluate(() => window.scrollTo(0, 0));
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-file-390.png") });
  for (const width of [320, 375, 414]) {
    await page.setViewportSize({ width, height: 844 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
});

test("F-05 跨渠道多文件会聚合关系候选，提交重试复用幂等键", async ({ page }) => {
  test.setTimeout(90_000);
  await page.setViewportSize({ width: 390, height: 2000 });
  const commitAttempts = await openBatchRelationFilter(page);
  await expect(page.getByRole("button", { name: "全部 2", exact: true })).toBeVisible();
  const automaticFilter = page.getByRole("button", { name: "已匹配 1", exact: true });
  await clickComposeTarget(page, automaticFilter, 0.5, 0.5);
  await expect(page.getByText("已关联", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("待确认", { exact: true })).toHaveCount(0);
  const pendingFilter = page.getByRole("button", { name: "待确认 1", exact: true });
  await clickComposeTarget(page, pendingFilter, 0.1, 0.25);
  await expect(page.getByText("待确认", { exact: true })).toBeVisible();
  const relationActions = page.locator('[id="import.relation-actions.pending-relation"]').last();
  await clickComposeTarget(page, relationActions);
  await expect(page.getByRole("button", { name: "配对类型" }).last()).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "拒绝配对", exact: true }).last());
  await clickComposeTarget(page, page.getByRole("button", { name: "全部 2", exact: true }));
  await expect(page.getByText("已拒绝", { exact: true }).last()).toBeVisible();
  await clickComposeTarget(page, page.locator('[id="import.relation-actions.pending-relation"]').last());
  await clickComposeTarget(page, page.getByRole("button", { name: "撤销拒绝", exact: true }).last());
  await expect(page.getByText("待确认", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: /^选择流水/ }).last(), 0.1, 0.25);
  await expect(page.getByText(/钱包咖啡店 · 28\.50 CNY/).last()).toBeVisible();
  const walletCandidateOption = page.locator('[id="choice-option-wallet-row"]');
  await expect(walletCandidateOption).toBeVisible();
  await walletCandidateOption.click({ force: true });
  const emptyPendingFilter = page.getByRole("button", { name: "待确认 0", exact: true });
  await expect(emptyPendingFilter).toBeVisible();
  await clickComposeTarget(page, emptyPendingFilter, 0.1, 0.25);
  await clickComposeTarget(page, page.getByRole("button", { name: "全部 2", exact: true }), 0.5, 0.5);
  await expect(page.getByText("已关联", { exact: true }).last()).toBeVisible();
  await expect(page.getByText(/钱包咖啡店 · 28\.50 CNY/).last()).toBeVisible();

  await clickComposeTarget(page, page.locator('[id="import.confirm"]').last());
  await expect(page.getByText("暂时无法完成导入，请稍后重试。", { exact: true })).toBeVisible();
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
  test.setTimeout(90_000);
  const assertDesktopFooterAlignment = async () => {
    const [modalBounds, previousBounds, confirmBounds] = await Promise.all([
      page.locator('[id="secondary-page-modal"]').last().boundingBox(),
      page.locator('[id="import.previous"]').last().boundingBox(),
      page.locator('[id="import.confirm"]').last().boundingBox(),
    ]);
    expect(modalBounds).not.toBeNull();
    expect(previousBounds).not.toBeNull();
    expect(confirmBounds).not.toBeNull();
    expect(confirmBounds!.x + confirmBounds!.width).toBeGreaterThan(modalBounds!.x + modalBounds!.width * 0.6);
    expect(confirmBounds!.x).toBeGreaterThan(previousBounds!.x + previousBounds!.width);
    expect(confirmBounds!.height).toBeLessThanOrEqual(48);
    const previousBorder = await page.locator('[id="import.previous"]').last().evaluate((element) => getComputedStyle(element).borderTopColor);
    expect(previousBorder).not.toBe("rgba(0, 0, 0, 0)");
  };
  const assertCompactFooterAlignment = async () => {
    const [footerBounds, previousBounds, confirmBounds] = await Promise.all([
      page.locator('[id="secondary-page-footer"]').last().boundingBox(),
      page.locator('[id="import.previous"]').last().boundingBox(),
      page.locator('[id="import.confirm"]').last().boundingBox(),
    ]);
    expect(footerBounds).not.toBeNull();
    expect(previousBounds).not.toBeNull();
    expect(confirmBounds).not.toBeNull();
    // Material 3 button semantics expose the full clickable bounds, aligned to the footer edges.
    expect(Math.abs(previousBounds!.x - footerBounds!.x)).toBeLessThanOrEqual(2);
    expect(Math.abs(confirmBounds!.x + confirmBounds!.width - footerBounds!.x - footerBounds!.width)).toBeLessThanOrEqual(2);
    const previousBorder = await page.locator('[id="import.previous"]').last().evaluate((element) => getComputedStyle(element).borderTopColor);
    expect(previousBorder).not.toBe("rgba(0, 0, 0, 0)");
  };
  const assertOverlayDoesNotCoverTrigger = async (trigger: Locator) => {
    const triggerBounds = await trigger.boundingBox();
    const menuBounds = await page.locator('[id="choice-overlay-menu"]').last().boundingBox();
    expect(triggerBounds).not.toBeNull();
    expect(menuBounds).not.toBeNull();
    const opensBelow = menuBounds!.y >= triggerBounds!.y + triggerBounds!.height - 2;
    const opensAbove = menuBounds!.y + menuBounds!.height <= triggerBounds!.y + 2;
    expect(opensBelow || opensAbove, JSON.stringify({ triggerBounds, menuBounds })).toBe(true);
  };
  await page.setViewportSize({ width: 390, height: 844 });
  await openBatchRelationFilter(page);

  const relationFilter = page.getByRole("button", { name: "全部 2", exact: true });
  await clickComposeTarget(page, relationFilter, 0.1, 0.25);
  await assertCompactFooterAlignment();
  await mkdir(screenshotDir, { recursive: true });
  const pendingRelation = page.locator('[id="import.relation-row.pending-relation"]').last();
  await expect(page.getByText("09 月 24 日 · 10:30", { exact: true }).first()).toBeVisible();
  await expect(pendingRelation.getByText("日常账户", { exact: true })).toHaveCount(0);
  await expect(pendingRelation.getByText("⋯", { exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^选择流水/ }).last()).toBeVisible();
  await expect(page.getByText("对侧流水", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-390.png") });

  const recordPicker = pendingRelation.getByRole("button", { name: /^选择流水/ }).last();
  const compactCardBounds = await pendingRelation.boundingBox();
  await clickComposeTarget(page, recordPicker, 0.1, 0.25);
  await expect(page.locator('[id="choice-option-wallet-row"]')).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(recordPicker);
  const compactCardBoundsWhileOpen = await pendingRelation.boundingBox();
  expect(compactCardBoundsWhileOpen?.height).toBeCloseTo(compactCardBounds?.height ?? -1, 0);
  expect(compactCardBoundsWhileOpen?.width).toBeCloseTo(compactCardBounds?.width ?? -1, 0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-390.png") });
  await page.keyboard.press("Escape");
  await expect(page.locator('[id="choice-option-wallet-row"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: "待确认 1", exact: true })).toBeVisible();
  await clickComposeTarget(page, recordPicker, 0.1, 0.25);
  await expect(page.locator('[id="choice-option-wallet-row"]')).toBeVisible();
  const relationTitleBounds = await page.getByText("关系配对", { exact: true }).boundingBox();
  if (!relationTitleBounds) throw new Error("关系标题没有可见位置");
  await page.mouse.click(relationTitleBounds.x + relationTitleBounds.width / 2, relationTitleBounds.y + relationTitleBounds.height / 2);
  await expect(page.locator('[id="choice-option-wallet-row"]')).toHaveCount(0);
  await expect(page.getByRole("button", { name: "待确认 1", exact: true })).toBeVisible();
  await recordPicker.focus();
  await page.keyboard.press("Enter");
  const compactOption = page.locator('[id="choice-option-wallet-row"]');
  await expect(compactOption).toBeVisible();
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.getByRole("button", { name: /钱包咖啡店 · 28\.50 CNY/ })).toBeVisible();
  const compactCardBoundsAfterSelection = await pendingRelation.boundingBox();
  expect(compactCardBounds).not.toBeNull();
  expect(compactCardBoundsAfterSelection).not.toBeNull();

  await page.setViewportSize({ width: 768, height: 1024 });
  await page.reload();
  await expect(page.locator('[id="import.choose-file"]').last()).toBeVisible();
  await chooseComposeFiles(page, [
    { name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
    { name: "wallet.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-17.25\n") },
  ]);
  await page.locator('[id="import.next"]').last().dispatchEvent("click");
  await expect(page.getByText("跨渠道账单 · 2 个来源账户", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.locator('[id="import.next"]').last());
  await expect(page.getByText("银行咖啡店", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "继续", exact: true }).last());
  await expect(page.getByText("关系配对", { exact: true })).toBeVisible();
  const regularRelation = page.locator('[id="import.relation-row.pending-relation"]').last();
  await assertDesktopFooterAlignment();
  const regularCardBounds = await regularRelation.boundingBox();
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-768.png") });
  const regularPicker = regularRelation.getByRole("button", { name: /^选择流水/ }).last();
  await clickComposeTarget(page, regularPicker, 0.1, 0.25);
  await expect(page.locator('[id="choice-option-wallet-row"]')).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(regularPicker);
  const regularCardBoundsWhileOpen = await regularRelation.boundingBox();
  expect(regularCardBoundsWhileOpen?.height).toBeCloseTo(regularCardBounds?.height ?? -1, 0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-768.png") });
  await clickComposeTarget(page, page.locator('[id="choice-option-wallet-row"]'));
  await expect(page.getByRole("button", { name: /钱包咖啡店 · 28\.50 CNY/ }).last()).toBeVisible();

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.reload();
  await expect(page.locator('[id="import.choose-file"]').last()).toBeVisible();
  await chooseComposeFiles(page, [
    { name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
    { name: "wallet.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-17.25\n") },
  ]);
  await page.locator('[id="import.next"]').last().dispatchEvent("click");
  await expect(page.getByText("跨渠道账单 · 2 个来源账户", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.locator('[id="import.next"]').last());
  await expect(page.getByText("银行咖啡店", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "继续", exact: true }).last());
  await expect(page.getByText("关系配对", { exact: true })).toBeVisible();
  await assertDesktopFooterAlignment();
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await expect(page.getByRole("button", { name: /^选择流水/ }).last()).toBeVisible();
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-1440.png") });
  const wideRelation = page.locator('[id="import.relation-row.pending-relation"]').last();
  const wideCardBounds = await wideRelation.boundingBox();
  await clickComposeTarget(page, page.getByRole("button", { name: /^选择流水/ }).last(), 0.1, 0.25);
  await expect(page.locator('[id="choice-option-wallet-row"]')).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(wideRelation.getByRole("button", { name: /^选择流水/ }).last());
  const wideCardBoundsWhileOpen = await wideRelation.boundingBox();
  expect(wideCardBoundsWhileOpen?.height).toBeCloseTo(wideCardBounds?.height ?? -1, 0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-1440.png") });
  await clickComposeTarget(page, page.locator('[id="choice-option-wallet-row"]'));
  await expect(page.getByRole("button", { name: /钱包咖啡店 · 28\.50 CNY/ }).last()).toBeVisible();
});

test("未登录邀请与认证页共用入口卡片并继续登录", async ({ page }) => {
  const api = await installComposeApiFixtures(page, { seedToken: false, initialSession: null });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/workspace-management?invite=invite-e2e-token`);
  await expect(page.getByText("加入「共享账本」", { exact: true })).toBeVisible();

  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.waitForTimeout(100);
    const card = page.locator('[id="auth.card"]').last();
    await expect(page.locator('[id="auth.card"]')).toHaveCount(1);
    await expect.poll(async () => (await card.boundingBox())?.width ?? 0).toBeCloseTo(Math.min(width - 32, 440), 0);
    await expect.poll(async () => {
      const bounds = await card.boundingBox();
      return bounds ? Math.abs(bounds.x - (width - bounds.width) / 2) : Number.POSITIVE_INFINITY;
    }).toBeLessThanOrEqual(2);
    const bounds = await card.boundingBox();
    expect(bounds).not.toBeNull();
    expect(Math.abs((bounds?.x ?? 0) - (width - (bounds?.width ?? 0)) / 2)).toBeLessThanOrEqual(2);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }

  await page.getByRole("button", { name: "登录后接受邀请", exact: true }).click({ force: true });
  await expect(page.locator('[id="auth.title"]').last()).toHaveText("登录");
  expect(api.calls.some((call) => call.method === "GET" && call.path.endsWith("/invite-e2e-token"))).toBe(true);
});

test("F-06 可预览并接受邀请，随后返回原工作区页面", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/workspace-management?invite=invite-e2e-token`);
  await expect(page.getByText("加入「共享账本」", { exact: true })).toBeVisible();
  await expect(page.locator('[id="destination.top-bar-title"]').last()).toHaveText("接受邀请");
  await expect(page.locator('[id="destination.body-title"]')).toHaveCount(0);
  await page.getByRole("button", { name: "接受邀请", exact: true }).click({ force: true });

  await expect(page.locator('[id="workspace-management.screen"]')).toBeVisible();
  await expect(page).toHaveURL(`${workspaceRoot}/workspace-management`);
  expect(api.calls.some((call) => call.method === "POST" && call.path.endsWith("/invite-e2e-token/accept"))).toBe(true);
});

test("F-07 分类目录按上级路径搜索并可新增分类", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  await page.goto(`${workspaceRoot}/cash-categories`);
  await expect(page.getByText("咖啡", { exact: true })).toBeVisible();

  await typeIntoComposeInput(page, '[id="cash-category.search"]', "餐饮", { replace: true });
  await expect(page.getByText("咖啡", { exact: true })).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash-category.search"]', "咖啡", { replace: true });
  await expect(page.getByText("没有匹配的分类。", { exact: true })).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash-category.search"]', "", { replace: true });
  await typeIntoComposeInput(page, '[id="cash-category.search"]', " ", { replace: true });

  await page.keyboard.press("Tab");
  await clickComposeUntil(page, page.locator('[id="cash-category-create"]').last(), async () => page.locator('[id="cash-category.name"]').last().isVisible());
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
  await clickComposeUntil(page, page.locator('[id="cash-category.save"]').last(), async () => api.state.categoryItems.some((item) => item.name === "外卖"));
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
    const themeSuffix = process.env.FT_COMPOSE_COLOR_SCHEME === "dark" ? "-dark" : "";
    await mkdir(screenshotDir, { recursive: true });
    await page.setViewportSize({ width: 390, height: 1800 });
    await page.screenshot({ path: path.join(screenshotDir, `web-holdings-390${themeSuffix}.png`) });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(screenshotDir, `web-holdings-1440${themeSuffix}.png`) });
  }
});

test("F-09 投资事件可加载下一页并查看证据", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/investment-events`);
  await expect(page.getByText("买入苹果", { exact: true })).toBeVisible();
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForTimeout(2500);
    await page.screenshot({ path: "/tmp/finance-compose-investment-events-390.png" });
  }
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
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForTimeout(2500);
    await page.screenshot({ path: "/tmp/finance-compose-investment-events-1440.png" });
  }
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
  await clickComposeTarget(page, page.locator('[id="investment-events.load-more"]').last());
  await expect.poll(() => api.calls.some((call) => call.path.includes("cursor=event-page-2"))).toBe(true);
});

test("F-10 工作区管理员可修改名称并创建邀请链接", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/workspace-management`);
  await expect(page.getByText("成员", { exact: true }).last()).toContainText("成员");
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
    await page.screenshot({ path: "/tmp/finance-compose-workspace-management-390.png" });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.waitForTimeout(300);
    await page.screenshot({ path: "/tmp/finance-compose-workspace-management-1440.png" });
    await page.setViewportSize({ width: 390, height: 2000 });
    await page.waitForTimeout(500);
    await page.reload();
    await expect(page.getByText("成员", { exact: true }).last()).toBeVisible();
  }
  await typeIntoComposeInput(page, '[id="workspace-management.name"]', "新的家庭账本", { replace: true });
  await page.keyboard.press("Tab");
  const saveWorkspace = page.getByRole("button", { name: "保存", exact: true }).last();
  await expect(saveWorkspace).toBeEnabled();
  await clickComposeUntil(page, saveWorkspace, async () => api.calls.some((call) => call.method === "PUT" && call.path === "/api/v1/auth/workspace"));
  await expect.poll(() => api.calls.some((call) => call.method === "PUT" && call.path === "/api/v1/auth/workspace")).toBe(true);
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
  test.setTimeout(60_000);
  const viewerSession = {
    ...activeSession,
    workspaces: [{ ...activeSession.workspaces[0], role: "viewer" as const }],
  };
  const api = await installComposeApiFixtures(page, { initialSession: viewerSession });
  await page.setViewportSize({ width: 390, height: 1800 });
  await page.goto(`${workspaceRoot}/`);
  const fab = page.locator('[id="ledger.actions-fab"]').last();
  await expect(fab).toBeVisible();
  await fab.click({ force: true });
  await expect(page.locator('[id="ledger.add"]').last()).toBeHidden();
  await expect(page.locator('[id="record.screen"]')).toHaveCount(0);
  await expect.poll(() => api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-records")).toBe(false);

  await page.goto(`${workspaceRoot}/cash-import`);
  await expect(page.getByText("当前工作区仅可查看。", { exact: true })).toBeVisible();
  await expect(page.locator('[id="import.choose-file"]')).toHaveCount(0);

  await page.goto(`${workspaceRoot}/cash-categories`);
  await expect(page.locator('[id="cash-categories.screen"]').last()).toBeVisible();
  await page.locator('[id="cash-category-create"]').last().dispatchEvent("click");
  await expect(page.locator('[id="cash-category.name"]')).toHaveCount(0);
  expect(api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-categories")).toBe(false);
});

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
const accountMappingScreenshotDir = path.resolve(process.cwd(), "../openspec/changes/compose-cash-import-account-mapping-redesign/screenshots");
const accountMappingRunScreenshotDir = process.env.FT_COMPOSE_COLOR_SCHEME === "dark"
  ? path.join(accountMappingScreenshotDir, "dark")
  : accountMappingScreenshotDir;
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

async function clickImportPrimaryFooter(page: Page, width: number, height: number, fullWidth = false): Promise<void> {
  const compact = width < 600;
  const x = fullWidth ? width / 2 : compact ? width - 82 : width - 128;
  const y = height - (compact ? 40 : 100);
  await page.mouse.click(x, y);
  await page.waitForTimeout(220);
}

async function clickBatchCategoryAction(
  page: Page,
  targetBounds: { x: number; y: number; width: number; height: number },
): Promise<void> {
  await page.waitForTimeout(250);
  const x = targetBounds.x + targetBounds.width / 2;
  const y = targetBounds.y + targetBounds.height / 2;
  await page.mouse.click(x, y);
  await page.waitForTimeout(120);
}

async function openChoiceMenu(page: Page, picker: Locator): Promise<Locator> {
  const menu = page.locator('[id="choice-overlay-menu"]').last();
  await clickComposeUntil(page, picker, async () => menu.isVisible());
  await expect(menu).toBeVisible();
  return menu;
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
    ? path.join(accountMappingScreenshotDir, "dark")
    : accountMappingScreenshotDir;
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

async function screenshotDifferenceRatioOutsideRect(
  page: Page,
  before: Buffer,
  after: Buffer,
  excluded: { x: number; y: number; width: number; height: number },
): Promise<number> {
  return page.evaluate(async ({ beforeBase64, afterBase64, excluded }) => {
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
    if (!context) throw new Error("Unable to compare Compose screenshot regions");
    context.drawImage(beforeImage, 0, 0);
    const beforePixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(afterImage, 0, 0);
    const afterPixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const left = Math.max(0, Math.floor(excluded.x));
    const top = Math.max(0, Math.floor(excluded.y));
    const right = Math.min(canvas.width, Math.ceil(excluded.x + excluded.width));
    const bottom = Math.min(canvas.height, Math.ceil(excluded.y + excluded.height));
    let changed = 0;
    let compared = 0;
    for (let y = 0; y < canvas.height; y += 1) {
      for (let x = 0; x < canvas.width; x += 1) {
        if (x >= left && x < right && y >= top && y < bottom) continue;
        const index = (y * canvas.width + x) * 4;
        compared += 1;
        if (
          Math.abs(beforePixels[index] - afterPixels[index]) > 12 ||
          Math.abs(beforePixels[index + 1] - afterPixels[index + 1]) > 12 ||
          Math.abs(beforePixels[index + 2] - afterPixels[index + 2]) > 12
        ) changed += 1;
      }
    }
    return changed / Math.max(1, compared);
  }, { beforeBase64: before.toString("base64"), afterBase64: after.toString("base64"), excluded });
}

async function screenshotPixel(page: Page, screenshot: Buffer, x: number, y: number): Promise<[number, number, number, number]> {
  return page.evaluate(async ({ base64, pixelX, pixelY }) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Unable to read Compose screenshot pixels");
    context.drawImage(image, 0, 0);
    return Array.from(context.getImageData(pixelX, pixelY, 1, 1).data) as [number, number, number, number];
  }, { base64: screenshot.toString("base64"), pixelX: Math.floor(x), pixelY: Math.floor(y) });
}

async function screenshotMaxBrightness(page: Page, screenshot: Buffer, bounds: { x: number; y: number; width: number; height: number }): Promise<number> {
  return page.evaluate(async ({ base64, rect }) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Unable to read Compose screenshot pixels");
    context.drawImage(image, 0, 0);
    const x = Math.max(0, Math.floor(rect.x));
    const y = Math.max(0, Math.floor(rect.y));
    const width = Math.min(canvas.width - x, Math.ceil(rect.width));
    const height = Math.min(canvas.height - y, Math.ceil(rect.height));
    const pixels = context.getImageData(x, y, width, height).data;
    let maxBrightness = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      maxBrightness = Math.max(maxBrightness, (pixels[index] + pixels[index + 1] + pixels[index + 2]) / 3);
    }
    return maxBrightness;
  }, { base64: screenshot.toString("base64"), rect: bounds });
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
  await clickComposeUntil(page, moreFilters, async () => page.locator('[id="ledger.filter.account"]').last().isVisible());
  await expect(page.getByRole("textbox", { name: "最低金额" })).toBeVisible();
  const accountFilter = page.locator('[id="ledger.filter.account"]').last();
  await clickComposeTarget(page, accountFilter);
  const accountMenu = page.locator('[id="choice-overlay-menu"]').last();
  await expect(accountMenu).toContainText("日常账户");
  const dailyAccountOption = accountMenu.locator('[id="choice-option-1"]').last();
  await clickComposeTarget(page, dailyAccountOption);
  await expect.poll(() => api.calls.some((call) => call.path.includes("account_id=1"))).toBe(true);
  const filteredLedgerPath = api.calls.find((call) => call.path.includes("account_id=1"))?.path.split("?")[0];
  expect(filteredLedgerPath).toBeTruthy();
  await page.reload();
  await expect(page.locator('[id="ledger.filters"]').last()).toBeVisible();
  await expect(page.getByRole("textbox", { name: "最低金额" })).toHaveCount(0);
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width >= 768) {
      await expect(page.locator('[id="ledger.table-header"]')).toContainText("发生时间");
      await expect(page.locator('[id="ledger.record-projection-1"]')).toContainText("-28.50 CNY");
    } else {
      await expect(page.locator('[id="ledger.table-header"]')).toHaveCount(0);
      await expect(page.locator('[id="ledger.record-projection-1"]')).toBeVisible();
    }
  }
  await page.goto(`${workspaceRoot}/`);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 900, "wide"]] as const) {
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

test("F-03 ChoicePicker 锚定菜单覆盖账本、流水、分类、导入、工作区和投资页面", async ({ page }) => {
  test.setTimeout(180_000);
  const disposedRootErrors: string[] = [];
  page.on("pageerror", (error) => {
    if (error.message.includes("RootNodeOwner is already disposed")) disposedRootErrors.push(error.message);
  });
  await installComposeApiFixtures(page);
  await mkdir(accountMappingScreenshotDir, { recursive: true });
  const sizes = [
    { name: "compact", width: 390, height: 844 },
    { name: "regular", width: 768, height: 1024 },
    { name: "wide", width: 1440, height: 900 },
  ] as const;

  for (const size of sizes) {
    await page.setViewportSize({ width: size.width, height: size.height });

    await page.goto(`${workspaceRoot}/`);
    await clickComposeUntil(page, page.locator('[id="ledger.more-filters"]').last(), async () =>
      page.locator('[id="ledger.filter.account"]').last().isVisible(),
    );
    const ledgerAccount = page.locator('[id="ledger.filter.account"]').last();
    const ledgerMenu = await openChoiceMenu(page, ledgerAccount);
    await expect(ledgerMenu).toContainText("日常账户");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-ledger-${size.name}.png`) });
    await clickComposeTarget(page, ledgerMenu.locator('[id="choice-option-1"]').last());

    await page.goto(`${workspaceRoot}/`);
    await openLedgerActions(page);
    await clickComposeUntil(page, page.locator('[id="ledger.add"]:visible').last(), async () =>
      page.locator('[id="record.type"]').last().isVisible(),
    );
    const recordType = page.locator('[id="record.type"]').last();
    const recordMenu = await openChoiceMenu(page, recordType);
    await expect(recordMenu).toContainText("收入");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-record-${size.name}.png`) });
    await page.keyboard.press("Escape");

    await page.goto(`${workspaceRoot}/cash-categories`);
    await clickComposeUntil(page, page.locator('[id="cash-category-create"]').last(), async () =>
      page.locator('[id="cash-category.parent"]').last().isVisible(),
    );
    const categoryParent = page.locator('[id="cash-category.parent"]').last();
    const categoryMenu = await openChoiceMenu(page, categoryParent);
    await expect(categoryMenu).toContainText("餐饮");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-category-${size.name}.png`) });
    await clickComposeTarget(page, categoryMenu.locator('[id="choice-option-food"]').last());

    await page.goto(`${workspaceRoot}/cash-import`);
    await chooseComposeFiles(page, [{
      name: `choice-picker-${size.name}.csv`,
      mimeType: "text/csv",
      buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
    }]);
    await page.locator('[id="import.next"]').last().dispatchEvent("click");
    await expect(page.locator('[id="import.mapping.bank-account"]').last()).toBeVisible();
    const importAccount = page.locator('[id="import.mapping.bank-account"]').last();
    const importMenu = await openChoiceMenu(page, importAccount);
    await expect(importMenu).toContainText("日常账户");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-import-${size.name}.png`) });
    await clickComposeTarget(page, page.locator('[id="choice-option-1"]').last());

    await page.goto(`${workspaceRoot}/workspace-management`);
    const workspaceRole = page.locator('[id="workspace-management.member-role"]').last();
    await expect(workspaceRole).toBeVisible();
    const workspaceMenu = await openChoiceMenu(page, workspaceRole);
    await expect(workspaceMenu).toContainText("仅可查看");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-workspace-${size.name}.png`) });
    await clickComposeTarget(page, workspaceMenu.locator('[id="choice-option-editor"]').last());

    await page.goto(`${workspaceRoot}/investment-holdings`);
    const investmentAccount = page.locator('[id="investment-holdings.account"]').last();
    await expect(investmentAccount).toBeVisible();
    const investmentMenu = await openChoiceMenu(page, investmentAccount);
    await expect(investmentMenu).toContainText("证券账户");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-investment-${size.name}.png`) });
    await clickComposeTarget(page, investmentMenu.locator('[id="choice-option-9"]').last());
    await page.waitForTimeout(250);
    await expect(page.getByText("证券账户", { exact: true }).last()).toBeVisible();
    const afterSelection = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(100);
    const afterEscape = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, afterSelection, afterEscape)).toBeLessThan(0.01);
  }
  expect(disposedRootErrors).toEqual([]);
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
    await clickComposeUntil(page, page.locator('[id="ledger.batch-category"]').last(), async () => dialog.isVisible());
    await expect(dialog).toBeVisible();
    const cancelBounds = await page.locator('[id="ledger.category.cancel"]').last().boundingBox();
    expect(cancelBounds).not.toBeNull();
    await captureLedgerScene(page, `batch-category-dialog-${sizeName}`, width, height);
    const categoryPicker = dialog.locator('[id="ledger.category-picker"]').last();
    await clickComposeTarget(page, categoryPicker);
    const categoryMenu = page.locator('[id="choice-overlay-menu"]').last();
    await expect(categoryMenu.locator('[id="choice-option-coffee"]').last()).toBeVisible();
    await captureLedgerScene(page, `batch-category-options-${sizeName}`, width, height);
    await clickComposeTarget(page, categoryMenu.locator('[id="choice-option-coffee"]').last());
    await captureLedgerScene(page, `batch-classify-ready-${sizeName}`, width, height);
    const writesBeforeCancel = api.state.classificationWrites.length;
    await clickBatchCategoryAction(page, cancelBounds!);
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
    const dialog = page.locator('[id="ledger.category-dialog"]:visible').last();
    await clickComposeUntil(page, page.locator('[id="ledger.batch-category"]').last(), async () => dialog.isVisible());
    await expect(dialog).toBeVisible();
    const applyBounds = await page.locator('[id="ledger.category.apply"]').last().boundingBox();
    expect(applyBounds).not.toBeNull();
    const categoryPicker = dialog.locator('[id="ledger.category-picker"]').last();
    await clickComposeTarget(page, categoryPicker);
    const categoryMenu = page.locator('[id="choice-overlay-menu"]').last();
    await expect(categoryMenu).toContainText("餐饮 / 咖啡");
    await clickComposeTarget(page, categoryMenu.locator('[id="choice-option-coffee"]').last());
    await captureLedgerScene(page, `batch-classify-ready-${sizeName}`, width, height);
    const writesBeforeApply = api.state.classificationWrites.length;
    await clickBatchCategoryAction(page, applyBounds!);
    await expect.poll(() => api.state.classificationWrites.length).toBe(writesBeforeApply + 1);
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

test("F-03 收支账本滚动接近末尾时自动追加下一页", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page, { cashPaginatedPageCount: 2 });
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 900, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const firstPageRecord = page.locator('[id="ledger.record-cash-scroll-1-1"]').last();
    await expect(firstPageRecord).toBeVisible();
    expect(api.state.cashProjectionPageIds[0]?.filter((id) => id === "cash-scroll-1-1")).toHaveLength(2);
    await expect(page.getByText("重复流水", { exact: true })).toHaveCount(0);
    const cursorPageCallsBeforeScroll = api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length;
    await page.mouse.move(width / 2, height / 2);
    await page.mouse.wheel(0, 6_000);
    await expect.poll(() => api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length).toBeGreaterThan(cursorPageCallsBeforeScroll);
    await page.mouse.wheel(0, 6_000);
    const lastPageTwoRecord = page.locator('[id="ledger.record-cash-scroll-2-24"]').last();
    await expect(lastPageTwoRecord).toBeVisible();
    await expect(lastPageTwoRecord).toContainText("第2页流水24");
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") await captureLedgerScene(page, `ledger-infinite-scroll-${sizeName}`, width, height);
  }
});

test("F-03 收支账本追加失败时保留流水并可重试，切换筛选后回到首项", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page, { cashPaginatedPageCount: 2, cashAppendFailures: 1 });
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 900, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await expect(page.locator('[id="ledger.record-cash-scroll-1-1"]').last()).toBeVisible();
    const pageTwoCallsBeforeScroll = api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length;
    await page.mouse.move(width / 2, height / 2);
    await page.mouse.wheel(0, 12_000);
    await expect.poll(() => api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length).toBe(pageTwoCallsBeforeScroll + 1);
    await page.mouse.wheel(0, 12_000);
    const retryButton = page.locator('[id="ledger.append-retry"]').last();
    await expect(retryButton).toBeAttached();
    await retryButton.scrollIntoViewIfNeeded();
    await expect(retryButton).toContainText("重试加载");
    await expect(page.locator('[id="ledger.record-cash-scroll-1-24"]').last()).toBeVisible();
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") await captureLedgerScene(page, `ledger-append-error-${sizeName}`, width, height);

    const pageTwoCallsBeforeRetry = api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length;
    await clickComposeUntil(page, retryButton, async () =>
      api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length === pageTwoCallsBeforeRetry + 1,
    );
    await page.mouse.wheel(0, 12_000);
    const lastPageTwoRecord = page.locator('[id="ledger.record-cash-scroll-2-24"]').last();
    await expect(lastPageTwoRecord).toBeVisible();
    await expect(lastPageTwoRecord).toContainText("第2页流水24");
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") await captureLedgerScene(page, `ledger-append-recovered-${sizeName}`, width, height);

    await page.mouse.move(width / 2, height / 2);
    await page.mouse.wheel(0, -12_000);
    const incomeFilter = page.locator('[id="ledger.filter.quick-type-income"]').last();
    await clickComposeUntil(page, incomeFilter, async () => api.calls.some((call) => call.path.includes("economic_type=income")));
    const incomeFirstPageRequest = api.calls.filter((call) => call.path.includes("economic_type=income")).at(-1);
    expect(incomeFirstPageRequest?.path).not.toContain("cursor=");
    await expect(page.locator('[id="ledger.record-cash-scroll-1-1"]').last()).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
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

test("F-03 各尺寸账单行按交易对方、备注顺序显示", async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 900, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const row = page.locator('[id="ledger.record-projection-1"]').last();
    await expect(row).toContainText("咖啡店");
    await expect(row).toContainText("拿铁");
    const accessibleRow = await row.getAttribute("aria-label") ?? await row.textContent() ?? "";
    expect(accessibleRow.indexOf("咖啡店")).toBeGreaterThanOrEqual(0);
    expect(accessibleRow.indexOf("拿铁")).toBeGreaterThan(accessibleRow.indexOf("咖啡店"));
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await captureLedgerScene(page, `ledger-counterparty-note-${sizeName}`, width, height);
  }
});

test("F-03 Regular 与 Wide 表格列顺序和 Web 保持一致", async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const [width, height] of [[768, 1024], [1440, 900]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const header = page.locator('[id="ledger.table-header"]').last();
    const labels = ["发生时间", "账户", "交易信息", "分类", "流水类型", "金额"];
    for (const label of labels) await expect(header).toContainText(label);
    const columnIds = ["date", "account", "transaction-info", "category", "economic-type", "amount"];
    const expectedProportions = [0.12, 0.15, 0.24, 0.12, 0.11, 0.13];
    const headerBounds = await header.boundingBox();
    expect(headerBounds).not.toBeNull();
    const positions = await Promise.all(columnIds.map(async (column) => {
      const bounds = await page.locator(`[id="ledger.column-${column}"]`).last().boundingBox();
      expect(bounds).not.toBeNull();
      return bounds!.x;
    }));
    expect(positions).toEqual([...positions].sort((left, right) => left - right));
    const proportions = await Promise.all(columnIds.map(async (column) => {
      const bounds = await page.locator(`[id="ledger.column-${column}"]`).last().boundingBox();
      expect(bounds).not.toBeNull();
      return (bounds!.width + 16) / headerBounds!.width;
    }));
    proportions.forEach((proportion, index) => {
      expect(Math.abs(proportion - expectedProportions[index]), `${columnIds[index]} column should match old Web width`).toBeLessThan(0.02);
    });
    const selectionWidth = positions[0] - headerBounds!.x - 8;
    const amountBounds = await page.locator('[id="ledger.column-amount"]').last().boundingBox();
    expect(amountBounds).not.toBeNull();
    const actionWidth = headerBounds!.x + headerBounds!.width - (amountBounds!.x + amountBounds!.width + 8);
    expect(Math.abs(selectionWidth / headerBounds!.width - 0.05), "selection column should match old Web width").toBeLessThan(0.02);
    expect(Math.abs(actionWidth / headerBounds!.width - 0.08), "action column should match old Web width").toBeLessThan(0.02);
    const detailAction = page.locator('[id="ledger.open-record"]').last();
    await expect(detailAction).toBeVisible();
    const detailActionBounds = await detailAction.boundingBox();
    expect(detailActionBounds).not.toBeNull();
    expect(detailActionBounds!.height, "table detail action should stay compact at regular and wide widths").toBeLessThanOrEqual(56);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await captureLedgerScene(page, `ledger-table-columns-${width === 768 ? "regular" : "wide"}`, width, height);
  }
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
  const api = await installComposeApiFixtures(page, { withAcceptedCashRelation: true });
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
  const relationKindPicker = page.locator('[id="record.related.kind.edit"]').last();
  await expect(relationKindPicker).toBeVisible();
  const saveRelationType = page.locator('[id="record.related.kind.save"]').last();
  const saveRelationBounds = await saveRelationType.boundingBox();
  expect(saveRelationBounds).not.toBeNull();
  await clickComposeTarget(page, relationKindPicker);
  const relationKindMenu = page.locator('[id="choice-overlay-menu"]').last();
  const refundOffsetOption = relationKindMenu.locator('[id="choice-option-refund_offset"]').last();
  await expect(refundOffsetOption).toBeVisible();
  await clickComposeTarget(page, refundOffsetOption);
  await page.waitForTimeout(250);
  await page.mouse.click(saveRelationBounds!.x + saveRelationBounds!.width / 2, saveRelationBounds!.y + saveRelationBounds!.height / 2);
  await expect.poll(() => api.calls.some((call) =>
    call.method === "PUT" && /^\/api\/v1\/cash-relations\/relation-\d+$/.test(call.path) && call.body?.includes('"kind":"refund_offset"'),
  )).toBe(true);
  await expect(page.locator('[id="record.related.kind.save"]')).toHaveCount(0);
  await expect(page.getByText("退款冲销", { exact: true }).last()).toBeVisible();

  await page.goto(`${workspaceRoot}/`);
  const refreshedRelationDetail = page.locator('[id="ledger.open-record"]:visible').last();
  await clickComposeUntil(page, refreshedRelationDetail, async () => page.locator('[id="ledger.record-panel"]').isVisible());
  await page.locator('[id="record.related.toggle"]').last().click({ force: true });
  const refreshedRelatedContent = page.locator('[id="record.related.content"]').last();
  await expect(refreshedRelatedContent).toBeVisible();
  await page.mouse.move(1360, 700);
  await page.mouse.wheel(0, 900);
  await page.waitForTimeout(180);
  await clickComposeUntil(page, refreshedRelatedContent.getByRole("button", { name: "编辑流水", exact: true }).first(), async () => page.locator('[id="record.counterparty"]').last().isVisible());
  await expect(page.locator('[id="record.counterparty"]').last()).toBeVisible();
  await expect(page.getByText("便利店", { exact: true }).last()).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByText("流水详情", { exact: true }).last()).toBeVisible();
  await page.locator('[id="record.related.toggle"]').last().click({ force: true });

  await page.mouse.move(1360, 700);
  await page.mouse.wheel(0, 900);
  await page.waitForTimeout(180);
  await clickComposeTarget(page, page.getByRole("button", { name: "取消关联", exact: true }).first());
  await expect(page.getByText("超市", { exact: true }).last()).toBeVisible();
  await expect(page.getByText("便利店", { exact: true })).toHaveCount(0);
  await expect.poll(() => api.calls.some((call) => call.method === "DELETE" && call.path === "/api/v1/cash-relations/relation-1")).toBe(true);
});

test("F-05 导入可完成选择、扫描、预览、确认和结果摘要", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 2000 });
  await page.goto(`${workspaceRoot}/cash-import`);
  await expect(page.locator('[id="import.choose-file"]')).toBeVisible();
  await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length).toBeGreaterThan(0);
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
  const ledgerReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length;
  const accountReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/accounts")).length;
  const categoryReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path === "/api/v1/cash-categories").length;
  await clickComposeUntil(page, page.locator('[id="import.return"]').last(), async () => page.url().endsWith("/w/workspace-1/"));
  await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length).toBeGreaterThan(ledgerReadsBeforeReturn);
  await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/accounts")).length).toBeGreaterThan(accountReadsBeforeReturn);
  await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path === "/api/v1/cash-categories").length).toBeGreaterThan(categoryReadsBeforeReturn);
  const importedProjection = page.locator('[id="ledger.record-projection-imported"]').last();
  await expect(importedProjection).toContainText("刚刚导入的流水");
});

test("F-05 浅色与夜间主题下导入全流程覆盖三类窗口", async ({ page }) => {
  test.setTimeout(300_000);
  await mkdir(accountMappingScreenshotDir, { recursive: true });
  const sizes = [
    { name: "compact", width: 390, height: 844 },
    { name: "regular", width: 768, height: 1024 },
    { name: "wide", width: 1440, height: 900 },
  ] as const;
  const api = await installComposeApiFixtures(page);

  for (const colorScheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme });
    const themeDirectory = colorScheme === "dark" ? path.join(accountMappingScreenshotDir, "dark") : accountMappingScreenshotDir;
    await mkdir(themeDirectory, { recursive: true });

    for (const size of sizes) {
      const commitsBefore = api.calls.filter((call) => call.path === "/api/v1/cash-import/commit").length;
      await page.setViewportSize({ width: size.width, height: size.height });
      await page.goto(`${workspaceRoot}/cash-import`);
      await expect(page.locator('[id="import.choose-file"]')).toBeVisible();
      await page.evaluate(async () => { await document.fonts.ready; });
      await page.waitForTimeout(500);
      await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-file-selection.png`) });

      await chooseComposeFiles(page, [{
        name: `statement-${size.name}.csv`,
        mimeType: "text/csv",
        buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
      }]);
      await page.locator('[id="import.next"]').last().dispatchEvent("click");
      await expect(page.getByText("选择目标账户", { exact: true })).toBeVisible();
      await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-mapping.png`) });

      const mappingTarget = page.locator('[id="import.mapping.bank-account"]').last();
      const mappingTargetBounds = await mappingTarget.boundingBox();
      expect(mappingTargetBounds).not.toBeNull();
      const mappingMenu = await openChoiceMenu(page, mappingTarget);
      await expect(mappingMenu).toContainText("创建新账户");
      const createOption = mappingMenu.getByText("创建新账户", { exact: true }).last();
      await clickComposeTarget(page, createOption);
      await page.waitForTimeout(350);
      const createdAccountScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-mapping-created-account.png`) });
      const editButtonPoint = {
        x: mappingTargetBounds!.x + mappingTargetBounds!.width - 34,
        y: mappingTargetBounds!.y + mappingTargetBounds!.height + (size.width < 600 ? 36 : 13),
        width: mappingTargetBounds!.width,
        height: mappingTargetBounds!.height,
      };
      await page.mouse.click(editButtonPoint.x, editButtonPoint.y);
      await page.waitForTimeout(350);
      const mappingEditScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-edit-dialog.png`) });
      expect(Buffer.compare(createdAccountScreenshot, mappingEditScreenshot), "the account editor should visibly cover the page").not.toBe(0);
      const surfaceWidth = Math.min(560, size.width * 0.9);
      const dialogCenterY = size.height / 2;
      const typePickerPoint = {
        x: size.width / 2 + Math.min(180, size.width * 0.38),
        y: dialogCenterY + 38,
      };
      await page.mouse.click(typePickerPoint.x, typePickerPoint.y);
      await page.waitForTimeout(250);
      const typeMenuScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-edit-type-menu.png`) });
      expect(Buffer.compare(mappingEditScreenshot, typeMenuScreenshot), "the account type menu should appear anchored in the editor").not.toBe(0);
      await page.keyboard.press("Escape");
      await page.waitForTimeout(250);
      const editDialogAfterEscape = await page.screenshot();
      expect(Buffer.compare(typeMenuScreenshot, editDialogAfterEscape), "Escape should close the account type menu").not.toBe(0);
      const cancelEditPoint = {
        x: size.width / 2 + surfaceWidth / 2 - 140,
        y: dialogCenterY + 107,
      };
      await page.mouse.click(cancelEditPoint.x, cancelEditPoint.y);
      await page.mouse.move(2, 2);
      await page.waitForTimeout(300);
      const editCancelledScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-edit-cancelled.png`) });
      expect(await screenshotDifferenceRatio(page, createdAccountScreenshot, editCancelledScreenshot)).toBeLessThan(0.01);

      const previewCallsBefore = api.calls.filter((call) => call.path === "/api/v1/cash-import/preview").length;
      await clickImportPrimaryFooter(page, size.width, size.height);
      await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-import/preview").length).toBe(previewCallsBefore + 1);
      await page.waitForTimeout(250);
      const previewScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-preview.png`) });
      expect(await screenshotDifferenceRatio(page, editCancelledScreenshot, previewScreenshot)).toBeGreaterThan(0.01);

      await clickImportPrimaryFooter(page, size.width, size.height);
      await page.waitForTimeout(250);
      const relationsScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-relations.png`) });
      expect(await screenshotDifferenceRatio(page, previewScreenshot, relationsScreenshot)).toBeGreaterThan(0.005);

      await clickImportPrimaryFooter(page, size.width, size.height);
      await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-import/commit").length).toBe(commitsBefore + 1);
      await page.waitForTimeout(250);
      const completeScreenshot = await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-complete.png`) });
      expect(await screenshotDifferenceRatio(page, relationsScreenshot, completeScreenshot)).toBeGreaterThan(0.005);
      await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      const ledgerReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length;
      const accountReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/accounts")).length;
      const categoryReadsBeforeReturn = api.calls.filter((call) => call.method === "GET" && call.path === "/api/v1/cash-categories").length;
      await clickImportPrimaryFooter(page, size.width, size.height, true);
      await expect.poll(() => page.url().endsWith(`${workspaceRoot}/`)).toBe(true);
      await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length).toBeGreaterThan(ledgerReadsBeforeReturn);
      await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/accounts")).length).toBeGreaterThan(accountReadsBeforeReturn);
      await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path === "/api/v1/cash-categories").length).toBeGreaterThan(categoryReadsBeforeReturn);
      await page.screenshot({ path: path.join(themeDirectory, `import-flow-${size.name}-ledger-return.png`) });
    }
  }
});

test("F-05 未确认离开导入页不触发账本返回刷新", async ({ page }) => {
  test.setTimeout(90_000);
  const api = await installComposeApiFixtures(page);
  for (const [width, height] of [[390, 844], [768, 1024], [1440, 900]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    await expect(page.locator('[id="ledger.record-projection-1"]').last()).toBeVisible();
    const ledgerReadsBeforeImport = api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length;
    await openLedgerActions(page);
    await clickComposeUntil(page, page.locator('[id="ledger.import"]:visible').last(), async () => page.url().endsWith("/cash-import"));
    await chooseComposeFiles(page, [{
      name: `unconfirmed-${width}.csv`,
      mimeType: "text/csv",
      buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
    }]);
    await page.locator('[id="import.next"]').last().dispatchEvent("click");
    await expect(page.getByText("选择目标账户", { exact: true })).toBeVisible();
    const leaveImport = page.locator(width < 600 ? '[id="secondary-page-back"]' : '[id="secondary-page-close"]').last();
    await clickComposeUntil(page, leaveImport, async () => page.url().endsWith(`${workspaceRoot}/`));
    await expect.poll(() => api.calls.filter((call) => call.method === "GET" && call.path.startsWith("/api/v1/cash-projections")).length).toBe(ledgerReadsBeforeImport);
  }
});

test("F-05 账单导入文件选择页适配夜间主题", async ({ page }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await installComposeApiFixtures(page);
  await mkdir(path.join(accountMappingScreenshotDir, "dark"), { recursive: true });

  for (const { width, height, label } of [
    { width: 390, height: 844, label: "compact" },
    { width: 768, height: 1024, label: "regular" },
    { width: 1440, height: 900, label: "wide" },
  ]) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/cash-import`);
    const dropzone = page.locator('[id="import.dropzone"]').last();
    await expect(dropzone).toBeVisible();
    await page.waitForTimeout(120);
    const bounds = await dropzone.boundingBox();
    expect(bounds).not.toBeNull();
    const screenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "dark", `${label}-file-selection.png`) });
    const pixel = await screenshotPixel(page, screenshot, bounds!.x + 40, bounds!.y + 40);
    expect((pixel[0] + pixel[1] + pixel[2]) / 3, `${label} file selection should use the dark surface`).toBeLessThan(100);
    for (const text of ["选择账单文件", "添加账单文件"]) {
      const textBounds = await page.getByText(text, { exact: true }).last().boundingBox();
      expect(textBounds, `${label} ${text} should be visible`).not.toBeNull();
      expect(await screenshotMaxBrightness(page, screenshot, textBounds!), `${label} ${text} should contrast with the dark surface`).toBeGreaterThan(160);
    }
  }
});

test("F-05 导入主要操作保留图标与文字标签", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto(`${workspaceRoot}/`);
  await openLedgerActions(page);
  await expect(page.locator('[id="ledger.import"]').last()).toBeVisible();
  await page.screenshot({ path: path.join(accountMappingScreenshotDir, "home-import-menu.png") });
  await clickComposeUntil(page, page.locator('[id="ledger.import"]').last(), async () => page.url().endsWith("/cash-import"));
  await expect(page.locator('[id="import.choose-file"]').last()).toBeVisible();
  await expect(page.getByRole("button", { name: /选择文件/ }).last()).toBeVisible();
  await page.screenshot({ path: path.join(accountMappingScreenshotDir, "compact-import-choose-file.png") });

  await chooseComposeFiles(page, [{
    name: "icons.csv",
    mimeType: "text/csv",
    buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
  }]);
  await expect(page.locator('[id^="import.remove-file."]').last()).toBeVisible();
  await expect(page.locator('[id="import.next"]').last()).toBeVisible();
  await page.screenshot({ path: path.join(accountMappingScreenshotDir, "compact-import-selected-file.png") });
  await page.locator('[id="import.next"]').dispatchEvent("click");
  await expect(page.locator('[id="import.previous"]').last()).toBeVisible();
  await expect(page.locator('[id="import.next"]').last()).toBeVisible();
  await page.screenshot({ path: path.join(accountMappingScreenshotDir, "compact-import-account-mapping-actions.png") });
});

test("F-05 账户映射菜单和选中账户在三种窗口保持一致", async ({ page }) => {
  test.setTimeout(90_000);
  if (process.env.FT_COMPOSE_COLOR_SCHEME === "dark") await page.emulateMedia({ colorScheme: "dark" });
  await mkdir(accountMappingRunScreenshotDir, { recursive: true });
  await installComposeApiFixtures(page);
  await page.route("**/api/v1/cash-import/scan", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        import_token: "mapping-redesign-token",
        contract: "cash-import-v1",
        channel: "bank",
        channel_label: "银行账单",
        ready: true,
        file: { name: "accounts.csv", digest: "mapping-redesign-digest" },
        digest: "mapping-redesign-digest",
        files: [{ index: 0, name: "accounts.csv", filename: "accounts.csv", digest: "mapping-redesign-digest", size: 42, channel: "bank", channel_label: "银行账单", row_count: 1, status: "ready" }],
        accounts: [{ id: 1, name: "交通银行储蓄卡", type: "cash", active: true, currencies: ["CNY"] }],
        groups: [{
          group_id: "existing-group",
          source_type: "bank",
          channel: "bank",
          channel_label: "银行账单",
          display_name: "交通银行储蓄卡(1234)",
          masked_evidence: "尾号 1234",
          currencies: ["CNY", "USD"],
          row_count: 1,
          suggestion: { account_id: null, account: null, missing_currencies: [], mapping_revision: null },
        }],
      }),
    });
  });

  for (const { width, height, label } of [
    { width: 390, height: 844, label: "compact" },
    { width: 768, height: 1024, label: "regular" },
    { width: 1440, height: 900, label: "wide" },
  ]) {
    await page.setViewportSize({ width, height });
    await page.goto(workspaceRoot + "/cash-import");
    await chooseComposeFiles(page, [{
      name: "accounts.csv",
      mimeType: "text/csv",
      buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n"),
    }]);
    await page.locator('[id="import.next"]').dispatchEvent("click");
    await expect(page.getByText("选择目标账户", { exact: true })).toBeVisible();

    const picker = page.locator('[id="import.mapping.existing-group"]').last();
    await picker.scrollIntoViewIfNeeded();
    await page.screenshot({ path: path.join(accountMappingRunScreenshotDir, label + "-mapping-initial.png") });
    const closedScreenshot = await page.screenshot();
    await clickComposeTarget(page, picker);
    const menu = page.locator('[id="choice-overlay-menu"]').last();
    await expect(menu).toBeVisible();
    await expect(menu).toContainText("交通银行储蓄卡");
    await expect(menu).toContainText("现金账户");
    await expect(menu).toContainText("币种：CNY");
    const menuBounds = await menu.boundingBox();
    expect(menuBounds).not.toBeNull();
    await page.screenshot({ path: path.join(accountMappingRunScreenshotDir, label + "-account-menu.png") });
    const openScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatioOutsideRect(page, closedScreenshot, openScreenshot, menuBounds!)).toBeLessThan(0.02);

    await clickComposeTarget(page, menu.getByText("交通银行储蓄卡 · 现金账户 · 币种：CNY", { exact: true }).last());
    await expect(page.getByText(/交通银行储蓄卡 · 现金账户/).last()).toBeVisible();
    const selectedScreenshot = await page.screenshot({ path: path.join(accountMappingRunScreenshotDir, label + "-mapping-selected-account.png") });
    expect(await screenshotDifferenceRatio(page, openScreenshot, selectedScreenshot)).toBeGreaterThan(0.005);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
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
  await expect(page.getByRole("textbox", { name: /选择流水/ })).toHaveCount(1);

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
  await clickComposeTarget(page, page.getByRole("textbox", { name: /^选择流水/ }).last(), 0.1, 0.25);
  await expect(page.getByText(/钱包咖啡店 · 28\.50 CNY/).last()).toBeVisible();
  const walletCandidateOption = page.locator('[id="choice-overlay-menu"]').last().locator('[id="choice-option-wallet-row"]');
  await expect(walletCandidateOption).toBeVisible();
  await clickComposeTarget(page, walletCandidateOption);
  await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-selection-commit-ready-compact-390.png") });

  await page.waitForTimeout(250);
  await clickImportPrimaryFooter(page, 390, 2000);
  await expect.poll(() => commitAttempts.length).toBe(1);
  const retryErrorScreenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-retry-error-compact-390.png") });
  await clickImportPrimaryFooter(page, 390, 2000);
  await expect.poll(() => commitAttempts.length).toBe(2);
  await page.waitForTimeout(250);
  const retrySuccessScreenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-retry-success-compact-390.png") });
  expect(await screenshotDifferenceRatio(page, retryErrorScreenshot, retrySuccessScreenshot)).toBeGreaterThan(0.005);
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
  const assertOverlayDoesNotCoverTrigger = async (triggerBounds: { x: number; y: number; width: number; height: number }) => {
    const menuBounds = await page.locator('[id="choice-overlay-menu"]').last().boundingBox();
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
  await expect(page.getByRole("textbox", { name: /^选择流水/ }).last()).toBeVisible();
  await expect(page.getByText("对侧流水", { exact: true })).toHaveCount(0);
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-390.png") });

  const recordPicker = pendingRelation.getByRole("textbox", { name: /^选择流水/ }).last();
  const compactPickerBounds = await recordPicker.boundingBox();
  expect(compactPickerBounds).not.toBeNull();
  const compactClosedScreenshot = await page.screenshot();
  await clickComposeTarget(page, recordPicker, 0.1, 0.25);
  const compactMenu = page.locator('[id="choice-overlay-menu"]').last();
  const compactOption = compactMenu.locator('[id="choice-option-wallet-row"]');
  await expect(compactOption).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(compactPickerBounds!);
  const compactMenuBounds = await compactMenu.boundingBox();
  expect(compactMenuBounds).not.toBeNull();
  const compactMenuScreenshot = await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-390.png") });
  expect(await screenshotDifferenceRatioOutsideRect(page, compactClosedScreenshot, compactMenuScreenshot, compactMenuBounds!)).toBeLessThan(0.02);
  await clickComposeTarget(page, compactOption);
  const compactSelectedScreenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-selection-compact-390.png") });
  expect(await screenshotDifferenceRatio(page, compactMenuScreenshot, compactSelectedScreenshot)).toBeGreaterThan(0.001);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

  await page.setViewportSize({ width: 768, height: 1024 });
  await page.reload();
  await expect(page.locator('[id="import.choose-file"]').last()).toBeVisible();
  await chooseComposeFiles(page, [
    { name: "bank.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-28.50\n") },
    { name: "wallet.csv", mimeType: "text/csv", buffer: Buffer.from("date,amount\n2026-09-24,-17.25\n") },
  ]);
  await expect(page.getByText("bank.csv", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("wallet.csv", { exact: true }).first()).toBeVisible();
  await expect(page.locator('[id="import.next"]').last()).toBeEnabled();
  await page.locator('[id="import.next"]').last().dispatchEvent("click");
  await expect(page.getByText("跨渠道账单 · 2 个来源账户", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.locator('[id="import.next"]').last());
  await expect(page.getByText("银行咖啡店", { exact: true })).toBeVisible();
  await clickComposeTarget(page, page.getByRole("button", { name: "继续", exact: true }).last());
  await expect(page.getByText("关系配对", { exact: true })).toBeVisible();
  const regularRelation = page.locator('[id="import.relation-row.pending-relation"]').last();
  await assertDesktopFooterAlignment();
  const regularClosedScreenshot = await page.screenshot();
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-768.png") });
  const regularPicker = regularRelation.getByRole("textbox", { name: /^选择流水/ }).last();
  const regularPickerBounds = await regularPicker.boundingBox();
  expect(regularPickerBounds).not.toBeNull();
  await clickComposeTarget(page, regularPicker, 0.1, 0.25);
  const regularMenu = page.locator('[id="choice-overlay-menu"]').last();
  await expect(regularMenu.locator('[id="choice-option-wallet-row"]')).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(regularPickerBounds!);
  const regularMenuBounds = await regularMenu.boundingBox();
  expect(regularMenuBounds).not.toBeNull();
  const regularMenuScreenshot = await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-768.png") });
  expect(await screenshotDifferenceRatioOutsideRect(page, regularClosedScreenshot, regularMenuScreenshot, regularMenuBounds!)).toBeLessThan(0.02);
  await clickComposeTarget(page, regularMenu.locator('[id="choice-option-wallet-row"]'));
  const regularSelectedScreenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-selection-regular-768.png") });
  expect(await screenshotDifferenceRatio(page, regularMenuScreenshot, regularSelectedScreenshot)).toBeGreaterThan(0.001);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);

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
  await expect(page.getByRole("textbox", { name: /^选择流水/ }).last()).toBeVisible();
  await page.screenshot({ path: path.join(screenshotDir, "compose-import-relations-1440.png") });
  const wideClosedScreenshot = await page.screenshot();
  const widePicker = page.getByRole("textbox", { name: /^选择流水/ }).last();
  const widePickerBounds = await widePicker.boundingBox();
  expect(widePickerBounds).not.toBeNull();
  await clickComposeTarget(page, widePicker, 0.1, 0.25);
  const wideMenu = page.locator('[id="choice-overlay-menu"]').last();
  await expect(wideMenu.locator('[id="choice-option-wallet-row"]')).toHaveText("钱包咖啡店 · 28.50 CNY");
  await assertOverlayDoesNotCoverTrigger(widePickerBounds!);
  const wideMenuBounds = await wideMenu.boundingBox();
  expect(wideMenuBounds).not.toBeNull();
  const wideMenuScreenshot = await page.screenshot({ path: path.join(screenshotDir, "compose-import-relation-picker-1440.png") });
  expect(await screenshotDifferenceRatioOutsideRect(page, wideClosedScreenshot, wideMenuScreenshot, wideMenuBounds!)).toBeLessThan(0.02);
  await clickComposeTarget(page, wideMenu.locator('[id="choice-option-wallet-row"]'));
  const wideSelectedScreenshot = await page.screenshot({ path: path.join(accountMappingScreenshotDir, "import-relations-selection-wide-1440.png") });
  expect(await screenshotDifferenceRatio(page, wideMenuScreenshot, wideSelectedScreenshot)).toBeGreaterThan(0.001);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
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

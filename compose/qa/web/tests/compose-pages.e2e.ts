import { expect, test, type Locator, type Page } from "@playwright/test";
import path from "node:path";
import { mkdir, readFile } from "node:fs/promises";
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
const dialogConformanceScreenshotDir = path.resolve(process.cwd(), "../openspec/changes/compose-material3-dialog-conformance/screenshots");
const cashLedgerScreenshotDir = path.resolve(process.cwd(), "../openspec/changes/compose-cash-ledger-redesign/screenshots/current-review-20261017");
const browserDiagnostics = new WeakMap<import("@playwright/test").Page, BrowserDiagnostics>();

async function clickComposeTarget(page: Page, locator: Locator, xRatio = 0.5, yRatio = 0.5): Promise<void> {
  await locator.scrollIntoViewIfNeeded();
  const bounds = await locator.boundingBox();
  if (!bounds) throw new Error("Missing Compose target bounds");
  await page.mouse.click(bounds.x + bounds.width * xRatio, bounds.y + bounds.height * yRatio);
  await page.waitForTimeout(60);
}

async function clickComposeTargetByClientRect(page: Page, locator: Locator, xRatio = 0.5, yRatio = 0.5): Promise<void> {
  const bounds = await locator.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
  });
  if (bounds.width <= 0 || bounds.height <= 0) throw new Error("Compose target has no visible DOM rectangle");
  await page.mouse.click(bounds.x + bounds.width * xRatio, bounds.y + bounds.height * yRatio);
  await page.waitForTimeout(60);
}

async function clickInlineDatePickerAction(page: Page, picker: Locator, action: "cancel" | "confirm"): Promise<void> {
  await expect(picker).toBeVisible();
  const actionButton = page.locator(`[id="ledger.filter.date-from-${action}"]:visible`).last();
  await clickComposeUntil(page, actionButton, async () => !(await picker.isVisible()));
}

async function clickFilterSheetDone(page: Page, details: Locator): Promise<void> {
  const bounds = await details.boundingBox();
  if (!bounds) throw new Error("Missing detailed filter bounds");
  await page.mouse.click(bounds.x + bounds.width - 50, bounds.y + bounds.height - 32);
  await page.waitForTimeout(100);
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

async function scrollDialogBodyTo(page: Page, panel: Locator, target: Locator): Promise<void> {
  const viewport = page.viewportSize();
  if (!viewport) throw new Error("Missing Playwright viewport");
  for (let attempt = 0; attempt < 10; attempt += 1) {
    // Compose Web exposes canvas controls through mirrored DOM nodes. Those
    // nodes can be clipped by the dialog scrollport, so ask the container to
    // scroll instead of relying on DOM scrollIntoView for the mirror.
    const targetIsVisible = await target.isVisible().catch(() => false);
    const targetBounds = await target.boundingBox().catch(() => null);
    if (targetIsVisible &&
      targetBounds && targetBounds.width > 0 && targetBounds.height > 0 &&
      targetBounds.y >= 0 && targetBounds.y + targetBounds.height <= viewport.height - 100
    ) return;
    const panelBounds = await panel.boundingBox();
    if (!panelBounds) throw new Error("Dialog panel has no rendered bounds");
    const panelId = await panel.getAttribute("id");
    const scrollX = panelId === "ledger.filter.details"
      ? panelBounds.x + Math.min(36, panelBounds.width * 0.1)
      : panelBounds.x + panelBounds.width / 2;
    await page.mouse.move(
      Math.min(scrollX, viewport.width - 1),
      Math.min(panelBounds.y + panelBounds.height / 2, viewport.height - 20),
    );
    const targetIsAbovePanel = targetIsVisible && targetBounds != null && targetBounds.y + targetBounds.height <= panelBounds.y;
    const targetIsInsidePanel = targetIsVisible && targetBounds != null &&
      targetBounds.y >= panelBounds.y &&
      targetBounds.y + targetBounds.height <= panelBounds.y + panelBounds.height;
    const aboveScrollport = targetIsInsidePanel && targetBounds!.y + targetBounds!.height < panelBounds.y + 64;
    const delta = Math.min(420, Math.max(260, panelBounds.height * 0.45));
    const scrollUp = targetIsVisible
      ? targetIsAbovePanel || aboveScrollport
      : attempt >= 5;
    await page.mouse.wheel(0, scrollUp ? -delta : delta);
    await page.waitForTimeout(100);
  }
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

async function openDetailedFilterField(page: Page, semanticId: string): Promise<Locator> {
  const details = page.locator('[id="ledger.filter.details"]:visible').last();
  await clickComposeUntil(page, page.locator('[id="ledger.more-filters"]').last(), async () => details.isVisible());
  const viewport = page.viewportSize();
  if (!viewport) throw new Error("Missing viewport for detailed filters");
  await expect.poll(async () => {
    const bounds = await details.boundingBox().catch(() => null);
    return bounds != null && bounds.height > 0 && bounds.y < viewport.height - 120;
  }).toBe(true);
  await page.waitForTimeout(180);
  const field = details.locator(`[id="${semanticId}"]`).last();
  await expect(field).toBeAttached();
  const fieldIsInViewport = async () => {
    const bounds = await field.boundingBox().catch(() => null);
    return Boolean(
      bounds && bounds.width > 0 && bounds.height > 0 &&
      bounds.y >= 0 && bounds.y + bounds.height <= viewport.height,
    );
  };
  for (let attempt = 0; attempt < 8 && !(await fieldIsInViewport()); attempt += 1) {
    const bounds = await field.boundingBox().catch(() => null);
    const scrollUp = bounds != null && bounds.y + bounds.height < 0;
    await page.mouse.move(viewport.width / 2, Math.max(80, viewport.height * 0.82));
    await page.mouse.wheel(0, (scrollUp ? -1 : 1) * Math.max(320, viewport.height * 0.4));
    await page.waitForTimeout(100);
  }
  expect(await fieldIsInViewport(), `${semanticId} should be physically inside the browser viewport before clicking`).toBe(true);
  await expect(field).toBeVisible();
  return field;
}

async function hasRenderedComposeBounds(locator: Locator): Promise<boolean> {
  const bounds = await locator.boundingBox();
  return !!bounds && bounds.width > 0 && bounds.height > 0;
}

async function getLedgerFab(page: Page): Promise<Locator> {
  const taggedFab = page.locator('[id="ledger.actions-fab"]').last();
  if (await taggedFab.count() && await taggedFab.isVisible().catch(() => false)) return taggedFab;
  return page.getByRole("button", { name: "账单操作" }).last();
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

async function ensureLedgerMenuActionVisible(page: Page, semanticId: string): Promise<void> {
  const action = page.locator(`[id="${semanticId}"]`).last();
  if (await action.isVisible().catch(() => false)) return;
  await expect.poll(() => action.isVisible().catch(() => false), { timeout: 2_500 }).toBe(true);
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
    ? path.join(cashLedgerScreenshotDir, "dark")
    : cashLedgerScreenshotDir;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.setViewportSize({ width, height });
  await page.waitForTimeout(250);
  await page.screenshot({ path: path.join(screenshotDirectory, `${name}-${width}.png`) });
}

async function captureDialogConformanceScene(page: Page, name: string): Promise<void> {
  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS !== "1") return;
  const screenshotDirectory = process.env.FT_COMPOSE_COLOR_SCHEME === "dark"
    ? path.join(dialogConformanceScreenshotDir, "dark")
    : dialogConformanceScreenshotDir;
  await mkdir(screenshotDirectory, { recursive: true });
  await page.screenshot({ path: path.join(screenshotDirectory, `${name}.png`) });
}

async function screenshotDifferenceRatio(
  page: Page,
  before: Buffer,
  after: Buffer,
  sampleStep = 24,
  differenceThreshold = 72,
): Promise<number> {
  return page.evaluate(async ({ beforeBase64, afterBase64, sampleStep, differenceThreshold }) => {
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
    for (let y = Math.floor(sampleStep / 2); y < canvas.height; y += sampleStep) {
      for (let x = Math.floor(sampleStep / 2); x < canvas.width; x += sampleStep) {
        const offset = (y * canvas.width + x) * 4;
        const difference = Math.abs(beforePixels[offset] - afterPixels[offset]) +
          Math.abs(beforePixels[offset + 1] - afterPixels[offset + 1]) +
          Math.abs(beforePixels[offset + 2] - afterPixels[offset + 2]);
        if (difference > differenceThreshold) changed += 1;
        samples += 1;
      }
    }
    return samples === 0 ? 0 : changed / samples;
  }, { beforeBase64: before.toString("base64"), afterBase64: after.toString("base64"), sampleStep, differenceThreshold });
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

async function clickBottomPrimaryAction(page: Page): Promise<void> {
  const screenshot = await page.screenshot();
  const target = await page.evaluate(async (base64) => {
    const image = new Image();
    image.src = `data:image/png;base64,${base64}`;
    await image.decode();
    const canvas = document.createElement("canvas");
    canvas.width = image.naturalWidth;
    canvas.height = image.naturalHeight;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Unable to inspect the visible primary action");
    context.drawImage(image, 0, 0);
    const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
    const cellSize = 8;
    const columns = Math.ceil(canvas.width / cellSize);
    const rows = Math.ceil(canvas.height / cellSize);
    const counts = new Uint16Array(columns * rows);
    for (let y = Math.floor(canvas.height * 0.55); y < canvas.height; y += 1) {
      for (let x = Math.floor(canvas.width * 0.35); x < canvas.width * 0.8; x += 1) {
        const offset = (y * canvas.width + x) * 4;
        const red = pixels[offset];
        const green = pixels[offset + 1];
        const blue = pixels[offset + 2];
        if (blue > 150 && blue - red > 55 && green - red > 20) {
          counts[Math.floor(y / cellSize) * columns + Math.floor(x / cellSize)] += 1;
        }
      }
    }
    let best: { count: number; bounds: [number, number, number, number] } | null = null;
    const visited = new Uint8Array(counts.length);
    for (let start = 0; start < counts.length; start += 1) {
      if (counts[start] < 4 || visited[start]) continue;
      const queue = [start];
      visited[start] = 1;
      let count = 0;
      let minX = columns;
      let maxX = -1;
      let minY = rows;
      let maxY = -1;
      while (queue.length > 0) {
        const current = queue.pop()!;
        const x = current % columns;
        const y = Math.floor(current / columns);
        count += counts[current];
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        for (let dy = -1; dy <= 1; dy += 1) {
          for (let dx = -1; dx <= 1; dx += 1) {
            const nextX = x + dx;
            const nextY = y + dy;
            if (nextX < 0 || nextX >= columns || nextY < 0 || nextY >= rows) continue;
            const next = nextY * columns + nextX;
            if (counts[next] < 4 || visited[next]) continue;
            visited[next] = 1;
            queue.push(next);
          }
        }
      }
      if (!best || count > best.count) best = { count, bounds: [minX, minY, maxX, maxY] };
    }
    if (!best) return null;
    const [minX, minY, maxX, maxY] = best.bounds;
    return {
      x: Math.round((minX + maxX + 1) * cellSize / 2),
      y: Math.round((minY + maxY + 1) * cellSize / 2),
    };
  }, screenshot.toString("base64"));
  if (!target) throw new Error("The visible primary action was not found in the screenshot");
  await page.mouse.click(target.x, target.y);
  await page.waitForTimeout(220);
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


test.beforeEach(({ page }, testInfo) => {
  const expectedErrorStatuses = testInfo.title.includes("关联影响确认") ? [409] : [];
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

  const accountFilter = await openDetailedFilterField(page, "ledger.filter.account");
  await expect(page.getByRole("textbox", { name: "最低金额" })).toBeAttached();
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
    if (width === 390) {
      const transferBounds = await page.locator('[id="ledger.filter.quick-type-internal_transfer"]').last().boundingBox();
      const detailedFilterBounds = await page.locator('[id="ledger.more-filters"]').last().boundingBox();
      expect(transferBounds).not.toBeNull();
      expect(detailedFilterBounds).not.toBeNull();
      expect(Math.abs(transferBounds!.y - detailedFilterBounds!.y)).toBeLessThanOrEqual(8);
    }
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

test("F-03 ChoicePicker 锚定菜单覆盖账本、流水、分类、工作区和投资页面", async ({ page }) => {
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
    const ledgerAccount = await openDetailedFilterField(page, "ledger.filter.account");
    const ledgerMenu = await openChoiceMenu(page, ledgerAccount);
    await expect(ledgerMenu).toContainText("日常账户");
    await page.screenshot({ path: path.join(accountMappingScreenshotDir, `choice-picker-ledger-${size.name}.png`) });
    await clickComposeTarget(page, ledgerMenu.locator('[id="choice-option-1"]').last());

    await page.goto(`${workspaceRoot}/`);
    await openLedgerActions(page);
    await clickComposeUntil(page, page.locator('[id="ledger.add"]:visible').last(), async () =>
      page.locator('[id="cash.create.form"]').last().isVisible(),
    );
    const recordType = page.locator('[id="cash.create.account_id"]').last();
    const recordMenu = await openChoiceMenu(page, recordType);
    await expect(recordMenu).toContainText("日常账户");
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
  await expect(page.locator('[id="ledger.batch-category"]')).toBeVisible();
  const selectedCountBounds = await page.locator('[id="ledger.selected-count"]').last().boundingBox();
  const fabBounds = await (await getLedgerFab(page)).boundingBox();
  expect(selectedCountBounds).not.toBeNull();
  expect(fabBounds).not.toBeNull();
  expect(selectedCountBounds!.x + selectedCountBounds!.width).toBeLessThanOrEqual(fabBounds!.x);
  await captureLedgerScene(page, "batch-selection-toolbar-compact", 390, 844);
  await row.click({ force: true });
  await expect(page.locator('[id="ledger.selected-count"]')).toHaveCount(0);
  await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(0);
  await expect(page.locator('[id="ledger.batch-category"]')).toHaveCount(0);
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
    await expect(page.locator('[id="ledger.batch-category"]')).toBeVisible();
    const selectedCountBounds = await page.locator('[id="ledger.selected-count"]').last().boundingBox();
    const fabBounds = await (await getLedgerFab(page)).boundingBox();
    expect(selectedCountBounds).not.toBeNull();
    expect(fabBounds).not.toBeNull();
    expect(selectedCountBounds!.x + selectedCountBounds!.width).toBeLessThanOrEqual(fabBounds!.x);
    const sizeName = width === 768 ? "regular" : "wide";
    await captureLedgerScene(page, `batch-selection-toolbar-${sizeName}`, width, 1000);
    await expect(page.locator('[id="ledger.batch-delete"]')).toBeVisible();
    await expect(page.locator('[id="ledger.cancel-selection"]')).toBeVisible();
    await captureLedgerScene(page, `fab-batch-${sizeName}`, width, 1000);
    await page.locator('[id="ledger.cancel-selection"]').last().click({ force: true });
    await expect(page.locator('[id="ledger.selected-count"]')).toHaveCount(0);
    await expect(page.locator('[id^="ledger.record-checkbox-"]')).toHaveCount(1);
  }
});

test("F-03 筛选卡片固定，列表独立滚动并触发分页", async ({ page }) => {
  test.setTimeout(120_000);
  const api = await installComposeApiFixtures(page, { cashPaginatedPageCount: 2 });
  for (const [width, height] of [[390, 844], [768, 1024], [1440, 1000]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const filters = page.locator('[id="ledger.filters"]').last();
    const ledgerList = page.locator('[id="ledger.list"]').last();
    await expect(filters).toBeVisible();
    const initialFilterBounds = await filters.boundingBox();
    const listBounds = await ledgerList.boundingBox();
    expect(initialFilterBounds).not.toBeNull();
    expect(listBounds).not.toBeNull();

    const nextPageCallsBefore = api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length;
    await page.mouse.move(listBounds!.x + listBounds!.width / 2, listBounds!.y + listBounds!.height * 0.75);
    await page.mouse.wheel(0, 6_000);
    await expect.poll(() => api.calls.filter((call) => call.path.includes("cursor=cash-scroll-page-2")).length).toBeGreaterThan(nextPageCallsBefore);
    const finalFilterBounds = await filters.boundingBox();
    expect(finalFilterBounds).not.toBeNull();
    expect(Math.abs(finalFilterBounds!.y - initialFilterBounds!.y)).toBeLessThanOrEqual(2);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await captureLedgerScene(page, `ledger-sticky-filter-${width}`, width, height);
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

    await ensureLedgerMenuActionVisible(page, "ledger.batch-category");
    await captureLedgerScene(page, `fab-batch-${sizeName}`, width, height);
    const dialog = page.locator('[id="ledger.category-dialog"]').last();
    await clickComposeUntil(page, page.locator('[id="ledger.batch-category"]').last(), async () => dialog.isVisible());
    await expect(dialog).toBeVisible();
    const cancelBounds = await page.locator('[id="ledger.category.cancel"]').last().boundingBox();
    expect(cancelBounds).not.toBeNull();
    const categoryPicker = dialog.locator('[id="ledger.category-picker"]').last();
    await captureLedgerScene(page, `batch-category-dialog-${sizeName}`, width, height);
    await clickComposeTarget(page, categoryPicker);
    const categoryMenu = page.locator('[id="choice-overlay-menu"]').last();
    await expect(categoryMenu.locator('[id="choice-option-coffee"]').last()).toBeVisible();
    await captureLedgerScene(page, `batch-category-options-${sizeName}`, width, height);
    await clickComposeTarget(page, categoryMenu.locator('[id="choice-option-coffee"]').last());
    await captureLedgerScene(page, `batch-classify-ready-${sizeName}`, width, height);
    const writesBeforeCancel = api.state.classificationWrites.length;
    const readyScreenshot = await page.screenshot();
    await clickBatchCategoryAction(page, cancelBounds!);
    const cancelledScreenshot = await page.screenshot();
    await captureLedgerScene(page, `batch-classify-cancelled-${sizeName}`, width, height);
    expect(await screenshotDifferenceRatio(page, readyScreenshot, cancelledScreenshot)).toBeGreaterThan(0.01);
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
    await ensureLedgerMenuActionVisible(page, "ledger.batch-category");
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
    await ensureLedgerMenuActionVisible(page, "ledger.batch-delete");
    await page.locator('[id="ledger.batch-delete"]').last().click({ force: true });
    await expect.poll(() => api.calls.filter((call) => call.path === "/api/v1/cash-projections/delete-impact" && call.method === "POST").length).toBe(deleteCallsBefore + 1);
    await expect(page.getByRole("button", { name: "取消", exact: true }).last()).toBeVisible();
    await captureLedgerScene(page, `batch-delete-confirm-${sizeName}`, width, height);
    await page.getByRole("button", { name: "取消", exact: true }).last().click({ force: true });
    expect(api.state.projectionDeletes).toHaveLength(0);
    await captureLedgerScene(page, `batch-delete-cancelled-${sizeName}`, width, height);
  }
});

test("F-03 批量删除进行中仍可确认或取消，失败反馈保留在账本", async ({ page }) => {
  test.setTimeout(120_000);
  await installComposeApiFixtures(page);
  for (const [width, height] of [[390, 844], [768, 1024], [1440, 1000]] as const) {
    let releaseDelete!: () => void;
    const deleteGate = new Promise<void>((resolve) => { releaseDelete = resolve; });
    let deleteCount = 0;
    const delayedDelete = async (route: import("@playwright/test").Route) => {
      if (route.request().method() !== "DELETE") return route.fallback();
      deleteCount += 1;
      await deleteGate;
      await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: { code: "network_unavailable" } }) });
    };
    await page.route("**/api/v1/cash-projections", delayedDelete);
    try {
      await page.setViewportSize({ width, height });
      await page.goto(`${workspaceRoot}/`);
      await selectFirstLedgerRecord(page, width);
      await ensureLedgerMenuActionVisible(page, "ledger.batch-delete");
      const actionMenuScreenshot = await page.screenshot();
      await page.locator('[id="ledger.batch-delete"]').last().click({ force: true });
      const dialog = page.locator('[id="ledger.delete-projection.dialog"]').last();
      await expect(dialog).toBeVisible();
      const confirmDialogScreenshot = await page.screenshot();
      expect(await screenshotDifferenceRatio(page, actionMenuScreenshot, confirmDialogScreenshot), "batch delete action should open its visible confirmation dialog").toBeGreaterThan(0.01);
      const confirm = page.locator('[id="ledger.delete-projection.confirm"]').last();
      const cancel = page.locator('[id="ledger.delete-projection.cancel"]').last();
      await clickComposeTarget(page, confirm);
      await expect.poll(() => deleteCount, { timeout: 5_000 }).toBe(1);
      await expect(confirm).toBeEnabled();
      await expect(cancel).toBeEnabled();
      await clickComposeTarget(page, confirm);
      await page.waitForTimeout(180);
      expect(deleteCount).toBe(1);
      const pendingConfirmScreenshot = await page.screenshot();
      await clickComposeTarget(page, cancel);
      releaseDelete();
      await expect(page.locator('[id="ledger.delete.error-dialog"]')).toBeVisible();
      await expect(page.getByText("无法删除流水", { exact: true })).toBeVisible();
      const failureScreenshot = await page.screenshot();
      expect(await screenshotDifferenceRatio(page, pendingConfirmScreenshot, failureScreenshot)).toBeGreaterThan(0.01);
      await clickComposeTarget(page, page.locator('[id="ledger.delete.error-acknowledge"]').last());
    } finally {
      releaseDelete();
      await page.unroute("**/api/v1/cash-projections", delayedDelete).catch(() => {});
    }
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
  const api = await installComposeApiFixtures(page, { cashPaginatedPageCount: 2, cashCrossMonthBoundary: true });
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
    await expect(lastPageTwoRecord).toContainText("2026年9月");
    const septemberSummary = page.locator('[id="ledger.table-2026-09"]:visible').last();
    for (let attempt = 0; attempt < 80 && !(await septemberSummary.isVisible().catch(() => false)); attempt += 1) {
      await page.mouse.wheel(0, -120);
      await page.waitForTimeout(50);
    }
    await expect(septemberSummary).toBeVisible();
    await captureLedgerScene(page, `ledger-month-boundary-${sizeName}`, width, height);
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
  expect(bounds?.height ?? 1000).toBeLessThan(800);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.waitForTimeout(150);
  bounds = await details.boundingBox();
  expect(bounds).not.toBeNull();
  expect(bounds?.height ?? 1000).toBeLessThan(800);
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

test("F-03 详细筛选按字段语义分组并适配窗口宽度", async ({ page }) => {
  const api = await installComposeApiFixtures(page, { cashTransferSubtypes: ["ordinary_transfer"] });
  const sizes = [
    { name: "compact-320", width: 320, height: 800, compact: true, capture: false },
    { name: "compact-375", width: 375, height: 812, compact: true, capture: false },
    { name: "compact", width: 390, height: 844, compact: true, capture: true },
    { name: "compact-414", width: 414, height: 896, compact: true, capture: false },
    { name: "regular", width: 768, height: 1024, compact: false, capture: true },
    { name: "wide", width: 1440, height: 1000, compact: false, capture: true },
  ] as const;
  const groupIds = [
    "time",
    "amount",
    "category",
    "account",
    "other",
  ] as const;
  const groupLabels = ["时间", "金额", "分类", "账户", "其他条件"];

  for (const size of sizes) {
    await page.setViewportSize({ width: size.width, height: size.height });
    await page.goto(`${workspaceRoot}/`);
    const transferCallsBefore = api.calls.filter((call) => call.path.includes("economic_type=internal_transfer")).length;
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.filter.quick-type-internal_transfer"]').last(),
      async () => api.calls.filter((call) => call.path.includes("economic_type=internal_transfer")).length > transferCallsBefore,
    );
    const details = page.locator('[id="ledger.filter.details"]').last();
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.more-filters"]').last(),
      async () => details.isVisible(),
    );
    const filterClose = page.locator('[id="ledger.filter.close"]').last();
    const filterTitle = page.locator('[id="ledger.filter.title"]').last();
    await expect(filterClose).toBeVisible();
    const filterCloseBounds = await filterClose.boundingBox();
    const filterTitleBounds = await filterTitle.boundingBox();
    expect(filterCloseBounds).not.toBeNull();
    expect(filterTitleBounds).not.toBeNull();
    expect(filterCloseBounds!.x + filterCloseBounds!.width).toBeLessThanOrEqual(filterTitleBounds!.x + 1);

    const groups = groupIds.map((id) => page.locator(`[id="ledger.filter.group.${id}"]`).last());
    for (const [index, group] of groups.entries()) {
      await expect(group).toContainText(groupLabels[index]);
    }
    const domGroupOrder = await details
      .locator('[id^="ledger.filter.group."]')
      .evaluateAll((elements) => elements.map((element) => element.id));
    expect(domGroupOrder).toEqual(groupIds.map((id) => `ledger.filter.group.${id}`));
    for (const fieldId of ["ledger.filter.date-from", "ledger.filter.date-to"]) {
      await expect(groups[0].locator(`[id="${fieldId}"]`)).toBeAttached();
    }
    for (const fieldId of [
      "ledger.filter.currency",
      "ledger.filter.amount-minimum",
      "ledger.filter.amount-maximum",
    ]) {
      await expect(groups[1].locator(`[id="${fieldId}"]`)).toBeAttached();
    }
    await expect(groups[2].locator('[id="ledger.filter.category"]')).toBeAttached();
    await expect(groups[3].locator('[id="ledger.filter.account"]')).toBeAttached();
    await expect(groups[4].locator('[id="ledger.filter.transfer-subtype"]')).toBeAttached();
    await expect(groups[4].locator('[id="ledger.filter.composition"]')).toBeAttached();
    const startDate = await page.getByRole("textbox", { name: "开始日期" }).boundingBox();
    const endDate = await page.getByRole("textbox", { name: "结束日期" }).boundingBox();
    const currency = await page.locator('[id="ledger.filter.currency"]').last().boundingBox();
    const minimum = await page.getByRole("textbox", { name: "最低金额" }).boundingBox();
    const maximum = await page.getByRole("textbox", { name: "最高金额" }).boundingBox();
    expect(startDate).not.toBeNull();
    expect(endDate).not.toBeNull();
    expect(currency).not.toBeNull();
    expect(minimum).not.toBeNull();
    expect(maximum).not.toBeNull();
    if (size.compact) {
      expect(endDate!.y).toBeGreaterThan(startDate!.y);
      expect(currency!.y).toBeLessThan(minimum!.y);
      expect(maximum!.y).toBeGreaterThan(minimum!.y);
      const detailsBounds = await details.boundingBox();
      expect(detailsBounds).not.toBeNull();
      await page.mouse.move(detailsBounds!.x + detailsBounds!.width / 2, detailsBounds!.y + detailsBounds!.height / 2);
      await page.mouse.wheel(0, 1_200);
      await expect(groups[4]).toBeVisible();
      if (size.capture) await captureLedgerScene(page, "filters-grouped-compact-bottom", size.width, size.height);
    } else {
      const groupBounds = await Promise.all(groups.map(async (group) => {
        const bounds = await group.boundingBox();
        expect(bounds).not.toBeNull();
        return bounds!;
      }));
      expect(groupBounds.map((bounds) => bounds.y)).toEqual(
        [...groupBounds.map((bounds) => bounds.y)].sort((left, right) => left - right),
      );
      expect(Math.abs(endDate!.y - startDate!.y)).toBeLessThan(8);
      expect(Math.abs(currency!.y - minimum!.y)).toBeLessThan(8);
      expect(Math.abs(maximum!.y - minimum!.y)).toBeLessThan(8);
    }
    if (size.capture) await captureLedgerScene(page, `filters-grouped-${size.name}`, size.width, size.height);
    const beforeClose = await page.screenshot();
    await clickComposeTarget(page, page.getByRole("button", { name: "完成", exact: true }).last());
    await page.waitForTimeout(300);
    const afterClose = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, beforeClose, afterClose)).toBeGreaterThan(0.03);
  }
});

test("F-03 Escape 先关闭筛选选项菜单，再关闭筛选面板", async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 900, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const field = await openDetailedFilterField(page, "ledger.filter.category");
    const filterPanelBounds = await page.locator('[id="ledger.filter.details"]').last().boundingBox();
    expect(filterPanelBounds).not.toBeNull();
    const filterOpenScreenshot = await page.screenshot();
    const panelSample = {
      x: Math.floor(filterPanelBounds!.x + 8),
      y: Math.floor(filterPanelBounds!.y + 48),
    };
    const openPanelPixel = await screenshotPixel(page, filterOpenScreenshot, panelSample.x, panelSample.y);
    const categoryMenu = page.locator('[id="choice-overlay-menu"]').last();
    await clickComposeUntil(page, field, async () => categoryMenu.isVisible());
    await expect(categoryMenu).toBeVisible();
    const menuBounds = await categoryMenu.boundingBox();
    expect(menuBounds).not.toBeNull();
    const menuOpenScreenshot = await page.screenshot();
    await captureDialogConformanceScene(page, `filter-escape-menu-open-${sizeName}`);

    await page.keyboard.press("Escape");
    await page.waitForTimeout(500);
    const menuClosedScreenshot = await page.screenshot();
    await expect.poll(() => categoryMenu.evaluate((element: HTMLElement) => getComputedStyle(element).pointerEvents)).toBe("none");
    expect(await screenshotDifferenceRatio(page, menuOpenScreenshot, menuClosedScreenshot, 4, 24)).toBeGreaterThan(0.015);
    const panelRemainsPixel = await screenshotPixel(page, menuClosedScreenshot, panelSample.x, panelSample.y);
    const panelPixelDifference = Math.max(
      Math.abs(panelRemainsPixel[0] - openPanelPixel[0]),
      Math.abs(panelRemainsPixel[1] - openPanelPixel[1]),
      Math.abs(panelRemainsPixel[2] - openPanelPixel[2]),
    );
    expect(panelPixelDifference).toBeLessThan(20);
    await captureDialogConformanceScene(page, `filter-escape-dialog-remains-${sizeName}`);

    await page.keyboard.press("Escape");
    await page.waitForTimeout(300);
    const dialogClosedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, filterOpenScreenshot, dialogClosedScreenshot)).toBeGreaterThan(0.03);
    await captureDialogConformanceScene(page, `filter-escape-dialog-closed-${sizeName}`);
  }
});

test("F-03 日期选择只在确认后应用，取消保留原值", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  const dateFilterRequestCount = () => api.calls.filter((call) => call.path.includes("date_from=")).length;
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/`);
    const dateRequestsBeforeCancel = dateFilterRequestCount();
    const details = page.locator('[id="ledger.filter.details"]').last();
    await openDetailedFilterField(page, "ledger.filter.date-from");
    const inlinePicker = details.locator('[id="ledger.filter.date-from-picker"]').last();
    const confirm = page.locator('[id="ledger.filter.date-from-confirm"]').last();
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.filter.date-from-open"]').last(),
      async () => inlinePicker.isVisible(),
    );
    await expect(inlinePicker).toBeVisible();
    await expect(details.locator('[id="ledger.filter.date-from-picker"]')).toHaveCount(1);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const calendarDays = inlinePicker.getByRole("button", { name: /^\d{4}年\d{1,2}月\d{1,2}日星期/ });
    const firstDay = calendarDays.nth(1);
    await expect(firstDay).toBeVisible();
    await clickComposeTarget(page, firstDay);
    await expect(inlinePicker.getByText("当前的选择：无", { exact: true })).toHaveCount(0);
    await expect.poll(dateFilterRequestCount).toBe(dateRequestsBeforeCancel);
    const detailsBounds = await details.boundingBox();
    expect(detailsBounds).not.toBeNull();
    await page.mouse.move(detailsBounds!.x + detailsBounds!.width / 2, detailsBounds!.y + detailsBounds!.height * 0.7);
    await page.mouse.wheel(0, 240);
    await page.waitForTimeout(180);
    await expect(confirm).toBeVisible();
    const cancel = page.locator('[id="ledger.filter.date-from-cancel"]').last();
    await expect(cancel).toBeVisible();
    const pickerBeforeCancel = await page.screenshot();
    await clickInlineDatePickerAction(page, inlinePicker, "cancel");
    const pickerAfterCancel = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, pickerBeforeCancel, pickerAfterCancel)).toBeGreaterThan(0.01);
    await expect.poll(dateFilterRequestCount).toBe(dateRequestsBeforeCancel);
    await expect(details.locator('[id="ledger.filter.date-from"]').last()).toContainText("选择");
    const filtersBeforeEscape = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(350);
    const filtersAfterEscape = await page.screenshot();
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") await captureDialogConformanceScene(page, `filter-date-cancel-escape-${sizeName}`);
    expect(await screenshotDifferenceRatio(page, filtersBeforeEscape, filtersAfterEscape)).toBeGreaterThan(0.03);

    await page.goto(`${workspaceRoot}/`);
    await openDetailedFilterField(page, "ledger.filter.date-from");
    const confirmedPicker = page.locator('[id="ledger.filter.date-from-picker"]').last();
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.filter.date-from-open"]').last(),
      async () => confirmedPicker.isVisible(),
    );
    await expect(confirmedPicker).toBeVisible();
    const confirmedDays = confirmedPicker.getByRole("button", { name: /^\d{4}年\d{1,2}月\d{1,2}日星期/ });
    await clickComposeTarget(page, confirmedDays.nth(1));
    await expect(confirmedPicker.getByText("当前的选择：无", { exact: true })).toHaveCount(0);
    await captureDialogConformanceScene(page, `filter-date-selected-${sizeName}`);
    const confirmedDetails = page.locator('[id="ledger.filter.details"]').last();
    const confirmedDetailsBounds = await confirmedDetails.boundingBox();
    expect(confirmedDetailsBounds).not.toBeNull();
    await page.mouse.move(confirmedDetailsBounds!.x + confirmedDetailsBounds!.width / 2, confirmedDetailsBounds!.y + confirmedDetailsBounds!.height * 0.7);
    await page.mouse.wheel(0, 240);
    await page.waitForTimeout(180);
    await captureDialogConformanceScene(page, `filter-date-confirm-ready-${sizeName}`);
    const dateRequestsBeforeConfirm = dateFilterRequestCount();
    await clickInlineDatePickerAction(page, confirmedPicker, "confirm");
    await expect.poll(dateFilterRequestCount).toBeGreaterThan(dateRequestsBeforeConfirm);
    await expect(confirmedDetails.locator('[id="ledger.filter.date-from"]').last()).toContainText("2026年10月2日");
    await captureDialogConformanceScene(page, `filter-date-confirmed-${sizeName}`);

    const unfilteredRequestsBefore = api.calls.filter((call) => call.path.includes("cash-projections") && !call.path.includes("date_from=")).length;
    const confirmedDateField = confirmedDetails.locator('[id="ledger.filter.date-from"]').last();
    await scrollDialogBodyTo(page, confirmedDetails, confirmedDateField);
    const clearDate = page.getByRole("button", { name: "清除", exact: true }).first();
    await expect(clearDate).toBeVisible();
    await captureDialogConformanceScene(page, `filter-date-clear-visible-${sizeName}`);
    await clearDate.click({ force: true });
    await page.waitForTimeout(100);
    await expect.poll(() => api.calls.filter((call) => call.path.includes("cash-projections") && !call.path.includes("date_from=")).length).toBeGreaterThan(unfilteredRequestsBefore);
    await expect(confirmedDetails.locator('[id="ledger.filter.date-from"]').last()).toContainText("选择");
    const clearedPanel = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(350);
    const clearedPanelClosed = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, clearedPanel, clearedPanelClosed)).toBeGreaterThan(0.03);

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
    await page.waitForTimeout(600);
    const doneClosedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, openScreenshot, doneClosedScreenshot)).toBeGreaterThan(0.02);
    await captureLedgerScene(page, `filters-done-closed-${size.name}`, size.width, size.height);

    await page.goto(`${workspaceRoot}/`);
    await clickComposeUntil(
      page,
      page.locator('[id="ledger.more-filters"]').last(),
      async () => page.getByRole("textbox", { name: "最低金额" }).isVisible(),
    );
    openScreenshot = await page.screenshot();
    await page.keyboard.press("Escape");
    await page.waitForTimeout(600);
    const closedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, openScreenshot, closedScreenshot)).toBeGreaterThan(0.02);
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
  await clickComposeUntil(
    page,
    page.locator('[id="cash-category-create"]').last(),
    async () => page.getByText("新增一级分类", { exact: true }).last().isVisible(),
  );
  await typeIntoComposeInput(page, '[id="cash-category.name"]', "外卖");
  for (const width of responsiveWidths) {
    await page.setViewportSize({ width, height: 844 });
    await page.waitForTimeout(150);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await expect(page.getByText("新增一级分类", { exact: true }).last()).toBeVisible();
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

  let releaseDelete!: () => void;
  let notifyDeleteStarted!: () => void;
  const deleteGate = new Promise<void>((resolve) => { releaseDelete = resolve; });
  const deleteStarted = new Promise<void>((resolve) => { notifyDeleteStarted = resolve; });
  const categoryDeletePath = `/api/v1/cash-categories/${String(api.state.categoryItems.find((item) => item.name === "外卖")?.id)}`;
  const delayedCategoryDelete = async (route: import("@playwright/test").Route) => {
    if (route.request().method() !== "DELETE") return route.fallback();
    notifyDeleteStarted();
    await deleteGate;
    await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: { code: "network_unavailable" } }) });
  };
  await page.route(`**${categoryDeletePath}`, delayedCategoryDelete);
  try {
    await clickComposeTarget(page, page.getByRole("button", { name: "删除", exact: true }).last());
    const confirm = page.locator('[id="cash-category.delete.confirm"]').last();
    const cancel = page.locator('[id="cash-category.delete.cancel"]').last();
    await expect(confirm).toBeVisible();
    await clickComposeTarget(page, confirm);
    await deleteStarted;
    await expect(confirm).toBeEnabled();
    await expect(cancel).toBeEnabled();
    await clickComposeTarget(page, cancel);
    releaseDelete();
    await expect(page.locator('[id="cash-category.delete-error-dialog"]')).toBeVisible();
    await expect(page.getByText("无法删除分类", { exact: true })).toBeVisible();
    await expect(page.getByText(/网络连接失败/).last()).toBeVisible();
    await clickComposeTarget(page, page.locator('[id="cash-category.delete-error-acknowledge"]').last());
  } finally {
    releaseDelete();
    await page.unroute(`**${categoryDeletePath}`, delayedCategoryDelete).catch(() => {});
  }
});

test("F-08 持仓可查看估值详情", async ({ page }) => {
  test.setTimeout(90_000);
  await installComposeApiFixtures(page);
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/investment-holdings`);
    const appleRow = page.locator('[id="investment-holdings.screen-AAPL"]').last();
    await expect(appleRow).toContainText("Apple");
    await expect(page.getByText("当前总市值", { exact: true })).toBeVisible();
    await expect(appleRow).toContainText("+10%");
    await expect(page.getByText(/null%/)).toHaveCount(0);

    const detail = page.locator('[id="investment.position.detail.panel"]').last();
    if (width >= 1024) {
      await appleRow.scrollIntoViewIfNeeded();
      await clickComposeUntil(page, appleRow, async () => detail.isVisible());
    } else {
      const detailAction = page.locator('[id="investment.position.detail.open"]').last();
      for (let attempt = 0; attempt < 8; attempt += 1) {
        const bounds = await detailAction.boundingBox().catch(() => null);
        if (await detailAction.isVisible().catch(() => false) && bounds && bounds.y >= 0 && bounds.y + bounds.height <= height) break;
        await page.mouse.move(width / 2, height * 0.75);
        await page.mouse.wheel(0, Math.max(420, Math.round(height * 0.65)));
        await page.waitForTimeout(100);
      }
      await clickVisibleComposeTargetUntil(
        page,
        page.locator('[id="investment.position.detail.open"]'),
        async () => detail.isVisible(),
      );
    }
    await expect(detail).toBeVisible();
    await expect(page.getByText("估值状态", { exact: true }).last()).toContainText("估值状态");
    await expect(page.getByText("行情完整", { exact: true }).last()).toContainText("行情完整");
    await expect(page.locator('[id="investment.position.detail.close"]').last()).toBeVisible();
    const positionCloseBounds = await page.locator('[id="investment.position.detail.close"]').last().boundingBox();
    const positionTitleBounds = await page.locator('[id="investment.position.detail.title"]').last().boundingBox();
    expect(positionCloseBounds).not.toBeNull();
    expect(positionTitleBounds).not.toBeNull();
    expect(positionCloseBounds!.x + positionCloseBounds!.width).toBeLessThanOrEqual(positionTitleBounds!.x + 1);
    const bounds = await detail.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.width).toBeLessThanOrEqual(560);
    expect(bounds!.x).toBeGreaterThanOrEqual(16);
    expect(bounds!.y).toBeGreaterThanOrEqual(16);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
      await mkdir(dialogConformanceScreenshotDir, { recursive: true });
      await page.screenshot({ path: path.join(dialogConformanceScreenshotDir, `investment-position-${sizeName}.png`) });
    }
    const detailBeforeClose = await page.screenshot();
    await clickComposeTarget(page, page.locator('[id="investment.position.detail.close"]').last());
    await page.waitForTimeout(250);
    const listAfterClose = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, detailBeforeClose, listAfterClose)).toBeGreaterThan(0.03);
  }

  if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
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
  for (const [width, height, sizeName] of [[390, 844, "compact"], [768, 1024, "regular"], [1440, 1000, "wide"]] as const) {
    await page.setViewportSize({ width, height });
    await page.goto(`${workspaceRoot}/investment-events`);
    const openDatePicker = page.getByRole("button", { name: "选择", exact: true }).first();
    await clickComposeTarget(page, openDatePicker);
    const datePickerClose = page.locator('[id="date-picker-close"]').last();
    const datePickerHeaderControl = page.getByRole("button", { name: "切换到文本字段输入模式", exact: true }).last();
    await expect(datePickerClose).toBeVisible();
    await expect(datePickerHeaderControl).toBeVisible();
    await page.waitForTimeout(300);
    const datePickerCloseBounds = await datePickerClose.boundingBox();
    const datePickerHeaderBounds = await datePickerHeaderControl.boundingBox();
    expect(datePickerCloseBounds).not.toBeNull();
    expect(datePickerHeaderBounds).not.toBeNull();
    expect(datePickerCloseBounds!.x).toBeLessThan(datePickerHeaderBounds!.x);
    expect(datePickerCloseBounds!.y).toBeLessThan(datePickerHeaderBounds!.y);
    await captureDialogConformanceScene(page, `investment-date-picker-open-${sizeName}`);
    const datePickerOpenScreenshot = await page.screenshot();
    const datePickerOpenCorner = await screenshotPixel(page, datePickerOpenScreenshot, 8, 8);
    await clickComposeTargetByClientRect(page, datePickerClose);
    await page.waitForTimeout(200);
    const datePickerClosedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, datePickerOpenScreenshot, datePickerClosedScreenshot)).toBeGreaterThan(0.03);
    const datePickerClosedCorner = await screenshotPixel(page, datePickerClosedScreenshot, 8, 8);
    expect(Math.max(
      Math.abs(datePickerOpenCorner[0] - datePickerClosedCorner[0]),
      Math.abs(datePickerOpenCorner[1] - datePickerClosedCorner[1]),
      Math.abs(datePickerOpenCorner[2] - datePickerClosedCorner[2]),
    )).toBeGreaterThan(12);
    await captureDialogConformanceScene(page, `investment-date-picker-closed-${sizeName}`);
    // Compose Web can retain the previous dialog's semantic mirror after the
    // visual close. Reload the same route before asserting page content.
    await page.reload();
    await expect(page.locator('[id="investment-events.screen"]').last()).toBeVisible();
    await expect(page.getByText("买入苹果", { exact: true })).toBeVisible();
    await clickComposeTarget(page, page.getByRole("button", { name: "查看详情", exact: true }).last());
    const detail = page.locator('[id="investment.event.detail"]').last();
    await expect(detail).toBeVisible();
    await expect(page.getByText("发生时间", { exact: true }).last()).toBeVisible();
    await expect(page.locator('[id="investment.event.detail.close"]').last()).toBeVisible();
    const detailCloseBounds = await page.locator('[id="investment.event.detail.close"]').last().boundingBox();
    const detailTitleBounds = await page.locator('[id="investment.event.detail.title"]').last().boundingBox();
    expect(detailCloseBounds).not.toBeNull();
    expect(detailTitleBounds).not.toBeNull();
    expect(detailCloseBounds!.x + detailCloseBounds!.width).toBeLessThanOrEqual(detailTitleBounds!.x + 1);
    const bounds = await detail.boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.width).toBeLessThanOrEqual(560);
    expect(bounds!.x).toBeGreaterThanOrEqual(16);
    expect(bounds!.y).toBeGreaterThanOrEqual(16);
    if (process.env.FT_CAPTURE_COMPOSE_SCREENSHOTS === "1") {
      await mkdir(dialogConformanceScreenshotDir, { recursive: true });
      await page.screenshot({ path: path.join(dialogConformanceScreenshotDir, `investment-event-${sizeName}.png`) });
    }
    const dialogOpenScreenshot = await page.screenshot();
    await clickComposeTarget(page, page.locator('[id="investment.event.detail.close"]').last());
    await page.waitForTimeout(250);
    const dialogClosedScreenshot = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, dialogOpenScreenshot, dialogClosedScreenshot)).toBeGreaterThan(0.03);
  }

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
  const detailOpenScreenshot = await page.screenshot();
  await page.getByRole("button", { name: "关闭", exact: true }).last().click({ force: true });
  await page.waitForTimeout(250);
  const detailClosedScreenshot = await page.screenshot();
  expect(await screenshotDifferenceRatio(page, detailOpenScreenshot, detailClosedScreenshot)).toBeGreaterThan(0.03);
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
  await clickComposeTarget(page, page.locator('[id="navigation-item-investment-holdings"]').last());
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

test("F-10 工作区删除确认处理中保留取消，关闭后仍显示失败原因", async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  let releaseDelete!: () => void;
  let notifyDeleteStarted!: () => void;
  const deleteGate = new Promise<void>((resolve) => { releaseDelete = resolve; });
  const deleteStarted = new Promise<void>((resolve) => { notifyDeleteStarted = resolve; });
  let deleteCount = 0;
  const delayedDelete = async (route: import("@playwright/test").Route) => {
    if (route.request().method() !== "DELETE") return route.fallback();
    deleteCount += 1;
    notifyDeleteStarted();
    await deleteGate;
    await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: { code: "network_unavailable" } }) });
  };
  await page.route("**/api/v1/auth/workspace", delayedDelete);
  try {
    await page.setViewportSize({ width: 390, height: 2000 });
    await page.goto(`${workspaceRoot}/workspace-management`);
    await clickComposeUntil(
      page,
      page.locator('[id="workspace.management.delete"]').last(),
      async () => page.locator('[id="workspace.management.delete-name"]').isVisible(),
    );
    const name = page.locator('[id="workspace.management.delete-name"]').last();
    const confirm = page.locator('[id="workspace.management.delete-confirm"]').last();
    const cancel = page.locator('[id="workspace.management.delete-cancel"]').last();
    const deleteCallsBeforeInput = api.calls.filter((call) => call.method === "DELETE" && call.path === "/api/v1/auth/workspace").length;
    await clickComposeTarget(page, confirm);
    expect(api.calls.filter((call) => call.method === "DELETE" && call.path === "/api/v1/auth/workspace")).toHaveLength(deleteCallsBeforeInput);
    await typeIntoComposeInput(page, '[id="workspace.management.delete-name"]', activeSession.workspaces[0].name, { replace: true });
    await expect(confirm).toBeEnabled();
    await clickComposeTarget(page, confirm);
    await deleteStarted;
    await expect(confirm).toBeEnabled();
    await expect(cancel).toBeEnabled();
    await expect(name).toBeVisible();
    const confirmationWhileBusy = await page.screenshot();
    await clickComposeTarget(page, confirm);
    await page.waitForTimeout(160);
    expect(deleteCount).toBe(1);
    await clickComposeTarget(page, cancel);
    const confirmationCancelled = await page.screenshot();
    expect(await screenshotDifferenceRatio(page, confirmationWhileBusy, confirmationCancelled)).toBeGreaterThan(0.01);
    releaseDelete();
    await expect(page.locator('[id="workspace.management.delete-error-dialog"]')).toBeVisible();
    await expect(page.getByText("无法删除工作区", { exact: true })).toBeVisible();
    await expect(page.getByText(/网络连接失败/).last()).toBeVisible();
    await clickComposeTarget(page, page.locator('[id="workspace.management.delete-error-acknowledge"]').last());
  } finally {
    releaseDelete();
    await page.unroute("**/api/v1/auth/workspace", delayedDelete).catch(() => {});
  }
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
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1);
  const chooser = page.waitForEvent("filechooser", { timeout: 1200 }).catch(() => null);
  await clickComposeTarget(page, page.locator('[id="cash.import.add-files"]:visible').last());
  expect(await chooser).toBeNull();
  expect(api.calls.some(call => call.method === "POST" && call.path.startsWith("/api/v1/cash-import/sessions"))).toBe(false);

  await page.goto(`${workspaceRoot}/cash-categories`);
  await expect(page.locator('[id="cash-categories.screen"]').last()).toBeVisible();
  await page.locator('[id="cash-category-create"]').last().dispatchEvent("click");
  await expect(page.locator('[id="cash-category.name"]')).toHaveCount(0);
  expect(api.calls.some((call) => call.method === "POST" && call.path === "/api/v1/cash-categories")).toBe(false);
});

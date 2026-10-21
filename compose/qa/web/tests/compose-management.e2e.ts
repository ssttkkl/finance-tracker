import { test, expect, type Page, type Locator } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { activeSession, installComposeApiFixtures, typeIntoComposeInput, captureBrowserDiagnostics } from './compose-fixtures';

const root = '/w/workspace-1';
const id = (page: Page, value: string) => page.locator(`[id="${value}"]`).last();
const evidence = path.resolve('..', 'local-evidence', 'unify-management-settings');

const backgroundPixel = (page: Page) => page.screenshot({ clip: { x: page.viewportSize()!.width - 40, y: 16, width: 24, height: 24 } });
const markerClip = (box: { x: number; y: number; height: number }) => ({
  x: Math.round(box.x) - 2, y: Math.round(box.y + (box.height - 24) / 2) + 2, width: 1, height: 20,
});

async function click(page: Page, target: Locator) {
  await expect(target).toBeVisible();
  const box = await target.boundingBox();
  if (!box) throw new Error('Missing Compose target geometry');
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(100);
}

async function directoryMarker(page: Page, key: string) {
  const box = (await id(page, `management.directory.item.${key}`).boundingBox())!;
  return page.screenshot({ clip: markerClip(box) });
}

async function openDirectory(page: Page) {
  const trigger = id(page, 'management.directory.open');
  if (await trigger.isVisible().catch(() => false)) await click(page, trigger);
  else {
    // Compose Web may retain a disposed Dialog semantics mirror after Escape.
    // Exercise the visibly rendered Toolbar action, as in the existing import QA.
    await page.mouse.click(page.viewportSize()!.width - 28, 28);
  }
  await expect(id(page, 'management.directory.dialog')).toBeVisible();
}

async function openEditor(page: Page, category: string) {
  for (let n = 0; n < 3; n++) {
    await click(page, id(page, `cash-category.edit.${category}`));
    try {
      await expect(id(page, 'cash-category.editor')).toBeVisible({ timeout: 1_500 });
      return;
    } catch {
      if (!(await id(page, `cash-category.edit.${category}`).isVisible().catch(() => false))) {
        await expect(id(page, 'cash-category.editor')).toBeVisible();
        return;
      }
    }
  }
  await expect(id(page, 'cash-category.editor')).toBeVisible();
}

async function section(page: Page, key: string) {
  if (page.viewportSize()!.width < 600) {
    await openDirectory(page);
    await expect(id(page, 'management.directory.dialog')).toBeVisible();
  }
  await click(page, id(page, `management.directory.item.${key}`));
  await expect(id(page, 'management.directory.dialog')).toHaveCount(0);
  await expect.poll(async () => (await id(page, `management.section.${key}`).boundingBox())?.y).toBeLessThan(120);
}

test('管理共用目录在三类窗口定位分组，手机使用全屏目录', async ({ page }) => {
  test.setTimeout(120_000);
  const diagnostics = captureBrowserDiagnostics(page);
  await installComposeApiFixtures(page);
  await mkdir(evidence, { recursive: true });
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${root}/workspace-management`);
    await expect(id(page, 'workspace-management.name')).toBeVisible();
    await expect(id(page, 'destination.top-bar-title')).toHaveText('管理');
    await expect(id(page, 'navigation-item-cash-categories')).toHaveCount(0);
    await page.screenshot({ path: path.join(evidence, `compose-management-${width}.png`) });
    if (width === 390) {
      await openDirectory(page);
      const box = await id(page, 'management.directory.dialog').boundingBox();
      expect(box?.x).toBeCloseTo(0, 0);
      expect(box?.width).toBeCloseTo(width, 0);
      expect(box?.height).toBeCloseTo(900, 0);
      await page.screenshot({ path: path.join(evidence, `compose-directory-${width}.png`) });
      const categories = (await id(page, 'management.directory.item.categories').boundingBox())!;
      const workspace = (await id(page, 'management.directory.item.workspace').boundingBox())!;
      const marker = markerClip;
      const selected = await page.screenshot({ clip: marker(workspace) });
      await click(page, id(page, 'management.directory.item.categories'));
      await expect(id(page, 'management.directory.dialog')).toHaveCount(0);
      await page.waitForTimeout(350);
      await page.screenshot({ path: path.join(evidence, `compose-categories-${width}.png`) });
      // CMP-10623: disposing a Popup stops the mirrored DOM syncing. Verify
      // the actual Canvas action and active marker without treating stale DOM as UI.
      await page.mouse.click(width - 28, 28);
      await page.waitForTimeout(200);
      await page.screenshot({ path: path.join(evidence, 'compose-directory-selected-390.png') });
      expect((await page.screenshot({ clip: marker(categories) })).equals(selected)).toBe(true);
      await page.keyboard.press('Escape');
    } else {
      await expect(id(page, 'management.directory')).toBeVisible();
      const selected = await directoryMarker(page, 'workspace');
      await section(page, 'categories');
      expect((await directoryMarker(page, 'categories')).equals(selected)).toBe(true);
      await page.waitForTimeout(350);
      await page.screenshot({ path: path.join(evidence, `compose-categories-${width}.png`) });
      await section(page, 'danger');
      await expect(id(page, 'workspace.management.delete')).toBeVisible();
      await section(page, 'workspace');
    }
    await page.goto(`${root}/cash-categories`);
    await expect(id(page, 'cash-category.search')).toBeVisible();
    const background = await backgroundPixel(page);
    await openEditor(page, 'coffee');
    const editor = id(page, 'cash-category.editor');
    const box = (await editor.boundingBox())!;
    if (width === 390) { expect(box.x).toBeCloseTo(0, 0); expect(box.width).toBeCloseTo(width, 0); }
    else { expect(box.width).toBeLessThanOrEqual(560); expect(box.height).toBeLessThan(900); }
    await page.waitForTimeout(350);
    await page.screenshot({ path: path.join(evidence, `compose-category-editor-${width}.png`) });
    await page.keyboard.press('Escape');
    await expect.poll(async () => (await backgroundPixel(page)).equals(background)).toBe(true);
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

test('旧分类链接打开管理页，分类编辑保留搜索并调用原 API', async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto(`${root}/workspace-management`);
  await expect(id(page, 'workspace-management.name')).toBeVisible();
  const selected = await directoryMarker(page, 'workspace');
  await page.goto(`${root}/cash-categories`);
  await expect(id(page, 'destination.top-bar-title')).toHaveText('管理');
  await expect.poll(async () => (await directoryMarker(page, 'categories')).equals(selected)).toBe(true);
  await expect(id(page, 'cash-category.search')).toBeVisible();
  await typeIntoComposeInput(page, '[id="cash-category.search"]', '餐饮');
  await expect(id(page, 'cash-category.search')).toContainText('餐饮');
  await page.keyboard.press('Tab');
  await page.waitForTimeout(350);
  const searchBox = (await id(page, 'cash-category.search').boundingBox())!;
  const searchClip = { x: searchBox.x + 16, y: searchBox.y + 18, width: 120, height: 22 };
  const searchBefore = await page.screenshot({ clip: searchClip });
  const background = await backgroundPixel(page);
  await openEditor(page, 'coffee');
  await typeIntoComposeInput(page, '[id="cash-category.name"]', '工作餐', { replace: true });
  await click(page, id(page, 'cash-category.save'));
  await expect.poll(async () => (await backgroundPixel(page)).equals(background)).toBe(true);
  await expect.poll(() => api.calls.some(c => c.method === 'PATCH' && c.path === '/api/v1/cash-categories/coffee')).toBe(true);
  expect((await page.screenshot({ clip: searchClip })).equals(searchBefore)).toBe(true);
  await page.screenshot({ path: path.join(evidence, 'compose-category-saved-search-768.png') });
  await page.goto(`${root}/`); await page.goBack();
  await expect(id(page, 'destination.top-bar-title')).toHaveText('管理');
  await expect(id(page, 'cash-category.search')).toBeVisible();
});

for (const role of ['editor', 'viewer'] as const) {
  test(`管理角色 ${role} 保持分组权限`, async ({ page }) => {
    await installComposeApiFixtures(page, { initialSession: { ...activeSession, workspaces: [{ ...activeSession.workspaces[0], role }] } });
    await page.goto(`${root}/cash-categories`);
    await expect(id(page, 'cash-category.search')).toBeVisible();
    await expect(id(page, 'management.section.danger')).toHaveCount(0);
    await expect(id(page, 'management.directory.item.danger')).toHaveCount(0);
    if (role === 'viewer') {
      await click(page, id(page, 'cash-category.edit.coffee'));
      await expect(id(page, 'cash-category.editor')).toHaveCount(0);
    }
    else {
      await expect(id(page, 'cash-category.edit.coffee')).toBeEnabled();
      await openEditor(page, 'coffee');
      await expect(id(page, 'cash-category.editor')).toBeVisible();
    }
  });
}

test('管理分类空状态和请求错误不阻断工作区', async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  api.state.categoryItems = [];
  await page.setViewportSize({ width: 768, height: 900 });
  await page.goto(`${root}/cash-categories`);
  await expect(id(page, 'cash-categories.screen')).toContainText('还没有分类');
  await expect(id(page, 'cash-category-create')).toBeEnabled();
  await page.route('**/api/v1/cash-categories', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'network_unavailable' } }) }));
  await page.reload();
  await expect(id(page, 'cash-categories.screen')).toContainText('网络连接失败');
  await section(page, 'workspace');
  await expect(id(page, 'workspace-management.name')).toBeEnabled();
  await page.screenshot({ path: path.join(evidence, 'compose-category-error-768.png') });
});

test('管理目录随手动滚动高亮，工作区失败不阻断分类', async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`${root}/workspace-management`);
  await expect(id(page, 'workspace-management.name')).toBeVisible();
  const selected = await directoryMarker(page, 'workspace');
  await page.mouse.move(650, 650);
  await page.mouse.wheel(0, 1200);
  await expect.poll(async () => (await directoryMarker(page, 'workspace')).equals(selected)).toBe(false);
  await page.mouse.wheel(0, -5000);
  await expect.poll(async () => (await directoryMarker(page, 'workspace')).equals(selected)).toBe(true);
  await page.route('**/api/v1/auth/workspace', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'network_unavailable' } }) }));
  await page.goto(`${root}/cash-categories`);
  await expect(id(page, 'cash-category.search')).toBeVisible();
  await openEditor(page, 'coffee');
  await expect(id(page, 'cash-category.name')).toBeVisible();
});

test('分类保存失败保留当前任务，保存中阻止退出和重复提交', async ({ page }) => {
  const api = await installComposeApiFixtures(page);
  await page.setViewportSize({ width: 768, height: 900 });
  let release!: () => void;
  let saving = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/cash-categories/coffee', async route => {
    if (route.request().method() !== 'PATCH') return route.fallback();
    saving++;
    await gate;
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'network_unavailable' } }) });
  });
  try {
    await page.goto(`${root}/cash-categories`);
    await expect(id(page, 'cash-category.search')).toBeVisible();
    await openEditor(page, 'coffee');
    await typeIntoComposeInput(page, '[id="cash-category.name"]', '未保存分类', { replace: true });
    await click(page, id(page, 'cash-category.save'));
    await expect.poll(() => saving).toBe(1);
    await click(page, id(page, 'cash-category.editor.close'));
    await page.keyboard.press('Escape');
    await expect(id(page, 'cash-category.editor')).toBeVisible();
    await click(page, id(page, 'cash-category.save'));
    expect(saving).toBe(1);
    release();
    await expect(id(page, 'cash-category.editor')).toContainText('网络连接失败');
    await expect(id(page, 'cash-category.name')).toContainText('未保存分类');
    await page.screenshot({ path: path.join(evidence, 'compose-category-save-error-768.png') });
    expect(api.calls.filter(call => call.method === 'PATCH')).toHaveLength(0);
  } finally { release(); }
});

test('管理分类空状态之外的加载过程与旧规格小屏边界', async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const width of [320, 375, 414]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${root}/workspace-management`);
    await expect(id(page, 'workspace-management.name')).toBeVisible();
    const field = (await id(page, 'workspace-management.name').boundingBox())!;
    expect(field.x).toBeGreaterThanOrEqual(0);
    expect(field.x + field.width).toBeLessThanOrEqual(width);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  let release!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/v1/cash-categories', async route => { await gate; await route.fallback(); });
  try {
    await page.setViewportSize({ width: 768, height: 900 });
    await page.goto(`${root}/workspace-management`);
    await expect(id(page, 'workspace-management.name')).toBeEnabled();
    await section(page, 'categories');
    await expect(id(page, 'cash-categories.screen')).toContainText('正在读取分类');
    await page.screenshot({ path: path.join(evidence, 'compose-category-loading-768.png') });
    release();
    await expect(id(page, 'cash-category.search')).toBeVisible();
  } finally { release(); }
});

test('管理局部操作遵循表单布局，三种窗口保持文字与分组层级', async ({ page }) => {
  await installComposeApiFixtures(page);
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${root}/workspace-management`);
    await expect(id(page, 'workspace-management.name')).toBeVisible();
    const name = (await id(page, 'workspace-management.name').boundingBox())!;
    const save = (await id(page, 'management.save-name').boundingBox())!;
    if (width < 600) expect(save.y).toBeGreaterThanOrEqual(name.y + name.height);
    else {
      expect(save.x).toBeGreaterThanOrEqual(name.x + name.width);
      expect(save.y + save.height / 2).toBeGreaterThanOrEqual(name.y);
      expect(save.y + save.height / 2).toBeLessThanOrEqual(name.y + name.height);
    }
    await page.screenshot({ path: path.join(evidence, `typography-management-${width}.png`) });
    await page.mouse.move(width < 600 ? width / 2 : 300, 500);
    await page.mouse.wheel(0, 650);
    await expect.poll(async () => (await id(page, 'workspace.management.invite').boundingBox())?.width ?? 0).toBeGreaterThan(0);
    const invite = (await id(page, 'workspace.management.invite').boundingBox())!;
    expect(invite.width).toBeLessThan(300);
  }
});

test('管理两级目录保持归属、父级定位与二级缩进', async ({ page }) => {
  test.setTimeout(90_000);
  await installComposeApiFixtures(page);
  await mkdir(evidence, { recursive: true });
  for (const width of [390, 768, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`${root}/workspace-management`);
    await expect(id(page, 'workspace-management.name')).toBeVisible();
    await expect(id(page, 'management.group.workspace')).toHaveText('工作区资料');
    if (width === 390) await openDirectory(page);
    await page.waitForTimeout(350);
    const box = async (key: string) => (await id(page, key).boundingBox())!;
    const workspace = await box('management.directory.group.workspace');
    const basic = await box('management.directory.item.workspace');
    const danger = await box('management.directory.item.danger');
    const access = await box('management.directory.group.access');
    const members = await box('management.directory.item.members');
    const invitations = await box('management.directory.item.invitations');
    const cash = await box('management.directory.group.cash-ledger');
    const categories = await box('management.directory.item.categories');
    expect(basic.x).toBeGreaterThan(workspace.x);
    expect(members.x).toBeGreaterThan(access.x);
    expect(categories.x).toBeGreaterThan(cash.x);
    expect(workspace.y).toBeLessThan(basic.y);
    expect(basic.y).toBeLessThan(danger.y);
    expect(danger.y).toBeLessThan(access.y);
    expect(access.y).toBeLessThan(members.y);
    expect(members.y).toBeLessThan(invitations.y);
    expect(invitations.y).toBeLessThan(cash.y);
    expect(cash.y).toBeLessThan(categories.y);
    await expect(id(page, 'management.directory.item.workspace')).toHaveText('基本信息');
    await expect(page.locator('[id^="management.directory.group."]')).toHaveCount(3);
    const selected = await directoryMarker(page, 'workspace');
    await writeFile(path.join(evidence, `hierarchy-marker-baseline-${width}.png`), selected);
    await page.screenshot({ path: path.join(evidence, `hierarchy-directory-${width}.png`) });
    await click(page, id(page, 'management.directory.group.access'));
    if (width === 390) {
      await expect(id(page, 'management.directory.dialog')).toHaveCount(0);
      await page.waitForTimeout(350);
      await page.screenshot({ path: path.join(evidence, `hierarchy-access-${width}.png`) });
      await page.mouse.click(width - 28, 28);
      await page.waitForTimeout(200);
      await page.screenshot({ path: path.join(evidence, 'hierarchy-directory-reopened-390.png') });
      await writeFile(path.join(evidence, 'hierarchy-marker-members-390.png'), await directoryMarker(page, 'members'));
      await expect.poll(async () => (await directoryMarker(page, 'members')).equals(selected)).toBe(true);
      await page.keyboard.press('Escape');
    } else {
      await expect.poll(async () => (await id(page, 'management.section.members').boundingBox())?.y).toBeLessThan(120);
      await expect.poll(async () => (await directoryMarker(page, 'members')).equals(selected)).toBe(true);
      await page.screenshot({ path: path.join(evidence, `hierarchy-access-${width}.png`) });
      await section(page, 'danger');
      await section(page, 'categories');
      await click(page, id(page, 'management.directory.group.workspace'));
      await expect.poll(async () => (await id(page, 'management.group.workspace').boundingBox())?.y).toBeLessThan(120);
    }
  }
});

import { expect, test, type Page } from "@playwright/test";
import { captureBrowserDiagnostics, clickStableComposeTarget, typeIntoComposeInput } from "./compose-fixtures";

async function press(page: Page, id: string) {
  await clickStableComposeTarget(page, page.locator(`[id="${id}"]:visible`).last());
}

async function pull(page: Page) {
  const list = page.locator('[id="ledger.list"]:visible').last();
  let box = await list.boundingBox();
  let stable = 0;
  await expect.poll(async () => {
    const next = await list.boundingBox();
    const width = page.viewportSize()!.width;
    stable = next && box && next.width <= width && ["x", "y", "width", "height"].every(
      key => Math.abs(next[key as keyof typeof next] - box![key as keyof typeof box]) <= 1,
    ) ? stable + 1 : 0;
    box = next;
    return stable >= 3;
  }, { intervals: [100] }).toBe(true);
  if (!box) throw new Error("The real ledger list must be visible");
  await page.mouse.move(box.x + box.width / 2, box.y + 24);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width / 2, box.y + 230, { steps: 16 });
  await page.mouse.up();
}

async function focusByKeyboard(page: Page, id: string, unfocused: Buffer, margin = 0) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  const box = await target.boundingBox();
  if (!box) throw new Error(`Missing keyboard target ${id}`);
  const clip = { x: box.x - margin, y: box.y - margin, width: box.width + margin * 2, height: box.height + margin * 2 };
  for (let attempt = 0; attempt < 24; attempt++) {
    await page.keyboard.press("Tab");
    await page.waitForTimeout(150);
    if (!(await page.screenshot({ clip })).equals(unfocused)) return;
  }
  throw new Error(`No rendered keyboard focus reached ${id}`);
}

test("真实本地账本统一在线详情与维护，刷新保留筛选并恢复离线读取", async ({ page, context, baseURL }, info) => {
  test.setTimeout(180_000);
  await page.setViewportSize({ width: 1440, height: 1100 });
  const diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404, 503] });
  const email = `local-ledger-${crypto.randomUUID()}@example.invalid`;
  const password = `Ledger-${crypto.randomUUID()}`;
  const registered = await page.request.post(`${baseURL}/api/v1/auth/register`, { data: { email, password } });
  expect(registered.status()).toBe(200);
  const { access_token: token } = await registered.json();
  const headers = { Authorization: `Bearer ${token}` };
  const created = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, { headers, data: { name: "本地账本验证" } });
  expect(created.status()).toBe(200);
  const workspace = (await created.json()).active_workspace_id;
  const account = await page.request.post(`${baseURL}/api/v1/accounts`, {
    headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
    data: { name: "账本验证现金", type: "cash", currencies: ["CNY"] },
  });
  expect(account.status()).toBe(201);
  const category = await page.request.post(`${baseURL}/api/v1/cash-categories`, { headers, data: { name: "验证分类" } });
  expect(category.status()).toBe(201);
  const createRow = async (counterparty: string) => {
    const response = await page.request.post(`${baseURL}/api/v1/cash-records`, { headers, data: {
      account_name: "账本验证现金", amount: "-12.5000", currency: "CNY", occurred_at: "2026-11-01T08:00:00Z",
      counterparty, note: "来源备注", record_type: "consumption", record_subtype: "not_applicable",
    } });
    expect(response.status()).toBe(201);
  };
  await createRow("本地详情商户"); await createRow("本地删除商户");

  const retiredReads: string[] = [];
  const commands: { path: string; workspace?: string; body: unknown }[] = [];
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (request.method() === "GET" && (path.startsWith("/api/v1/cash-projections")
      || path.startsWith("/api/v1/evidence/cash-projections/") || /^\/api\/v1\/cash-records\/[^/]+$/.test(path))) retiredReads.push(path);
    if (["POST", "PUT", "DELETE"].includes(request.method()) && (path.startsWith("/api/v1/cash-projections") || path.startsWith("/api/v1/cash-mutations/plans"))) {
      commands.push({ path, workspace: request.headers()["x-ft-workspace-id"], body: request.postDataJSON() });
    }
  });
  await page.goto(`/w/${workspace}/`);
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', password);
  await press(page, "auth.submit");
  const rows = page.locator('[id^="ledger.record-cash-entry:"]:visible');
  await expect(rows).toHaveCount(2, { timeout: 30_000 });
  const first = rows.filter({ hasText: "本地详情商户" });
  const firstId = (await first.getAttribute("id"))!;
  const secondId = (await rows.filter({ hasText: "本地删除商户" }).getAttribute("id"))!;
  await press(page, firstId);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("来源备注");
  await press(page, "cash.current.edit");
  await typeIntoComposeInput(page, '[id="cash.entry.note"]', "缓存维护备注", { replace: true });
  await page.mouse.click(1200, 42);
  const committed = page.waitForResponse(response => /\/cash-mutations\/plans\/[^/]+\/commit$/.test(new URL(response.url()).pathname));
  await press(page, "cash.entry.save");
  const confirm = page.locator('[id="cash.maintenance.confirm"]:visible').last();
  const commitPath = await Promise.race([
    committed.then(() => "committed"),
    confirm.waitFor({ state: "visible", timeout: 15_000 }).then(() => "confirmation"),
  ]);
  if (commitPath === "confirmation") await clickStableComposeTarget(page, confirm);
  expect((await committed).status()).toBe(200);
  await expect(page.locator('[id="cash.entry.editor"]:visible')).toHaveCount(0);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("缓存维护备注");
  const beforeClose = await page.screenshot();
  await press(page, "ledger.record-panel.close");
  await page.waitForTimeout(300);
  const afterClose = await page.screenshot({ path: info.outputPath("local-ledger-after-edit-1440.png") });
  expect(afterClose.equals(beforeClose), "The rendered detail panel must close").toBe(false);
  // Rebuild the Compose dialog semantics mirror after the rendered panel closes.
  await page.reload();
  await expect(rows).toHaveCount(2);
  await expect(page.locator(`[id="${firstId}"]:visible`)).toContainText("缓存维护备注");

  await press(page, firstId.replace("ledger.record-", "ledger.record-checkbox-"));
  await expect(page.locator('[id="ledger.selected-count"]:visible')).toContainText("1");
  await press(page, "ledger.batch-category");
  const applyBounds = await page.locator('[id="ledger.category.apply"]:visible').last().boundingBox();
  expect(applyBounds).not.toBeNull();
  await press(page, "ledger.category-picker");
  await clickStableComposeTarget(page, page.locator('[id="choice-overlay-menu"]:visible').last().getByText("验证分类", { exact: true }));
  const classified = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/cash-projections/categories");
  await page.waitForTimeout(250);
  // The popup exporter can dispose the parent mirror; click its measured canvas action.
  await page.mouse.click(applyBounds!.x + applyBounds!.width / 2, applyBounds!.y + applyBounds!.height / 2);
  expect((await classified).status()).toBe(200);
  await page.reload();
  await expect(page.locator(`[id="${firstId}"]:visible`)).toContainText("验证分类");
  await press(page, secondId.replace("ledger.record-", "ledger.record-checkbox-"));
  await expect(page.locator('[id="ledger.selected-count"]:visible')).toContainText("1");
  await press(page, "ledger.batch-delete");
  await expect(page.locator('[id="ledger.delete-projection.dialog"]:visible')).toBeVisible();
  const deleted = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/cash-projections" && response.request().method() === "DELETE");
  await press(page, "ledger.delete-projection.confirm");
  expect((await deleted).status()).toBe(200);
  await page.reload();
  await expect(rows).toHaveCount(1);

  await createRow("其他设备新增商户");
  await typeIntoComposeInput(page, '[id="ledger.search"]', "商户", { replace: true });
  let versionCalls = 0; let releaseVersion: (() => void) | undefined;
  await context.route("**/api/v1/ledger/version?**", async route => {
    versionCalls++;
    await new Promise<void>(resolve => { releaseVersion = resolve; });
    await route.continue();
  });
  const refresh = page.locator('[id="ledger.refresh"]:visible').last();
  await expect(refresh).toHaveCount(0);
  await expect(rows).toHaveCount(1);
  await page.mouse.move(700, 400);
  await page.waitForTimeout(350);
  const fabBox = (await page.locator('[id="ledger.actions-fab"]:visible').boundingBox())!;
  const unfocusedFab = await page.screenshot({ clip: { x: fabBox.x - 12, y: fabBox.y - 12, width: fabBox.width + 24, height: fabBox.height + 24 } });
  await focusByKeyboard(page, "ledger.actions-fab", unfocusedFab, 12);
  await page.keyboard.press("Enter");
  await expect(refresh).toHaveText("刷新");
  // The existing menu traverses Add, Import, then Refresh from its focused FAB.
  for (let i = 0; i < 3; i++) await page.keyboard.press("Tab");
  await page.screenshot({ path: info.outputPath("local-ledger-refresh-keyboard-focus-1440.png") });
  await page.keyboard.press("Enter");
  await expect.poll(() => versionCalls).toBe(1);
  await press(page, "ledger.actions-fab");
  await expect(refresh).toHaveCount(0);
  await press(page, "ledger.actions-fab");
  await pull(page); expect(versionCalls).toBe(1);
  await page.screenshot({ path: info.outputPath("local-ledger-sync-pending-1440.png") });
  releaseVersion!();
  await expect(rows).toHaveCount(2);
  await context.unroute("**/api/v1/ledger/version?**");
  await expect(page.locator('[id="ledger.search"]:visible').last()).toContainText("商户");

  let explicitFailures = 0;
  await context.route("**/api/v1/ledger/version?**", route => {
    explicitFailures++;
    return route.fulfill({ status: 503, json: { error: { code: "projection.unavailable", message: "合成的读取失败" } } });
  });
  await pull(page);
  await expect(page.locator('[id="ledger.retry"]:visible')).toBeVisible();
  expect(explicitFailures).toBe(1);
  await page.screenshot({ path: info.outputPath("local-ledger-sync-error-1440.png") });
  await context.unroute("**/api/v1/ledger/version?**");
  await press(page, "ledger.retry");
  await expect(rows).toHaveCount(2);
  await expect(page.locator('[id="ledger.search"]:visible').last()).toContainText("商户");

  let outages = 0;
  await context.route("**/api/v1/ledger/version?**", route => { outages++; return route.abort("failed"); });
  await page.setViewportSize({ width: 390, height: 844 });
  await pull(page); await expect.poll(() => outages).toBe(1);
  await expect(rows).toHaveCount(2);
  await expect(page.locator('[id="ledger.retry"]:visible')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("local-ledger-offline-refresh-390.png") });
  await context.unroute("**/api/v1/ledger/version?**");

  await context.route("**/api/**", route => route.abort("failed"));
  await page.reload();
  await expect(rows).toHaveCount(2, { timeout: 30_000 });
  await press(page, firstId);
  await expect(page.locator('[id="cash.current.detail"]:visible')).toContainText("缓存维护备注");
  await page.screenshot({ path: info.outputPath("local-ledger-offline-detail-390.png") });
  await context.unroute("**/api/**");
  expect(retiredReads).toEqual([]);
  expect(commands.length).toBeGreaterThanOrEqual(4);
  expect(commands.every(command => command.workspace === workspace)).toBe(true);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  await info.attach("local-ledger-contract", { body: JSON.stringify({ workspace, retiredReads, commands, versionCalls, explicitFailures, outages,
    consoleErrors: diagnostics.consoleErrors, failedRequests: diagnostics.failedRequests }, null, 2), contentType: "application/json" });
});

test("只读成员通过 FAB 刷新，不能看到写入动作或展开忙碌空菜单", async ({ page, context, baseURL }, info) => {
  const password = `Viewer-${crypto.randomUUID()}`;
  const register = async (prefix: string) => {
    const r = await page.request.post(`${baseURL}/api/v1/auth/register`, { data: {
      email: `${prefix}-${crypto.randomUUID()}@example.invalid`, password,
    } });
    expect(r.status()).toBe(200);
    return (await r.json()).access_token as string;
  };
  const admin = await register("refresh-admin");
  const headers = { Authorization: `Bearer ${admin}` };
  const created = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, { headers, data: { name: "只读刷新验证" } });
  expect(created.status()).toBe(200);
  const workspace = (await created.json()).active_workspace_id;
  const invited = await page.request.post(`${baseURL}/api/v1/auth/invitations`, { headers, data: { role: "viewer" } });
  expect(invited.status()).toBe(200);
  const invitation = (await invited.json()).token;
  const viewer = await register("refresh-viewer");
  const accepted = await page.request.post(`${baseURL}/api/v1/auth/invitations/${invitation}/accept`, {
    headers: { Authorization: `Bearer ${viewer}` },
  });
  expect(accepted.status()).toBe(200);
  await page.addInitScript(token => localStorage.setItem("finance-tracker:session-token", token), viewer);
  await page.goto(`/w/${workspace}/`);
  const fab = page.locator('[id="ledger.actions-fab"]:visible').last();
  await expect(fab).toBeEnabled({ timeout: 30_000 });
  await press(page, "ledger.actions-fab");
  const refresh = page.locator('[id="ledger.refresh"]:visible');
  await expect(refresh).toHaveText("刷新");
  await expect(page.locator('[id="ledger.add"]:visible')).toHaveCount(0);
  await expect(page.locator('[id="ledger.import"]:visible')).toHaveCount(0);
  let calls = 0; let release: (() => void) | undefined;
  await context.route("**/api/v1/ledger/version?**", async route => {
    calls++;
    await new Promise<void>(resolve => { release = resolve; });
    await route.continue();
  });
  await press(page, "ledger.refresh");
  await expect.poll(() => calls).toBe(1);
  await expect(refresh).toHaveCount(0);
  await expect(fab).toBeDisabled();
  const bounds = (await fab.boundingBox())!;
  await page.mouse.click(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await expect(refresh).toHaveCount(0);
  expect(calls).toBe(1);
  release!();
  await expect(fab).toBeEnabled();
  await context.unroute("**/api/v1/ledger/version?**");
  await press(page, "ledger.actions-fab");
  await expect(refresh).toBeVisible();
  await page.screenshot({ path: info.outputPath("local-ledger-viewer-refresh-fab-390.png") });
});

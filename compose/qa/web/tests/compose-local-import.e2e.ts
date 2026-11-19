import { chromium, expect, test, type BrowserContext, type Page } from "@playwright/test";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { captureBrowserDiagnostics, clickStableComposeTarget, installComposeApiFixtures, typeIntoComposeInput } from "./compose-fixtures";

// Each persistent context belongs to a separate browser process and has its own trace.
test.use({ trace: "off" });

async function waitForCanvasToSettle(page: import("@playwright/test").Page) {
  let previousFrame: Buffer | undefined;
  let stableFrames = 0;
  await expect.poll(async () => {
    const frame = await page.screenshot();
    stableFrames = previousFrame?.equals(frame) ? stableFrames + 1 : 0;
    previousFrame = frame;
    return stableFrames >= 3;
  }, { intervals: [100] }).toBe(true);
}

const feedbackCsv = [
    "支付宝交易记录明细查询", "支付宝账户：[synthetic@example.test]",
    "起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]", "交易类型：[全部]", "共1笔记录",
    "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号",
    "2026-08-15 10:00:00,日用百货,合成商户,account,合成商品,支出,20.00,支付宝余额,交易成功,0000123,merchant-1",
  ].join("\n");

test("读取账单期间点击关闭在当前导入任务提示一次，原读取继续", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.addInitScript(() => {
    const read = FileReader.prototype.readAsArrayBuffer;
    FileReader.prototype.readAsArrayBuffer = function (file) {
      if (file instanceof File && file.name === "snackbar-import.csv") {
        Object.assign(window, { releaseImportRead: () => read.call(this, file) });
      } else {
        read.call(this, file);
      }
    };
  });
  await page.goto("/w/workspace-1/cash-import");
  const diagnostics = captureBrowserDiagnostics(page);
  const chooser = page.waitForEvent("filechooser");
  await clickStableComposeTarget(page, page.locator('[id="cash.import.add-files"]').last());
  await (await chooser).setFiles({ name: "snackbar-import.csv", mimeType: "text/csv", buffer: Buffer.from(feedbackCsv) });
  await page.waitForFunction(() => "releaseImportRead" in window);

  await clickStableComposeTarget(page, page.locator('[id="cash.import.close"]').last());
  const notice = page.locator('[id="cash.import.file-notice"]').last();
  await expect(notice.getByText("请等待当前操作完成。")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(notice.getByText("请等待当前操作完成。")).toHaveCount(1);
  await page.screenshot({ path: "/tmp/compose-snackbar-import-busy-390.png" });

  await page.evaluate(() => (window as Window & { releaseImportRead: () => void }).releaseImportRead());
  await expect(page.locator('[id="cash.import.to-accounts"]').last()).toBeEnabled();
  await expect(notice.getByText("请等待当前操作完成。")).toHaveCount(0);
  await expect(page.locator('[id="cash.import.task"]').last()).toBeVisible();
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

test("新建账户重试沿用原保存，返回时刷新账户列表", async ({ page }) => {
  await page.setViewportSize({ width: 960, height: 900 });
  await installComposeApiFixtures(page);
  let finishSave!: () => void;
  const waiting = new Promise<void>(resolve => { finishSave = resolve; });
  let directoryReads = 0;
  const writes: Array<{ key: string | undefined; body: string | null }> = [];
  await page.route(url => url.pathname === "/api/v1/ledger/version", route => {
    directoryReads++;
    return route.fallback();
  });
  await page.route(url => url.pathname === "/api/v1/accounts", async route => {
    if (route.request().method() !== "POST") return route.fallback();
    writes.push({ key: route.request().headers()["idempotency-key"], body: route.request().postData() });
    await waiting;
    await route.fulfill({ status: 503, json: { error: { code: "account_result_unknown" } } });
  });
  try {
    const tagged = (id: string) => page.locator(`[id="${id}"]:visible`).last();
    await page.goto("/w/workspace-1/cash-import");
    const chooser = page.waitForEvent("filechooser");
    await clickStableComposeTarget(page, tagged("cash.import.add-files"));
    await (await chooser).setFiles({ name: "account-feedback.csv", mimeType: "text/csv", buffer: Buffer.from(feedbackCsv) });
    const next = tagged("cash.import.to-accounts");
    await expect(next).toBeEnabled();
    await clickStableComposeTarget(page, next);
    const group = page.locator('[id^="cash.import.account-group-"]:visible').first();
    const picker = group.locator('[id^="cash.import.account-"]:visible').first();
    await expect(picker).toBeVisible();
    const pickerBounds = (await picker.boundingBox())!;
    await clickStableComposeTarget(page, picker);
    const create = page.getByText("＋新建账户", { exact: true }).last();
    await expect(create).toBeVisible();
    const createBounds = (await create.boundingBox())!;
    await clickStableComposeTarget(page, create);
    await clickStableComposeTarget(page, tagged("cash.import.account-form.save"));
    await expect.poll(() => writes.length).toBe(1);
    await expect(tagged("cash.import.account-form.progress")).toBeVisible();
    await expect(page.getByText(/账户操作结果待核实/)).toHaveCount(0);
    await expect(tagged("cash.import.account-form.resolve")).toHaveCount(0);
    await page.screenshot({ path: "/tmp/compose-progress-account-960.png" });
    finishSave();
    await expect(tagged("cash.import.account-form.progress")).toHaveCount(0);
    await expect(page.getByText("保存遇到问题，请重试。")).toBeVisible();
    await expect(tagged("cash.import.account-form.resolve")).toHaveCount(0);
    await clickStableComposeTarget(page, tagged("cash.import.account-form.save"));
    await expect.poll(() => writes.length).toBe(2);
    expect(writes[0].key).toBeTruthy();
    expect(writes[1]).toEqual(writes[0]);
    await expect(page.getByText("保存遇到问题，请重试。")).toBeVisible();
    await page.screenshot({ path: "/tmp/compose-account-network-retry.png" });
    const priorReads = directoryReads;
    const refreshed = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/ledger/version");
    await clickStableComposeTarget(page, tagged("cash.import.account-form.close"));
    await (await refreshed).finished();
    await expect.poll(() => directoryReads).toBeGreaterThan(priorReads);
    expect(writes).toHaveLength(2);
    await expect(page.locator('[id="cash.import.resolve-accounts"]')).toHaveCount(0);
    // Compose may keep the detached overlay in the exported DOM. Reopen on the rendered page.
    await page.waitForTimeout(350);
    await page.mouse.click(pickerBounds.x + pickerBounds.width / 2, pickerBounds.y + pickerBounds.height / 2);
    await page.waitForTimeout(250);
    await page.mouse.click(createBounds.x + createBounds.width / 2, createBounds.y + createBounds.height / 2);
    await page.waitForTimeout(250);
    await clickStableComposeTarget(page, tagged("cash.import.account-form.save"));
    await expect.poll(() => writes.length).toBe(3);
    expect(writes[2]).toEqual(writes[0]);
  } finally {
    finishSave();
  }
});

for (const scenario of ["normal", "conflict", "completion"] as const) {
const recheckAfterConflict = scenario === "conflict";
const interruptCompletion = scenario === "completion";
test(`新登录后离线解析和核对 CSV${recheckAfterConflict ? "，冲突后重新核对" : interruptCompletion ? "，已保存后恢复结果整理" : ""}，联网保存原写集并离线冷启动账本与结果`, async ({ baseURL }, info) => {
  test.skip(!!process.env.CI, "Canvas import account-picker timing differs on CI runners; covered by local runs and shared import tests");
  test.setTimeout(180_000);
  if (!baseURL) throw new Error("A real Kotlin backend preview is required");
  const profile = await mkdtemp(join(tmpdir(), "ft-local-import-"));
  const launch = () => chromium.launchPersistentContext(profile, {
    headless: true, viewport: { width: 1440, height: 1100 }, locale: "zh-CN", timezoneId: "Asia/Shanghai",
    ...(process.env.PLAYWRIGHT_CHROME_PATH ? { executablePath: process.env.PLAYWRIGHT_CHROME_PATH } : {}),
  });
  let context: BrowserContext | undefined;
  let tracePart = 0;
  let tracing = false;
  const stopTrace = async () => {
    if (!context || !tracing) return;
    const name = `application-trace-${tracePart++}`;
    const path = info.outputPath(`${name}.zip`);
    await context.tracing.stop({ path });
    tracing = false;
    await info.attach(name, { path, contentType: "application/zip" });
  };
  try {
  context = await launch();
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  tracing = true;
  let page = context.pages()[0] ?? await context.newPage();
  await page.setViewportSize({ width: 1440, height: 1100 });
  const email = `local-import-${crypto.randomUUID()}@example.invalid`;
  const password = `Import-${crypto.randomUUID()}`;
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, { data: { email, password } });
  expect(authentication.status()).toBe(200);
  const { access_token: setupToken } = await authentication.json();
  const setupHeaders = { Authorization: `Bearer ${setupToken}` };
  const createdWorkspace = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers: setupHeaders, data: { name: "本地导入验证" },
  });
  expect(createdWorkspace.status()).toBe(200);
  const workspaceSession = await createdWorkspace.json();
  const workspace = workspaceSession.active_workspace_id;
  expect(workspace).toBeTruthy();
  const account = await page.request.post(`${baseURL}/api/v1/accounts`, {
    headers: { ...setupHeaders, "Idempotency-Key": crypto.randomUUID() },
    data: { name: "本地导入现金", type: "cash", currencies: ["CNY"] },
  });
  expect(account.status()).toBe(201);
  const accountId = (await account.json()).account_id;
  let diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404] });
  const oldImportRequests: string[] = [];
  const writeKeys: string[] = [];
  const observeRequests = (target: Page) => target.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (/\/cash-import\/(sessions|plans)(?:\/|$)/.test(path)) oldImportRequests.push(path);
    if (request.method() === "POST" && path === "/api/v1/cash-import/write-set") {
      writeKeys.push(request.headers()["idempotency-key"] ?? "");
    }
  });
  observeRequests(page);
  await page.goto(`${baseURL}/w/${workspace}/cash-import`);
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', password);
  await clickStableComposeTarget(page, page.locator('[id="auth.submit"]:visible').last());
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1, { timeout: 30_000 });

  // Catalogs already use the verified local snapshot while online list/detail migration continues.
  const onlineCatalogRequests: string[] = [];
  const blockRemoteCatalogs = async (route: import("@playwright/test").Route) => {
    const url = new URL(route.request().url());
    if (route.request().method() === "GET" && (url.pathname === "/api/v1/cash-categories"
      || url.pathname === "/api/v1/cash-ledger/options"
      || url.pathname === "/api/v1/accounts" && url.searchParams.get("view") === "cash")) {
      onlineCatalogRequests.push(url.pathname);
      return route.abort("failed");
    }
    return route.continue();
  };
  await context.route("**/api/**", blockRemoteCatalogs);
  await page.goto(`${baseURL}/w/${workspace}/`);
  await expect(page.locator('[id="ledger.empty"]:visible')).toHaveCount(1);
  await clickStableComposeTarget(page, page.locator('[id="ledger.more-filters"]:visible').last());
  const details = page.locator('[id="ledger.filter.details"]:visible').last();
  await expect(details).toBeVisible();
  const onlineAccount = details.locator('[id="ledger.filter.account"]:visible').last();
  await expect(onlineAccount).toBeInViewport();
  await clickStableComposeTarget(page, onlineAccount);
  await expect(page.locator('[id="choice-overlay-menu"]:visible').last()
    .getByText("本地导入现金", { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath("local-csv-online-local-accounts.png") });
  expect(onlineCatalogRequests).toEqual([]);
  await context.unroute("**/api/**", blockRemoteCatalogs);
  await page.goto(`${baseURL}/w/${workspace}/cash-import`);
  await expect(page.locator('[id="cash.import.task"]:visible')).toHaveCount(1);
  diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404] });

  const offlineRequests: string[] = [];
  const completionFailures: string[] = [];
  let blockCompletionRefresh = false;
  await page.route("**/api/**", (route) => {
    offlineRequests.push(new URL(route.request().url()).pathname);
    return route.abort("failed");
  });
  const csv = [
    "支付宝交易记录明细查询", "支付宝账户：[synthetic@example.test]",
    "起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]", "交易类型：[全部]", "共1笔记录",
    "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号",
    "2026-08-15 10:00:00,日用百货,合成商户,account,合成商品,支出,20.00,支付宝余额,交易成功,0000123,merchant-1",
  ].join("\n");
  const chooser = page.waitForEvent("filechooser");
  await clickStableComposeTarget(page, page.locator('[id="cash.import.add-files"]:visible').last());
  await (await chooser).setFiles({ name: "synthetic.csv", mimeType: "text/csv", buffer: Buffer.from(csv) });
  const next = page.locator('[id="cash.import.to-accounts"]:visible').last();
  await expect(next).toBeEnabled();
  await clickStableComposeTarget(page, next);
  const group = page.locator('[id^="cash.import.account-group-"]:visible');
  await expect(group).toHaveCount(1, { timeout: 30_000 });
  const review = page.locator('[id="cash.import.review"]:visible').last();
  await expect(review).toBeVisible({ timeout: 30_000 });
  const reviewBounds = await review.boundingBox();
  if (!reviewBounds) throw new Error("Missing review action bounds before opening the account popup");
  const picker = group.locator('[id^="cash.import.account-"]:visible').last();
  await clickStableComposeTarget(page, picker);
  const menu = page.locator('[id="choice-overlay-menu"]:visible').last();
  await clickStableComposeTarget(page, menu.getByText("本地导入现金", { exact: true }).last());
  // CMP-10623 can leave the disposed popup in the exported semantics mirror.
  // Click the previously measured real canvas action after the popup finishes closing.
  await waitForCanvasToSettle(page);
  await page.mouse.click(reviewBounds.x + reviewBounds.width / 2, reviewBounds.y + reviewBounds.height / 2);
  await waitForCanvasToSettle(page);
  expect(offlineRequests.filter((path) => path !== "/api/v1/auth/session")).toEqual([]);
  expect(writeKeys).toEqual([]);
  await page.screenshot({ path: info.outputPath("local-csv-offline-review.png") });

  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  await page.reload();
  const restoredNext = page.locator('[id="cash.import.to-accounts"]:visible').last();
  await expect(restoredNext).toBeEnabled({ timeout: 30_000 });
  await clickStableComposeTarget(page, restoredNext);
  await expect(page.locator('[id^="cash.import.account-group-"]:visible')).toHaveCount(1);
  const restoredReview = page.locator('[id="cash.import.review"]:visible').last();
  await expect(restoredReview).toBeEnabled();
  // Startup deliberately verifies the session while disconnected. Account preparation
  // and a new local preview must issue no requests after the cached task is restored.
  const restoredRequestCount = offlineRequests.length;
  await clickStableComposeTarget(page, restoredReview);
  await expect(page.locator('[id="cash.import.confirm"]:visible').last()).toBeDisabled();
  await expect(page.getByText("当前断网，只能浏览本地数据；联网后将重新同步", { exact: true }).last()).toBeVisible();
  const blockedConfirm = await page.locator('[id="cash.import.confirm"]:visible').last().boundingBox();
  if (!blockedConfirm) throw new Error("Missing disabled confirmation bounds");
  await page.mouse.click(blockedConfirm.x + blockedConfirm.width / 2, blockedConfirm.y + blockedConfirm.height / 2);
  await waitForCanvasToSettle(page);
  expect(offlineRequests.slice(restoredRequestCount).filter((path) => path !== "/api/v1/auth/session")).toEqual([]);
  expect(writeKeys).toEqual([]);
  await page.screenshot({ path: info.outputPath("local-csv-offline-restored-review.png") });
  diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404] });

  if (scenario === "normal") {
    // HTTP authentication can succeed while restoration of the new local owner fails.
    const unavailableFacts = "**/api/v1/ledger/version**";
    await page.route(unavailableFacts, (route) => route.fulfill({ status: 503,
      json: { error: { code: "snapshot_unavailable" } } }));
    await page.unroute("**/api/**");
    await expect(page.getByText("连接尚未就绪，请重试", { exact: true }).last()).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('[id="cash.import.confirm"]:visible').last()).toBeDisabled();
    await expect(page.locator('[id="cash.import.close"]:visible').last()).toBeEnabled();
    expect(writeKeys).toEqual([]);
    await page.screenshot({ path: info.outputPath("local-csv-reconnect-local-unavailable.png") });
    await page.unroute(unavailableFacts);
    diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404] });
  } else {
    await page.unroute("**/api/**");
  }
  await page.reload();
  // Reconnection verifies the session and restores preparation; it cannot send the old runtime plan.
  await expect(page.locator('[id="cash.import.to-accounts"]:visible').last()).toBeEnabled();
  expect(writeKeys).toEqual([]);
  await clickStableComposeTarget(page, page.locator('[id="cash.import.to-accounts"]:visible').last());
  await clickStableComposeTarget(page, page.locator('[id="cash.import.review"]:visible').last());
  await expect(page.locator('[id="cash.import.confirm"]:visible').last()).toBeEnabled();
  expect(writeKeys).toEqual([]);
  if (interruptCompletion) await page.route("**/api/**", async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    if (request.method() === "POST" && path === "/api/v1/cash-import/write-set") {
      const response = await route.fetch();
      if (response.status() === 200) blockCompletionRefresh = true;
      return route.fulfill({ response });
    }
    if (blockCompletionRefresh && request.method() === "GET" && path === "/api/v1/ledger/snapshot") {
      completionFailures.push(path);
      return route.fulfill({ status: 503, contentType: "application/json",
        body: JSON.stringify({ error: { code: "snapshot_unavailable", retryable: true } }) });
    }
    return route.continue();
  });
  if (recheckAfterConflict) {
    diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404, 409] });
    const changed = await page.request.patch(`${baseURL}/api/v1/accounts/by-id/${accountId}?workspace_id=${workspace}`, {
      headers: { ...setupHeaders, "Idempotency-Key": crypto.randomUUID() },
      data: { expected_revision: 1, patch: { name: "本地导入现金已更名" } },
    });
    expect(changed.status()).toBe(200);
    const rejected = page.waitForResponse((response) => response.request().method() === "POST"
      && new URL(response.url()).pathname === "/api/v1/cash-import/write-set");
    await page.mouse.click(1350, 1072);
    expect((await rejected).status()).toBe(409);
    const updatedReview = page.locator('[id="cash.import.review"]:visible').last();
    await expect(updatedReview).toBeEnabled({ timeout: 30_000 });
    await expect(page.locator('[id^="cash.import.account-group-"]:visible')).toHaveCount(1);
    await expect(page.getByText("数据已变化，请返回后重新核对。", { exact: true }).last()).toBeVisible();
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-conflict-recheck.png") });
    const updatedBounds = await updatedReview.boundingBox();
    if (!updatedBounds) throw new Error("Missing review action after a retired request");
    await clickStableComposeTarget(page, page.locator('[id^="cash.import.account-group-"]:visible')
      .locator('[id^="cash.import.account-"]:visible').last());
    await clickStableComposeTarget(page, page.locator('[id="choice-overlay-menu"]:visible').last()
      .getByText(/^本地导入现金(?:已更名)?$/).last());
    await waitForCanvasToSettle(page);
    await page.mouse.click(updatedBounds.x + updatedBounds.width / 2, updatedBounds.y + updatedBounds.height / 2);
    await waitForCanvasToSettle(page);
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-conflict-reviewed.png") });
  }
  const saved = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/cash-import/write-set", { timeout: 30_000 });
  // The real confirm button in the captured 1440 x 1100 review canvas sits here;
  // the previous mapping footer has different bounds, and its DOM mirror is stale.
  await page.mouse.click(1350, 1072);
  const response = await saved;
  expect(response.status()).toBe(200);
  const receipt = await response.json();
  expect(receipt.committed).toBe(true);
  expect(receipt.summary.created_posted).toBe(1);
  if (interruptCompletion) {
    const result = page.locator('[id="cash.import.result"]:visible').last();
    await expect(result).toBeVisible({ timeout: 30_000 });
    await expect(result.getByText("账本已保存，结果整理尚未完成。", { exact: true })).toBeVisible();
    const resume = page.locator('[id="cash.import.resolve"]:visible').last();
    await expect(resume).toBeEnabled();
    await expect(resume).toHaveText("继续整理结果");
    expect(completionFailures.length).toBeGreaterThan(0);
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-completion-interrupted.png") });
    blockCompletionRefresh = false;
    await clickStableComposeTarget(page, resume);
    await expect(result.getByText("账本已保存，结果整理尚未完成。", { exact: true })).toHaveCount(0);
    await expect(page.locator('[id="cash.import.resolve"]:visible')).toHaveCount(0);
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-completion-recovered.png") });
  }
  const snapshotResponse = await page.request.get(`${baseURL}/api/v1/ledger/snapshot?workspace_id=${workspace}`, { headers: setupHeaders });
  expect(snapshotResponse.status()).toBe(200);
  const snapshot = await snapshotResponse.json();
  expect(snapshot.facts.cash_transactions).toHaveLength(1);
  expect(snapshot.facts.cash_transactions[0].account_id).toBe(accountId);
  expect(snapshot.facts.cash_transactions[0].counterparty).toBe("合成商户");
  expect(snapshot.facts.cash_transactions[0].amount).toMatch(/^-20(?:\.0+)?$/);
  await waitForCanvasToSettle(page);
  expect(writeKeys).toHaveLength(recheckAfterConflict ? 2 : 1);
  if (recheckAfterConflict) expect(writeKeys[1]).toBe(`${writeKeys[0]}:attempt:1`);
  expect(writeKeys[0]).not.toBe("");
  expect(oldImportRequests).toEqual([]);
  await page.screenshot({ path: info.outputPath("local-csv-saved.png") });
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  const expectedApiErrors = recheckAfterConflict ? [
    "404 /api/v1/cash-mutations/receipts/by-key", "409 /api/v1/cash-import/write-set",
    "404 /api/v1/cash-mutations/receipts/by-key", "404 /api/v1/cash-mutations/receipts/by-key",
  ] : ["404 /api/v1/cash-mutations/receipts/by-key",
    ...completionFailures.map((path) => "503 " + path)];
  expect(diagnostics.expectedApiErrors).toEqual(expectedApiErrors);

  const previousBrowser = context.browser();
  expect(previousBrowser).not.toBeNull();
  await stopTrace();
  await context.close();
  context = undefined;
  expect(previousBrowser?.isConnected()).toBe(false);
  context = await launch();
  expect(context.browser()).not.toBe(previousBrowser);
  await context.tracing.start({ screenshots: true, snapshots: true, sources: true });
  tracing = true;
  page = context.pages()[0] ?? await context.newPage();
  observeRequests(page);
  const coldRequests: string[] = [];
  const coldPageErrors: string[] = [];
  page.on("pageerror", (error) => coldPageErrors.push(error.message));
  await context.route("**/api/**", (route) => {
    coldRequests.push(new URL(route.request().url()).pathname);
    return route.abort("failed");
  });
  await page.goto(`${baseURL}/w/${workspace}/`);
  const coldRecord = page.locator('[id^="ledger.record-cash-entry:"]:visible');
  await expect(coldRecord).toHaveCount(1, { timeout: 30_000 });
  expect(receipt.write_set_result.entries).toHaveLength(1);
  await expect(coldRecord).toHaveAttribute("id", `ledger.record-cash-entry:${receipt.write_set_result.entries[0].id}`);
  await expect(coldRecord).toContainText("合成商户");
  await expect(coldRecord).toContainText("-20 CNY");
  await page.screenshot({ path: info.outputPath("local-csv-offline-ledger-cold-start.png") });
  await page.goto(`${baseURL}/w/${workspace}/cash-import`);
  await expect(page.locator('[id="cash.import.result"]:visible')).toHaveCount(1, { timeout: 30_000 });
  await expect(page.locator('[id="cash.import.return"]:visible')).toBeEnabled();
  expect(writeKeys).toHaveLength(recheckAfterConflict ? 2 : 1);
  expect(coldRequests.length).toBeGreaterThan(0);
  expect(coldRequests.every((path) => path === "/api/v1/auth/session")).toBe(true);
  expect(coldPageErrors).toEqual([]);
  await page.screenshot({ path: info.outputPath("local-csv-offline-result.png") });
  await context.unroute("**/api/**");
  diagnostics = captureBrowserDiagnostics(page);
  await page.reload();
  await expect(page.locator('[id="cash.import.result"]:visible')).toHaveCount(1);
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  expect(diagnostics.expectedApiErrors).toEqual([]);
  expect(writeKeys).toHaveLength(recheckAfterConflict ? 2 : 1);
  expect(oldImportRequests).toEqual([]);
  await info.attach("local-import-protocol", { body: JSON.stringify({ oldImportRequests, writeKeys, receipt,
    browserRestarted: true, completionFailures, onlineCatalogRequests, onlineWorkspace: workspace, coldRequests, coldPageErrors, reverifiedWorkspace: workspace }, null, 2), contentType: "application/json" });
  } finally {
    try { await stopTrace(); }
    finally { await context?.close(); await rm(profile, { recursive: true, force: true }); }
  }
});
}

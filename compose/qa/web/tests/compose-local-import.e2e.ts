import { chromium, expect, test, type BrowserContext, type Page } from "@playwright/test";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { captureBrowserDiagnostics, clickStableComposeTarget, typeIntoComposeInput } from "./compose-fixtures";

// Each persistent context belongs to a separate browser process and has its own trace.
test.use({ trace: "off" });

function isCashImportPreviewWorker(worker: { url(): string }) {
  return /^\/cash-import-worker\/assets\/[a-f0-9]{64}\/entry\.mjs$/.test(new URL(worker.url()).pathname);
}

test("现金导入 Worker 在浏览器断网后可取消并重建", async ({ page, context }) => {
  await page.goto("/health");
  const clientUrl = "/cash-import-worker/client.mjs";
  const warming = page.waitForEvent("worker", { predicate: isCashImportPreviewWorker });
  await page.evaluate(async (url) => { await import(url); }, clientUrl);
  const original = await warming;
  await expect.poll(() => original.evaluate(() => typeof self.onmessage)).toBe("function");
  const count = 1_000;
  const ledgerSeq = "9007199254740993";
  const payload = syntheticPreviewPayload(count, ledgerSeq);
  await context.setOffline(true);
  const restarting = page.waitForEvent("worker", { predicate: isCashImportPreviewWorker });
  const result = await page.evaluate(async ({ clientUrl, payload }) => {
    const { startPreview } = await import(clientUrl);
    const cancelled = startPreview(payload);
    cancelled.cancel();
    await cancelled.result.catch(() => {});
    let frames = 0;
    let clicks = 0;
    let complete = false;
    const countFrame = () => {
      if (complete) return;
      frames++;
      requestAnimationFrame(countFrame);
    };
    requestAnimationFrame(countFrame);
    document.body.addEventListener("click", () => clicks++, { once: true });
    setTimeout(() => document.body.click(), 20);
    const response = await startPreview(payload).result;
    complete = true;
    const { writeSet } = JSON.parse(response);
    return { frames, clicks, ledgerSeq: writeSet.base_ledger_seq, rows: writeSet.changes.transactions.length,
      exactAmount: writeSet.changes.transactions.every((row: { values: { amount: string } }) => row.values.amount === "-10.0001") };
  }, { clientUrl, payload });
  expect(await restarting).not.toBe(original);
  expect(result).toMatchObject({ ledgerSeq, rows: count, clicks: 1, exactAmount: true });
  expect(result.frames).toBeGreaterThan(1);
});

function syntheticPreviewPayload(count: number, ledgerSeq: string) {
  const method = "支付宝余额";
  const owner = "synthetic";
  const source = "alipay";
  const sourceAccountRef = createHash("sha256")
    .update(JSON.stringify(["funding-group-v1", source, "payment_method", owner, method])).digest("hex");
  const rows = Array.from({ length: count }, (_, i) => {
    const id = `TEST-${i}`;
    return {
      position: i + 1,
      fields: {
        amount: "-10.0001", currency: "CNY", occurred_at: "2026-08-15T02:00:00Z",
        record_type: "consumption", record_subtype: "not_applicable", counterparty: `合成商户 ${i}`,
        note: "合成账单", source_payload: { 交易订单号: id, "收/付款方式": method, "收/支": "支出", 交易状态: "交易成功" },
      },
      payment_method: method, provider_id: id, issue_codes: [], error_code: null, disposition: "normalized",
      source_account_identifier: null, document_position: null,
      source_explanation: { txn_id: id, payment_method: method, platform_status: "交易成功", _alipay_direction: "支出" },
    };
  });
  const facts = {
    accounts: [{ id: "10", name: "合成账户", type: "cash", active: true, currencies: ["CNY"], revision: ledgerSeq }],
    account_aliases: [], statement_account_mappings: [], cash_categories: [], cash_transactions: [],
    cash_transaction_components: [], cash_source_bindings: [], cash_source_empty_pages: [], transaction_relations: [],
    cash_relation_candidates: [], cash_transaction_issues: [], cash_issue_candidates: [], cash_entries: [],
    cash_entry_members: [], cash_investment_funding_relations: [], category_directory_revision: "0",
  };
  return JSON.stringify({
    files: [{
      fileRef: "f", digest: "f".repeat(64), status: "parsed", filename: "synthetic.csv", size: count, sourceType: source,
      document: {
        source_type: source, table_key: "csv", parser_version: "p", rows, source_account_identifier: owner,
        source_account_label: null, coverage: { start: null, end: null, complete: false, evidence: "", filters: {}, strategyVersion: "v" },
        declared_count: count, observed_count: count, source_nickname: null,
      },
    }],
    snapshot: { workspace_id: "w", ledger_seq: ledgerSeq, rules_version: "cash-rules-v1", facts },
    choices: {
      account_selections: [{ source_account_ref: sourceAccountRef, account_id: "10", expected_account_revision: "$EXACT_REVISION" }],
      row_choices: [], relation_choices: [], entry_choices: [],
    },
  }).replace('"$EXACT_REVISION"', ledgerSeq);
}

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
  const previewWorker = () => page.workers().find(isCashImportPreviewWorker);
  await expect.poll(() => previewWorker()?.evaluate(() => typeof self.onmessage)).toBe("function");
  const crashWorker = scenario === "normal" ? previewWorker() : undefined;
  const crashed = crashWorker && new Promise<void>((resolve) => crashWorker.once("close", () => resolve()));
  await crashWorker?.evaluate(() => {
    self.onmessage = () => { throw new Error("synthetic preview Worker crash"); };
  });
  const clickReviewAction = () => page.mouse.click(reviewBounds.x + reviewBounds.width - 16, reviewBounds.y + reviewBounds.height / 2);
  await clickReviewAction();
  if (crashed) {
    await crashed;
    await waitForCanvasToSettle(page);
    expect(writeKeys).toEqual([]);
    await page.screenshot({ path: info.outputPath("local-csv-worker-failed.png") });
    // The retry occupies the same trailing action slot. The stale popup semantics
    // can still hide that slot from locators after the canvas has closed the popup.
    const restarted = page.waitForEvent("worker", { predicate: (worker) => worker.url() === crashWorker?.url() });
    await clickReviewAction();
    await restarted;
  }
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
  await expect.poll(() => previewWorker()?.evaluate(() => typeof self.onmessage)).toBe("function");
  await context.setOffline(true);
  try {
    await clickStableComposeTarget(page, restoredReview);
    await expect(page.locator('[id="cash.import.confirm"]:visible').last()).toBeDisabled();
  } finally {
    await context.setOffline(false);
  }
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

import { chromium, expect, test, type BrowserContext, type Page } from "@playwright/test";
import { createHash } from "node:crypto";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { captureBrowserDiagnostics, clickStableComposeTarget, installComposeApiFixtures, typeIntoComposeInput } from "./compose-fixtures";

// Each persistent context belongs to a separate browser process and has its own trace.
test.use({ trace: "off" });

function isCashImportPreviewWorker(worker: { url(): string }) {
  const url = new URL(worker.url());
  return !url.hash && /^\/cash-import-worker\/assets\/[a-f0-9]{64}\/entry\.mjs$/.test(url.pathname);
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
    const { openPreview, readPreview, freezeConfirmation, inspectBatch } = await import(clientUrl);
    const cancelled = openPreview(payload);
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
    const response = await openPreview(payload).result;
    complete = true;
    const header = JSON.parse(response.header);
    const page = JSON.parse((await readPreview(JSON.stringify({ mode: "items", start: 50, limit: 50 }), response.items).result).payload);
    const detail = JSON.parse((await readPreview(JSON.stringify({ mode: "detail", id: header.items[0].preview_item_id }),
      response.detail).result).payload);
    const frozen = await freezeConfirmation(response.confirmation).result;
    const writeSet = JSON.parse(frozen.body);
    if (JSON.parse(frozen.resultInput).version !== 1) throw new Error("Missing frozen result material");
    const legacyBatch = await inspectBatch(JSON.stringify(["preserved UTF-16: \uD800"])).result;
    const invalidBatch = await inspectBatch('{"version":1,"files":[],"attempt":"2"}').result;
    return { legacyBatch, invalidBatch, frames, clicks, ledgerSeq: writeSet.base_ledger_seq, rows: writeSet.changes.transactions.length,
      exactAmount: writeSet.changes.transactions.every((row: { values: { amount: string } }) => row.values.amount === "-10.0001"),
      headerChars: response.header.length, firstPage: header.items.length, secondPage: page.items.length, total: page.total_count,
      detailMatches: detail.preview_item_id === header.items[0].preview_item_id,
      shared: response.confirmation instanceof SharedArrayBuffer && response.items instanceof SharedArrayBuffer };
  }, { clientUrl, payload });
  expect(await restarting).not.toBe(original);
  expect(result).toMatchObject({ ledgerSeq, rows: count, clicks: 1, exactAmount: true, firstPage: 50, secondPage: 50,
    total: count, detailMatches: true, shared: true,
    legacyBatch: { files: ["preserved UTF-16: \uD800"], attempt: 0 },
    invalidBatch: { code: "internal_error", phase: "inspectBatch" } });
  expect(result.headerChars).toBeLessThan(100_000);
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
    planId: "synthetic", batchId: "batch", revision: 2, timezone: "Asia/Shanghai",
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

const feedbackCsv = [
    "支付宝交易记录明细查询", "支付宝账户：[synthetic@example.test]",
    "起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59]", "交易类型：[全部]", "共1笔记录",
    "交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号",
    "2026-08-15 10:00:00,日用百货,合成商户,account,合成商品,支出,20.00,支付宝余额,交易成功,0000123,merchant-1",
  ].join("\n");

test("导入补币种后按原修订撤销账户改动", async ({ page, baseURL }, info) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, {
    data: { email: `account-compensation-${crypto.randomUUID()}@example.invalid`, password: "Synthetic account password" },
  });
  expect(authentication.ok()).toBe(true);
  const { access_token: token } = await authentication.json();
  const headers = { Authorization: `Bearer ${token}` };
  const createdWorkspace = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, { headers, data: { name: "账户撤销验证" } });
  expect(createdWorkspace.ok()).toBe(true);
  const { active_workspace_id: workspace } = await createdWorkspace.json();
  const seeded = await page.request.post(`${baseURL}/api/v1/accounts?workspace_id=${workspace}`, {
    headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
    data: { name: "本地外币账户", type: "cash", currencies: ["USD"] },
  });
  expect(seeded.ok()).toBe(true);
  const account = await seeded.json();
  await page.addInitScript(value => localStorage.setItem("finance-tracker:session-token", value), token);
  await page.goto(`/w/${workspace}/cash-import`);
  const tagged = (id: string) => page.locator(`[id="${id}"]:visible`).last();
  const click = (id: string) => clickStableComposeTarget(page, tagged(id));
  const chooser = page.waitForEvent("filechooser");
  await click("cash.import.add-files");
  await (await chooser).setFiles({ name: "account-undo.csv", mimeType: "text/csv", buffer: Buffer.from(feedbackCsv) });
  await expect(tagged("cash.import.to-accounts")).toBeEnabled();
  await click("cash.import.to-accounts");
  const group = page.locator('[id^="cash.import.account-group-"]:visible').last();
  const review = tagged("cash.import.review");
  await expect(review).toBeEnabled();
  const bounds = (await review.boundingBox())!;
  const closeBounds = (await tagged("cash.import.close").boundingBox())!;
  await clickStableComposeTarget(page, group.locator('[id^="cash.import.account-"]:visible').last());
  const menu = page.locator('[id="choice-overlay-menu"]:visible').last();
  await clickStableComposeTarget(page, menu.getByText("本地外币账户", { exact: true }).last());
  await waitForCanvasToSettle(page);
  const ensure = page.waitForResponse(response => new URL(response.url()).pathname === "/api/v1/cash-import/accounts/currency-requirements");
  await page.mouse.click(bounds.x + bounds.width - 16, bounds.y + bounds.height / 2);
  const expanded = await ensure;
  expect(expanded.ok()).toBe(true);
  const updated = (await expanded.json()).results[0].account;
  expect(updated.currencies.slice().sort()).toEqual(["CNY", "USD"]);

  const writes: Array<{ method: string; revision: number; patch: unknown }> = [];
  const path = `/api/v1/accounts/by-id/${account.account_id}`;
  page.on("request", request => {
    if (new URL(request.url()).pathname !== path || !["PATCH", "DELETE"].includes(request.method())) return;
    const body = request.postDataJSON();
    writes.push({ method: request.method(), revision: body.expected_revision, patch: body.patch });
  });
  await waitForCanvasToSettle(page);
  await page.screenshot({ path: info.outputPath("currency-requirement-completed.png") });
  await page.mouse.click(closeBounds.x + closeBounds.width / 2, closeBounds.y + closeBounds.height / 2);
  await waitForCanvasToSettle(page);
  await page.screenshot({ path: info.outputPath("currency-requirement-exit.png") });
  // The disposed picker can remain in the accessibility overlay. Use the
  // rendered dialog controls in the fixed 1440 × 1100 viewport.
  expect(writes).toHaveLength(0);
  await page.mouse.click(500, 539);
  await waitForCanvasToSettle(page);
  const update = page.waitForResponse(response => new URL(response.url()).pathname === path && response.request().method() === "PATCH");
  await page.mouse.click(910, 615);
  expect((await update).ok()).toBe(true);
  expect(writes).toEqual([{ method: "PATCH", revision: updated.account_revision, patch: { currencies: ["USD"] } }]);
  const restored = await page.request.get(`${baseURL}${path}?workspace_id=${workspace}`, { headers });
  expect(restored.ok()).toBe(true);
  expect((await restored.json()).currencies).toEqual(["USD"]);

});

test("读取账单期间点击关闭在当前导入任务提示一次，原读取继续", async ({ page }) => {
  await installComposeApiFixtures(page);
  await page.addInitScript(() => {
    const post = Worker.prototype.postMessage;
    Worker.prototype.postMessage = function (message, transfer) {
      if (message.operation === "captureFile" && message.payload.file.name === "snackbar-import.csv") {
        Object.assign(window, { releaseImportRead: () => post.call(this, message, transfer ?? []) });
        return;
      }
      return post.call(this, message, transfer ?? []);
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
  await page.route(url => url.pathname === "/api/v1/cash-mutations/receipts/by-key", route =>
    route.fulfill({ status: 404, json: { error: { code: "receipt_not_found" } } }));
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
    const groups = page.locator('[id^="cash.import.account-group-"]:visible');
    for (let attempt = 0; attempt < 4 && await groups.count() === 0; attempt++) {
      await expect(next).toBeEnabled();
      await clickStableComposeTarget(page, next);
      await page.waitForTimeout(500);
    }
    const group = groups.first();
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

for (const scenario of ["normal", "conflict", "completion", "size-refusal"] as const) {
const recheckAfterConflict = scenario === "conflict";
const unchangedAfterConflict = scenario === "rebase" || scenario === "rebase-worker";
const interruptCompletion = scenario === "completion";
const interruptArchive = scenario === "archive-worker";
const interruptSnapshot = scenario === "snapshot-worker";
test(scenario === "size-refusal" ? "确认明确超限后回到原批次调整文件" : `新登录后离线解析和核对 CSV${recheckAfterConflict ? "，冲突后重新核对" : unchangedAfterConflict ? (scenario === "rebase-worker" ? "，重算 Worker 崩溃后继续保存" : "，无关账本变更后后台重算") : interruptCompletion ? "，已保存后恢复结果整理" : interruptArchive ? "，归档 Worker 崩溃后恢复结果" : interruptSnapshot ? "，快照 Worker 崩溃后重新打开继续整理" : scenario === "pack-fence" ? "，封装写集时会话代次变化停止写入" : ""}，联网保存原写集并离线冷启动账本与结果`, async ({ baseURL }, info) => {
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
  const receiptLookups: string[] = [];
  const observeRequests = (target: Page) => target.on("request", (request) => {
    const path = new URL(request.url()).pathname;
    if (/\/cash-import\/(sessions|plans)(?:\/|$)/.test(path)) oldImportRequests.push(path);
    if (path === "/api/v1/cash-mutations/receipts/by-key") receiptLookups.push(request.url());
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
  const previewWorker = () => page.workers().find(isCashImportPreviewWorker);
  await expect.poll(() => previewWorker()?.evaluate(() => typeof self.onmessage)).toBe("function");
  if (scenario === "normal") {
    await previewWorker()!.evaluate(() => {
      const handle = self.onmessage!;
      self.onmessage = function (event) {
        if (event.data?.operation === "captureFile" && event.data.payload.file.name === "retry.csv") {
          throw new Error("synthetic file Worker crash");
        }
        return handle.call(this, event);
      };
    });
  }
  const chooser = page.waitForEvent("filechooser");
  await clickStableComposeTarget(page, page.locator('[id="cash.import.add-files"]:visible').last());
  const files = [{ name: "synthetic.csv", mimeType: "text/csv", buffer: Buffer.from(csv) }];
  if (scenario === "normal") files.push({ name: "retry.csv", mimeType: "text/csv",
    buffer: Buffer.from(csv.replace("0000123,merchant-1", "0000124,merchant-2")) });
  await (await chooser).setFiles(files);
  if (scenario === "normal") {
    const readyFile = (name: string) => page.locator('[id^="cash.import.file-"]:visible')
      .filter({ hasText: name });
    const retryFile = page.locator('[id^="cash.import.local-file-"]:visible')
      .filter({ hasText: "retry.csv" }).locator('[id^="cash.import.retry-local-"]:visible');
    await expect(retryFile).toBeVisible();
    await expect(readyFile("synthetic.csv")).toBeVisible();
    expect(writeKeys).toEqual([]);
    await page.screenshot({ path: info.outputPath("local-csv-file-worker-failed.png") });
    await clickStableComposeTarget(page, retryFile);
    await expect(readyFile("retry.csv")).toBeVisible();
    await expect(readyFile("synthetic.csv")).toBeVisible();
    await clickStableComposeTarget(page, readyFile("retry.csv").locator('[id^="cash.import.remove-file-"]:visible'));
  }
  await expect(page.locator('[id^="cash.import.remove-file-"]:visible')).toHaveCount(1);
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
  if (scenario === "size-refusal") {
    diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404, 413] });
    await page.route("**/api/v1/cash-import/write-set?*", (route) => route.request().method() === "POST"
      ? route.fulfill({ status: 413, json: { error: { code: "payload_too_large", retryable: false }, commit_state: "not_committed" } })
      : route.continue());
    await clickStableComposeTarget(page, page.locator('[id="cash.import.confirm"]:visible').last());
    await expect(page.locator('[id="cash.import.preview-too-large"]:visible').last())
      .toHaveText("本次导入内容过多，请减少文件后重试。", { timeout: 30_000 });
    expect(writeKeys).toHaveLength(1);
    expect(receiptLookups.filter((url) => new URL(url).searchParams.get("key") === writeKeys[0]).length).toBeGreaterThanOrEqual(2);
    await expect(page.getByText("synthetic.csv", { exact: true }).last()).toBeAttached();
    await expect(page.locator('[id="cash.import.resolve"]:visible')).toHaveCount(0);
    await page.screenshot({ path: info.outputPath("local-csv-size-refusal.png") });
    return;
  }
  const crashConfirmation = scenario === "normal" || interruptArchive;
  let archiveCrash: Promise<void> | undefined;
  if (crashConfirmation) {
    await expect.poll(() => previewWorker()?.evaluate(() => typeof self.onmessage)).toBe("function");
    const worker = previewWorker()!;
    if (interruptArchive) archiveCrash = new Promise(resolve => worker.once("close", () => resolve()));
    await worker.evaluate((failOn) => {
      const handle = self.onmessage!;
      self.onmessage = function (event) {
        if (event.data?.operation === failOn) throw new Error("synthetic confirmation Worker crash");
        return handle.call(this, event);
      };
    }, interruptArchive ? "assembleArchive" : "freezeConfirmation");
  }
  if (interruptSnapshot) await page.evaluate(() => {
    let interruptedReceipt = false;
    const postMessage = Worker.prototype.postMessage;
    Worker.prototype.postMessage = function (message, transfer) {
      if (message?.operation === "encodeSubmission" && message.payload.receipt && !interruptedReceipt) {
        interruptedReceipt = true;
        Object.assign(window, { interruptedReceipt });
        queueMicrotask(() => this.dispatchEvent(new ErrorEvent("error", { message: "synthetic receipt Worker crash" })));
        return;
      }
      if (message?.operation === "prepareSnapshot") {
        queueMicrotask(() => this.dispatchEvent(new ErrorEvent("error", { message: "synthetic snapshot Worker crash" })));
        return;
      }
      return postMessage.call(this, message, transfer ?? []);
    };
  });
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
  if (unchangedAfterConflict) {
    diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404, 409] });
    const other = await page.request.post(`${baseURL}/api/v1/accounts`, {
      headers: { ...setupHeaders, "Idempotency-Key": crypto.randomUUID() },
      data: { name: "无关的现金账户", type: "cash", currencies: ["CNY"] },
    });
    expect(other.ok()).toBe(true);
    await page.evaluate((crash) => {
      const rebaseAttempts: string[] = [];
      Object.assign(window, { rebaseAttempts });
      const postMessage = Worker.prototype.postMessage;
      Worker.prototype.postMessage = function (message, transfer) {
        if (message?.operation === "rebaseImport") {
          rebaseAttempts.push(message.payload.base);
          if (crash && rebaseAttempts.length === 1) {
            queueMicrotask(() => this.dispatchEvent(new ErrorEvent("error", { message: "synthetic rebase Worker crash" })));
            return;
          }
        }
        return postMessage.call(this, message, transfer ?? []);
      };
    }, scenario === "rebase-worker");
    if (scenario === "rebase-worker") {
      const rejected = page.waitForResponse((response) => response.request().method() === "POST"
        && new URL(response.url()).pathname === "/api/v1/cash-import/write-set" && response.status() === 409);
      await page.mouse.click(1350, 1072);
      expect((await rejected).status()).toBe(409);
      const resume = page.locator('[id="cash.import.resolve"]:visible').last();
      await expect(resume).toBeEnabled({ timeout: 30_000 });
      expect(writeKeys).toHaveLength(1);
      await page.screenshot({ path: info.outputPath("local-csv-rebase-worker-retry.png") });
    }
  }
  if (scenario === "pack-fence") {
    await page.evaluate(() => {
      const hooks = window as Window & { releasePackedWrite?: () => void; packedWriteReady?: boolean };
      const post = Worker.prototype.postMessage;
      Worker.prototype.postMessage = function (message, transfer) {
        if (message.operation === "packJsonRequest") {
          this.addEventListener("message", event => {
            if (event.data?.requestId === message.requestId && event.data.result?.blob) hooks.packedWriteReady = true;
          }, { once: true });
          hooks.releasePackedWrite = () => post.call(this, message, transfer ?? []);
          return;
        }
        return post.call(this, message, transfer ?? []);
      };
    });
    await page.mouse.click(1350, 1072);
    await page.waitForFunction(() => Boolean((window as Window & { releasePackedWrite?: () => void }).releasePackedWrite));
    await page.evaluate(() => {
      localStorage.setItem("finance-tracker:session-token", "superseded-synthetic-session");
      (window as Window & { releasePackedWrite: () => void }).releasePackedWrite();
    });
    await page.waitForFunction(() => (window as Window & { packedWriteReady?: boolean }).packedWriteReady === true);
    await page.waitForTimeout(750);
    expect(writeKeys).toEqual([]);
    return;
  }
  const saved = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/cash-import/write-set"
    && (!unchangedAfterConflict || response.status() !== 409), { timeout: 30_000 });
  // The real confirm button in the captured 1440 x 1100 review canvas sits here;
  // the previous mapping footer has different bounds, and its DOM mirror is stale.
  if (scenario === "rebase-worker") {
    await clickStableComposeTarget(page, page.locator('[id="cash.import.resolve"]:visible').last());
  } else await page.mouse.click(1350, 1072);
  if (scenario === "normal") {
    const retry = page.locator('[id="cash.import.resolve"]:visible').last();
    await expect(retry).toBeEnabled();
    expect(writeKeys).toEqual([]);
    await page.screenshot({ path: info.outputPath("local-csv-confirmation-worker-failed.png") });
    await clickStableComposeTarget(page, retry);
  }
  const response = await saved;
  expect(response.status()).toBe(200);
  const receipt = await response.json();
  expect(receipt.committed).toBe(true);
  expect(receipt.summary.created_posted).toBe(1);
  if (unchangedAfterConflict) {
    const attempts = await page.evaluate(() => (window as Window & { rebaseAttempts: string[] }).rebaseAttempts);
    expect(attempts).toHaveLength(scenario === "rebase-worker" ? 2 : 1);
    expect(new Set(attempts).size).toBe(1);
    expect(writeKeys).toEqual([writeKeys[0], `${writeKeys[0]}:attempt:1`]);
  }
  if (interruptSnapshot) {
    const result = page.locator('[id="cash.import.result"]:visible').last();
    await expect(result.getByText("账本已保存，结果整理尚未完成。", { exact: true })).toBeVisible({ timeout: 30_000 });
    expect(await page.evaluate(() => (window as Window & { interruptedReceipt?: boolean }).interruptedReceipt)).toBe(true);
    await page.screenshot({ path: info.outputPath("local-csv-snapshot-worker-interrupted.png") });
    await page.addInitScript(() => {
      const operations: string[] = [];
      Object.assign(window, { recoveryWorkerOps: operations });
      const postMessage = Worker.prototype.postMessage;
      Worker.prototype.postMessage = function (message, transfer) {
        operations.push(message?.operation);
        return postMessage.call(this, message, transfer ?? []);
      };
    });
    await page.reload();
    await expect(result).toBeVisible({ timeout: 30_000 });
    await expect(result.getByText("账本已保存，结果整理尚未完成。", { exact: true })).toHaveCount(0);
    await expect.poll(() => page.evaluate(() =>
      (window as Window & { recoveryWorkerOps: string[] }).recoveryWorkerOps.includes("inspectSubmission"))).toBe(true);
    expect(writeKeys).toHaveLength(1);
    expect(receiptLookups).toHaveLength(1);
  }
  if (interruptCompletion) {
    const result = page.locator('[id="cash.import.result"]:visible').last();
    await expect(result.getByText("暂时无法显示最新流水。", { exact: true })).toBeVisible({ timeout: 30_000 });
    await expect(result.getByRole("button", { name: "返回账本" })).toBeEnabled();
    await expect(page.locator('[id="cash.import.resolve"]:visible')).toHaveCount(0);
    expect(completionFailures.length).toBeGreaterThan(0);
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-completion-interrupted.png") });
  }
  if (interruptArchive) {
    await archiveCrash;
    const result = page.locator('[id="cash.import.result"]:visible').last();
    await expect(result).toBeVisible({ timeout: 30_000 });
    const pending = result.getByText("账本已保存，结果整理尚未完成。", { exact: true });
    const resume = page.locator('[id="cash.import.resolve"]:visible').last();
    expect(writeKeys).toHaveLength(1);
    if (await pending.isVisible()) {
      await expect(resume).toHaveText("继续整理结果");
      await page.screenshot({ path: info.outputPath("local-csv-completion-interrupted.png") });
      blockCompletionRefresh = false;
      if (await resume.isEnabled()) await clickStableComposeTarget(page, resume);
    }
    await expect(pending).toHaveCount(0);
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
  expect(writeKeys).toHaveLength((recheckAfterConflict || unchangedAfterConflict) ? 2 : 1);
  if (recheckAfterConflict) expect(writeKeys[1]).toBe(`${writeKeys[0]}:attempt:1`);
  expect(writeKeys[0]).not.toBe("");
  expect(oldImportRequests).toEqual([]);
  await page.screenshot({ path: info.outputPath("local-csv-saved.png") });
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
  const expectedApiErrors = recheckAfterConflict || unchangedAfterConflict ? [
    "404 /api/v1/cash-mutations/receipts/by-key", "409 /api/v1/cash-import/write-set",
    "404 /api/v1/cash-mutations/receipts/by-key", "404 /api/v1/cash-mutations/receipts/by-key",
    ...(scenario === "rebase-worker" ? ["404 /api/v1/cash-mutations/receipts/by-key", "404 /api/v1/cash-mutations/receipts/by-key"] : []),
  ] : ["404 /api/v1/cash-mutations/receipts/by-key",
    ...completionFailures.map((path) => "503 " + path)];
  expect(diagnostics.expectedApiErrors).toEqual(expectedApiErrors);
  if (interruptCompletion) {
    blockCompletionRefresh = false;
    await page.reload();
    const result = page.locator('[id="cash.import.result"]:visible').last();
    await expect(result.getByRole("button", { name: "查看账本" })).toBeEnabled({ timeout: 30_000 });
    await expect(result.getByText("暂时无法显示最新流水。", { exact: true })).toHaveCount(0);
    expect(writeKeys).toHaveLength(1);
    await page.screenshot({ path: info.outputPath("local-csv-completion-recovered.png") });
  }

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
  expect(writeKeys).toHaveLength((recheckAfterConflict || unchangedAfterConflict) ? 2 : 1);
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
  expect(writeKeys).toHaveLength((recheckAfterConflict || unchangedAfterConflict) ? 2 : 1);
  expect(oldImportRequests).toEqual([]);
  await info.attach("local-import-protocol", { body: JSON.stringify({ oldImportRequests, writeKeys, receipt,
    browserRestarted: true, completionFailures, onlineCatalogRequests, onlineWorkspace: workspace, coldRequests, coldPageErrors, reverifiedWorkspace: workspace }, null, 2), contentType: "application/json" });
  } finally {
    try { await stopTrace(); }
    finally { await context?.close(); await rm(profile, { recursive: true, force: true }); }
  }
});
}

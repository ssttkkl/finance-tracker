import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { captureBrowserDiagnostics, clickStableComposeTarget, typeIntoComposeInput } from "./compose-fixtures";

const fixtureDirectory = fileURLToPath(new URL("../../../backend/src/test/resources/cash-import-slice/", import.meta.url));

test("真实后端导入匿名月度 CSV、XLS、XLSX、PDF 并在账本读取结果", async ({ page, baseURL }) => {
  test.setTimeout(240_000);
  if (!baseURL) throw new Error("A local backend preview is required");
  await page.setViewportSize({ width: 1440, height: 3000 });
  const diagnostics = captureBrowserDiagnostics(page, { expectedErrorStatuses: [404] });
  const email = `cash-slice-${crypto.randomUUID()}@example.invalid`;
  const password = `Fixture-test-${crypto.randomUUID()}`;
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, { data: { email, password } });
  expect(authentication.status()).toBe(200);
  const { access_token: token } = await authentication.json();
  const headers = { Authorization: `Bearer ${token}` };
  const workspaceResponse = await page.request.post(`${baseURL}/api/v1/auth/workspaces`, {
    headers, data: { name: "匿名月度账单回归" },
  });
  expect(workspaceResponse.status()).toBe(200);
  const { active_workspace_id: workspace } = await workspaceResponse.json();
  const accountNames = new Map([
    ["alipay", "测试支付宝资金"], ["wechat", "测试微信资金"],
    ["ccb_debit", "测试建行资金"], ["icbc_debit", "测试工行资金"],
  ]);
  const accountIds = new Map<string, string>();
  for (const [source, name] of accountNames) {
    const created = await page.request.post(`${baseURL}/api/v1/accounts`, {
      headers: { ...headers, "Idempotency-Key": `fixture-account-${source}` },
      data: { name, type: "cash", currencies: ["CNY"] },
    });
    expect(created.status()).toBe(201);
    accountIds.set(source, (await created.json()).account_id);
  }
  const oldRequests: string[] = [];
  const importPosts: string[] = [];
  let commits = 0;
  page.on("request", (request) => {
    const pathname = new URL(request.url()).pathname;
    if (/\/cash-import\/(sessions|plans)(?:\/|$)/.test(pathname)
      || request.method() === "GET" && pathname.startsWith("/api/v1/cash-projections")) oldRequests.push(pathname);
    if (request.method() === "POST" && pathname.startsWith("/api/v1/cash-import/")) importPosts.push(pathname);
    if (request.method() === "POST" && pathname === "/api/v1/cash-import/write-set") commits++;
  });
  async function snapshot() {
    const response = await page.request.get(`${baseURL}/api/v1/ledger/snapshot?workspace_id=${workspace}`, { headers });
    expect(response.status()).toBe(200);
    return response.json();
  }
  await page.goto(`/w/${workspace}/cash-import`);
  await expect(page.locator('[id="auth.email"]')).toHaveCount(1);
  await typeIntoComposeInput(page, '[id="auth.email"]', email);
  await typeIntoComposeInput(page, '[id="auth.password"]', password);
  await clickStableComposeTarget(page, page.locator('[id="auth.submit"]:visible').last());
  const addFiles = page.locator('[id="cash.import.add-files"]:visible').last();
  await expect(addFiles).toBeEnabled();
  const chooser = page.waitForEvent("filechooser");
  await clickStableComposeTarget(page, addFiles);
  await (await chooser).setFiles([
    "alipay-01.csv", "wechat-01.xlsx", "ccb-debit-01.xls", "icbc-debit-01.pdf",
  ].map((name) => path.join(fixtureDirectory, name)));
  await expect(page.locator('[id^="cash.import.file-"]:visible')).toHaveCount(4, { timeout: 60_000 });
  const next = page.locator('[id="cash.import.to-accounts"]:visible').last();
  await expect(next).toBeEnabled();
  expect((await snapshot()).facts.cash_transactions).toHaveLength(0);
  expect(commits).toBe(0);
  await clickStableComposeTarget(page, next);
  await expect(page.locator('[id="cash.import.mapping-step"]:visible')).toHaveCount(1);
  await clickStableComposeTarget(page, page.locator('[id="cash.import.merge-same-name"]:visible').last());

  // Capture the real channel headings and controls before any popup can replace
  // the Compose semantics mirror (CMP-10623). No server plan or private state is read.
  const channelLabels = new Map([
    ["alipay", "支付宝"], ["wechat", "微信"], ["ccb_debit", "建设银行"], ["icbc_debit", "工商银行借记卡"],
  ]);
  const headings: Array<{ source: string; y: number }> = [];
  for (const [source, label] of channelLabels) {
    const heading = page.getByText(label, { exact: true }).filter({ visible: true }).first();
    await expect(heading).toBeVisible();
    headings.push({ source, y: (await heading.boundingBox())!.y });
  }
  headings.sort((left, right) => left.y - right.y);
  const groups = page.locator('[id^="cash.import.account-group-"]:visible');
  expect(await groups.count()).toBeGreaterThanOrEqual(4);
  const controls: Array<{ ref: string; source: string; x: number; y: number; height: number; resetOnMerge: boolean }> = [];
  for (const group of await groups.all()) {
    const ref = (await group.getAttribute("id"))!.replace("cash.import.account-group-", "");
    const target = page.locator(`[id="cash.import.account-${ref}"]:visible`).last();
    const box = await target.boundingBox();
    expect(box).not.toBeNull();
    expect(box!.y + box!.height).toBeLessThanOrEqual(3000);
    const source = headings.findLast((heading) => heading.y <= box!.y)!.source;
    const resetOnMerge = /工商银行信用卡\(7001\)|建设银行储蓄卡\(7007\)/.test(await group.innerText())
      && ["alipay", "wechat"].includes(source);
    controls.push({ ref, source, x: box!.x + box!.width / 2, y: box!.y + box!.height / 2, height: box!.height, resetOnMerge });
  }
  expect(new Set(controls.map((control) => control.source))).toEqual(new Set(accountNames.keys()));
  async function settleControlGeometry() {
    let previous = "";
    let stable = 0;
    let review: { x: number; y: number; width: number; height: number } | null = null;
    await expect.poll(async () => {
      const boxes = await Promise.all([
        ...controls.map((control) => page.locator(`[id="cash.import.account-${control.ref}"]:visible`).last().boundingBox()),
        page.locator('[id="cash.import.review"]:visible').last().boundingBox(),
      ]);
      if (boxes.some((box) => !box || box.x < 0 || box.y < 0 || box.x + box.width > 1441 || box.y + box.height > 3001)) return false;
      const geometry = JSON.stringify(boxes.map((box) => Object.values(box!).map(Math.round)));
      stable = geometry === previous ? stable + 1 : 0;
      previous = geometry;
      for (const [index, control] of controls.entries()) {
        const box = boxes[index]!;
        control.x = box.x + box.width / 2; control.y = box.y + box.height / 2; control.height = box.height;
      }
      review = boxes.at(-1)!;
      return stable >= 3;
    }, { intervals: [100] }).toBe(true);
    return review!;
  }
  const reviewBox = await settleControlGeometry();
  await page.screenshot({ path: test.info().outputPath("account-mapping-before.png") });
  async function selectAccounts(values: typeof controls) {
  for (const control of values) {
    await page.mouse.click(control.x, control.y);
    const menu = page.locator('[id="choice-overlay-menu"]:visible').last();
    await expect(menu).toBeVisible();
    await expect.poll(async () => {
      const box = await menu.boundingBox();
      return box != null && (Math.abs(box.y - control.y - control.height / 2) < 24
        || Math.abs(box.y + box.height - control.y + control.height / 2) < 24);
    }).toBe(true);
    await clickStableComposeTarget(page, menu.getByText(accountNames.get(control.source)!, { exact: true }).last());
    let previous: Buffer | undefined;
    let stable = 0;
    await expect.poll(async () => {
      const frame = await page.screenshot();
      stable = previous?.equals(frame) ? stable + 1 : 0;
      previous = frame;
      return stable >= 3;
    }, { intervals: [100] }).toBe(true);
  }
  }
  await selectAccounts(controls);
  await page.screenshot({ path: test.info().outputPath("account-mapping-selected.png") });
  const currenciesResponse = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/cash-import/accounts/currency-requirements");
  await page.mouse.click(reviewBox.x + reviewBox.width / 2, reviewBox.y + reviewBox.height / 2);
  const currenciesHttp = await currenciesResponse;
  expect(currenciesHttp.status()).toBe(200);
  expect(currenciesHttp.request().postDataJSON().items).toEqual([{
    idempotency_key: expect.any(String), account_id: accountIds.get("icbc_debit"), expected_revision: 1, currencies: ["USD"],
  }]);
  const currenciesResult = (await currenciesHttp.json()).results;
  expect(currenciesResult).toHaveLength(1);
  expect(currenciesResult[0].status).toBe("updated");
  expect(currenciesResult[0].account.currencies).toEqual(["CNY", "USD"]);
  expect(currenciesResult[0].account.account_revision).toBe(2);
  expect(currenciesResult[0].account.committed).toBe(true);
  // The disposed popup can remain the sole semantics tree (CMP-10623). Capture
  // the actual review, then restore through a real reload before asserting its controls.
  let reviewFrame: Buffer | undefined;
  let stableReview = 0;
  await expect.poll(async () => {
    const frame = await page.screenshot();
    stableReview = reviewFrame?.equals(frame) ? stableReview + 1 : 0;
    reviewFrame = frame;
    return stableReview >= 3;
  }, { intervals: [100] }).toBe(true);
  expect((await snapshot()).facts.cash_transactions).toHaveLength(0);
  expect(commits).toBe(0);
  await page.screenshot({ path: test.info().outputPath("review-before-reload.png") });
  await page.reload();
  // A reload restores the original encrypted preparation and choices, then recomputes.
  await expect(page.locator('[id="cash.import.to-accounts"]:visible').last()).toBeEnabled();
  await clickStableComposeTarget(page, page.locator('[id="cash.import.to-accounts"]:visible').last());
  // The default merged view clears conflicting targets in the two shared card
  // groups. Unique choices survive; explicitly restore the four split choices.
  await clickStableComposeTarget(page, page.locator('[id="cash.import.merge-same-name"]:visible').last());
  expect(controls.filter((control) => control.resetOnMerge)).toHaveLength(4);
  for (const control of controls) {
    await expect(page.locator(`[id="cash.import.account-group-${control.ref}"]:visible`)
      .getByText(control.resetOnMerge ? "暂不选择账户" : accountNames.get(control.source)!, { exact: true }).last()).toBeVisible();
    const box = (await page.locator(`[id="cash.import.account-${control.ref}"]:visible`).last().boundingBox())!;
    control.x = box.x + box.width / 2; control.y = box.y + box.height / 2; control.height = box.height;
  }
  const restoredReviewBox = await settleControlGeometry();
  await page.screenshot({ path: test.info().outputPath("restored-account-choices.png") });
  await selectAccounts(controls.filter((control) => control.resetOnMerge));
  await page.mouse.click(restoredReviewBox.x + restoredReviewBox.width / 2, restoredReviewBox.y + restoredReviewBox.height / 2);
  reviewFrame = undefined; stableReview = 0;
  await expect.poll(async () => {
    const frame = await page.screenshot();
    stableReview = reviewFrame?.equals(frame) ? stableReview + 1 : 0;
    reviewFrame = frame;
    return stableReview >= 3;
  }, { intervals: [100] }).toBe(true);
  await page.screenshot({ path: test.info().outputPath("review-after-reload.png") });
  expect((await snapshot()).facts.cash_transactions).toHaveLength(0);
  const confirmation = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/cash-import/write-set");
  const completionSnapshot = page.waitForResponse((response) => response.request().method() === "GET"
    && new URL(response.url()).pathname === "/api/v1/ledger/snapshot" && response.status() === 200);
  // The same popup disposal leaves only its old DOM mirror. The real canvas
  // confirmation occupies the existing bottom-right action, as in the CSV flow.
  await page.mouse.click(1350, 2972);
  const confirmed = await confirmation;
  expect(confirmed.status()).toBe(200);
  const submitted = confirmed.request().postDataJSON().changes.transactions;
  expect(submitted).toHaveLength(206);
  for (const row of submitted) expect(row.values.account_id).toBe(accountIds.get(row.values.source_type));
  const receipt = await confirmed.json();
  expect(receipt.summary.created_posted).toBe(206);
  expect(receipt.summary.saved_draft).toBe(0);
  expect(receipt.pending_item_count).toBe(0);
  expect(receipt.commit_outcome.has_new_failures).toBe(false);
  expect(commits).toBe(1);
  await completionSnapshot;
  reviewFrame = undefined; stableReview = 0;
  await expect.poll(async () => {
    const frame = await page.screenshot();
    stableReview = reviewFrame?.equals(frame) ? stableReview + 1 : 0;
    reviewFrame = frame;
    return stableReview >= 3;
  }, { intervals: [100] }).toBe(true);
  await page.screenshot({ path: test.info().outputPath("completed-result-before-reload.png") });
  await page.reload();
  await expect(page.locator('[id="cash.import.result"]:visible')).toHaveCount(1);
  await expect(page.getByText("已处理 206 条账单明细", { exact: true })).toBeVisible();
  await expect(page.getByText("账本已保存，结果整理尚未完成。", { exact: true })).toHaveCount(0);
  const saved = await snapshot();
  const expectedCounts = { alipay: 75, wechat: 102, ccb_debit: 11, icbc_debit: 18 };
  expect(saved.facts.cash_transactions).toHaveLength(206);
  for (const [source, count] of Object.entries(expectedCounts)) {
    const rows = saved.facts.cash_transactions.filter((row: { source_type: string }) => row.source_type === source);
    expect(rows).toHaveLength(count);
    for (const row of rows) expect(row.account_id).toBe(accountIds.get(source));
  }
  await clickStableComposeTarget(page, page.locator('[id="cash.import.return"]:visible').last());
  await expect(page).toHaveURL(new RegExp(`/w/${workspace}/$`));
  await page.reload();
  await expect(page.locator('[id="ledger.screen"]:visible')).toHaveCount(1);
  await expect(page.locator('[id^="ledger.record-"]:visible').first()).toBeVisible();
  await page.screenshot({ path: test.info().outputPath("returned-local-ledger.png") });
  const receiptRead = await page.request.get(`${baseURL}/api/v1/cash-mutations/receipts/${receipt.receipt_id}?workspace_id=${workspace}`, { headers });
  expect(receiptRead.status()).toBe(200);
  expect((await receiptRead.json()).summary).toEqual(receipt.summary);
  expect(commits).toBe(1);
  expect(oldRequests).toEqual([]);
  expect(importPosts.filter((value) => value.endsWith("/pdf/parse"))).toHaveLength(1);
  expect(importPosts.filter((value) => value.endsWith("/xls/parse"))).toHaveLength(1);
  expect(importPosts.filter((value) => value.endsWith("/accounts/currency-requirements"))).toHaveLength(1);
  expect(new Set(importPosts)).toEqual(new Set([
    "/api/v1/cash-import/pdf/parse", "/api/v1/cash-import/xls/parse",
    "/api/v1/cash-import/accounts/currency-requirements", "/api/v1/cash-import/write-set",
  ]));
  await diagnostics.settle();
  expect(diagnostics.expectedApiErrors).toEqual(["404 /api/v1/cash-mutations/receipts/by-key"]);
  expect(diagnostics.diagnosticLogs).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

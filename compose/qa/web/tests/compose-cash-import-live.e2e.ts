import { expect, test } from "@playwright/test";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { captureBrowserDiagnostics, clickStableComposeTarget } from "./compose-fixtures";

const fixtureDirectory = fileURLToPath(new URL("../../../backend/src/test/resources/cash-import-slice/", import.meta.url));

test("真实后端导入匿名月度 CSV、XLS、XLSX、PDF 并在账本读取结果", async ({ page, baseURL }) => {
  test.setTimeout(240_000);
  if (!baseURL) throw new Error("A local backend preview is required");
  await page.setViewportSize({ width: 1440, height: 3000 });
  const diagnostics = captureBrowserDiagnostics(page);
  const authentication = await page.request.post(`${baseURL}/api/v1/auth/register`, {
    data: { email: `cash-slice-${crypto.randomUUID()}@example.invalid`, password: `Fixture-test-${crypto.randomUUID()}` },
  });
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
  await page.addInitScript((value) => localStorage.setItem("finance-tracker:session-token", value), token);
  const contexts: Array<{ session_id: string; files: Array<{ status: string; source_type: string; recognized_row_count: number }>; source_accounts: Array<{ source_account_ref: string; source_type: string }> }> = [];
  const contextReads: Array<Promise<void>> = [];
  let commits = 0;
  let previews = 0;
  page.on("response", (response) => {
    const url = new URL(response.url());
    if (/\/cash-import\/sessions(?:\/[^/]+(?:\/files)?)?$/.test(url.pathname) && [200, 201].includes(response.status())) {
      contextReads.push(response.json().then((value) => {
        if (Array.isArray(value.files)) contexts.push(value);
      }));
    }
  });
  page.on("request", (request) => {
    if (request.method() === "POST" && /\/cash-import\/plans\/[^/]+\/commit$/.test(new URL(request.url()).pathname)) commits++;
    if (request.method() === "POST" && /\/cash-import\/sessions\/[^/]+\/plans$/.test(new URL(request.url()).pathname)) previews++;
  });
  await page.goto(`/w/${workspace}/cash-import`);
  const addFiles = page.locator('[id="cash.import.add-files"]:visible').last();
  await expect(addFiles).toBeEnabled();
  const chooser = page.waitForEvent("filechooser");
  await clickStableComposeTarget(page, addFiles);
  await (await chooser).setFiles([
    "alipay-01.csv", "wechat-01.xlsx", "ccb-debit-01.xls", "icbc-debit-01.pdf",
  ].map((name) => path.join(fixtureDirectory, name)));
  await expect.poll(async () => {
    await Promise.all(contextReads);
    return contexts.some((value) => value.files.length === 4 && value.files.every((file) => file.status === "ready"));
  }, { timeout: 60_000, message: "All four actual formats must be parsed by the backend" }).toBe(true);
  const context = contexts.findLast((value) => value.files.length === 4 && value.files.every((file) => file.status === "ready"))!;
  expect(context.files.map((file) => `${file.source_type}:${file.recognized_row_count}`).sort()).toEqual([
    "alipay:75", "wechat:102", "ccb_debit:11", "icbc_debit:18",
  ].sort());
  const before = await page.request.get(`${baseURL}/api/v1/cash-projections?start_index=0`, { headers });
  expect((await before.json()).total_count).toBe(0);
  expect(commits).toBe(0);
  const next = page.locator('[id="cash.import.to-accounts"]:visible').last();
  await expect(next).toBeEnabled();
  await clickStableComposeTarget(page, next);
  await expect(page.locator('[id="cash.import.mapping-step"]:visible')).toHaveCount(1);
  await clickStableComposeTarget(page, page.locator('[id="cash.import.merge-same-name"]:visible').last());
  const accountTargets = context.source_accounts.map((group) =>
    page.locator(`[id="cash.import.account-${group.source_account_ref}"]:visible`).last());
  const reviewTarget = page.locator('[id="cash.import.review"]:visible').last();
  // CMP-10623 can leave only the disposed popup in the semantics mirror. Keep
  // the whole fixed-height mapping form in view and capture its stable geometry
  // before opening any popup; verify every actual selection in the plan request.
  let previous = "";
  let stable = 0;
  let boxes: Array<{ x: number; y: number; width: number; height: number }> = [];
  await expect.poll(async () => {
    const values = await Promise.all([...accountTargets, reviewTarget].map((target) => target.boundingBox()));
    if (values.some((box) => !box || box.x < 0 || box.y < 0 || box.x + box.width > 1441 || box.y + box.height > 3001)) return false;
    boxes = values as typeof boxes;
    const geometry = JSON.stringify(boxes.map((box) => Object.values(box).map(Math.round)));
    stable = geometry === previous ? stable + 1 : 0;
    previous = geometry;
    return stable >= 3;
  }, { intervals: [100], message: "All account controls must settle within the mapping viewport" }).toBe(true);
  await page.screenshot({ path: test.info().outputPath("account-mapping-before.png") });
  let index = 0;
  for (const group of context.source_accounts) {
    const anchor = boxes[index++];
    await page.mouse.click(anchor.x + anchor.width / 2, anchor.y + anchor.height / 2);
    const menu = page.locator('[id="choice-overlay-menu"]:visible').last();
    await expect(menu).toBeVisible();
    await expect.poll(async () => {
      const bounds = await menu.boundingBox();
      return bounds != null && (Math.abs(bounds.y - anchor.y - anchor.height) < 24
        || Math.abs(bounds.y + bounds.height - anchor.y) < 24);
    }, { message: "The menu must belong to the current account control" }).toBe(true);
    await clickStableComposeTarget(page, menu.getByText(accountNames.get(group.source_type)!, { exact: true }).last());
    let lastFrame: Buffer | undefined;
    let stableFrames = 0;
    await expect.poll(async () => {
      const frame = await page.screenshot();
      stableFrames = lastFrame?.equals(frame) ? stableFrames + 1 : 0;
      lastFrame = frame;
      return stableFrames >= 3;
    }, { intervals: [100], message: "The actual canvas must finish closing the account popup before the next click" }).toBe(true);
  }
  await page.screenshot({ path: test.info().outputPath("account-mapping-selected.png") });
  const currenciesResponse = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === "/api/v1/cash-import/accounts/currency-requirements", { timeout: 30_000 });
  const previewResponse = page.waitForResponse((response) => response.request().method() === "POST"
    && /\/cash-import\/sessions\/[^/]+\/plans$/.test(new URL(response.url()).pathname), { timeout: 30_000 });
  const reviewBounds = boxes.at(-1)!;
  await page.mouse.click(reviewBounds.x + reviewBounds.width / 2, reviewBounds.y + reviewBounds.height / 2);
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
  const previewHttp = await previewResponse;
  expect(previewHttp.status()).toBe(200);
  const preview = await previewHttp.json();
  expect(preview.can_commit).toBe(true);
  const submitted = previewHttp.request().postDataJSON().account_selections;
  expect(submitted).toHaveLength(context.source_accounts.length);
  for (const group of context.source_accounts) {
    expect(submitted.find((choice: { source_account_ref: string }) => choice.source_account_ref === group.source_account_ref).account_id)
      .toBe(accountIds.get(group.source_type));
  }
  // Exercise recovery of the exact frozen plan. A real reload also resets the
  // disposed account popup's stale Compose semantics mirror (CMP-10623).
  await page.screenshot({ path: test.info().outputPath("review-before-reload.png") });
  const recoveredPlan = page.waitForResponse((response) => response.request().method() === "GET"
    && new URL(response.url()).pathname === `/api/v1/cash-import/plans/${preview.plan_id}`);
  await page.reload();
  const recoveredHttp = await recoveredPlan;
  expect(recoveredHttp.status()).toBe(200);
  const recovered = await recoveredHttp.json();
  expect(recovered.plan_id).toBe(preview.plan_id);
  expect(recovered.session_revision).toBe(preview.session_revision);
  expect(recovered.monthly_summaries).toEqual(preview.monthly_summaries);
  expect(previews).toBe(1);
  await expect(page.locator('[id="cash.import.items"]:visible')).toHaveCount(1);
  const afterPreview = await page.request.get(`${baseURL}/api/v1/cash-projections?start_index=0`, { headers });
  expect((await afterPreview.json()).total_count).toBe(0);
  expect(commits).toBe(0);
  const confirmation = page.waitForResponse((response) => response.request().method() === "POST"
    && new URL(response.url()).pathname === `/api/v1/cash-import/plans/${preview.plan_id}/commit`, { timeout: 30_000 });
  await clickStableComposeTarget(page, page.locator('[id="cash.import.confirm"]:visible').last());
  const confirmed = await confirmation;
  expect(confirmed.status()).toBe(200);
  expect(confirmed.request().postDataJSON()).toEqual({ expected_session_revision: preview.session_revision });
  const receipt = await confirmed.json();
  expect(receipt.summary.created_posted).toBe(206);
  expect(receipt.summary.saved_draft).toBe(0);
  expect(receipt.summary.skipped).toBe(0);
  expect(receipt.summary.relations_failed).toBe(0);
  expect(commits).toBe(1);
  await expect(page.locator('[id="cash.import.result"]:visible')).toHaveCount(1);
  const returnedLedger = page.waitForResponse((response) => response.request().method() === "GET"
    && new URL(response.url()).pathname === "/api/v1/cash-projections");
  await clickStableComposeTarget(page, page.locator('[id="cash.import.return"]:visible').last());
  await expect(page).toHaveURL(new RegExp(`/w/${workspace}/$`));
  const returned = await returnedLedger;
  expect(returned.status()).toBe(200);
  expect((await returned.json()).total_count).toBeGreaterThan(0);
  await page.screenshot({ path: test.info().outputPath("returned-ledger-before-reload.png") });
  // The disposed full-screen dialog has the same stale semantics limitation.
  // Read the persisted ledger through a fresh real page, with no mock or seed.
  await page.reload();
  await expect(page.locator('[id="ledger.screen"]:visible')).toHaveCount(1);
  await expect(page.locator('[id^="ledger.record-"]:visible').first()).toBeVisible();
  const ledger = await page.request.get(`${baseURL}/api/v1/cash-projections?start_index=0&hide_zero_amount=false`, { headers });
  expect(ledger.status()).toBe(200);
  expect((await ledger.json()).total_count).toBeGreaterThan(0);
  const receiptRead = await page.request.get(`${baseURL}/api/v1/cash-mutations/receipts/${receipt.receipt_id}`, { headers });
  expect(receiptRead.status()).toBe(200);
  expect((await receiptRead.json()).summary).toEqual(receipt.summary);
  expect(previews).toBe(1);
  expect(commits).toBe(1);
  await diagnostics.settle();
  expect(diagnostics.expectedApiErrors).toEqual([]);
  expect(diagnostics.diagnosticLogs).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
  expect(diagnostics.staticHttpErrors).toEqual([]);
});

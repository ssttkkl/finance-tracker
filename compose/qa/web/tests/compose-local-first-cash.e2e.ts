import { test, expect } from "@playwright/test";
import { installComposeApiFixtures, captureBrowserDiagnostics, clickStableComposeTarget, buildLedgerSnapshot } from "./compose-fixtures";

test("local ledger renders the committed snapshot expense and opens its detail", async ({ page }) => {
  const diagnostics = captureBrowserDiagnostics(page);
  const workerQueries: string[] = [];
  await page.exposeFunction("observeLedgerQuery", (mode: string) => workerQueries.push(mode));
  await page.addInitScript(() => {
    const post = Worker.prototype.postMessage;
    Worker.prototype.postMessage = function (message, transfer) {
      if (message.operation === "readLedgerPage") void (window as any).observeLedgerQuery(JSON.parse(message.payload.query).mode ?? "page");
      return post.call(this, message, transfer ?? []);
    };
  });
  await installComposeApiFixtures(page);
  await page.goto("/w/workspace-1/");
  await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible();
  const row = page.locator('[id="ledger.record-cash-entry:2001"]:visible').last();
  await expect(row).toBeVisible();
  await expect(row).toContainText("咖啡店");
  await expect(row).toContainText("-28.5 CNY");
  await clickStableComposeTarget(page, row);
  const detail = page.locator('[id="cash.current.detail"]:visible').last();
  await expect(detail).toBeVisible();
  await expect(detail).toContainText("拿铁");
  expect(workerQueries).toEqual(expect.arrayContaining(["page", "record"]));
  await diagnostics.settle();
  expect(diagnostics.consoleErrors).toEqual([]);
  expect(diagnostics.failedRequests).toEqual([]);
});

test("local ledger retains rows when a Worker fails and retries the filter without the API", async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1100 });
  const snapshot = buildLedgerSnapshot("workspace-1", [
    { transactionId: "11", entryId: "1101", occurredAt: null, amount: null, currency: null,
      counterparty: "待补充的账单", note: "待核对金额与日期", state: "draft",
      issues: [{ code: "amount_unreadable", field_path: "amount" }] },
    { transactionId: "13", entryId: "1301", occurredAt: "2026-10-01T02:00:00Z", amount: "-20.00", currency: "CNY",
      counterparty: "已确认消费", note: "已核对的消费" },
  ]);
  await installComposeApiFixtures(page, { snapshot });
  await page.goto("/w/workspace-1/");
  await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible();
  const pending = page.locator('[id="ledger.record-cash-entry:1101"]:visible').last();
  await expect(pending).toBeVisible();
  const confirmed = page.locator('[id="ledger.record-cash-entry:1301"]:visible').last();
  await expect(confirmed).toBeVisible();
  const selection = page.locator('[id="ledger.record-checkbox-cash-entry:1301"]:visible').last();
  await expect(selection).toBeEnabled();
  const ledgerWorker = page.workers().find(worker => new URL(worker.url()).hash === "#cash-ledger");
  expect(ledgerWorker).toBeDefined();
  await ledgerWorker!.evaluate(() => {
    self.onmessage = () => setTimeout(() => { throw new Error("synthetic ledger Worker crash"); }, 2_500);
  });
  const requests: string[] = [];
  await page.route("**/api/**", route => {
    requests.push(new URL(route.request().url()).pathname);
    return route.abort("failed");
  });
  await clickStableComposeTarget(page, page.locator('[id="ledger.filter.pending"]:visible').last());
  const retry = page.locator('[id="ledger.retry"]:visible').last();
  await clickStableComposeTarget(page, selection);
  await expect(page.locator('[id="ledger.selected-count"]:visible')).toHaveCount(0);
  await expect(retry).toHaveCount(0);
  await expect(retry).toBeVisible();
  await expect(confirmed).toBeVisible();
  await expect(pending).toBeVisible();
  await clickStableComposeTarget(page, retry);
  await expect(retry).toHaveCount(0);
  await expect(confirmed).toHaveCount(0);
  await expect(pending).toBeVisible();
  expect(requests).toEqual([]);
});

test("ledger Worker rebuilds the same large capture while the browser is offline", async ({ page, context }) => {
  const count = 3_000;
  const workspace = "worker-ledger";
  const ledgerSeq = "9007199254740993";
  const snapshot = buildLedgerSnapshot(workspace, Array.from({ length: count }, (_, index) => ({
    transactionId: String(index + 1), entryId: String(index + 10001), occurredAt: "2026-09-30T18:30:00Z",
    amount: "-10.0001", currency: "CNY", counterparty: "合成商户", note: "合成流水",
  })));
  snapshot.ledger_seq = ledgerSeq;
  await page.goto("/health");
  const moduleUrl = "/cash-import-worker/client.mjs";
  await page.evaluate(async ({ moduleUrl, workspace, snapshot }) => {
    const { createCashLedgerClient } = await import(moduleUrl);
    const client = createCashLedgerClient();
    const query = JSON.stringify({ workspace, filters: {}, zone: "Asia/Shanghai", start: 0, limit: 20 });
    try {
      const response = await client.page(query, JSON.stringify(snapshot)).result;
      if (!response.payload.includes('"total_count":3000')) throw new Error("Missing the complete local ledger count");
    } finally { client.close(); }
  }, { moduleUrl, workspace, snapshot });
  await context.setOffline(true);
  const result = await page.evaluate(async ({ moduleUrl, workspace, snapshot, ledgerSeq }) => {
    const { createCashLedgerClient } = await import(moduleUrl);
    const client = createCashLedgerClient();
    const filters = { counterparty: "合成商户" };
    const query = (start: number, version?: string) =>
      JSON.stringify({ workspace, filters, zone: "Asia/Shanghai", start, expectedSeq: version, limit: 20 });
    const snapshotText = JSON.stringify(snapshot);
    let frames = 0;
    let active = true;
    const recordFrame = () => {
      if (!active) return;
      frames++;
      requestAnimationFrame(recordFrame);
    };
    requestAnimationFrame(recordFrame);
    try {
      const started = performance.now();
      const first = (await client.page(query(0), snapshotText).result).payload;
      const firstPageMs = performance.now() - started;
      const firstPageFrames = frames;
      const paging = performance.now();
      const second = (await client.page(query(20, ledgerSeq)).result).payload;
      const nextPageMs = performance.now() - paging;
      const stale = (await client.page(query(20, "9007199254740992")).result).payload;
      const parsed = JSON.parse(first);
      return { exactVersion: first.includes(ledgerSeq), count: parsed.total_count, month: parsed.monthly_summaries[0]?.month,
        rows: JSON.parse(second).items.length, stale: JSON.parse(stale).code, firstPageMs, nextPageMs, firstPageFrames };
    } finally { active = false; client.close(); }
  }, { moduleUrl, workspace, snapshot, ledgerSeq });
  expect(result).toMatchObject({ exactVersion: true, count, month: "2026-10", rows: 20, stale: "read_snapshot_changed" });
  expect(result.firstPageFrames).toBeGreaterThan(0);
  console.log("Offline ledger Worker timing", result.firstPageMs, result.nextPageMs, result.firstPageFrames);
});

test("the first ledger Worker failure offers a local retry instead of an empty ledger", async ({ page }, info) => {
  await page.addInitScript(() => {
    const BrowserWorker = globalThis.Worker;
    let failFirstLedger = true;
    globalThis.Worker = class extends BrowserWorker {
      constructor(url, options) {
        super(url, options);
        if (!failFirstLedger || new URL(url, location.href).hash !== "#cash-ledger") return;
        const post = this.postMessage.bind(this);
        this.postMessage = (message, transfer) => {
          const query = JSON.parse(message.payload.query);
          if (failFirstLedger && !query.mode) {
            failFirstLedger = false;
            this.dispatchEvent(new ErrorEvent("error"));
          } else {
            post(message, transfer ?? []);
          }
        };
      }
    };
  });
  await installComposeApiFixtures(page);
  await page.goto("/w/workspace-1/");
  const retry = page.locator('[id="ledger.retry"]:visible').last();
  await expect(retry).toBeVisible();
  await expect(page.locator('[id="ledger.empty"]:visible')).toHaveCount(0);
  await page.screenshot({ path: info.outputPath("ledger-first-worker-failure.png") });
  await clickStableComposeTarget(page, retry);
  await expect(page.locator('[id="ledger.record-cash-entry:2001"]:visible').last()).toBeVisible();
  await expect(retry).toHaveCount(0);
});

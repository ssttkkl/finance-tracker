import { test, expect, type Page } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { installComposeApiFixtures, captureBrowserDiagnostics } from "./compose-fixtures";

const evidence = "/tmp/ft-compose-cash-evidence";
async function clickCanvasControl(page: Page, id: string) {
  const target = page.locator(`[id="${id}"]:visible`).last();
  await expect(target).toBeVisible();
  const rect = await target.boundingBox();
  if (!rect) throw new Error(`Missing control ${id}`);
  await page.mouse.click(rect.x + rect.width / 2, rect.y + rect.height / 2, { delay: 50 });
}

async function chipBackground(page: Page): Promise<number[]> {
  const bounds = await page.locator('[id="ledger.filter.pending"]:visible').last().boundingBox();
  if (!bounds) throw new Error("Missing pending chip bounds");
  const png = await page.screenshot({ clip: { x: bounds.x + 7, y: bounds.y + bounds.height / 2 - 3, width: 6, height: 6 } });
  return page.evaluate(async (base64) => {
    const bitmap = await createImageBitmap(new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: "image/png" }));
    const canvas = document.createElement("canvas");
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    const context = canvas.getContext("2d")!;
    context.drawImage(bitmap, 0, 0);
    const { data } = context.getImageData(0, 0, bitmap.width, bitmap.height);
    const rgb = [0, 0, 0];
    for (let i = 0; i < data.length; i += 4) for (let channel = 0; channel < 3; channel++) rgb[channel] += data[i + channel] / (data.length / 4);
    bitmap.close();
    return rgb;
  }, png.toString("base64"));
}

async function renderedDifference(page: Page, before: Buffer, after: Buffer): Promise<number> {
  return page.evaluate(async ({ before, after }) => {
    const pixels = async (base64: string) => {
      const bitmap = await createImageBitmap(new Blob([Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))], { type: "image/png" }));
      const canvas = document.createElement("canvas");
      canvas.width = bitmap.width; canvas.height = bitmap.height;
      const context = canvas.getContext("2d")!;
      context.drawImage(bitmap, 0, 0);
      const data = context.getImageData(0, 0, canvas.width, canvas.height).data;
      bitmap.close();
      return data;
    };
    const [left, right] = await Promise.all([pixels(before), pixels(after)]);
    let changed = 0;
    for (let i = 0; i < left.length; i += 4) {
      if (Math.max(...[0, 1, 2].map((c) => Math.abs(left[i + c] - right[i + c]))) > 16) changed++;
    }
    return changed / (left.length / 4);
  }, { before: before.toString("base64"), after: after.toString("base64") });
}

for (const width of [390, 768, 1440]) {
  test(`current cash list keeps unknown drafts and server pending filter at ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: width === 390 ? 844 : width === 768 ? 1024 : 900 });
    const diagnostics = captureBrowserDiagnostics(page);
    await installComposeApiFixtures(page);
    const queries: URLSearchParams[] = [];
    let mode: "rows" | "error" | "empty" = "rows";
    await page.route(/\/api\/v1\/cash-projections(?:\?|$)/, async (route) => {
      const query = new URL(route.request().url()).searchParams;
      queries.push(query);
      if (mode === "error") {
        await route.fulfill({ status: 503, json: { error: { code: "request_failed", message: "请求暂时无法完成" } } });
        return;
      }
      if (mode === "empty") {
        await route.fulfill({ json: { items: [], next_cursor: null, projection_version: 9, pending_item_count: 0,
          page_size: 20, start_index: 0, total_count: 0, filters: {}, filter_options: {}, monthly_summaries: [] } });
        return;
      }
      const pending = { projection_id: "draft-11", record_id: "source-11", transaction_id: "11", entry_id: "entry-one", occurred_at: null,
        amount: null, currency: null, economic_type: null, state: "draft", statistics_eligible: false,
        confirmation_status: "pending", attention_count: 2, counterparty: "待补充的账单", note: "待核对金额与日期" };
      const zero = { projection_id: "refund-12", record_id: "source-12", transaction_id: "12", entry_id: "entry-two", occurred_at: "2026-10-01T10:00:00Z",
        amount: "0.00", currency: "CNY", economic_type: "expense", state: "posted", statistics_eligible: true,
        confirmation_status: "confirmed", attention_count: 0, counterparty: "全额退款", note: "消费与退款已抵扣" };
      const posted = { ...zero, projection_id: "posted-13", record_id: "source-13", transaction_id: "13", entry_id: "entry-three", amount: "-20.00", counterparty: "已确认消费", note: "已核对的消费" };
      const items = query.get("confirmation_status") === "pending" ? [pending]
        : query.get("hide_zero_amount") === "false" ? [posted, zero, pending] : [posted, pending];
      await route.fulfill({ json: { projection_version: 9, pending_item_count: 1,
        items, start_index: 0, total_count: items.length,
        next_cursor: null, page_size: 20, filters: {}, filter_options: {},
        monthly_summaries: query.get("confirmation_status") === "pending" ? [] : [{ month: "2026-10", currencies: [{ currency: "CNY", income: "0.00", expense: "20.00" }] }] } });
    });
    let detailReads = 0;
    await page.route(/\/api\/v1\/cash-projections\/draft-11$/, route => route.fulfill({ json: {
      projection_version: 9, members: [{ transaction_id: "11", row_revision: 3, state: "draft", confirmation_status: "pending" }], issues: [],
    } }));
    await page.route(/\/api\/v1\/cash-records\/11$/, async (route) => {
      detailReads++;
      await route.fulfill({ json: { transaction_id: "11", state: "draft", row_revision: 3, record_origin: "import", confirmation_status: "pending",
        fields: { source_payload: { 商品: "原始账单商品", 备注: "账单原始内容" }, amount: null, occurred_at: null, currency: null, counterparty: "待补充的账单", note: "来源原文" },
        editable_fields: ["amount", "occurred_at", "currency", "account_id", "note"],
        entry: { entry_id: "entry-one", revision: 4, fields: { counterparty: "待补充的账单", note: "共同备注" }, manual_fields: ["note"], editable_fields: ["note"], supports_transfer_edit: false, original_note: "账单原始备注", original_note_token: "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" },
        issues: [{ issue_id: "31", revision: 1, code: "amount_unreadable", message: "账单金额待补充", field_path: "amount", blocking_scope: "posting", next_action: "edit", allowed_resolutions: [], pending_fields: {} }],
        relations: [], projection_ref: { transaction_id: "11", projection_id: "draft-11", attention_count: 2, projection_version: 9 } } });
    });
    let sourceReads = 0;
    let sourceRetryEnabled = false;
    await page.route(/\/api\/v1\/evidence\/cash-projections\/draft-11$/, async (route) => {
      sourceReads++;
      if (!sourceRetryEnabled) { await route.fulfill({ status: 503, json: { error: { code: "request_failed", message: "请求暂时无法完成" } } }); return; }
      await route.fulfill({ json: { projection_version: 9,
        projection: { projection_id: "draft-11", record_id: "source-11", transaction_id: "11", occurred_at: null, amount: null, currency: null, economic_type: null, state: "draft", statistics_eligible: false },
        members: [{ id: "11", row_revision: 3, occurred_at: null, amount: null, currency: null, counterparty: "来源对方", note: "来源原文", components: [{ id: "31", amount: null, currency: null }] }] } });
    });
    await page.goto("/w/workspace-1/");
    await expect(page.locator('[id="ledger.screen"]:visible').last()).toBeVisible();
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).toBeVisible();
    await expect(page.locator('[id="ledger.record-refund-12"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="ledger.record-posted-13"]:visible').last()).toContainText("-20 CNY");
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).toContainText("草稿待补充");
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).not.toContainText("多个账户");
    await mkdir(evidence, { recursive: true });
    await page.screenshot({ path: `${evidence}/list-${width}.png`, fullPage: true });
    await clickCanvasControl(page, "ledger.filter.pending");
    await expect.poll(() => queries.some((q) => q.get("confirmation_status") === "pending")).toBe(true);
    await expect(page.locator('[id="ledger.record-posted-13"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).toBeVisible();
    await clickCanvasControl(page, "ledger.filter.pending");
    await expect(page.locator('[id="ledger.record-posted-13"]:visible').last()).toBeVisible();
    await clickCanvasControl(page, "ledger.search");
    await page.mouse.move(width / 2, (page.viewportSize()?.height ?? 0) - 10);
    await page.waitForTimeout(300);
    const unfocused = await chipBackground(page);
    let focused = false;
    for (let attempt = 0; attempt < 12; attempt++) {
      await page.keyboard.press("Tab");
      await page.waitForTimeout(250);
      const current = await chipBackground(page);
      if (Math.max(...current.map((value, channel) => Math.abs(value - unfocused[channel]))) > 8) {
        focused = true;
        break;
      }
    }
    expect(focused, "Pending chip must expose its Material keyboard focus state layer").toBe(true);
    await page.keyboard.press("Space");
    await expect.poll(() => queries.at(-1)?.get("confirmation_status")).toBe("pending");
    await expect(page.locator('[id="ledger.record-posted-13"]:visible')).toHaveCount(0);
    await page.keyboard.press("Space");
    await expect(page.locator('[id="ledger.record-posted-13"]:visible').last()).toBeVisible();
    await page.screenshot({ path: `${evidence}/list-keyboard-${width}.png`, fullPage: true });
    mode = "error";
    await page.reload();
    await expect(page.locator('[id="ledger.retry"]:visible').last()).toBeVisible();
    await page.waitForLoadState("networkidle");
    await page.evaluate(async () => { await document.fonts.ready; });
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${evidence}/list-error-${width}.png`, fullPage: true });
    mode = "empty";
    await clickCanvasControl(page, "ledger.retry");
    await expect(page.locator('[id="ledger.empty"]:visible').last()).toBeVisible();
    await expect(page.locator('[id="ledger.filter.pending"]:visible')).toHaveCount(0);
    await page.screenshot({ path: `${evidence}/list-empty-${width}.png`, fullPage: true });
    mode = "rows";
    await page.reload();
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).toBeVisible();
    const beforeDetailQueries = queries.length;
    await clickCanvasControl(page, "ledger.record-draft-11");
    const detail = page.locator('[id="cash.current.detail"]:visible').last();
    await expect(detail).toContainText("共同备注");
    await expect(detail).toContainText("账单金额待补充");
    await expect(detail).not.toContainText("null");
    await expect(page.locator('[id="cash.current.sources-retry"]:visible').last()).toBeVisible();
    await page.waitForLoadState("networkidle");
    const sourcesBeforeRetry = sourceReads;
    const recordsBeforeRetry = detailReads;
    sourceRetryEnabled = true;
    await clickCanvasControl(page, "cash.current.sources-retry");
    await expect(detail).toContainText("账单原始内容");
    await expect(detail).toContainText("原始账单商品");
    await expect(detail).not.toContainText("null");
    await page.waitForTimeout(300);
    await page.screenshot({ path: `${evidence}/detail-${width}.png`, fullPage: true });
    // One explicit retry reloads one evidence response and its source record.
    expect(detailReads).toBe(recordsBeforeRetry + 1);
    expect(sourceReads).toBe(sourcesBeforeRetry + 1);
    expect(queries.length).toBe(beforeDetailQueries);
    await diagnostics.settle();
    expect(diagnostics.consoleErrors).toEqual([]);
    expect(diagnostics.failedRequests).toEqual([]);
    expect(diagnostics.staticHttpErrors).toEqual([]);
    const beforeClose = await page.screenshot();
    await clickCanvasControl(page, "ledger.record-panel.close");
    await page.waitForTimeout(700);
    const afterClose = await page.screenshot({ path: `${evidence}/after-close-${width}.png`, fullPage: true });
    expect(await renderedDifference(page, beforeClose, afterClose), "The actual detail panel must close before rebuilding its stale semantics mirror").toBeGreaterThan(0.03);
    // Rebuild the accessibility mirror after closing the current exporter dialog.
    await page.reload();
    await expect(page.locator('[id="cash.current.detail"]:visible')).toHaveCount(0);
    await expect(page.locator('[id="ledger.record-draft-11"]:visible').last()).toBeVisible();
  });
}

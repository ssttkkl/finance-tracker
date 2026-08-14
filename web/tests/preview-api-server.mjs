import { createServer } from "node:http";

const account = { id: 901, name: "预览账户", type: "cash", active: true, currencies: ["CNY", "HKD", "USD"] };
const investmentAccount = { id: 902, name: "预览投资账户", type: "security", active: true };
const portfolioPosition = {
  ticker: "AAPL.US", shares: "10", total_cost: "1000", cost_currency: "USD", is_cash: false,
  current_price: "101.25", market_value: "1012.50", profit: "12.50", quote_status: "complete", quote_reason: "ok",
  quote_currency: "USD", display_currency: null, display_market_value: null, fx_rate: null, fx_status: null, fx_reason: null,
  period_profit: "8.04", period_profit_rate: "0.0080",
};
const portfolio = {
  accounts: [{ name: investmentAccount.name, currency: "USD", positions: [portfolioPosition] }],
  total_market_value: "1012.50", total_profit: "12.50", total_profit_rate: "0.0125",
  period_profit: "8.04", period_profit_rate: "0.0080",
};
const investmentEvent = {
  event_id: "preview:investment-001", occurred_at: "2026-07-03T09:00:00+00:00", account: investmentAccount,
  record_type: "trade", record_subtype: "security", currency: "USD", note: "预览买入",
  from_asset: { ticker: "USD", amount: "1000" }, to_asset: { ticker: "AAPL.US", amount: "10" },
  commission: { amount: "0", asset: "USD" }, source_type: "preview", record_id: "investment-001", relations: [],
};
const holdings = {
  ...portfolio,
  total_market_value: null, total_profit: null, total_profit_rate: null, period_profit: null, period_profit_rate: null,
  accounts: [{ ...portfolio.accounts[0], positions: [{
    ...portfolioPosition,
    current_price: null, market_value: null, profit: null, quote_status: null, quote_reason: null, quote_currency: null,
    display_currency: null, display_market_value: null, fx_rate: null, fx_status: null, fx_reason: null,
    period_profit: null, period_profit_rate: null,
  }] }],
};
const foodCategory = { id: "preview-food", parent_id: null, name: "测试", description: null, path: [{ id: "preview-food", name: "测试" }], depth: 1, sort_order: 1, revision: 1 };
const transferCategory = { id: "preview-transfer", parent_id: null, name: "转账", description: null, path: [{ id: "preview-transfer", name: "转账" }], depth: 1, sort_order: 2, revision: 1 };
const categories = [foodCategory, transferCategory];
let portfolioStreamVersion = 0;
const port = Number(process.env.FT_PREVIEW_API_PORT ?? "8766");
const allowedOrigin = process.env.FT_PREVIEW_WEB_ORIGIN ?? "http://127.0.0.1:5173";
const previewProjection = {
  projection_id: "cash:preview-001", occurred_at: "2026-07-03T09:00:00+08:00", account,
  counterparty: "示例商户", category: foodCategory, amount: "1", currency: "CNY",
  note: "", source_type: "preview", source_types: ["preview"], record_id: "preview-001",
  economic_type: "income", transfer_subtype: null, composition: [], member_count: 1,
  accepted_relation_summary: [], visible: true, hidden_reason: null,
};
const bankSecurityProjection = {
  projection_id: "cash:preview-bank-security", occurred_at: "2026-07-02T09:00:00+08:00", account,
  counterparty: "Charles Schwab", category: transferCategory, amount: "0", currency: "USD",
  note: "", source_type: "preview", source_types: ["preview"], record_id: "preview-bank-security",
  economic_type: "internal_transfer", transfer_subtype: "bank_security_transfer", composition: [], member_count: 1,
  accepted_relation_summary: [], visible: true, hidden_reason: null,
  transfer: {
    from_account: account, from_amount: "-10000", from_currency: "HKD",
    to_account: investmentAccount, to_amount: "1275.5", to_currency: "USD",
  },
};
let projectionVersion = 1;
const page = {
  projection_version: projectionVersion,
  items: [previewProjection, bankSecurityProjection],
  next_cursor: null,
  page_size: 50,
  filters: {},
  filter_options: {
    categories,
    currencies: ["CNY", "HKD", "USD"],
    economic_types: [
      { economic_type: "income", transfer_subtypes: [] },
      { economic_type: "internal_transfer", transfer_subtypes: ["bank_security_transfer"] },
    ],
  },
};
const ledgerOptions = {
  record_types: [
    { value: "consumption", label: "消费", subtypes: [{ value: "not_applicable", label: "" }] },
    { value: "income", label: "收入", subtypes: [{ value: "not_applicable", label: "" }] },
    { value: "other", label: "其他", subtypes: [{ value: "not_applicable", label: "" }] },
    { value: "transfer_in", label: "转账入账", subtypes: [{ value: "ordinary_transfer", label: "普通转账" }] },
  ],
  relation_types: [
    { value: "payment_mirror", label: "同笔支付" },
    { value: "transfer_pair", label: "个人转账" },
    { value: "refund_offset", label: "退款冲销" },
  ],
};
const previewSession = {
  user: { email: "preview@example.com" },
  active_workspace_id: "preview-workspace",
  workspaces: [{ id: "preview-workspace", name: "预览工作区", role: "admin" }],
};
const records = new Map();
const manualRecord = {
  id: "preview-manual-001", occurred_at: "2026-07-01T09:00:00+00:00", account_name: account.name,
  account_id: account.id, account_type: account.type, amount: "0", currency: "CNY",
  counterparty: "预览手工记录", counterparty_account: "", counterparty_account_attrs: [],
  note: "", category: foodCategory, category_id: foodCategory.id, record_type: "other", record_subtype: "not_applicable",
  source_type: "", record_id: "", source_snapshot: null,
};
records.set(manualRecord.id, { record: manualRecord, relations: [], options: ledgerOptions });

function readBody(request) {
  return new Promise((resolve) => {
    const chunks = [];
    request.on("data", (chunk) => chunks.push(chunk));
    request.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
  });
}

function send(response, value, status = 200) {
  response.statusCode = status;
  response.end(JSON.stringify(value));
}
function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
function evidenceFor(projection) {
  return {
    projection_version: 1,
    projection,
    root_record: { ...projection, id: projection.record_id, source_snapshot: { merchant: projection.counterparty } },
    members: [{ ...projection, id: projection.record_id, roles: ["root"] }],
    accepted_relations: [],
    inactive_relation_hints: [],
    refund_timeline: [],
  };
}

const server = createServer(async (request, response) => {
  response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  response.setHeader("Access-Control-Allow-Credentials", "true");
  response.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
  response.setHeader("Access-Control-Allow-Headers", "Content-Type");
  response.setHeader("Content-Type", "application/json");
  if (request.method === "OPTIONS") {
    response.statusCode = 204;
    response.end();
    return;
  }
  if (request.url === "/health") {
    response.end(JSON.stringify({ status: "ok" }));
    return;
  }
  if (request.url === "/api/v1/auth/session") {
    send(response, previewSession);
    return;
  }
  if (request.url?.startsWith("/api/v1/accounts")) {
    send(response, { items: request.url.includes("view=investment") ? [investmentAccount] : [account] });
    return;
  }
  if (request.url?.startsWith("/api/v1/investment-portfolio/stream")) {
    response.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      Connection: "keep-alive",
      "X-Accel-Buffering": "no",
    });
    const timer = setTimeout(() => {
      portfolioStreamVersion += 1;
      response.write(`id: ${portfolioStreamVersion}\nevent: portfolio\ndata: ${JSON.stringify({ version: portfolioStreamVersion, portfolio })}\n\n`);
    }, 120);
    request.on("close", () => { clearTimeout(timer); response.end(); });
    return;
  }
  if (request.url?.startsWith("/api/v1/investment-portfolio/refresh") && request.method === "POST") {
    send(response, { accepted: true }, 202);
    return;
  }
  if (request.url?.startsWith("/api/v1/investment-portfolio")) {
    const isHoldingsPhase = new URL(request.url, "http://127.0.0.1").searchParams.get("phase") === "holdings";
    await wait(isHoldingsPhase ? 80 : 180);
    send(response, isHoldingsPhase ? holdings : portfolio);
    return;
  }
  if (request.url?.startsWith("/api/v1/investment-events")) {
    const ticker = new URL(request.url, "http://127.0.0.1").searchParams.get("ticker")?.toLowerCase() ?? "";
    const matches = !ticker || [investmentEvent.from_asset.ticker, investmentEvent.to_asset.ticker]
      .some((symbol) => symbol.toLowerCase().includes(ticker));
    send(response, { data_version: 1, items: matches ? [investmentEvent] : [], next_cursor: null, page_size: 50, filters: {} });
    return;
  }
  if (request.url === "/api/v1/cash-ledger/options") {
    send(response, ledgerOptions);
    return;
  }
  if (request.url === "/api/v1/cash-categories" && request.method === "GET") {
    send(response, { revision: categories.length, items: categories });
    return;
  }
  if (request.url === "/api/v1/cash-categories" && request.method === "POST") {
    const body = JSON.parse(await readBody(request) || "{}");
    const created = { id: "preview-created", parent_id: body.parent_id ?? null, name: body.name, description: body.description ?? null, path: [{ id: "preview-created", name: body.name }], depth: 1, sort_order: categories.length + 1, revision: 1 };
    categories.push(created);
    send(response, created, 201);
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-records") && request.method === "GET") {
    const id = request.url.match(/^\/api\/v1\/cash-records\/([^?]+)/)?.[1];
    if (id) {
      const detail = records.get(decodeURIComponent(id));
      if (detail) send(response, detail);
      else {
        const projection = [previewProjection, bankSecurityProjection].find((item) => item.record_id === decodeURIComponent(id));
        if (!projection) { send(response, { error: { code: "not_found" } }, 404); return; }
        send(response, {
          record: {
            id: projection.record_id,
            occurred_at: projection.occurred_at,
            account_name: projection.account.name,
            account_id: projection.account.id,
            account_type: projection.account.type,
            amount: projection.amount,
            currency: projection.currency,
            counterparty: projection.counterparty,
            counterparty_account: "",
            counterparty_account_attrs: [],
            note: projection.note,
            category: projection.category,
            category_id: projection.category?.id ?? null,
            record_type: projection.economic_type === "income" ? "income" : "other",
            record_subtype: "not_applicable",
            source_type: projection.source_type,
            record_id: projection.record_id,
            source_snapshot: { merchant: projection.counterparty },
          },
          relations: [],
          options: ledgerOptions,
        });
      }
      return;
    }
    send(response, { items: [...records.values()].map((value) => value.record), next_cursor: null });
    return;
  }
  if (request.url === "/api/v1/cash-records" && request.method === "POST") {
    const body = JSON.parse(await readBody(request) || "{}");
    const record = { ...manualRecord, ...body, id: `preview-manual-${records.size + 1}`, record_id: `manual-${records.size + 1}`, account_id: account.id, account_type: account.type, source_type: "" };
    const detail = { record, relations: [], options: ledgerOptions };
    records.set(record.id, detail);
    send(response, detail, 201);
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-records/") && request.method === "PUT") {
    const id = decodeURIComponent(request.url.split("/").pop());
    const detail = records.get(id);
    if (!detail) { send(response, { error: { code: "not_found" } }, 404); return; }
    const body = JSON.parse(await readBody(request) || "{}");
    detail.record = { ...detail.record, ...body };
    send(response, detail);
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-records/") && request.method === "DELETE") {
    const id = decodeURIComponent(request.url.split("/").pop());
    records.delete(id);
    send(response, { deleted: true, related_count: 0 });
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-import/scan")) {
    send(response, { contract: "cash-account-mapping-v1", channel: "preview", channel_label: "预览渠道", file: { name: "preview.csv", digest: "preview-digest" }, digest: "preview-digest", accounts: [account], groups: [{ group_id: "preview-group-1", display_name: "预览账单账户", masked_evidence: "账户尾号：0001", currencies: ["CNY"], row_count: 1, suggestion: { account_id: account.id, account, missing_currencies: [], mapping_revision: null } }] });
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-import/preview")) {
    send(response, { channel: "preview", channel_label: "预览渠道", file: { name: "preview.csv", digest: "preview-digest" }, columns: ["occurred_at", "amount", "currency", "account_name", "counterparty", "counterparty_account", "record_type", "record_subtype", "category", "note", "channel", "status"], items: [{ record_id: "preview-import-1", occurred_at: "2026-07-03T09:00", counterparty: "预览导入记录", counterparty_account: "", amount: "-1", currency: "CNY", account_name: account.name, record_type: "consumption", record_subtype: "not_applicable", category: "测试", note: "", channel: "preview", status: "new", message: "" }], summary: { total: 1, new: 1, existing: 0, unsupported: 0 }, relations: [] });
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-import/commit")) {
    send(response, { message: "已导入预览记录", new_rows: 1, updated_rows: 0 });
    return;
  }
  if (request.url?.startsWith("/api/v1/evidence/cash-projections/")) {
    response.end(JSON.stringify(evidenceFor(
      request.url.includes("preview-bank-security") ? bankSecurityProjection : previewProjection,
    )));
    return;
  }
  if (request.url === "/api/v1/cash-projections/categories" && request.method === "PUT") {
    const body = JSON.parse(await readBody(request) || "{}");
    const target = categories.find((item) => item.id === body.category_id) ?? null;
    for (const item of page.items) {
      if (body.projection_ids.includes(item.projection_id)) {
        item.category = target;
        item.category_id = target?.id ?? null;
      }
    }
    projectionVersion += 1;
    page.projection_version = projectionVersion;
    send(response, { projection_version: projectionVersion, projection_count: body.projection_ids.length, updated_transaction_count: body.projection_ids.length, category_id: body.category_id });
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-projections")) {
    page.projection_version = projectionVersion;
    page.filter_options.categories = categories;
    response.end(JSON.stringify(page));
    return;
  }
  send(response, { code: "not_found" }, 404);
});

server.listen(port, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}

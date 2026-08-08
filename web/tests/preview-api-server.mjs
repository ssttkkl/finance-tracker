import { createServer } from "node:http";

const account = { id: 901, name: "预览账户", type: "cash", active: true };
const investmentAccount = { id: 902, name: "预览投资账户", type: "security", active: true };
const port = Number(process.env.FT_PREVIEW_API_PORT ?? "8766");
const allowedOrigin = process.env.FT_PREVIEW_WEB_ORIGIN ?? "http://127.0.0.1:5173";
const previewProjection = {
  projection_id: "cash:preview-001", occurred_at: "2026-07-03T09:00:00+08:00", account,
  counterparty: "自包含预览投影", category: "测试", amount: "1", currency: "CNY",
  note: "", source_type: "preview", source_types: ["preview"], record_id: "preview-001",
  economic_type: "income", transfer_subtype: null, composition: [], member_count: 1,
  accepted_relation_summary: [], visible: true, hidden_reason: null,
};
const bankSecurityProjection = {
  projection_id: "cash:preview-bank-security", occurred_at: "2026-07-02T09:00:00+08:00", account,
  counterparty: "Charles Schwab", category: "转账", amount: "0", currency: "USD",
  note: "", source_type: "preview", source_types: ["preview"], record_id: "preview-bank-security",
  economic_type: "internal_transfer", transfer_subtype: "bank_security_transfer", composition: [], member_count: 1,
  accepted_relation_summary: [], visible: true, hidden_reason: null,
  transfer: {
    from_account: account, from_amount: "-10000", from_currency: "HKD",
    to_account: investmentAccount, to_amount: "1275.5", to_currency: "USD",
  },
};
const page = {
  projection_version: 1,
  items: [previewProjection, bankSecurityProjection],
  next_cursor: null,
  page_size: 50,
  filters: {},
  filter_options: {
    categories: ["测试", "转账"],
    currencies: ["CNY", "HKD", "USD"],
    economic_types: [
      { economic_type: "income", transfer_subtypes: [] },
      { economic_type: "internal_transfer", transfer_subtypes: ["bank_security_transfer"] },
    ],
  },
};
const cashInvestmentRelation = {
  kind: "cash_investment_funding", status: "accepted", direction: "cash_to_investment",
  rule_id: "cash-investment-funding-v1", cash_account: account, cash_amount: "-10000",
  cash_currency: "USD", cash_occurred_at: "2026-07-02T09:00:00+08:00", cash_counterparty: "银行",
  cash_note: "转证券", cash_source_type: "preview", cash_record_id: "cash-preview-002",
  evidence: { business_day_window: 0, candidate_count: 1, cash_record_type: "transfer_out", match_keys: ["amount", "currency"] },
};
const investmentEvent = {
  event_id: "preview:investment-001", occurred_at: "2026-07-02T01:00:00+00:00", account: investmentAccount,
  record_type: "trade", record_subtype: "security", currency: "USD", note: "预览买入",
  from_asset: { ticker: "USD", amount: "10000.000000000000000001" },
  to_asset: { ticker: "AAPL.US", amount: "100.000000000000000001" },
  commission: { asset: "USD", amount: "10.000000000000000000" }, source_type: "preview",
  record_id: "investment-001", relations: [cashInvestmentRelation],
};
const investmentPage = { data_version: 1, items: [investmentEvent], next_cursor: null, page_size: 50, filters: {} };
const investmentPortfolio = {
  total_market_value: "10125.00", total_profit: "125.00", total_profit_rate: "0.012345679012345679",
  period_profit: "86.40", period_profit_rate: "0.0061",
  accounts: [{ name: "预览投资账户", currency: "USD", positions: [
    { ticker: "AAPL.US", shares: "100.000000000000000001", total_cost: "10000.00", cost_currency: "USD", is_cash: false, current_price: "101.25", market_value: "10125.00", profit: "125.00", quote_status: "complete", quote_reason: "ok", quote_currency: "USD", display_currency: null, display_market_value: null, fx_rate: null, fx_status: null, fx_reason: null, period_profit: "86.40", period_profit_rate: "0.0086" },
  ] }],
};
function investmentEvidence() {
  return { data_version: 1, event: investmentEvent, source_snapshot: { action: "BUY" }, relations: [cashInvestmentRelation] };
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

const server = createServer((request, response) => {
  response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
  response.setHeader("Content-Type", "application/json");
  if (request.url === "/health") {
    response.end(JSON.stringify({ status: "ok" }));
    return;
  }
  const requestUrl = new URL(request.url ?? "/", `http://127.0.0.1:${port}`);
  if (requestUrl.pathname === "/api/v1/accounts") {
    response.end(JSON.stringify({ items: requestUrl.searchParams.get("view") === "investment" ? [investmentAccount] : [account] }));
    return;
  }
  if (requestUrl.pathname === "/api/v1/investment-portfolio") {
    response.end(JSON.stringify(investmentPortfolio));
    return;
  }
  if (requestUrl.pathname.startsWith("/api/v1/evidence/investment-events/")) {
    response.end(JSON.stringify(investmentEvidence()));
    return;
  }
  if (requestUrl.pathname === "/api/v1/investment-events") {
    response.end(JSON.stringify(investmentPage));
    return;
  }
  if (request.url?.startsWith("/api/v1/evidence/cash-projections/")) {
    response.end(JSON.stringify(evidenceFor(
      request.url.includes("preview-bank-security") ? bankSecurityProjection : previewProjection,
    )));
    return;
  }
  if (request.url?.startsWith("/api/v1/cash-projections")) {
    response.end(JSON.stringify(page));
    return;
  }
  response.statusCode = 404;
  response.end(JSON.stringify({ code: "not_found" }));
});

server.listen(port, "127.0.0.1");
for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}

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
  if (request.url?.startsWith("/api/v1/accounts")) {
    response.end(JSON.stringify({ items: [account] }));
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

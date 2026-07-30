import { createServer } from "node:http";

const account = { id: 901, name: "预览账户", type: "cash", active: true };
const port = Number(process.env.FT_PREVIEW_API_PORT ?? "8766");
const page = {
  projection_version: 1,
  items: [{
    projection_id: "cash:preview-001", occurred_at: "2026-07-03T09:00:00+08:00", account,
    counterparty: "自包含预览投影", category: "测试", amount: "1", currency: "CNY",
    note: "", source_type: "preview", record_id: "preview-001",
    economic_type: "income", transfer_subtype: null, composition: [], member_count: 1,
    accepted_relation_summary: [], visible: true, hidden_reason: null,
  }],
  next_cursor: null,
  page_size: 50,
  filters: {},
};

const server = createServer((request, response) => {
  response.setHeader("Access-Control-Allow-Origin", "http://127.0.0.1:5173");
  response.setHeader("Content-Type", "application/json");
  if (request.url === "/health") {
    response.end(JSON.stringify({ status: "ok" }));
    return;
  }
  if (request.url?.startsWith("/api/v1/accounts")) {
    response.end(JSON.stringify({ items: [account] }));
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

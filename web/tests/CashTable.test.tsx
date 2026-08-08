import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { CashTable } from "../src/components/CashTable";

afterEach(cleanup);

it("发生时间和月份键不固定地区时区", () => {
  const formatSource = readFileSync(resolve(process.cwd(), "src/format.ts"), "utf8");
  const tableSource = readFileSync(resolve(process.cwd(), "src/components/CashTable.tsx"), "utf8");

  expect(formatSource).not.toContain('timeZone: "Asia/Shanghai"');
  expect(tableSource).not.toContain('timeZone: "Asia/Shanghai"');
});

const projection = (projection_id: string, kind: string, note = "") => ({
  projection_id, occurred_at: "2026-07-03T09:00:00+08:00", account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: `交易对方${projection_id}`, category: "餐饮", amount: "-12.50", currency: "CNY", note, economic_type: "expense" as const,
  transfer_subtype: null, composition: [kind], member_count: 2, accepted_relation_summary: [{ kind, subtype: "", count: 1 }], source_type: "fixture", source_types: ["fixture"], record_id: `cash-${projection_id}`, visible: true, hidden_reason: null,
});

const transfer = (crossCurrency = false) => ({
  ...projection("transfer", "transfer_pair", "账户间转移"),
  counterparty: "信用账户", category: "转账", amount: "0", currency: "CNY",
  economic_type: "internal_transfer" as const, transfer_subtype: "ordinary_transfer",
  transfer: {
    from_account: { id: 101, name: "日常账户", type: "cash", active: true }, from_amount: "-200", from_currency: "CNY",
    to_account: { id: 102, name: "信用账户", type: "loan", active: true }, to_amount: crossCurrency ? "14" : "200", to_currency: crossCurrency ? "USD" : "CNY",
  },
});

it("在交易信息中展示交易对方、备注和相关记录标记，在来源列展示渠道", () => {
  render(<CashTable items={[projection("1", "payment_mirror", "午间消费"), projection("2", "refund_offset"), projection("3", "unknown_kind")]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易信息", "来源", "经济类型", "金额", "操作"]);
  expect(screen.getByRole("table")).toHaveClass("cash-table");
  expect(screen.getByRole("cell", { name: /交易对方1/ })).toHaveAttribute("headers", "cash-column-transaction-info");
  expect(screen.getAllByRole("cell", { name: "-12.50 CNY" })[0]).toHaveAttribute("data-direction", "支出");
  expect(screen.getByRole("row", { name: /交易对方1/ })).toHaveAttribute("data-projection-id", "1");
  expect(screen.getByText("午间消费")).toHaveAttribute("data-label", "备注");
  expect(screen.queryByRole("columnheader", { name: "组成方式" })).not.toBeInTheDocument();
  expect(screen.getByText("午间消费")).toBeInTheDocument();
  expect(screen.getAllByText("fixture")).toHaveLength(3);
  expect(screen.queryByText(/同笔支付关系|退款冲销关系|未识别的关系类型/)).not.toBeInTheDocument();
  expect(screen.getAllByText("相关记录")).toHaveLength(3);
  expect(screen.queryByText(/条账本记录/)).not.toBeInTheDocument();
});

it("相关记录在来源列展示所有成员来源并去重，单条记录回退为自身来源", () => {
  const single = { ...projection("single", "single"), composition: [], member_count: 1, accepted_relation_summary: [], source_types: ["支付宝"] };
  const related = { ...projection("related", "refund_offset"), source_types: ["支付宝", "工商银行"] };
  const missing = { ...projection("missing", "single"), composition: [], member_count: 1, accepted_relation_summary: [], source_type: null, source_types: [] };
  render(<CashTable items={[single, related, missing]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByRole("cell", { name: "支付宝" })).toHaveAttribute("headers", "cash-column-source");
  expect(screen.getByRole("cell", { name: "支付宝、工商银行" })).toHaveAttribute("headers", "cash-column-source");
  expect(screen.getByRole("cell", { name: "-" })).toHaveAttribute("headers", "cash-column-source");
  expect(screen.getByRole("row", { name: /交易对方related/ })).toHaveTextContent("相关记录");
});

it("仅为相关记录显示来源标记", () => {
  const single = { ...projection("single", "single"), composition: [], member_count: 1, accepted_relation_summary: [] };
  const related = { ...projection("related", "refund_offset"), member_count: 3 };
  render(<CashTable items={[single, related]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.queryByText("单源投影")).not.toBeInTheDocument();
  expect(screen.queryByText(/条账本记录/)).not.toBeInTheDocument();
  expect(screen.getByLabelText("相关记录")).toHaveTextContent("相关记录");
});

it("已确认的银证转账显示专用关系标记", () => {
  const bankSecurityTransfer = {
    ...projection("bank-security", "single"),
    amount: "0",
    currency: "USD",
    economic_type: "internal_transfer" as const,
    transfer_subtype: "bank_security_transfer",
    composition: [],
    member_count: 1,
    accepted_relation_summary: [],
  };
  render(<CashTable items={[bankSecurityTransfer]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getAllByText("银证转账", { exact: true })).toHaveLength(2);
  expect(screen.getByLabelText("银证转账")).toHaveTextContent("银证转账");
});

it("空的交易对方和备注显示横杠", () => {
  render(<CashTable items={[{ ...projection("empty", "single"), counterparty: "", note: "" }]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getAllByText("-")).toHaveLength(2);
});

it("普通收入和支出都显示货币单位", () => {
  const income = { ...projection("income", "payment_mirror"), amount: "18000", economic_type: "income" as const };
  render(<CashTable items={[projection("expense", "payment_mirror"), income]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByText("-12.50 CNY")).toBeInTheDocument();
  expect(screen.getByText("+18000 CNY")).toBeInTheDocument();
});

it("按月份插入收入和支出汇总分割行，并按币种展示", () => {
  const older = { ...projection("older", "payment_mirror"), occurred_at: "2026-06-30T09:00:00+08:00", amount: "-5", currency: "CNY" };
  const monthlySummaries = [
    { month: "2026-07", currencies: [{ currency: "CNY", income: "2000", expense: "-12.50" }] },
    { month: "2026-06", currencies: [{ currency: "CNY", income: "0", expense: "-5" }, { currency: "USD", income: "10", expense: "-3" }] },
  ];
  render(<CashTable items={[projection("current", "payment_mirror"), older]} monthlySummaries={monthlySummaries} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByRole("row", { name: /2026年7月.*收入 \+2000 CNY.*支出 -12\.50 CNY/ })).toBeInTheDocument();
  expect(screen.getByRole("row", { name: /2026年6月.*收入 0 CNY.*支出 -5 CNY.*收入 \+10 USD.*支出 -3 USD/ })).toBeInTheDocument();
  expect(screen.getAllByRole("row")).toHaveLength(5);
});

it("按浏览器本地时间将 UTC 月末流水归入显示月份", () => {
  const crossMonth = { ...projection("cross-month", "payment_mirror"), occurred_at: "2026-06-30T17:32:00Z" };
  const monthlySummaries = [{ month: "2026-07", currencies: [{ currency: "CNY", income: "100", expense: "-12.50" }] }];
  render(<CashTable items={[crossMonth]} monthlySummaries={monthlySummaries} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByRole("row", { name: /2026年7月.*收入 \+100 CNY.*支出 -12\.50 CNY/ })).toHaveAttribute("data-month", "2026-07");
});

it("同币种内部转账只显示一次金额且不显示负号", () => {
  render(<CashTable items={[transfer()]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByText("日常账户 → 信用账户")).toBeInTheDocument();
  expect(screen.getByText("200 CNY")).toBeInTheDocument();
  expect(screen.getByText("个人转账")).toBeInTheDocument();
});

it("跨币种内部转账显示两端金额且不显示负号", () => {
  render(<CashTable items={[transfer(true)]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByText("200 CNY → 14 USD")).toBeInTheDocument();
});

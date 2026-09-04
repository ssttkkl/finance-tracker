import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { CashTable } from "../src/components/CashTable";
import { TransactionTable, type TransactionTableItem } from "../src/components/TransactionTable";

afterEach(cleanup);

const importTableItem: TransactionTableItem = {
  id: "import-1",
  occurredAt: "2026-08-12T09:24:00+08:00",
  accountLabel: "支付宝余额",
  counterparty: "咖啡店",
  note: "拿铁",
  flowLabel: "消费",
  direction: "expense",
  amountLabel: "-12.50 CNY",
  statusLabel: "待新增",
  statusTone: "new",
};

it("发生时间和月份键不固定地区时区", () => {
  const formatSource = readFileSync(resolve(process.cwd(), "src/format.ts"), "utf8");
  const tableSource = readFileSync(resolve(process.cwd(), "src/components/CashTable.tsx"), "utf8");

  expect(formatSource).not.toContain('timeZone: "Asia/Shanghai"');
  expect(tableSource).not.toContain('timeZone: "Asia/Shanghai"');
});

it("共享表格组件支持导入预览字段、状态和加载骨架", () => {
  const { rerender } = render(<TransactionTable items={[importTableItem]} variant="import" groupByMonth />);

  expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易信息", "流水类型", "状态", "金额"]);
  expect(screen.getByText("2026年8月")).toBeInTheDocument();
  expect(screen.getByText("咖啡店")).toBeInTheDocument();
  expect(screen.getByText("拿铁")).toBeInTheDocument();
  expect(screen.getByText("待新增")).toBeInTheDocument();
  expect(screen.getByRole("cell", { name: "-12.50 CNY" })).toHaveAttribute("data-direction", "支出");
  expect(screen.queryByRole("columnheader", { name: "分类" })).not.toBeInTheDocument();
  expect(screen.queryByRole("columnheader", { name: "操作" })).not.toBeInTheDocument();

  rerender(<TransactionTable items={[]} variant="import" loading />);
  expect(screen.getAllByTestId("现金流水骨架行")).toHaveLength(3);
});

const projection = (projection_id: string, kind: string, note = "") => ({
  projection_id, occurred_at: "2026-07-03T09:00:00+08:00", account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: `交易对方${projection_id}`, category: { id: "food", parent_id: null, name: "餐饮", description: null, path: [{ id: "food", name: "餐饮" }], depth: 1, sort_order: 0, revision: 1 }, amount: "-12.50", currency: "CNY", note, economic_type: "expense" as const,
  transfer_subtype: null, composition: [kind], member_count: 2, accepted_relation_summary: [{ kind, subtype: "", count: 1 }], source_type: "fixture", source_types: ["fixture"], record_id: `cash-${projection_id}`, visible: true, hidden_reason: null,
});

const transfer = (crossCurrency = false) => ({
  ...projection("transfer", "transfer_pair", "账户间转移"),
  counterparty: "信用账户", amount: "0", currency: "CNY",
  economic_type: "internal_transfer" as const, transfer_subtype: "ordinary_transfer",
  transfer: {
    from_account: { id: 101, name: "日常账户", type: "cash", active: true }, from_amount: "-200", from_currency: "CNY",
    to_account: { id: 102, name: "信用账户", type: "loan", active: true }, to_amount: crossCurrency ? "14" : "200", to_currency: crossCurrency ? "USD" : "CNY",
  },
});

it("在交易信息中展示交易对方、备注和已合并标记，不在列表展示来源字段", () => {
  render(<CashTable items={[projection("1", "payment_mirror", "午间消费"), projection("2", "refund_offset"), projection("3", "unknown_kind")]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易信息", "分类", "流水类型", "金额", "操作"]);
  expect(screen.getByRole("table")).toHaveClass("cash-table");
  expect(screen.getByRole("cell", { name: /交易对方1/ })).toHaveAttribute("headers", "cash-column-transaction-info");
  expect(screen.getAllByRole("cell", { name: "-12.50 CNY" })[0]).toHaveAttribute("data-direction", "支出");
  expect(screen.getByRole("row", { name: /交易对方1/ })).toHaveAttribute("data-projection-id", "1");
  expect(screen.getByText("午间消费")).toHaveAttribute("data-label", "备注");
  expect(screen.queryByRole("columnheader", { name: "关联记录" })).not.toBeInTheDocument();
  expect(screen.getByText("午间消费")).toBeInTheDocument();
  expect(screen.queryByText("fixture")).not.toBeInTheDocument();
  expect(screen.queryByText(/同笔支付关系|退款冲销关系|未识别的关系类型/)).not.toBeInTheDocument();
  expect(screen.getAllByText("已合并")).toHaveLength(3);
  expect(screen.queryByText(/条账本记录/)).not.toBeInTheDocument();
});

it("移动端整张卡片承担查看详情操作，桌面保留可访问的查看按钮", () => {
  let opened = "";
  render(<CashTable items={[projection("1", "payment_mirror")]} onEvidence={(item) => { opened = item.projection_id; }} />);

  const trigger = screen.getByRole("button", { name: "查看交易对方1的收支详情" });
  expect(trigger).toHaveClass("evidence-trigger");
  expect(trigger.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  const row = screen.getByRole("row", { name: /交易对方1/ });
  expect(row).toHaveAttribute("tabindex", "0");
  fireEvent.click(row);
  expect(opened).toBe("1");
});

it("行级操作菜单复用查看、编辑、分类和删除入口", () => {
  const actions: string[] = [];
  render(<CashTable items={[projection("menu", "single")]} onEvidence={() => undefined} onAction={(_item, action) => actions.push(action)} />);

  fireEvent.click(screen.getByRole("button", { name: "打开交易对方menu的操作菜单" }));
  const menu = screen.getByRole("menu", { name: "交易对方menu的操作" });
  expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual(["查看详情", "编辑", "修改分类", "删除"]);
  fireEvent.click(within(menu).getByRole("menuitem", { name: "修改分类" }));
  expect(actions).toEqual(["category"]);
});

it("列表不显示单笔或已合并流水的来源", () => {
  const single = { ...projection("single", "single"), composition: [], member_count: 1, accepted_relation_summary: [], source_types: ["支付宝"] };
  const related = { ...projection("related", "refund_offset"), source_types: ["支付宝", "工商银行"] };
  const missing = { ...projection("missing", "single"), composition: [], member_count: 1, accepted_relation_summary: [], source_type: null, source_types: [] };
  render(<CashTable items={[single, related, missing]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.queryByText("支付宝")).not.toBeInTheDocument();
  expect(screen.queryByText("支付宝、工商银行")).not.toBeInTheDocument();
  expect(screen.getByRole("row", { name: /交易对方related/ })).toHaveTextContent("已合并");
});

it("仅为已合并流水显示来源标记", () => {
  const single = { ...projection("single", "single"), composition: [], member_count: 1, accepted_relation_summary: [] };
  const related = { ...projection("related", "refund_offset"), member_count: 3 };
  render(<CashTable items={[single, related]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.queryByText("单源投影")).not.toBeInTheDocument();
  expect(screen.queryByText(/条账本记录/)).not.toBeInTheDocument();
  expect(screen.getByLabelText("已合并")).toHaveTextContent("已合并");
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

  expect(screen.getAllByText("银证转账")).toHaveLength(2);
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
  expect(screen.getAllByText("已合并")).toHaveLength(2);
});

it("跨币种内部转账显示两端金额且不显示负号", () => {
  render(<CashTable items={[transfer(true)]} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getByText("200 CNY → 14 USD")).toBeInTheDocument();
});

it("选择模式展示分类列和当前已加载条目复选框", () => {
  const category = { id: "food", parent_id: null, name: "餐饮", description: null, path: [{ id: "food", name: "餐饮" }], depth: 1, sort_order: 1, revision: 1 };
  render(<CashTable items={[{ ...projection("selectable", "single"), category }]} selectable selectedIds={new Set()} onToggleSelection={() => undefined} onToggleAll={() => undefined} onEvidence={(_projection, _source) => undefined} />);

  expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["选择", "发生时间", "账户", "交易信息", "分类", "流水类型", "金额", "操作"]);
  expect(screen.getByRole("checkbox", { name: "选择交易对方selectable" })).toBeInTheDocument();
  expect(screen.getByRole("checkbox", { name: "选择当前已加载记录" })).toBeInTheDocument();
  expect(screen.getByRole("cell", { name: "餐饮" })).toHaveAttribute("headers", "cash-column-category");
});

it("桌面表头保持纯文字，不显示字段图标", () => {
  render(<CashTable items={[projection("icons", "single", "备注")]} onEvidence={(_projection, _source) => undefined} />);

  const headers = screen.getAllByRole("columnheader");
  expect(headers.every((header) => header.querySelector("svg") === null)).toBe(true);
  expect(screen.getByText("备注").querySelector("svg")).toBeNull();
});

it("移动端选择卡片以图标标识辅助字段，将分类放在左侧并固定复选框", () => {
  const category = { id: "food", parent_id: null, name: "餐饮", description: null, path: [{ id: "food", name: "餐饮" }], depth: 1, sort_order: 1, revision: 1 };
  let opened = "";
  let toggled = "";
  render(<CashTable items={[{ ...projection("mobile", "single"), category }]} selectable selectedIds={new Set()} onToggleSelection={(item) => { toggled = item.projection_id; }} onToggleAll={() => undefined} onEvidence={(item) => { opened = item.projection_id; }} />);

  const row = screen.getByRole("row", { name: /交易对方mobile/ });
  expect(row).toHaveClass("is-selectable");
  expect(row.querySelector(".selection")).toHaveClass("selection");
  expect(row.querySelector(".economic-type")).toHaveTextContent("消费");
  expect(row.querySelector(".category")).toHaveTextContent("餐饮");
  expect(row.querySelector(".source")).toBeNull();
  expect(row.querySelectorAll(".cash-mobile-field-marker").length).toBe(3);
  expect(row.querySelector(".occurred-at .cash-mobile-field-marker")).toBeNull();
  expect(row.querySelectorAll(".cash-mobile-field-value").length).toBe(3);
  expect(row.querySelectorAll(".cash-mobile-field-marker .mobile-field-label").length).toBe(0);
  fireEvent.click(screen.getByRole("checkbox", { name: "选择交易对方mobile" }));
  expect(row).toHaveClass("is-selectable");
  expect(toggled).toBe("mobile");
  expect(opened).toBe("");
  fireEvent.keyDown(screen.getByRole("checkbox", { name: "选择交易对方mobile" }), { key: " " });
  expect(opened).toBe("");
});

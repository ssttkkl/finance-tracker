import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashLedgerPage } from "../src/pages/CashLedgerPage";
import type { CashProjection } from "../src/api/types";
import { formatOccurredAt } from "../src/format";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const foodCategory = { id: "food", parent_id: null, name: "餐饮", description: null, path: [{ id: "food", name: "餐饮" }], depth: 1, sort_order: 0, revision: 1 };
const dailyCategory = { id: "daily", parent_id: null, name: "日用", description: null, path: [{ id: "daily", name: "日用" }], depth: 1, sort_order: 1, revision: 1 };
const salaryCategory = { id: "salary", parent_id: null, name: "工资", description: null, path: [{ id: "salary", name: "工资" }], depth: 1, sort_order: 2, revision: 1 };
const transferCategory = { id: "transfer", parent_id: null, name: "转账", description: null, path: [{ id: "transfer", name: "转账" }], depth: 1, sort_order: 3, revision: 1 };
const projection: CashProjection = {
  projection_id: "cash:1003", occurred_at: "2026-07-03T09:00:00+08:00", account,
  counterparty: "咖啡店", category: foodCategory, note: "午间消费", amount: "-12.50", currency: "CNY",
  economic_type: "expense" as const, transfer_subtype: null, composition: ["payment_mirror", "refund_offset"],
  member_count: 3, accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }, { kind: "refund_offset", subtype: "", count: 1 }],
  source_type: "alipay", source_types: ["alipay", "icbc_credit"], record_id: "cash-003", visible: true, hidden_reason: null,
};

function evidenceFor(item: CashProjection = projection) {
  return {
    projection_version: 1, projection: item,
    root_record: { id: "1003", occurred_at: item.occurred_at, account: item.account, counterparty: item.counterparty, category: item.category, note: item.note, amount: "-100.00", currency: item.currency, source_type: item.source_type, record_id: item.record_id, record_type: "consumption", source_snapshot: { merchant: "咖啡店" } },
    members: [
      { id: "1003", occurred_at: item.occurred_at, account: item.account, counterparty: item.counterparty, category: item.category, note: item.note, amount: "-100.00", currency: item.currency, source_type: item.source_type, record_id: item.record_id, roles: ["root"] },
      { id: "1004", occurred_at: "2026-07-04T09:00:00+08:00", account: item.account, counterparty: "咖啡店", category: item.category, note: "", amount: "30.00", currency: item.currency, source_type: "icbc_credit", record_id: "cash-004", record_type: "refund", roles: ["refund"] },
    ],
    accepted_relations: [{ id: "7", kind: "refund_offset", subtype: "", rule_id: "refund.amount.v1", confidence: "strong", evidence: { amount_match: true }, primary_record: null, secondary_record: null }],
    inactive_relation_hints: [{ id: "8", kind: "payment_mirror", subtype: "", status: "pending_review", primary_record: { id: "1003", occurred_at: item.occurred_at, account: item.account, counterparty: item.counterparty, category: item.category, note: item.note, amount: "-100.00", currency: item.currency, source_type: item.source_type, record_id: item.record_id }, secondary_record: null }],
    refund_timeline: [{ record_id: "cash-004", occurred_at: "2026-07-04T09:00:00+08:00", amount: "30.00", currency: item.currency, source_type: item.source_type }],
  };
}

function json(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } }));
}

function deferred<T>() {
  let resolve: (value: T) => void;
  const promise = new Promise<T>((value) => { resolve = value; });
  return { promise, resolve: resolve! };
}

beforeEach(() => vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000"));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("CashLedgerPage", () => {
  it("默认折叠筛选，主列表追加加载并保留已加载流水", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const third = { ...projection, projection_id: "cash:1005", counterparty: "第三笔" };
    const fetch = vi.fn((input: string) => input.includes("/accounts")
      ? json({ items: [account] })
      : input.includes("cursor=last")
        ? json({ projection_version: 1, items: [third], next_cursor: null, page_size: 1, filters: {} })
        : input.includes("cursor=next")
          ? json({ projection_version: 1, items: [second], next_cursor: "last", page_size: 1, filters: {} })
          : json({ projection_version: 1, items: [projection], next_cursor: "next", page_size: 1, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    const projectionRequest = fetch.mock.calls.find(([input]) => String(input).includes("/cash-projections"));
    expect(new URL(String(projectionRequest?.[0])).searchParams.get("timezone")).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
    expect(screen.getByRole("group", { name: "账本筛选工具" })).not.toHaveAttribute("open");
    expect(screen.queryByRole("navigation", { name: "流水分页" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await screen.findByText("第二笔");
    expect(screen.getByText("咖啡店")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await screen.findByText("第三笔");
    expect(screen.getByText("第二笔")).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("cursor=next"))).toHaveLength(1);
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("cursor=last"))).toHaveLength(1);
  });

  it("账户目录失败后可重试，且不重置已读取的收支记录", async () => {
    let accountAttempts = 0;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) {
        accountAttempts += 1;
        return accountAttempts === 1
          ? json({ error: { code: "storage.unavailable" } }, 503)
          : json({ items: [account] });
      }
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    await screen.findByText("咖啡店");
    const retry = await screen.findByRole("button", { name: "重试账户" });
    expect(screen.getByText("无法读取账户，请稍后重试。")).toBeInTheDocument();
    fireEvent.click(retry);
    await waitFor(() => expect(screen.queryByText("无法读取账户，请稍后重试。")).not.toBeInTheDocument());
    expect(screen.getByRole("option", { name: "日常账户" })).toBeInTheDocument();
    expect(screen.getByText("咖啡店")).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("/accounts"))).toHaveLength(2);
  });

  it("追加失败时保留当前列表，并允许重试", async () => {
    let pageAttempts = 0;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (!input.includes("cursor=next")) return json({ projection_version: 1, items: [projection], next_cursor: "next", page_size: 50, filters: {} });
      pageAttempts += 1;
      return pageAttempts === 1 ? json({ error: { code: "storage.busy" } }, 503) : json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:1005", counterparty: "重试后的记录" }], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    expect(await screen.findByRole("button", { name: "重试加载更多" })).toBeInTheDocument();
    expect(screen.getByText("咖啡店")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试加载更多" }));
    await screen.findByText("重试后的记录");
  });

  it("筛选变更会取消追加并从首批重新读取", async () => {
    const pendingPage = deferred<Promise<Response>>();
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : input.includes("cursor=next") ? pendingPage.promise : json({ projection_version: 1, items: [{ ...projection, counterparty: new URL(input).searchParams.get("category_id") === "food" ? "新筛选记录" : "咖啡店" }], next_cursor: "next", page_size: 50, filters: {}, filter_options: { categories: [foodCategory], currencies: ["CNY"] } }));
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "food" } });
    await screen.findByText("新筛选记录");
    expect(screen.getByText("全部账户 · 已筛选分类 · 全部收支")).toBeInTheDocument();
    pendingPage.resolve(json({ projection_version: 1, items: [{ ...projection, counterparty: "过期追加" }], next_cursor: null, page_size: 50, filters: {} }));
    await waitFor(() => expect(screen.queryByText("过期追加")).not.toBeInTheDocument());
  });


  it("在交易信息中展示备注，保留组成方式筛选且只调用投影端点", async () => {
    const withoutNote = { ...projection, projection_id: "cash:1004", counterparty: "无备注商户", note: "", record_id: "cash-004" };
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection, withoutNote], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [foodCategory], currencies: ["CNY"], economic_types: [{ economic_type: "expense", transfer_subtypes: [] }] } }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    expect(screen.getByText("正在读取收支记录…")).toBeInTheDocument();
    await screen.findByText("咖啡店");
    expect(screen.getByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "收支账本" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "账本筛选工具" })).not.toHaveAttribute("open");
    expect(screen.queryByText("本机账本")).not.toBeInTheDocument();
    expect(screen.queryByText("按主记录发生时间查看收支投影")).not.toBeInTheDocument();
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["选择", "发生时间", "账户", "交易信息", "分类", "流水类型", "金额", "操作"]);
    expect(screen.queryByRole("columnheader", { name: "关联记录" })).not.toBeInTheDocument();
    expect(screen.getByText("午间消费")).toBeInTheDocument();
    expect(screen.getByText("-")).toBeInTheDocument();
    expect(screen.queryByText("同笔支付关系（1）；退款冲销关系（1）")).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "全部消费" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "其他合并" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("合并状态"), { target: { value: "combined" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("composition=combined"))).toBe(true));
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-transactions"))).toBe(false);
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-projections"))).toBe(true);
  });

  it("使用后端全量聚合的分类和币种下拉选项", async () => {
    const filter_options = { categories: [foodCategory, dailyCategory, salaryCategory], currencies: ["CNY", "USD"] };
    const fetch = vi.fn((input: string) => input.includes("/accounts")
      ? json({ items: [account] })
      : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    const category = screen.getByLabelText("分类");
    const currency = screen.getByLabelText("币种");
    expect(category.tagName).toBe("SELECT");
    expect(currency.tagName).toBe("SELECT");
    expect(screen.queryByRole("textbox", { name: "分类" })).not.toBeInTheDocument();
    expect(category).toBeDisabled();
    expect(currency).toBeDisabled();

    await screen.findByText("咖啡店");
    expect(screen.getByRole("option", { name: "全部分类" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "工资" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "USD" })).toBeInTheDocument();
    expect(category).not.toBeDisabled();
    expect(currency).not.toBeDisabled();

    fireEvent.change(category, { target: { value: "salary" } });
    fireEvent.change(currency, { target: { value: "USD" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("category_id=salary") && String(input).includes("currency=USD"))).toBe(true));
  });

  it("使用后端类型树渲染分组选择，并规范化父级和子类型请求", async () => {
    const initialPage = deferred<Response>();
    let pageCalls = 0;
    const filter_options = {
      categories: [foodCategory, transferCategory], currencies: ["CNY", "USD"],
      economic_types: [
        { economic_type: "expense", transfer_subtypes: [] },
        { economic_type: "internal_transfer", transfer_subtypes: ["bank_security_transfer", "cross_currency_remittance", "unmapped_transfer"] },
      ],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      pageCalls += 1;
      return pageCalls === 1
        ? initialPage.promise
        : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    const economicType = screen.getByLabelText("流水类型");
    expect(economicType).toBeDisabled();
    initialPage.resolve(new Response(JSON.stringify({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options }), { headers: { "Content-Type": "application/json" } }));
    await screen.findByRole("option", { name: "unmapped_transfer" });
    expect(economicType.querySelector('optgroup[label="资金转移"]')).not.toBeNull();
    expect(within(economicType).getByRole("option", { name: "银证转账" })).toHaveValue("{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}");
    expect(within(economicType).getByRole("option", { name: "跨币种汇款" })).toHaveValue("{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"cross_currency_remittance\"}");

    fireEvent.change(economicType, { target: { value: "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("economic_type=internal_transfer") && String(input).includes("transfer_subtype=bank_security_transfer"))).toBe(true));

    fireEvent.change(economicType, { target: { value: "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":null}" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("economic_type=internal_transfer") && !String(input).includes("transfer_subtype="))).toBe(true));
  });

  it("重新读取投影时禁用经济类型筛选，但保留上次成功的类型树", async () => {
    const refreshedPage = deferred<Response>();
    const filter_options = {
      categories: [foodCategory], currencies: ["CNY"],
      economic_types: [{ economic_type: "internal_transfer", transfer_subtypes: ["bank_security_transfer"] }],
    };
    let pageCalls = 0;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      pageCalls += 1;
      return pageCalls === 1
        ? json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options })
        : refreshedPage.promise;
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    const economicType = await screen.findByLabelText("流水类型");
    await waitFor(() => expect(economicType).not.toBeDisabled());
    expect(within(economicType).getByRole("option", { name: "银证转账" })).toBeInTheDocument();

    fireEvent.change(economicType, { target: { value: "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}" } });

    expect(economicType).toBeDisabled();
    expect(economicType).toHaveAccessibleDescription("正在读取可用流水类型。");
    expect(within(economicType).getByRole("option", { name: "银证转账" })).toBeInTheDocument();

    refreshedPage.resolve(new Response(JSON.stringify({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options }), { headers: { "Content-Type": "application/json" } }));
    await waitFor(() => expect(economicType).not.toBeDisabled());
  });

  it("展示可见的内部转账及双端账户和金额", async () => {
    const transfer = {
      ...projection, projection_id: "cash:1004", counterparty: "信用账户", note: "账户间转移",
      amount: "0", currency: "USD", economic_type: "internal_transfer" as const, transfer_subtype: "ordinary_transfer",
      composition: ["transfer_pair"], visible: true, hidden_reason: null,
      transfer: {
        from_account: account, from_amount: "-200", from_currency: "CNY",
        to_account: { ...account, id: 102, name: "信用账户", type: "loan" }, to_amount: "14", to_currency: "USD",
      },
    };
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection, transfer], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [foodCategory, transferCategory], currencies: ["CNY", "USD"] } })));

    render(<CashLedgerPage />);

    await screen.findByText("咖啡店");
    const transferRow = screen.getByRole("row", { name: /日常账户 → 信用账户/ });
    expect(transferRow).toHaveTextContent("200 CNY → 14 USD");
    expect(transferRow).toHaveTextContent("已合并");
  });

  it("银证转账沿用双端账户和金额展示，并可作为独立条件筛选", async () => {
    const bankSecurityTransfer: CashProjection = {
      ...projection,
      projection_id: "cash:1005",
      counterparty: "Interactive Brokers",
      category: foodCategory,
      note: "",
      amount: "0",
      currency: "HKD",
      economic_type: "internal_transfer",
      transfer_subtype: "bank_security_transfer",
      composition: [],
      member_count: 1,
      accepted_relation_summary: [],
      record_id: "cash-005",
      transfer: {
        from_account: account,
        from_amount: "-10000",
        from_currency: "HKD",
        to_account: { ...account, id: 103, name: "投资账户", type: "security" },
        to_amount: "1275.5",
        to_currency: "USD",
      },
    };
    const fetch = vi.fn((input: string) => input.includes("/accounts")
      ? json({ items: [account] })
      : json({ projection_version: 1, items: [bankSecurityTransfer], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [transferCategory], currencies: ["HKD", "USD"], economic_types: [{ economic_type: "internal_transfer", transfer_subtypes: ["bank_security_transfer"] }] } }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    const transferRow = await screen.findByRole("row", { name: /日常账户 → 投资账户/ });
    expect(transferRow).toHaveTextContent("10000 HKD → 1275.5 USD");
    expect(transferRow).toHaveTextContent("银证转账");
    const economicType = screen.getByLabelText("流水类型");
    expect(within(economicType).getByRole("option", { name: "银证转账" })).toHaveValue("{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}");
    fireEvent.change(economicType, { target: { value: "{\"economic_type\":\"internal_transfer\",\"transfer_subtype\":\"bank_security_transfer\"}" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("economic_type=internal_transfer") && String(input).includes("transfer_subtype=bank_security_transfer"))).toBe(true));
    expect(screen.getByText("全部账户 · 银证转账")).toBeInTheDocument();
  });

  it("以收支详情和关联流水服务核对，不显示审计结构", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/cash-projections/cash%3A1003")) return json(evidenceFor());
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "查看咖啡店的收支详情" }));

    const dialog = await screen.findByRole("dialog", { name: "收支详情" });
    expect(within(dialog).getByRole("region", { name: "收支详情" })).toHaveClass("evidence-section");
    expect(within(dialog).getByRole("region", { name: "关联流水" })).toHaveClass("evidence-section");
    expect(within(dialog).getByText("已合并")).toBeInTheDocument();
    expect(within(dialog).queryByText(/条账本记录/)).not.toBeInTheDocument();
    expect(within(dialog).getByText("午间消费")).toBeInTheDocument();
    expect(within(dialog).getByText("alipay、icbc_credit")).toBeInTheDocument();
    const relatedRecord = within(within(dialog).getByRole("region", { name: "关联流水" })).getByRole("listitem");
    expect(within(relatedRecord).getByText("关联类型")).toBeInTheDocument();
    expect(within(relatedRecord).getAllByText("退款")).toHaveLength(2);
    expect(within(relatedRecord).getByText("+30.00 CNY")).toBeInTheDocument();
    expect(within(relatedRecord).getByText("icbc_credit")).toBeInTheDocument();
    expect(within(relatedRecord).getByText("已冲销本次消费。")).toBeInTheDocument();
    expect(within(dialog).queryByText("审计信息")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("merchant")).not.toBeInTheDocument();
    expect(within(dialog).queryByText("退款冲销关系（refund.amount.v1）")).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/同笔支付关系：待审核/)).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/30.00 CNY，fixture/)).not.toBeInTheDocument();
  });

  it("单源流水显示自身来源，不显示合并标记或关联流水", async () => {
    const single = {
      ...projection,
      projection_id: "cash:1006",
      composition: [],
      member_count: 1,
      accepted_relation_summary: [],
      source_types: ["alipay"],
      record_id: "cash-006",
    };
    const singleEvidence = evidenceFor(single);
    singleEvidence.members = [singleEvidence.members[0]];
    singleEvidence.accepted_relations = [];
    singleEvidence.inactive_relation_hints = [];
    singleEvidence.refund_timeline = [];
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json(singleEvidence);
      return json({ projection_version: 1, items: [single], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的收支详情" }));

    const dialog = await screen.findByRole("dialog", { name: "收支详情" });
    expect(within(dialog).queryByText("单源投影")).not.toBeInTheDocument();
    expect(within(dialog).getByText("alipay")).toBeInTheDocument();
    expect(within(dialog).queryByText("关系投影")).not.toBeInTheDocument();
    expect(within(dialog).queryByText(/条账本记录/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("region", { name: "关联流水" })).toBeInTheDocument();
    expect(within(dialog).getByText("暂无关联流水")).toBeInTheDocument();
    expect(within(dialog).queryByText("这笔收支由一条账本记录直接形成，没有关联流水。")).not.toBeInTheDocument();
  });

  it("银证资金调拨在详情中显示专用关系标记", async () => {
    const bankSecurityTransfer = {
      ...projection,
      projection_id: "cash:1007",
      counterparty: "Charles Schwab",
      category: foodCategory,
      note: "",
      amount: "0",
      currency: "USD",
      economic_type: "internal_transfer" as const,
      transfer_subtype: "bank_security_transfer",
      composition: [],
      member_count: 1,
      accepted_relation_summary: [],
      record_id: "cash-007",
      source_type: "icbc_asia",
      source_types: ["icbc_asia"],
    };
    const bankSecurityEvidence = evidenceFor(bankSecurityTransfer);
    bankSecurityEvidence.members = [bankSecurityEvidence.members[0]];
    bankSecurityEvidence.accepted_relations = [];
    bankSecurityEvidence.inactive_relation_hints = [];
    bankSecurityEvidence.refund_timeline = [];
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json(bankSecurityEvidence);
      return json({ projection_version: 1, items: [bankSecurityTransfer], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看Charles Schwab的收支详情" }));

    const dialog = await screen.findByRole("dialog", { name: "收支详情" });
    expect(within(dialog).getAllByText("银证转账")).toHaveLength(2);
    expect(within(dialog).getByRole("region", { name: "关联流水" })).toBeInTheDocument();
    expect(within(dialog).getByText("暂无关联流水")).toBeInTheDocument();
  });

  it("切换账户后保留投影合同并重新读取第一页", async () => {
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account, { ...account, id: 102, name: "信用账户" }] }) : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.change(screen.getByLabelText("账户"), { target: { value: "102" } });

    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("account_id=102"))).toBe(true));
  });

  it("将无效金额筛选关联到金额字段，并在修正后清除状态", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("amount_min=not-a-number")) return json({ error: { code: "invalid_filter" } }, 400);
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.change(screen.getByLabelText("最低金额"), { target: { value: "not-a-number" } });

    const filters = screen.getByRole("group", { name: "账本筛选工具" });
    expect(await within(filters).findByRole("alert")).toHaveTextContent("筛选条件有误，请检查日期、金额和选项后重试。");
    const minimum = screen.getByLabelText("最低金额");
    const maximum = screen.getByLabelText("最高金额");
    expect(minimum).toHaveAttribute("aria-invalid", "true");
    expect(maximum).toHaveAttribute("aria-invalid", "true");
    expect(minimum).toHaveAccessibleDescription("筛选条件有误，请检查日期、金额和选项后重试。");

    fireEvent.change(minimum, { target: { value: "5" } });

    await screen.findByText("咖啡店");
    expect(minimum).not.toHaveAttribute("aria-invalid");
    expect(maximum).not.toHaveAttribute("aria-invalid");
    expect(minimum).toHaveAccessibleDescription("金额筛选已应用。");
  });

  it("在投影不可用和存储忙碌时显示脱敏的重试状态", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ error: { code: "projection.unavailable" } }, 503)));
    const { rerender } = render(<CashLedgerPage />);
    expect(await screen.findByText("账本数据暂不可用，请先完成更新。")).toBeInTheDocument();

    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ error: { code: "storage.busy", message: "database is locked /private/ledger.db" } }, 503)));
    rerender(<CashLedgerPage key="busy" />);
    expect(await screen.findByText("账本正被其他操作占用，请稍后重试。")).toBeInTheDocument();
    expect(screen.queryByText(/ledger\.db/)).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveAttribute("data-status-kind", "error");
  });

  it.each([
    ["invalid_filter", "请修正标记的金额筛选条件后重试。"],
    ["invalid_cursor", "加载位置已失效，请重新读取记录。"],
    ["unmapped_failure", "请求失败，请稍后重试。"],
  ])("为 %s 显示可修正的请求错误", async (code, message) => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts")
      ? json({ items: [account] })
      : json({ error: { code } }, 400)));

    render(<CashLedgerPage />);

    expect((await screen.findAllByText(message)).length).toBeGreaterThan(0);
  });

  it("投影更新后保留筛选、关闭旧证据并刷新第一页", async () => {
    const refreshed = deferred<Promise<Response>>();
    let pageCalls = 0;
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json(evidenceFor());
      if (input.includes("/cash-records/")) return json(evidenceFor().root_record);
      pageCalls += 1;
      if (pageCalls === 1) return json({ projection_version: 1, items: [projection], next_cursor: "old-page", page_size: 50, filters: {}, filter_options: { categories: [foodCategory], currencies: ["CNY"] } });
      if (pageCalls === 2) return json({ error: { code: "projection.updated" } }, 409);
      return refreshed.promise;
    }));

    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的收支详情" });
    fireEvent.click(trigger);
    await screen.findByRole("dialog", { name: "收支详情" });
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "food" } });

    await screen.findByText("账本已更新，正在刷新记录。")
    expect(screen.queryByRole("dialog", { name: "收支详情" })).not.toBeInTheDocument();

    refreshed.resolve(json({ projection_version: 2, items: [{ ...projection, projection_id: "cash:2001", counterparty: "刷新后的投影" }], next_cursor: null, page_size: 50, filters: {} }));
    await screen.findByText("刷新后的投影");
    const confirmation = screen.getByRole("button", { name: "查看更新后的列表" });
    await waitFor(() => expect(confirmation).toHaveFocus());
    fireEvent.click(confirmation);
    await waitFor(() => expect(screen.getByRole("row", { name: /刷新后的投影/ })).toHaveFocus());
    expect(fetch).toHaveBeenLastCalledWith(expect.stringContaining("category_id=food"), expect.anything());
    expect(fetch).toHaveBeenLastCalledWith(expect.not.stringContaining("cursor=old-page"), expect.anything());
  });

  it("以文字说明证据详情不完整并允许重试", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json({ projection_version: 1, projection });
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的收支详情" }));

    expect(await screen.findByText("收支详情不完整，请重试。")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
  });

  it("快速变更筛选或关闭详情后，迟到响应不会覆盖当前状态", async () => {
    const stalePage = deferred<Promise<Response>>();
    const staleEvidence = deferred<Promise<Response>>();
    let pageCalls = 0;
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return staleEvidence.promise;
      pageCalls += 1;
      if (pageCalls === 1) return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [foodCategory, { ...foodCategory, id: "old", name: "旧筛选", path: [{ id: "old", name: "旧筛选" }] }, { ...foodCategory, id: "current", name: "当前筛选", path: [{ id: "current", name: "当前筛选" }] }], currencies: ["CNY"] } });
      if (pageCalls === 2) return stalePage.promise;
      return json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:3002", counterparty: "当前筛选结果" }], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的收支详情" }));
    await screen.findByRole("dialog", { name: "收支详情" });
    fireEvent.click(screen.getByRole("button", { name: "关闭收支详情" }));
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "old" } });
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "current" } });

    await screen.findByText("当前筛选结果");
    staleEvidence.resolve(json(evidenceFor()));
    stalePage.resolve(json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:3001", counterparty: "过期筛选结果" }], next_cursor: null, page_size: 50, filters: {} }));
    await waitFor(() => expect(screen.queryByText("过期筛选结果")).not.toBeInTheDocument());
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "收支详情" })).not.toBeInTheDocument());
    expect(screen.queryByRole("dialog", { name: "收支详情" })).not.toBeInTheDocument();
  });

  it("只选择当前已加载记录，并在筛选变更后清空选择", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const category = foodCategory;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/cash-categories")) return json({ revision: 1, items: [category] });
      return json({ projection_version: 1, items: [projection, second], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [category], currencies: ["CNY"], economic_types: [] } });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择当前已加载记录" }));
    expect(screen.getByRole("toolbar", { name: "批量操作" })).toBeInTheDocument();
    expect(screen.getByText("已选 2 项")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-07-01" } });
    await waitFor(() => expect(screen.queryByRole("toolbar", { name: "批量操作" })).not.toBeInTheDocument());
  });

  it("批量修改分类后刷新账本并清空选择", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const category = foodCategory;
    const updated = { ...projection, category, projection_id: "cash:1003" };
    const bodies: unknown[] = [];
    let pageCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/cash-categories")) return json({ revision: 1, items: [category] });
      if (input.includes("/cash-projections/categories") && init?.method === "PUT") {
        bodies.push(JSON.parse(String(init.body)));
        return json({ projection_version: 2, projection_count: 2, updated_transaction_count: 2, category_id: "food" });
      }
      pageCalls += 1;
      return json({ projection_version: pageCalls === 1 ? 1 : 2, items: pageCalls === 1 ? [projection, second] : [updated, { ...second, category }], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [category], currencies: ["CNY"], economic_types: [] } });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择当前已加载记录" }));
    fireEvent.click(screen.getByRole("button", { name: "修改分类" }));
    const dialog = screen.getByRole("dialog", { name: "修改分类" });
    fireEvent.change(within(dialog).getByLabelText("分类"), { target: { value: "food" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));

    await waitFor(() => expect(bodies).toEqual([{ projection_ids: ["cash:1003", "cash:1004"], projection_version: 1, category_id: "food" }]));
    await waitFor(() => expect(screen.getAllByRole("cell", { name: "餐饮" })).toHaveLength(2));
    expect(screen.queryByRole("toolbar", { name: "批量操作" })).not.toBeInTheDocument();
    expect(pageCalls).toBe(2);
  });

  it("批量分类遇到版本冲突时整批失败并要求重新选择", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const category = foodCategory;
    const bodies: unknown[] = [];
    let pageCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/cash-categories")) return json({ revision: 1, items: [category] });
      if (input.includes("/cash-projections/categories") && init?.method === "PUT") {
        bodies.push(JSON.parse(String(init.body)));
        return json({ error: { code: "projection.version_conflict" } }, 409);
      }
      pageCalls += 1;
      return json({ projection_version: pageCalls === 1 ? 1 : 2, items: [projection, second], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [category], currencies: ["CNY"], economic_types: [] } });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择当前已加载记录" }));
    fireEvent.click(screen.getByRole("button", { name: "修改分类" }));
    const dialog = screen.getByRole("dialog", { name: "修改分类" });
    fireEvent.change(within(dialog).getByLabelText("分类"), { target: { value: "food" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "保存" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("列表已更新，请重新选择记录。");
    expect(bodies).toEqual([{ projection_ids: ["cash:1003", "cash:1004"], projection_version: 1, category_id: "food" }]);
    expect(screen.queryByRole("toolbar", { name: "批量操作" })).not.toBeInTheDocument();
    expect(pageCalls).toBe(2);
  });

  it("批量删除先展示影响，再按投影版本提交并刷新列表", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const bodies: { method?: string; body?: unknown }[] = [];
    let pageCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/accounts")) return json({ items: [account] });
      if (url.pathname.endsWith("/cash-projections/delete-impact") && init?.method === "POST") return json({ projection_count: 2, transaction_count: 3, relation_group_count: 1 });
      if (url.pathname.endsWith("/cash-projections") && init?.method === "DELETE") { bodies.push({ method: init.method, body: JSON.parse(String(init.body)) }); return json({ deleted: true, projection_count: 2, transaction_count: 3, relation_group_count: 1, deleted_fact_ids: ["1003", "1004", "1005"], projection_version: 2 }); }
      pageCalls += 1;
      return json({ projection_version: pageCalls === 1 ? 1 : 2, items: pageCalls === 1 ? [projection, second] : [], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [foodCategory], currencies: ["CNY"], economic_types: [] } });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择当前已加载记录" }));
    fireEvent.click(screen.getByRole("button", { name: "删除" }));

    const dialog = await screen.findByRole("dialog", { name: "删除已选收支" });
    expect(await within(dialog).findByText("这次操作将删除以下内容：")).toBeInTheDocument();
    expect(within(dialog).getByText("2 笔")).toBeInTheDocument();
    expect(within(dialog).getByText("3 条")).toBeInTheDocument();
    expect(within(dialog).getByText("1 组")).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "确认删除" }));

    await waitFor(() => expect(bodies).toEqual([{ method: "DELETE", body: { projection_ids: ["cash:1003", "cash:1004"], projection_version: 1, confirmed: true } }]));
    await waitFor(() => expect(screen.queryByRole("toolbar", { name: "批量操作" })).not.toBeInTheDocument());
    expect(pageCalls).toBe(2);
  });

  it("读取批量删除影响失败时清空选择并提示重试", async () => {
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/accounts")) return json({ items: [account] });
      if (url.pathname.endsWith("/cash-projections/delete-impact") && init?.method === "POST") return json({ error: { code: "api_request_failed" } }, 503);
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {}, filter_options: { categories: [foodCategory], currencies: ["CNY"], economic_types: [] } });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("checkbox", { name: "选择当前已加载记录" }));
    fireEvent.click(screen.getByRole("button", { name: "删除" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("无法读取删除影响，请稍后重试。");
    expect(screen.queryByRole("toolbar", { name: "批量操作" })).not.toBeInTheDocument();
    expect(screen.queryByRole("dialog", { name: "删除已选收支" })).not.toBeInTheDocument();
  });

  it("将交易信息和金额范围传递到收支投影筛选", async () => {
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.change(screen.getByLabelText("交易信息"), { target: { value: "咖啡" } });
    fireEvent.change(screen.getByLabelText("最低金额"), { target: { value: "-20" } });
    fireEvent.change(screen.getByLabelText("最高金额"), { target: { value: "-10" } });

    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("counterparty=%E5%92%96%E5%95%A1") && String(input).includes("amount_min=-20") && String(input).includes("amount_max=-10"))).toBe(true));
  });

  it("从统一入口按页搜索已有流水，并固定确认为关联", async () => {
    const candidate = {
      id: "2001", occurred_at: "2026-07-02T08:30:00+08:00", account_name: "日常账户",
      account_id: 101, account_type: "cash", counterparty: "工资转入", category: salaryCategory,
      note: "七月工资", amount: "12000.00", currency: "CNY", source_type: "bank",
      record_id: "cash-2001", record_type: "income", record_subtype: "not_applicable",
      counterparty_account: "", counterparty_account_attrs: [],
    };
    const candidateNext = { ...candidate, id: "2002", counterparty: "工资转入（下一页）", record_id: "cash-2002" };
    const relationBodies: Record<string, unknown>[] = [];
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      const url = new URL(input);
      if (url.pathname.endsWith("/accounts")) return json({ items: [{ ...account, currencies: ["CNY"] }] });
      if (url.pathname.includes("/evidence/")) return json(evidenceFor());
      if (url.pathname.endsWith("/cash-ledger/options")) return json({
        record_types: [{ value: "consumption", label: "消费", subtypes: [{ value: "not_applicable", label: "不适用" }] }],
        relation_types: [{ value: "payment_mirror", label: "同笔支付" }],
      });
      if (url.pathname.endsWith("/cash-records") && (!init?.method || init.method === "GET")) {
        return json({ items: [url.searchParams.get("cursor") === "relation-next" ? candidateNext : candidate], next_cursor: url.searchParams.get("cursor") === "relation-next" ? null : "relation-next" });
      }
      if (url.pathname.endsWith("/cash-relations") && init?.method === "POST") {
        relationBodies.push(JSON.parse(String(init.body)) as Record<string, unknown>);
        return json({ record: candidate, relations: [], options: { record_types: [], relation_types: [] } });
      }
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的收支详情" }));
    const detail = await screen.findByRole("dialog", { name: "收支详情" });
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("/cash-records"))).toHaveLength(0);

    fireEvent.click(within(detail).getByRole("button", { name: "添加关联" }));

    const editor = await screen.findByRole("dialog", { name: "编辑收支详情" });
    const search = await within(editor).findByRole("searchbox", { name: "搜索流水" });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => {
      const url = new URL(String(input));
      return url.pathname.endsWith("/cash-records") && url.searchParams.get("exclude_id") === "1003" && url.searchParams.get("limit") === "20" && url.searchParams.get("date_from") === "2026-06-30" && url.searchParams.get("date_to") === "2026-07-06";
    })).toBe(true));
    expect(within(editor).queryByLabelText("保存方式")).not.toBeInTheDocument();
    expect(within(editor).queryByText("稍后确认")).not.toBeInTheDocument();
    expect(within(editor).getByLabelText("开始日期")).toHaveValue("2026-06-30");
    expect(within(editor).getByLabelText("结束日期")).toHaveValue("2026-07-06");
    expect(editor.textContent).toContain(`日常账户 · ${formatOccurredAt(candidate.occurred_at)}`);
    expect(within(editor).queryByRole("button", { name: "新建流水" })).not.toBeInTheDocument();
    expect(within(editor).getByLabelText("发生时间").closest(".edit-row")).toHaveClass("edit-row");
    expect(within(editor).getByLabelText("发生时间").closest(".edit-row")?.querySelector("svg")).toBeInTheDocument();
    expect(within(editor).getByLabelText("账户").closest(".edit-row")?.querySelector("svg")).toBeInTheDocument();
    expect(within(editor).getByLabelText("流水类型").closest(".edit-row")?.querySelector("svg")).toBeInTheDocument();
    expect(within(editor).getByLabelText("分类").closest(".edit-row")?.querySelector("svg")).toBeInTheDocument();
    expect(within(editor).getByLabelText("备注").closest(".edit-row")?.querySelector("svg")).toBeNull();
    expect(editor.querySelectorAll('input[type="radio"]')).toHaveLength(0);
    fireEvent.click(within(editor).getByRole("button", { name: "下一页" }));
    await within(editor).findByRole("radio", { name: /工资转入（下一页）/ });
    expect(within(editor).queryByRole("radio", { name: /工资转入(?!（下一页）)/ })).not.toBeInTheDocument();
    fireEvent.click(within(editor).getByRole("button", { name: "上一页" }));
    await within(editor).findByRole("radio", { name: /工资转入(?!（下一页）)/ });

    fireEvent.change(search, { target: { value: "工资" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => new URL(String(input)).searchParams.get("query") === "工资")).toBe(true));
    const selectedCandidate = await within(editor).findByRole("radio", { name: /工资转入/ });
    expect(selectedCandidate).toHaveAttribute("aria-checked", "false");
    fireEvent.click(selectedCandidate);
    expect(selectedCandidate).toHaveAttribute("aria-checked", "true");
    expect(selectedCandidate).toHaveClass("is-selected");
    fireEvent.click(within(editor).getByRole("button", { name: "添加关联" }));

    await waitFor(() => expect(relationBodies).toEqual([{
      primary_fact_id: "1003",
      secondary_fact_id: "2001",
      kind: "payment_mirror",
      status: "accepted",
    }]));
  });
});

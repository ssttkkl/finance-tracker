import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashLedgerPage } from "../src/pages/CashLedgerPage";

const account = { id: 101, name: "日常账户", type: "cash", active: true };
const projection = {
  projection_id: "cash:1003", occurred_at: "2026-07-03T09:00:00+08:00", account,
  counterparty: "咖啡店", category: "餐饮", note: "午间消费", amount: "-12.50", currency: "CNY",
  economic_type: "expense" as const, transfer_subtype: null, composition: ["payment_mirror", "refund_offset"],
  member_count: 3, accepted_relation_summary: [{ kind: "payment_mirror", subtype: "", count: 1 }, { kind: "refund_offset", subtype: "", count: 1 }],
  source_type: "fixture", record_id: "cash-003", visible: true, hidden_reason: null,
};

function evidenceFor(item = projection) {
  return {
    projection_version: 1, projection: item,
    root_record: { id: "1003", occurred_at: item.occurred_at, account: item.account, counterparty: item.counterparty, category: item.category, note: item.note, amount: "-100.00", currency: item.currency, source_type: item.source_type, record_id: item.record_id, source_snapshot: { merchant: "咖啡店" } },
    members: [
      { id: "1003", occurred_at: item.occurred_at, account: item.account, counterparty: item.counterparty, category: item.category, note: item.note, amount: "-100.00", currency: item.currency, source_type: item.source_type, record_id: item.record_id, roles: ["root"] },
      { id: "1004", occurred_at: "2026-07-04T09:00:00+08:00", account: item.account, counterparty: "咖啡店", category: "退款", note: "", amount: "30.00", currency: item.currency, source_type: item.source_type, record_id: "cash-004", roles: ["refund"] },
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
  it("默认折叠筛选，并以加载更多追加记录、去重且在末批停止", async () => {
    const second = { ...projection, projection_id: "cash:1004", counterparty: "第二笔" };
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : input.includes("cursor=next") ? json({ projection_version: 1, items: [projection, second], next_cursor: null, page_size: 50, filters: {} }) : json({ projection_version: 1, items: [projection], next_cursor: "next", page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    expect(screen.getByRole("group", { name: "账本筛选工具" })).not.toHaveAttribute("open");
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    await screen.findByText("第二笔");
    expect(screen.getAllByText("咖啡店")).toHaveLength(1);
    expect(screen.getByText("已显示全部记录。")).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("cursor=next"))).toHaveLength(1);
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
    const retry = await screen.findByRole("button", { name: "重试账户目录" });
    expect(screen.getByText("无法读取账户目录。请检查本机 API 后重试。")).toBeInTheDocument();
    fireEvent.click(retry);
    await waitFor(() => expect(screen.queryByText("无法读取账户目录。请检查本机 API 后重试。")).not.toBeInTheDocument());
    expect(screen.getByRole("option", { name: "日常账户" })).toBeInTheDocument();
    expect(screen.getByText("咖啡店")).toBeInTheDocument();
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("/accounts"))).toHaveLength(2);
  });

  it("追加失败时保留既有记录，并允许重试加载更多", async () => {
    let appendAttempts = 0;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (!input.includes("cursor=next")) return json({ projection_version: 1, items: [projection], next_cursor: "next", page_size: 50, filters: {} });
      appendAttempts += 1;
      return appendAttempts === 1 ? json({ error: { code: "storage.busy" } }, 503) : json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:1005", counterparty: "重试后的记录" }], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    expect(await screen.findByRole("button", { name: "重试加载更多" })).toBeInTheDocument();
    expect(screen.getByText("咖啡店")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "重试加载更多" }));
    await screen.findByText("重试后的记录");
    expect(screen.getByText("已显示全部记录。")).toBeInTheDocument();
  });

  it("筛选变更会取消追加并从首批重新读取", async () => {
    const pendingAppend = deferred<Promise<Response>>();
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : input.includes("cursor=next") ? pendingAppend.promise : json({ projection_version: 1, items: [{ ...projection, counterparty: input.includes("category=%E9%A4%90%E9%A5%AE") ? "新筛选记录" : "咖啡店" }], next_cursor: "next", page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "加载更多" }));
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "餐饮" } });
    await screen.findByText("新筛选记录");
    expect(screen.getByText("全部账户 · 分类：餐饮 · 全部收支")).toBeInTheDocument();
    pendingAppend.resolve(json({ projection_version: 1, items: [{ ...projection, counterparty: "过期追加" }], next_cursor: null, page_size: 50, filters: {} }));
    await waitFor(() => expect(screen.queryByText("过期追加")).not.toBeInTheDocument());
  });


  it("展示紧跟交易对方的备注列，保留组成方式筛选且只调用投影端点", async () => {
    const withoutNote = { ...projection, projection_id: "cash:1004", counterparty: "无备注商户", note: "", record_id: "cash-004" };
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection, withoutNote], next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    expect(screen.getByText("正在读取收支记录…")).toBeInTheDocument();
    await screen.findByText("咖啡店");
    expect(screen.getByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "收支账本工作台" })).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "账本筛选工具" })).not.toHaveAttribute("open");
    expect(screen.queryByText("本机账本")).not.toBeInTheDocument();
    expect(screen.queryByText("按主记录发生时间查看收支投影")).not.toBeInTheDocument();
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易对方", "备注", "分类", "金额", "来源", "操作"]);
    expect(screen.queryByRole("columnheader", { name: "组成方式" })).not.toBeInTheDocument();
    expect(screen.getByText("午间消费")).toBeInTheDocument();
    expect(screen.getByText("未提供")).toBeInTheDocument();
    expect(screen.queryByText("同笔支付关系（1）；退款冲销关系（1）")).not.toBeInTheDocument();
    expect(screen.getByRole("option", { name: "消费" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "组合关系" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("组成方式"), { target: { value: "combined" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("composition=combined"))).toBe(true));
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-transactions"))).toBe(false);
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/cash-projections"))).toBe(true);
  });

  it("不展示内部转账和全额退款的隐藏投影", async () => {
    const hidden = { ...projection, projection_id: "cash:1004", counterparty: "不应展示的内部转账", economic_type: "internal_transfer" as const, visible: false, hidden_reason: "internal_transfer" };
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} })));

    render(<CashLedgerPage />);

    await screen.findByText("咖啡店");
    expect(screen.queryByText(hidden.counterparty)).not.toBeInTheDocument();
  });

  it("在证据详情中展示主记录、成员、已采用关系、未生效提示和退款时间线", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/cash-projections/cash%3A1003")) return json(evidenceFor());
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.click(screen.getByRole("button", { name: "查看咖啡店的证据详情" }));

    const dialog = await screen.findByRole("dialog", { name: "证据详情" });
    expect(within(dialog).getByRole("region", { name: "投影结果" })).toHaveClass("evidence-section");
    expect(within(dialog).getByRole("region", { name: "退款时间线" })).toHaveClass("evidence-section");
    expect(within(dialog).getByText("午间消费")).toBeInTheDocument();
    expect(screen.getByText("merchant")).toBeInTheDocument();
    expect(screen.getByText("退款冲销关系（refund.amount.v1）")).toBeInTheDocument();
    expect(screen.getByText(/同笔支付关系：待审核/)).toBeInTheDocument();
    expect(screen.getByText(/30.00 CNY，fixture/)).toBeInTheDocument();
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
    expect(await screen.findByText("收支投影暂不可用，请先完成重建。")).toBeInTheDocument();

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
      pageCalls += 1;
      if (pageCalls === 1) return json({ projection_version: 1, items: [projection], next_cursor: "old-page", page_size: 50, filters: {} });
      if (pageCalls === 2) return json({ error: { code: "projection.updated" } }, 409);
      return refreshed.promise;
    }));

    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });
    fireEvent.click(trigger);
    await screen.findByRole("dialog", { name: "证据详情" });
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "餐饮" } });

    await screen.findByText("账本已更新，正在刷新记录。")
    expect(screen.queryByRole("dialog", { name: "证据详情" })).not.toBeInTheDocument();

    refreshed.resolve(json({ projection_version: 2, items: [{ ...projection, projection_id: "cash:2001", counterparty: "刷新后的投影" }], next_cursor: null, page_size: 50, filters: {} }));
    await screen.findByText("刷新后的投影");
    const confirmation = screen.getByRole("button", { name: "查看更新后的列表" });
    expect(confirmation).toHaveFocus();
    fireEvent.click(confirmation);
    await waitFor(() => expect(screen.getByRole("button", { name: "查看刷新后的投影的证据详情" })).toHaveFocus());
    expect(fetch).toHaveBeenLastCalledWith(expect.stringContaining("category=%E9%A4%90%E9%A5%AE"), expect.anything());
    expect(fetch).toHaveBeenLastCalledWith(expect.not.stringContaining("cursor=old-page"), expect.anything());
  });

  it("以文字说明证据详情不完整并允许重试", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json({ projection_version: 1, projection });
      return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的证据详情" }));

    expect(await screen.findByText("证据详情不完整，请重试或检查收支投影。")).toBeInTheDocument();
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
      if (pageCalls === 1) return json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} });
      if (pageCalls === 2) return stalePage.promise;
      return json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:3002", counterparty: "当前筛选结果" }], next_cursor: null, page_size: 50, filters: {} });
    }));

    render(<CashLedgerPage />);
    fireEvent.click(await screen.findByRole("button", { name: "查看咖啡店的证据详情" }));
    await screen.findByRole("dialog", { name: "证据详情" });
    fireEvent.click(screen.getByRole("button", { name: "关闭证据详情" }));
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "旧筛选" } });
    fireEvent.change(screen.getByLabelText("分类"), { target: { value: "当前筛选" } });

    await screen.findByText("当前筛选结果");
    staleEvidence.resolve(json(evidenceFor()));
    stalePage.resolve(json({ projection_version: 1, items: [{ ...projection, projection_id: "cash:3001", counterparty: "过期筛选结果" }], next_cursor: null, page_size: 50, filters: {} }));
    await waitFor(() => expect(screen.queryByText("过期筛选结果")).not.toBeInTheDocument());
    expect(screen.queryByRole("dialog", { name: "证据详情" })).not.toBeInTheDocument();
  });

  it("将交易对方和金额范围传递到收支投影筛选", async () => {
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection], next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");
    fireEvent.change(screen.getByLabelText("交易对方"), { target: { value: "咖啡" } });
    fireEvent.change(screen.getByLabelText("最低金额"), { target: { value: "-20" } });
    fireEvent.change(screen.getByLabelText("最高金额"), { target: { value: "-10" } });

    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("counterparty=%E5%92%96%E5%95%A1") && String(input).includes("amount_min=-20") && String(input).includes("amount_max=-10"))).toBe(true));
  });
});

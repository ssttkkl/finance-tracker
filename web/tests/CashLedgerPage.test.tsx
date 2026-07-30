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
  it("展示紧跟交易对方的备注列，保留组成方式筛选且只调用投影端点", async () => {
    const withoutNote = { ...projection, projection_id: "cash:1004", counterparty: "无备注商户", note: "", record_id: "cash-004" };
    const fetch = vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ projection_version: 1, items: [projection, withoutNote], next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<CashLedgerPage />);

    expect(screen.getByText("正在读取收支投影…")).toBeInTheDocument();
    await screen.findByText("咖啡店");
    expect(screen.getByRole("heading", { name: "收支账本" })).toBeInTheDocument();
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

  it("在投影不可用和存储忙碌时显示脱敏的重试状态", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ error: { code: "projection.unavailable" } }, 503)));
    const { rerender } = render(<CashLedgerPage />);
    expect(await screen.findByText("收支投影暂不可用，请先完成重建。")).toBeInTheDocument();

    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts") ? json({ items: [account] }) : json({ error: { code: "storage.busy", message: "database is locked /private/ledger.db" } }, 503)));
    rerender(<CashLedgerPage key="busy" />);
    expect(await screen.findByText("账本正被其他操作占用，请稍后重试。")).toBeInTheDocument();
    expect(screen.queryByText(/ledger\.db/)).not.toBeInTheDocument();
  });

  it.each([
    ["invalid_filter", "筛选条件有误，请检查日期、金额和选项后重试。"],
    ["invalid_cursor", "分页位置已失效，请返回第一页后重试。"],
    ["unmapped_failure", "请求失败，请稍后重试。"],
  ])("为 %s 显示可修正的请求错误", async (code, message) => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/accounts")
      ? json({ items: [account] })
      : json({ error: { code } }, 400)));

    render(<CashLedgerPage />);

    expect(await screen.findByText(message)).toBeInTheDocument();
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

    await screen.findByText("账本已更新，正在刷新第一页。")
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

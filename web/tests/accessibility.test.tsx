import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashLedgerPage } from "../src/pages/CashLedgerPage";

const transaction = {
  projection_id: "cash:1003", occurred_at: "2026-07-03T09:00:00+08:00",
  account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: "咖啡店", category: "餐饮", amount: "-12.5", currency: "CNY", note: "",
  source_type: "fixture", record_id: "cash-003", economic_type: "expense" as const, transfer_subtype: null,
  composition: [], member_count: 1, accepted_relation_summary: [], visible: true, hidden_reason: null,
};

function evidence() {
  const root = { id: "1003", occurred_at: transaction.occurred_at, account: transaction.account, counterparty: transaction.counterparty, category: transaction.category, note: transaction.note, amount: transaction.amount, currency: transaction.currency, source_type: transaction.source_type, record_id: transaction.record_id };
  return { projection_version: 1, projection: transaction, root_record: { ...root, source_snapshot: null }, members: [{ ...root, roles: ["root"] }], accepted_relations: [], inactive_relation_hints: [], refund_timeline: [] };
}

beforeEach(() => {
  vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000");
  vi.stubGlobal("fetch", vi.fn((input: string) => {
    if (input.includes("/accounts")) return Promise.resolve(new Response(JSON.stringify({ items: [transaction.account] })));
    if (input.includes("/evidence/")) return Promise.resolve(new Response(JSON.stringify(evidence())));
    return Promise.resolve(new Response(JSON.stringify({ projection_version: 1, items: [transaction], next_cursor: null, page_size: 50, filters: {} })));
  }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("现金账本无障碍", () => {
  it("为筛选提供显式标签，以键盘将焦点带入详情并在关闭后返回记录", async () => {
    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });
    expect(screen.getByLabelText("账户")).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "组成方式" })).toBeInTheDocument();

    fireEvent.click(trigger);
    const close = await screen.findByRole("button", { name: "关闭证据详情" });
    expect(close).toHaveFocus();
    fireEvent.click(close);
    expect(trigger).toHaveFocus();
  });

  it("以文字表达加载、空结果和错误状态", async () => {
    const { rerender } = render(<CashLedgerPage />);
    expect(screen.getByText("正在读取收支投影…")).toBeInTheDocument();
    await screen.findByText("咖啡店");
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("offline"))));
    rerender(<CashLedgerPage key="error" />);
    expect(await screen.findByText("请求失败，请稍后重试。")).toBeInTheDocument();
  });
});

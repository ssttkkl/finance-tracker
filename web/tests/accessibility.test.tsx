import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CashLedgerPage } from "../src/pages/CashLedgerPage";

const transaction = {
  projection_id: "cash:1003", occurred_at: "2026-07-03T09:00:00+08:00",
  account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: "咖啡店", category: "餐饮", amount: "-12.5", currency: "CNY", note: "",
  source_type: "fixture", source_types: ["fixture"], record_id: "cash-003", economic_type: "expense" as const, transfer_subtype: null,
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
    return Promise.resolve(new Response(JSON.stringify({ projection_version: 1, items: [transaction], next_cursor: "next", page_size: 50, filters: {} })));
  }));
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("现金账本无障碍", () => {
  it("为筛选提供显式标签，以键盘将焦点带入详情并在关闭后返回记录", async () => {
    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });
    expect(screen.getByLabelText("账户")).toBeInTheDocument();
    expect(screen.getAllByRole("columnheader").map((header) => header.textContent)).toEqual(["发生时间", "账户", "交易信息", "来源", "经济类型", "金额", "操作"]);
    expect(screen.getAllByRole("columnheader").map((header) => header.getAttribute("scope"))).toEqual(["col", "col", "col", "col", "col", "col", "col"]);
    expect(screen.getByRole("columnheader", { name: "交易信息" })).toHaveAttribute("id", "cash-column-transaction-info");
    expect(screen.getByRole("cell", { name: /咖啡店/ })).toHaveAttribute("headers", "cash-column-transaction-info");
    expect(screen.getByRole("table", { name: "收支账本中的收支记录" })).toHaveClass("cash-table");
    expect(screen.queryByRole("columnheader", { name: "组成方式" })).not.toBeInTheDocument();

    fireEvent.click(trigger);
    const close = await screen.findByRole("button", { name: "关闭证据详情" });
    expect(close).toHaveFocus();
    expect(screen.getByRole("dialog", { name: "证据详情" })).toHaveAttribute("data-focus-trap", "active");
    expect(screen.getByRole("dialog", { name: "证据详情" })).toHaveAttribute("data-state", "open");
    expect(document.querySelector("main.app-shell")).toHaveAttribute("inert");
    fireEvent.click(close);
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it("以键盘关闭详情并将 Tab 焦点限制在详情内", async () => {
    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });

    fireEvent.click(trigger);
    const close = await screen.findByRole("button", { name: "关闭证据详情" });
    fireEvent.keyDown(close, { key: "Tab" });
    expect(close).toHaveFocus();
    fireEvent.keyDown(close, { key: "Tab", shiftKey: true });
    expect(close).toHaveFocus();
    fireEvent.keyDown(close, { key: "Escape" });

    expect(screen.getByRole("dialog", { name: "证据详情" })).toHaveAttribute("data-state", "closing");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "证据详情" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it("点击遮罩关闭模态抽屉，但点击抽屉内容不会关闭", async () => {
    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });

    fireEvent.click(trigger);
    const dialog = await screen.findByRole("dialog", { name: "证据详情" });
    fireEvent.click(dialog);
    expect(screen.getByRole("dialog", { name: "证据详情" })).toBeInTheDocument();

    const backdrop = document.querySelector<HTMLElement>(".evidence-backdrop");
    expect(backdrop).not.toBeNull();
    fireEvent.click(backdrop!);
    expect(screen.getByRole("dialog", { name: "证据详情" })).toHaveAttribute("data-state", "closing");
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "证据详情" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
  });

  it("保留筛选摘要整体点击设计，并记录标准 Hallmark 标记", async () => {
    render(<CashLedgerPage />);
    await screen.findByText("咖啡店");

    const filters = screen.getByRole("group", { name: "账本筛选工具" });
    const summary = filters.querySelector("summary");
    expect(summary).not.toBeNull();
    expect(summary).toHaveTextContent("筛选");
    expect(summary).toHaveTextContent("全部账户 · 全部收支");

    const styles = readFileSync(resolve(import.meta.dirname, "../src/styles.css"), "utf8");
    expect(styles.split(/\r?\n/, 1)[0]).toContain("Hallmark");
    expect(styles).toContain("macrostructure: Workbench");
    expect(styles).toContain("genre: modern-minimal");
    expect(styles).toContain("theme: Cobalt");
  });

  it("以文字表达加载、空结果和错误状态", async () => {
    const { rerender } = render(<CashLedgerPage />);
    expect(screen.getByText("正在读取收支记录…")).toBeInTheDocument();
    await screen.findByText("咖啡店");
    vi.stubGlobal("fetch", vi.fn(() => Promise.reject(new Error("offline"))));
    rerender(<CashLedgerPage key="error" />);
    expect(await screen.findByText("请求失败，请稍后重试。")).toBeInTheDocument();
  });

  it("以语义化标识保留非颜色唯一的收支含义和可见焦点目标", async () => {
    render(<CashLedgerPage />);
    const trigger = await screen.findByRole("button", { name: "查看咖啡店的证据详情" });

    expect(screen.getByRole("cell", { name: "-12.5 CNY" })).toHaveAttribute("data-direction", "支出");
    expect(trigger).toHaveClass("evidence-trigger");
    expect(screen.getByRole("group", { name: "账本筛选工具" })).toHaveAttribute("data-layout", "filter-grid");
    expect(screen.getByRole("button", { name: "加载更多" })).toHaveAttribute("aria-describedby", "load-more-instructions");
    expect(screen.getByText("可继续加载更多记录。")).toHaveAttribute("role", "status");
  });
});

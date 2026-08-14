import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "../src/App";

const account = { id: 103, name: "投资账户", type: "security", active: true };
const cashItem = {
  projection_id: "cash:1003", occurred_at: "2026-07-03T09:00:00+08:00",
  account: { id: 101, name: "日常账户", type: "cash", active: true },
  counterparty: "咖啡店", category: "餐饮", amount: "-12.5", currency: "CNY", note: "",
  source_type: "fixture", source_types: ["fixture"], record_id: "cash-003", economic_type: "expense", transfer_subtype: null,
  composition: [], member_count: 1, accepted_relation_summary: [], visible: true, hidden_reason: null,
};
const cashEvidence = {
  projection_version: 1, projection: cashItem,
  root_record: { id: "1003", occurred_at: cashItem.occurred_at, account: cashItem.account, counterparty: cashItem.counterparty, category: cashItem.category, note: cashItem.note, amount: cashItem.amount, currency: cashItem.currency, source_type: cashItem.source_type, record_id: cashItem.record_id, source_snapshot: null },
  members: [{ id: "1003", occurred_at: cashItem.occurred_at, account: cashItem.account, counterparty: cashItem.counterparty, category: cashItem.category, note: cashItem.note, amount: cashItem.amount, currency: cashItem.currency, source_type: cashItem.source_type, record_id: cashItem.record_id, roles: ["root"] }],
  accepted_relations: [], inactive_relation_hints: [], refund_timeline: [],
};

beforeEach(() => {
  window.history.pushState({}, "", "/");
  window.history.replaceState({}, "", "/");
  vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000");
  vi.stubGlobal("fetch", vi.fn((input: string) => {
    if (input.includes("/accounts")) return Promise.resolve(new Response(JSON.stringify({ items: [account] })));
    if (input.includes("/evidence/")) return Promise.resolve(new Response(JSON.stringify(cashEvidence)));
    if (input.includes("/cash-projections")) return Promise.resolve(new Response(JSON.stringify({ items: [cashItem], next_cursor: null, monthly_summaries: [], filter_options: { categories: [], currencies: [], economic_types: [] } })));
    return Promise.resolve(new Response(JSON.stringify({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} })));
  }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("统一账本外壳", () => {
  it("在收支账本和投资账本路由下保持同一棵分级侧边导航", async () => {
    render(<App />);

    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    expect(within(navigation).getAllByRole("link").map((link) => link.textContent)).toEqual(["收支账本", "分类管理", "导入账单", "投资账本", "当前持仓", "投资事件"]);
    expect(within(navigation).getByRole("link", { name: "收支账本" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getByRole("link", { name: "当前持仓" })).not.toHaveAttribute("aria-current");

    window.history.pushState({}, "", "/investment-events");
    window.dispatchEvent(new PopStateEvent("popstate"));
    await waitFor(() => expect(screen.getByRole("heading", { name: "投资事件", level: 1 })).toBeInTheDocument());
    const investmentNavigation = screen.getByRole("navigation", { name: "主要导航" });
    expect(within(investmentNavigation).getAllByRole("link").map((link) => link.textContent)).toEqual(["收支账本", "分类管理", "导入账单", "投资账本", "当前持仓", "投资事件"]);
    expect(within(investmentNavigation).getByRole("link", { name: "投资事件" })).toHaveAttribute("aria-current", "page");
    expect(within(investmentNavigation).getByRole("link", { name: "当前持仓" })).toHaveAttribute("href", "/investment-holdings");
    expect(within(investmentNavigation).getByRole("link", { name: "投资事件" })).toHaveAttribute("href", "/investment-events");
  });

  it("将分类管理作为收支账本的子项，并在分类页保留投资账本入口", async () => {
    render(<App />);

    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    const cashSubnav = within(navigation).getByLabelText("收支账本页面");
    expect(within(cashSubnav).getByRole("link", { name: "分类管理" })).toBeInTheDocument();

    fireEvent.click(within(cashSubnav).getByRole("link", { name: "分类管理" }));
    await screen.findByRole("heading", { name: "分类管理", level: 1 });
    expect(screen.getByRole("navigation", { name: "主要导航" })).toBe(navigation);
    expect(within(navigation).getByRole("link", { name: "分类管理" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getByRole("link", { name: "投资账本" })).toBeInTheDocument();
  });

  it("导入账单复用统一应用外壳、导航和移动顶栏", async () => {
    window.history.replaceState({}, "", "/cash-import");
    render(<App mobileAccount={<button type="button" aria-label="账户">S</button>} />);

    expect(screen.getByRole("heading", { name: "导入账单", level: 1 })).toBeInTheDocument();
    expect(screen.getByLabelText("打开菜单")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "账户" })).toBeInTheDocument();
    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    expect(within(navigation).getByRole("link", { name: "导入账单" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getAllByRole("link").map((link) => link.textContent)).toEqual(["收支账本", "分类管理", "导入账单", "投资账本", "当前持仓", "投资事件"]);
    expect(document.querySelectorAll("main.app-shell")).toHaveLength(1);
    expect(document.querySelectorAll("#cash-import.app-shell")).toHaveLength(0);
  });

  it("从分类管理返回收支账本时切换 pathname 并只保留一个当前项", async () => {
    render(<App />);

    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    fireEvent.click(within(navigation).getByRole("link", { name: "分类管理" }));
    await screen.findByRole("heading", { name: "分类管理", level: 1 });

    fireEvent.click(within(navigation).getByRole("link", { name: "收支账本" }));
    await screen.findByRole("heading", { name: "收支账本", level: 1 });
    expect(window.location.pathname).toBe("/");
    expect(within(navigation).getByRole("link", { name: "收支账本" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getAllByRole("link").filter((link) => link.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("从分类管理切换到投资事件时不残留收支分类当前态", async () => {
    render(<App />);

    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    fireEvent.click(within(navigation).getByRole("link", { name: "分类管理" }));
    await screen.findByRole("heading", { name: "分类管理", level: 1 });

    fireEvent.click(within(navigation).getByRole("link", { name: "投资事件" }));
    await screen.findByRole("heading", { name: "投资事件", level: 1 });
    expect(within(navigation).getByRole("link", { name: "投资事件" })).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getByRole("link", { name: "分类管理" })).not.toHaveAttribute("aria-current");
    expect(within(navigation).getByRole("link", { name: "投资账本" })).not.toHaveAttribute("aria-current");
    expect(within(navigation).getAllByRole("link").filter((link) => link.hasAttribute("aria-current"))).toHaveLength(1);
  });

  it("打开详情抽屉时让统一外壳背景不可交互", async () => {
    render(<App />);

    const trigger = await screen.findByRole("button", { name: "查看咖啡店的收支详情" });
    trigger.click();
    await screen.findByRole("dialog", { name: "收支详情" });
    expect(document.querySelector("main.app-shell")).toHaveAttribute("inert");
  });

  it("移动端菜单默认收起，选择路由后自动收起", async () => {
    render(<App />);

    const menuButton = screen.getByLabelText("打开菜单");
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
    expect(document.querySelector("aside.sidebar")).not.toHaveClass("is-nav-open");

    fireEvent.click(menuButton);
    expect(screen.getByLabelText("关闭菜单")).toHaveAttribute("aria-expanded", "true");
    expect(document.querySelector("aside.sidebar")).toHaveClass("is-nav-open");

    const eventsLink = screen.getByRole("link", { name: "投资事件" });
    eventsLink.focus();
    fireEvent.click(eventsLink);
    await waitFor(() => expect(screen.getByRole("heading", { name: "投资事件", level: 1 })).toBeInTheDocument());
    expect(screen.getByLabelText("打开菜单")).toHaveAttribute("aria-expanded", "false");
    expect(document.querySelector("aside.sidebar")).not.toHaveClass("is-nav-open");
    await waitFor(() => expect(document.activeElement).toBe(screen.getByLabelText("打开菜单")));
  });
});

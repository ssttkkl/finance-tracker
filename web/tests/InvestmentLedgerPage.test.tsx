import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InvestmentLedgerPage } from "../src/pages/InvestmentLedgerPage";
import type { InvestmentEvent, InvestmentEvidence, Portfolio } from "../src/api/types";

const account = { id: 103, name: "投资账户", type: "security", active: true };
const event: InvestmentEvent = {
  event_id: "fixture:investment-003", occurred_at: "2026-08-07T01:22:00+00:00", account,
  record_type: "trade", record_subtype: "security", currency: "USD", note: "买入订单",
  from_asset: { ticker: "USD", amount: "1011.530000000000000000" }, to_asset: { ticker: "AAPL.US", amount: "10.000000000000000001" },
  commission: { asset: "USD", amount: "11.530000000000000000" },
  source_type: "fixture", record_id: "investment-003", relations: [],
};

const portfolio: Portfolio = { total_market_value: null, total_profit: null, total_profit_rate: null, period_profit: null, period_profit_rate: null, accounts: [{ name: "投资账户", currency: "USD", positions: [{
  ticker: "AAPL.US", shares: "10", total_cost: "1000.00", cost_currency: "USD", is_cash: false,
  current_price: "101.25", market_value: "1012.50", profit: "12.50", quote_status: "complete", quote_reason: "ok",
  quote_currency: "USD", display_currency: null, display_market_value: null, fx_rate: null, fx_status: null, fx_reason: null,
  quote_observed_at: "2026-08-12T13:00:00+00:00", quote_session: "post_market",
  period_profit: null, period_profit_rate: null,
}] }] };
const evidence: InvestmentEvidence = { data_version: 1, event, source_snapshot: { action: "BUY" }, relations: [] };

class MockEventSource {
  static instances: MockEventSource[] = [];
  readonly listeners = new Map<string, Array<(event: MessageEvent<string>) => void>>();
  readonly url: string;
  closed = false;
  onerror: (() => void) | null = null;
  constructor(url: string) { this.url = url; MockEventSource.instances.push(this); }
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void) { this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]); }
  close() { this.closed = true; }
  emit(type: string, value: unknown) { for (const listener of this.listeners.get(type) ?? []) listener(new MessageEvent(type, { data: JSON.stringify(value) })); }
}
const snapshotEvent: InvestmentEvent = {
  ...event,
  event_id: "fixture:investment-snapshot",
  occurred_at: "2026-08-06T01:22:00+00:00",
  record_type: "snapshot",
  record_subtype: "cash",
  note: "余额记录",
  from_asset: { ticker: null, amount: "0.000000000000000000" },
  to_asset: { ticker: "USD", amount: "2865.360000000000000000" },
  commission: { asset: null, amount: "0.000000000000000000" },
};
const incomeEvent: InvestmentEvent = {
  ...event,
  event_id: "fixture:investment-income",
  occurred_at: "2026-08-05T01:22:00+00:00",
  record_type: "income",
  record_subtype: "dividend_cash",
  note: "现金股息",
  from_asset: { ticker: null, amount: "0.000000000000000000" },
  to_asset: { ticker: "USD", amount: "4.200000000000000000" },
  commission: { asset: null, amount: "0.000000000000000000" },
};

function json(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } }));
}

function holdingsOnly(value: Portfolio): Portfolio {
  return {
    ...value,
    total_market_value: null,
    total_profit: null,
    total_profit_rate: null,
    period_profit: null,
    period_profit_rate: null,
    accounts: value.accounts.map((portfolioAccount) => ({
      ...portfolioAccount,
      positions: portfolioAccount.positions.map((position) => ({
        ...position,
        current_price: null,
        market_value: null,
        profit: null,
        quote_status: null,
        quote_reason: null,
        quote_currency: null,
        quote_observed_at: null,
        quote_session: null,
        display_currency: null,
        display_market_value: null,
        fx_rate: null,
        fx_status: null,
        fx_reason: null,
        period_profit: null,
        period_profit_rate: null,
      })),
    })),
  };
}

beforeEach(() => { vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000"); MockEventSource.instances = []; vi.stubGlobal("EventSource", MockEventSource); });
afterEach(() => { cleanup(); window.localStorage.clear(); vi.useRealTimers(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

describe("InvestmentLedgerPage", () => {
  it("将事件表和详情事实行固定为已确认原型的布局契约", () => {
    const styles = readFileSync(resolve(import.meta.dirname, "../src/investment.css"), "utf8");
    const prototype = readFileSync(resolve(import.meta.dirname, "../../openspec/changes/investment-ledger-browser/prototype/events.html"), "utf8");

    expect(styles).toContain(".investment-table{width:100%;min-width:1040px;border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:12px}");
    expect(styles).toContain(".investment-table th,.investment-table td{padding:13px var(--space-3);border-bottom:0;text-align:left;vertical-align:middle;white-space:nowrap}");
    expect(styles).toContain(".investment-table tbody tr{border-bottom:var(--rule-1) solid var(--color-rule);transition:background-color var(--dur-fast) var(--ease-standard),box-shadow var(--dur-fast) var(--ease-standard)}");
    expect(styles).toContain("@media(min-width:821px){.investment-table tbody tr:last-child{border-bottom:0}}");
    expect(styles).toContain(".investment-section .section-head{display:block;margin-bottom:var(--space-3)}");
    expect(styles).toContain(".evidence .investment-detail-changes dl,.evidence .investment-detail-supplement{display:block");
    expect(styles).toContain(".investment-detail-line,.investment-detail-fact{display:grid;grid-template-columns:88px minmax(0,1fr)");
    expect(styles).toContain(".holding-symbol,.holding-price{display:table-cell;white-space:normal!important}");
    expect(styles).toContain(".holding-symbol strong,.holding-symbol small,.holding-price>span,.holding-price>small{display:block}");
    expect(styles).toContain("@media(prefers-reduced-motion:reduce){.refresh-button[aria-busy=\"true\"] .refresh-ring{animation:none}}");
    expect(prototype).toContain('placeholder="如 AAPL 或 .US"');
  });

  it("独立读取事件和持仓，并保留精确十进制与估值状态", async () => {
    const partial = { ...portfolio.accounts[0].positions[0], ticker: "BTC", quote_status: "partial" as const, quote_reason: "query_deadline_exceeded", current_price: null, market_value: null };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json({ accounts: [{ ...portfolio.accounts[0], positions: [portfolio.accounts[0].positions[0], partial] }] });
      return json({ data_version: 1, items: [event], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage view="events" />);

    expect(screen.getByText("正在读取投资事件…")).toBeInTheDocument();
    await screen.findByText("买入订单");
    expect(screen.getByText("-1,011.53 USD")).toBeInTheDocument();
    expect(screen.queryByText("已估值")).not.toBeInTheDocument();
    expect(screen.queryByText("价格不完整")).not.toBeInTheDocument();
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/investment-events"))).toBe(true);
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/investment-portfolio"))).toBe(false);
  });

  it.each([
    ["秒", "2026-08-12T13:00:35+00:00", "报价于30秒前 · 盘后"],
    ["分钟和秒", "2026-08-12T12:59:00+00:00", "报价于2分5秒前 · 盘后"],
    ["小时、分钟和秒", "2026-08-12T09:57:00+00:00", "报价于3小时4分5秒前 · 盘后"],
  ])("在当前单价下显示相对报价时间（%s）和盘后时段", async (_unit, quoteObservedAt, expected) => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-12T13:01:05+00:00"));
    const quotedPortfolio = {
      ...portfolio,
      accounts: [{ ...portfolio.accounts[0], positions: [{ ...portfolio.accounts[0].positions[0], quote_observed_at: quoteObservedAt }] }],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json(quotedPortfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);

    await screen.findByText(expected);
  });

  it("支持筛选、证据抽屉焦点和关系/来源详情", async () => {
    const related = { kind: "cash_investment_funding", status: "accepted", direction: "cash_to_investment", rule_id: "cash-investment-funding-v1", cash_account: { id: 101, name: "日常账户", type: "cash", active: true }, cash_amount: "-1000.00", cash_currency: "USD", cash_occurred_at: event.occurred_at, cash_counterparty: "银行", cash_note: "转证券", cash_source_type: "fixture", cash_record_id: "cash-1", evidence: {} };
    const withRelation = { ...event, relations: [related] };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/evidence/")) return json({ ...evidence, event: withRelation, relations: [related] });
      if (input.includes("/investment-portfolio")) return json(portfolio);
      return json({ data_version: 1, items: [withRelation], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage view="events" />);
    const trigger = await screen.findByRole("button", { name: "查看买入订单的详情" });
    fireEvent.click(trigger);
    const close = await screen.findByRole("button", { name: "关闭" });
    expect(close).toHaveFocus();
    expect(screen.getByRole("dialog", { name: "买入" })).toBeInTheDocument();
    const dialog = screen.getByRole("dialog", { name: "买入" });
    expect(within(dialog).getByText("资产变动", { exact: true })).toBeInTheDocument();
    expect(within(dialog).getByText("现金账户", { exact: true })).toBeInTheDocument();
    expect(within(dialog).queryByText("资金流向", { exact: true })).not.toBeInTheDocument();
    expect(within(dialog).queryByText("更多信息", { exact: true })).not.toBeInTheDocument();
    fireEvent.keyDown(close, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog", { name: "投资详情" })).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();

    fireEvent.change(screen.getByLabelText("事件类型"), { target: { value: "trade" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("record_type=trade"))).toBe(true));

    const ticker = screen.getByLabelText("标的");
    expect(ticker).toHaveAttribute("placeholder", "如 AAPL 或 .US");
    fireEvent.change(ticker, { target: { value: "apl" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("ticker=apl"))).toBe(true));
  });

  it("按经济效果展示带符号资产，并让详情入口保持简洁", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      return json({ data_version: 1, items: [event, snapshotEvent, incomeEvent], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage view="events" />);
    await screen.findByText("+10 AAPL.US");

    expect(screen.getByRole("columnheader", { name: "事件" })).toBeInTheDocument();
    expect(screen.getByText("-1,011.53 USD")).toBeInTheDocument();
    expect(screen.getByText("+10 AAPL.US")).toBeInTheDocument();
    expect(screen.getByText("余额")).toBeInTheDocument();
    expect(screen.getByText("2,865.36 USD")).toBeInTheDocument();
    expect(screen.getByText("+4.2 USD")).toBeInTheDocument();
    expect(screen.queryByText("付出", { exact: true })).not.toBeInTheDocument();
    expect(screen.queryByText("换入", { exact: true })).not.toBeInTheDocument();

    const trigger = screen.getByRole("button", { name: "查看买入订单的详情" });
    expect(trigger.querySelector("svg")).toBeInTheDocument();
    expect(trigger).not.toHaveTextContent("查看详情");
  });

  it("事件列表失败时不影响独立的持仓页面", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      return json({ error: { code: "storage.busy" } }, 503);
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage view="events" />);

    expect(await screen.findByText("账本正忙，请稍后重试。")).toBeInTheDocument();
    expect(screen.queryByText("AAPL.US")).not.toBeInTheDocument();
  });

  it("没有匹配事件时显示空状态而不隐藏持仓", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage view="events" />);

    expect(await screen.findByText("当前筛选没有匹配的投资事件。"))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "当前持仓" })).not.toBeInTheDocument();
  });

  it("按选择的周期读取持仓，并用表格显示摘要和持仓表现", async () => {
    const richPortfolio: Portfolio = {
      total_market_value: "8231.71", total_profit: "179.45", total_profit_rate: "0.0218",
      period_profit: "86.40", period_profit_rate: "0.0061",
      accounts: [{ name: "投资账户", currency: "USD", positions: [{
        ...portfolio.accounts[0].positions[0], period_profit: "8.04", period_profit_rate: "0.0080",
      }] }],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json(richPortfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);
    await screen.findByText("AAPL.US");

    expect(screen.getByRole("table", { name: "当前持仓" })).toBeInTheDocument();
    expect(screen.getByText("总浮盈亏")).toBeInTheDocument();
    expect(screen.getByText("近 24 小时浮盈亏")).toBeInTheDocument();
    expect(screen.getByText("当前总市值")).toBeInTheDocument();
    expect(screen.queryByText("已估值")).not.toBeInTheDocument();
    expect(screen.queryByText("价格不完整")).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("时间范围"), { target: { value: "30d" } });
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("period=30d"))).toBe(true));
  });

  it("仅在展示层将持仓高精度数值四舍五入到两位", async () => {
    const precisePortfolio: Portfolio = {
      total_market_value: "12345.6789", total_profit: "-12.3456", total_profit_rate: "-0.001",
      period_profit: "0.0051", period_profit_rate: "0.0001",
      accounts: [{ name: "投资账户", currency: "USD", positions: [{
        ...portfolio.accounts[0].positions[0], shares: "1.23456", current_price: "123.4567",
        market_value: "12345.6789", profit: "-12.3456", period_profit: "0.0051", period_profit_rate: "0.0001",
      }] }],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json(precisePortfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);
    await screen.findByText("AAPL.US");

    expect(screen.getByText("123.46 USD")).toBeInTheDocument();
    expect(screen.getByText("1.23")).toBeInTheDocument();
    expect(screen.getAllByText("12,345.68 USD")).toHaveLength(2);
    expect(screen.getAllByText("-12.35 USD")).toHaveLength(2);
    expect(screen.queryByText(/123\.4567|12345\.6789|12\.3456/)).not.toBeInTheDocument();
  });

  it("记录基准显示准确时间，并提醒周期结果可能不完整", async () => {
    const baselineAt = "2026-08-12T09:30:00+00:00";
    const baselineDate = new Date(baselineAt);
    const baselineTime = `${baselineDate.getFullYear()}年${baselineDate.getMonth() + 1}月${baselineDate.getDate()}日 ${String(baselineDate.getHours()).padStart(2, "0")}:${String(baselineDate.getMinutes()).padStart(2, "0")}`;
    const baselinedPortfolio = {
      total_market_value: "1600", total_profit: "100", total_profit_rate: "0.0625",
      period_profit: "100", period_profit_rate: null,
      period_baselines: [{ account: "投资账户", ticker: "AAPL.US", occurred_at: baselineAt }],
      accounts: [{ name: "投资账户", currency: "USD", positions: [{
        ...portfolio.accounts[0].positions[0], period_profit: "100", period_profit_rate: "0.1",
        period_baselines: [{ account: "投资账户", ticker: "AAPL.US", occurred_at: baselineAt }],
      }] }],
    } as Portfolio;
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json(baselinedPortfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);

    expect(await screen.findByText(`以 ${baselineTime} 的记录为基准，可能无法反映真实盈亏。`)).toBeInTheDocument();
    expect(screen.getByText(`以 ${baselineTime} 的记录为基准`)).toBeInTheDocument();
  });

  it("先显示基础持仓，再通过 SSE 原位补齐行情、估值和总览", async () => {
    const complete: Portfolio = {
      total_market_value: "1012.50", total_profit: "12.50", total_profit_rate: "0.0125",
      period_profit: "8.04", period_profit_rate: "0.0080",
      accounts: [{ name: "投资账户", currency: "USD", positions: [{
        ...portfolio.accounts[0].positions[0], period_profit: "8.04", period_profit_rate: "0.0080",
      }] }],
    };
    let resolveHoldings!: (response: Response) => void;
    const holdingsResponse = new Promise<Response>((resolve) => { resolveHoldings = resolve; });
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio") && input.includes("phase=holdings")) return holdingsResponse;
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);

    await waitFor(() => expect(fetch.mock.calls.filter(([input]) => String(input).includes("/investment-portfolio"))).toHaveLength(1));
    await act(async () => { resolveHoldings(await json(holdingsOnly(complete))); });
    const table = await screen.findByRole("table", { name: "当前持仓" });
    expect(within(table).getByText("AAPL.US")).toBeInTheDocument();
    expect(within(table).getByText("10")).toBeInTheDocument();
    expect(within(table).queryByText("101.25 USD")).not.toBeInTheDocument();

    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    const stream = MockEventSource.instances[0];
    expect(new URL(stream.url).pathname).toBe("/api/v1/investment-portfolio/stream");
    expect(new URL(stream.url).searchParams.get("period")).toBe("24h");
    await act(async () => { stream.emit("portfolio", { version: 1, portfolio: complete }); });
    expect(await screen.findByText("101.25 USD")).toBeInTheDocument();
    expect(screen.getAllByText("+12.50 USD")).toHaveLength(2);
    expect(screen.getAllByText("1,012.50 USD")).toHaveLength(2);
  });

  it("手动刷新只触发服务端刷新，并在 SSE 未知结果中保留当前行情、估值和总览", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-08-12T13:02:05+00:00"));
    const complete: Portfolio = {
      total_market_value: "1012.50", total_profit: "12.50", total_profit_rate: "0.0125",
      period_profit: "8.04", period_profit_rate: "0.0080",
      accounts: [{ name: "投资账户", currency: "USD", positions: [{
        ...portfolio.accounts[0].positions[0], period_profit: "8.04", period_profit_rate: "0.0080",
      }] }],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio") && input.includes("phase=holdings")) return json(holdingsOnly(complete));
      if (input.includes("/investment-portfolio/refresh")) return json({ accepted: true }, 202);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    const stream = MockEventSource.instances[0];
    await act(async () => { stream.emit("portfolio", { version: 1, portfolio: complete }); });
    expect(await screen.findByText("101.25 USD")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "刷新持仓" }));
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("/investment-portfolio/refresh"))).toBe(true));
    await act(async () => { stream.emit("portfolio", { version: 2, portfolio: holdingsOnly(complete) }); });

    expect(screen.getByText("101.25 USD")).toBeInTheDocument();
    expect(screen.getByText("报价于2分5秒前 · 盘后")).toBeInTheDocument();
    expect(screen.getAllByText("+12.50 USD")).toHaveLength(2);
    expect(screen.getAllByText("1,012.50 USD")).toHaveLength(2);
  });

  it("持仓页使用 SSE 而不启动定时估值轮询，事件页也不连接持仓流", async () => {
    vi.useFakeTimers();
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio")) return json(portfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);
    await act(async () => { await Promise.resolve(); await Promise.resolve(); });
    const before = fetch.mock.calls.filter(([input]) => String(input).includes("/investment-portfolio")).length;
    await act(async () => { vi.advanceTimersByTime(30000); await Promise.resolve(); await Promise.resolve(); });
    expect(fetch.mock.calls.filter(([input]) => String(input).includes("/investment-portfolio")).length).toBe(before);
    expect(MockEventSource.instances).toHaveLength(1);

    cleanup();
    fetch.mockClear();
    render(<InvestmentLedgerPage view="events" />);
    await act(async () => { vi.advanceTimersByTime(30000); await Promise.resolve(); });
    expect(fetch.mock.calls.some(([input]) => String(input).includes("/investment-portfolio"))).toBe(false);
    expect(MockEventSource.instances).toHaveLength(1);
  });

  it("页面重新可见时重连 SSE，并向服务端请求一次优先刷新", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account] });
      if (input.includes("/investment-portfolio/refresh")) return json({ accepted: true }, 202);
      if (input.includes("/investment-portfolio")) return json(holdingsOnly(portfolio));
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });

    render(<InvestmentLedgerPage />);
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1));
    Object.defineProperty(document, "visibilityState", { configurable: true, value: "hidden" });
    fireEvent(document, new Event("visibilitychange"));
    expect(MockEventSource.instances[0].closed).toBe(true);

    Object.defineProperty(document, "visibilityState", { configurable: true, value: "visible" });
    fireEvent(document, new Event("visibilitychange"));
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(2));
    await waitFor(() => expect(fetch.mock.calls.some(([input]) => String(input).includes("/investment-portfolio/refresh"))).toBe(true));
  });

  it("合并同标的时按成本币种分组，并支持负成本的盈亏率方向", async () => {
    const secondAccount = { id: 104, name: "第二账户", type: "security", active: true };
    const thirdAccount = { id: 105, name: "欧元账户", type: "security", active: true };
    const position = (accountCurrency: string, shares: string, cost: string, value: string, usdValue: string) => ({
      ...portfolio.accounts[0].positions[0], quote_currency: accountCurrency, cost_currency: accountCurrency,
      shares, total_cost: cost, market_value: value, usd_market_value: usdValue, profit: String(Number(value) - Number(cost)),
    });
    const mergedPortfolio: Portfolio = {
      total_market_value: null, total_profit: null, total_profit_rate: null, period_profit: null, period_profit_rate: null,
      accounts: [
        { name: "投资账户", currency: "USD", positions: [position("USD", "2", "-100", "200", "200")] },
        { name: "第二账户", currency: "USD", positions: [position("USD", "3", "150", "300", "300")] },
        { name: "欧元账户", currency: "EUR", positions: [position("EUR", "4", "200", "400", "500")] },
      ],
    };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/accounts")) return json({ items: [account, secondAccount, thirdAccount] });
      if (input.includes("/investment-portfolio")) return json(mergedPortfolio);
      return json({ data_version: 1, items: [], next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);

    render(<InvestmentLedgerPage />);
    await waitFor(() => expect(screen.getAllByText("AAPL.US")).toHaveLength(3));
    fireEvent.change(screen.getByLabelText("同一标的"), { target: { value: "merge" } });

    expect(screen.getAllByText("AAPL.US")).toHaveLength(2);
    expect(screen.getAllByText("多个账户", { exact: false }).length).toBeGreaterThan(0);
    expect(screen.getByText("USD +450 · EUR +200")).toBeInTheDocument();
    expect(screen.getByText("USD 500 · EUR 400")).toBeInTheDocument();
    expect(Array.from(screen.getByRole("table", { name: "当前持仓" }).querySelectorAll('td[data-label="仓位"]')).map((cell) => cell.textContent)).toEqual(["+50%", "+50%"]);
    expect(screen.getByText("+450 USD")).toBeInTheDocument();
    expect(screen.getByText("+900%")).toBeInTheDocument();
  });
});

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import "../investment.css";
import { fetchInvestmentAccounts, fetchInvestmentEvidence, fetchInvestmentPage, fetchInvestmentPortfolio, openInvestmentPortfolioStream, requestInvestmentPortfolioRefresh } from "../api/investmentLedger";
import type { Account, InvestmentEvent, InvestmentEvidence, InvestmentFilters, Portfolio } from "../api/types";
import { InvestmentEvidenceDetail } from "../components/InvestmentEvidenceDetail";
import { InvestmentFiltersBar } from "../components/InvestmentFilters";
import { InvestmentHoldings, type HoldingDisplayOptions } from "../components/InvestmentHoldings";
import { InvestmentStatusView } from "../components/InvestmentStatusView";
import { InvestmentTable } from "../components/InvestmentTable";
import { LoadMoreControl } from "../components/Pagination";

const errorMessages: Record<string, string> = {
  api_origin_invalid: "暂时无法连接账本，请稍后重试。",
  "storage.busy": "账本正忙，请稍后重试。",
  "storage.readonly": "暂时无法读取账本，请稍后重试。",
  "storage.connect": "暂时无法连接账本，请稍后重试。",
  "storage.schema": "账本暂时无法读取，请稍后重试。",
  "storage.workspace": "暂时无法打开账本，请稍后重试。",
  "storage.config": "账本暂时无法使用，请稍后重试。",
  invalid_filter: "筛选条件有误，请检查后重试。",
  invalid_cursor: "记录已更新，请重新加载。",
  "investment.updated": "投资账本已更新，请重新加载。",
  "valuation.invalid_display_currency": "币种暂不可用，请换一个币种。",
  api_request_failed: "请求失败，请稍后重试。",
};

function messageFor(error: unknown) {
  const code = error instanceof Error ? error.message : "api_request_failed";
  return errorMessages[code] ?? errorMessages.api_request_failed;
}

const displayOptionsKey = "finance-tracker:investment-holdings-display";
const defaultDisplayOptions: HoldingDisplayOptions = {
  accountId: "", sort: "market_value_desc", grouping: "split", currency: "", period: "24h",
};

function readDisplayOptions(): HoldingDisplayOptions {
  try {
    const raw = window.localStorage.getItem(displayOptionsKey);
    if (!raw) return defaultDisplayOptions;
    const value = JSON.parse(raw) as Partial<HoldingDisplayOptions>;
    return {
      accountId: typeof value.accountId === "string" ? value.accountId : defaultDisplayOptions.accountId,
      sort: value.sort === "profit_desc" || value.sort === "ticker_asc" ? value.sort : defaultDisplayOptions.sort,
      grouping: value.grouping === "merge" ? "merge" : "split",
      currency: typeof value.currency === "string" && /^[A-Z]{3}$/.test(value.currency) ? value.currency : "",
      period: value.period && Object.hasOwn({ "24h": 1, week_to_date: 1, month_to_date: 1, "30d": 1, "90d": 1, year_to_date: 1, "365d": 1 }, value.period) ? value.period : "24h",
    } as HoldingDisplayOptions;
  } catch (_error) {
    return defaultDisplayOptions;
  }
}

function portfolioScope(currency: string, period: HoldingDisplayOptions["period"]) {
  return `${currency}:${period}`;
}

function positionKey(accountName: string, position: Portfolio["accounts"][number]["positions"][number]) {
  return `${accountName}:${position.ticker.toLowerCase()}:${position.cost_currency.toUpperCase()}`;
}

function hasCompleteMarketValue(position: Portfolio["accounts"][number]["positions"][number]) {
  return position.is_cash || (
    position.current_price !== null
    && position.market_value !== null
    && (position.display_currency === null || position.display_market_value !== null)
  );
}

function retainLastKnownValuation(previous: Portfolio, incoming: Portfolio): Portfolio {
  const previousPositions = new Map(
    previous.accounts.flatMap((account) => account.positions.map((position) => [positionKey(account.name, position), position])),
  );
  const accounts = incoming.accounts.map((account) => ({
    ...account,
    positions: account.positions.map((position) => {
      const previousPosition = previousPositions.get(positionKey(account.name, position));
      return previousPosition && !hasCompleteMarketValue(position) ? {
        ...position,
        current_price: previousPosition.current_price,
        market_value: previousPosition.market_value,
        profit: previousPosition.profit,
        quote_status: previousPosition.quote_status,
        quote_reason: previousPosition.quote_reason,
        quote_currency: previousPosition.quote_currency,
        quote_observed_at: previousPosition.quote_observed_at,
        quote_session: previousPosition.quote_session,
        display_currency: previousPosition.display_currency,
        display_market_value: previousPosition.display_market_value,
        fx_rate: previousPosition.fx_rate,
        fx_status: previousPosition.fx_status,
        fx_reason: previousPosition.fx_reason,
        period_profit: position.period_profit ?? previousPosition.period_profit,
        period_profit_rate: position.period_profit_rate ?? previousPosition.period_profit_rate,
      } : position;
    }),
  }));
  return {
    ...incoming,
    accounts,
    total_market_value: incoming.total_market_value ?? previous.total_market_value,
    total_profit: incoming.total_profit ?? previous.total_profit,
    total_profit_rate: incoming.total_profit_rate ?? previous.total_profit_rate,
    period_profit: incoming.period_profit ?? previous.period_profit,
    period_profit_rate: incoming.period_profit_rate ?? previous.period_profit_rate,
  };
}

export function InvestmentLedgerPage({ view = "holdings", onModalStateChange }: { view?: "holdings" | "events"; onModalStateChange?: (open: boolean) => void }) {
  const isEvents = view === "events";
  const [filters, setFilters] = useState<InvestmentFilters>({});
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [accountsError, setAccountsError] = useState(false);
  const [items, setItems] = useState<InvestmentEvent[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [eventStatus, setEventStatus] = useState<"loading" | "ready" | "empty" | "error">("loading");
  const [eventError, setEventError] = useState<string | undefined>();
  const [appendLoading, setAppendLoading] = useState(false);
  const [appendError, setAppendError] = useState<string | undefined>();
  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [holdings, setHoldings] = useState<Portfolio | null>(null);
  const [portfolioStatus, setPortfolioStatus] = useState<"loading" | "ready" | "error">("loading");
  const [portfolioError, setPortfolioError] = useState<string | undefined>();
  const [displayOptions, setDisplayOptions] = useState<HoldingDisplayOptions>(readDisplayOptions);
  const [portfolioRefreshing, setPortfolioRefreshing] = useState(false);
  const [portfolioPageVisible, setPortfolioPageVisible] = useState(() => document.visibilityState !== "hidden");
  const [selected, setSelected] = useState<InvestmentEvent | null>(null);
  const [evidence, setEvidence] = useState<InvestmentEvidence | null>(null);
  const [evidenceState, setEvidenceState] = useState<"loading" | "ready" | "error">("loading");
  const pageAbortController = useRef<AbortController | null>(null);
  const portfolioAbortController = useRef<AbortController | null>(null);
  const evidenceAbortController = useRef<AbortController | null>(null);
  const opener = useRef<HTMLButtonElement | null>(null);
  const restoreFocus = useRef(false);
  const pageRequestId = useRef(0);
  const portfolioRequestId = useRef(0);
  const evidenceRequestId = useRef(0);
  const appendCursor = useRef<string | null>(null);
  const loadMoreRef = useRef<(retry?: boolean) => void>(() => undefined);
  const lastGoodCurrency = useRef(displayOptions.currency);
  const lastPortfolioScope = useRef<string | null>(null);

  const loadAccounts = () => {
    const controller = new AbortController();
    fetchInvestmentAccounts(controller.signal).then((value) => { setAccounts(value); setAccountsError(false); }).catch((error: unknown) => { if (!controller.signal.aborted) setAccountsError(true); });
    return () => controller.abort();
  };

  const resetAndLoad = () => {
    pageAbortController.current?.abort();
    const controller = new AbortController(); pageAbortController.current = controller;
    const requestId = ++pageRequestId.current;
    appendCursor.current = null;
    setItems([]); setNextCursor(null); setAppendError(undefined); setAppendLoading(false); setEventStatus("loading"); setEventError(undefined);
    fetchInvestmentPage(filters, null, controller.signal).then((page) => {
      if (requestId !== pageRequestId.current) return;
      setItems(page.items); setNextCursor(page.next_cursor); setEventStatus(page.items.length ? "ready" : "empty");
    }).catch((error: unknown) => {
      if (!controller.signal.aborted && requestId === pageRequestId.current) { setEventError(messageFor(error)); setEventStatus("error"); }
    });
  };

  const loadMore = (retry = false) => {
    const cursor = nextCursor;
    if (!cursor || appendLoading || (!retry && appendError) || appendCursor.current === cursor) return;
    appendCursor.current = cursor;
    const controller = new AbortController(); pageAbortController.current = controller;
    setAppendLoading(true); setAppendError(undefined);
    fetchInvestmentPage(filters, cursor, controller.signal).then((page) => {
      setItems((current) => [...current, ...page.items.filter((item) => !current.some((old) => old.event_id === item.event_id))]);
      setNextCursor(page.next_cursor); setAppendLoading(false); appendCursor.current = null;
    }).catch((error: unknown) => {
      if (!controller.signal.aborted) { setAppendError(messageFor(error)); setAppendLoading(false); appendCursor.current = null; }
    });
  };
  loadMoreRef.current = loadMore;
  const refreshPortfolio = () => {
    if (isEvents) return;
    setPortfolioRefreshing(true); setPortfolioError(undefined);
    requestInvestmentPortfolioRefresh(displayOptions.currency || undefined, displayOptions.period).catch((error: unknown) => {
      setPortfolioError(messageFor(error)); setPortfolioRefreshing(false);
    });
  };

  useEffect(() => loadAccounts(), []);
  useEffect(() => {
    if (!isEvents) return undefined;
    resetAndLoad();
    return () => pageAbortController.current?.abort();
  }, [isEvents, filters.date_from, filters.date_to, filters.account_id, filters.record_type, filters.ticker]);
  useEffect(() => {
    if (isEvents || !portfolioPageVisible) return undefined;
    portfolioAbortController.current?.abort();
    const controller = new AbortController(); portfolioAbortController.current = controller;
    const requestId = ++portfolioRequestId.current;
    const scope = portfolioScope(displayOptions.currency, displayOptions.period);
    const hasCompatiblePortfolio = lastPortfolioScope.current === scope && portfolio !== null;
    let holdingsReady = false;
    if (!hasCompatiblePortfolio) {
      lastPortfolioScope.current = null;
      setPortfolio(null);
      setHoldings(null);
      setPortfolioStatus("loading");
    }
    setPortfolioRefreshing(true); setPortfolioError(undefined);
    const stream = openInvestmentPortfolioStream(displayOptions.currency || undefined, displayOptions.period, {
      onPortfolio: (value) => {
        if (requestId !== portfolioRequestId.current) return;
        lastGoodCurrency.current = displayOptions.currency;
        const shouldRetain = lastPortfolioScope.current === scope;
        lastPortfolioScope.current = scope;
        setPortfolio((previous) => shouldRetain && previous ? retainLastKnownValuation(previous, value) : value);
        setPortfolioStatus("ready"); setPortfolioRefreshing(false); setPortfolioError(undefined);
        try { window.localStorage.setItem(displayOptionsKey, JSON.stringify(displayOptions)); } catch (_error) { /* 浏览器存储不可用时仍保留当前页偏好。 */ }
      },
      onRefreshError: () => {
        if (requestId !== portfolioRequestId.current) return;
        setPortfolioError(messageFor(new Error("api_request_failed")));
        setPortfolioStatus(hasCompatiblePortfolio || holdingsReady ? "ready" : "error");
        setPortfolioRefreshing(false);
      },
    });
    fetchInvestmentPortfolio(displayOptions.currency || undefined, displayOptions.period, controller.signal, "holdings").then((value) => {
      if (requestId === portfolioRequestId.current) {
        holdingsReady = true;
        setHoldings(value);
        if (!hasCompatiblePortfolio) setPortfolioStatus("ready");
      }
    }).catch((error: unknown) => {
      if (!controller.signal.aborted && requestId === portfolioRequestId.current && !hasCompatiblePortfolio) {
        setPortfolioError(messageFor(error));
      }
    });
    return () => { controller.abort(); stream.close(); };
  }, [isEvents, portfolioPageVisible, displayOptions.currency, displayOptions.period]);
  useEffect(() => {
    if (isEvents) return undefined;
    const onVisibilityChange = () => {
      const visible = document.visibilityState !== "hidden";
      setPortfolioPageVisible(visible);
      if (!visible) {
        setPortfolioRefreshing(false);
      } else {
        refreshPortfolio();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    return () => document.removeEventListener("visibilitychange", onVisibilityChange);
  }, [isEvents, displayOptions.currency, displayOptions.period]);
  useEffect(() => {
    if (isEvents) return undefined;
    try {
      window.localStorage.setItem(displayOptionsKey, JSON.stringify({
        ...displayOptions,
        currency: displayOptions.currency === lastGoodCurrency.current ? displayOptions.currency : lastGoodCurrency.current,
      }));
    } catch (_error) {
      // 浏览器存储不可用时仍保留当前页偏好。
    }
    return undefined;
  }, [isEvents, displayOptions.accountId, displayOptions.sort, displayOptions.grouping]);
  useEffect(() => { if (!selected && restoreFocus.current) { restoreFocus.current = false; opener.current?.focus(); } }, [selected]);
  useEffect(() => {
    onModalStateChange?.(Boolean(selected));
    return () => onModalStateChange?.(false);
  }, [onModalStateChange, selected]);

  const openEvidence = (event: InvestmentEvent, source: HTMLButtonElement) => {
    evidenceAbortController.current?.abort();
    const controller = new AbortController(); evidenceAbortController.current = controller;
    const requestId = ++evidenceRequestId.current;
    opener.current = source; setSelected(event); setEvidence(null); setEvidenceState("loading");
    fetchInvestmentEvidence(event.event_id, controller.signal).then((value) => { if (requestId === evidenceRequestId.current) { setEvidence(value); setEvidenceState("ready"); } }).catch((error: unknown) => { if (!controller.signal.aborted && requestId === evidenceRequestId.current) setEvidenceState("error"); });
  };
  const closeEvidence = () => { evidenceAbortController.current?.abort(); evidenceRequestId.current += 1; restoreFocus.current = true; setSelected(null); setEvidence(null); };
  const retryEvidence = () => { if (selected && opener.current) openEvidence(selected, opener.current); };
  const retryMore = () => loadMoreRef.current(true);

  const displayedPortfolio = portfolio ?? holdings;
  return <><section className="ledger investment-workbench" id={isEvents ? "investment-events" : "investment-holdings"} aria-label={isEvents ? "投资事件" : "当前持仓"}><header className="page-header"><div><h1>{isEvents ? "投资事件" : "当前持仓"}</h1></div></header>{accountsError ? <div className="status-view status-error" role="alert"><p>暂时无法读取账户，请重试。</p><button type="button" onClick={loadAccounts}>重试</button></div> : null}{isEvents ? <><InvestmentFiltersBar filters={filters} accounts={accounts} onChange={setFilters} /><section className="investment-section" aria-labelledby="investment-events-title"><div className="section-head"><div><h2 id="investment-events-title">投资事件</h2></div><p>{eventStatus === "ready" ? `已加载 ${items.length} 条` : "按筛选读取"}</p></div>{eventStatus === "loading" ? <><InvestmentTable items={[]} loading onEvidence={openEvidence} /><InvestmentStatusView kind="loading" /></> : null}{eventStatus === "empty" ? <InvestmentStatusView kind="empty" /> : null}{eventStatus === "error" ? <InvestmentStatusView kind="error" message={eventError} onRetry={resetAndLoad} /> : null}{eventStatus === "ready" ? <><InvestmentTable items={items} onEvidence={openEvidence} /><LoadMoreControl hasMore={Boolean(nextCursor)} loading={appendLoading} error={appendError} onLoadMore={appendError ? retryMore : loadMore} /></> : null}</section></> : <InvestmentHoldings portfolio={displayedPortfolio} accounts={accounts} loading={portfolioStatus === "loading" && !displayedPortfolio} refreshing={portfolioRefreshing} error={portfolioError} options={displayOptions} onOptionsChange={setDisplayOptions} onRetry={refreshPortfolio} />}</section>{selected ? createPortal(<InvestmentEvidenceDetail evidence={evidence} loading={evidenceState === "loading"} error={evidenceState === "error"} onClose={closeEvidence} onRetry={retryEvidence} />, document.body) : null}</>;
}

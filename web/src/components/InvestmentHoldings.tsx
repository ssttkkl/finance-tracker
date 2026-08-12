import { useEffect, useMemo, useState } from "react";
import type { Account, Portfolio, PortfolioAccount, PortfolioPeriod, PortfolioPeriodBaseline, PortfolioPosition } from "../api/types";

export type HoldingSort = "market_value_desc" | "profit_desc" | "ticker_asc";
export type HoldingGrouping = "split" | "merge";
export type HoldingDisplayOptions = {
  accountId: string;
  sort: HoldingSort;
  grouping: HoldingGrouping;
  currency: string;
  period: PortfolioPeriod;
};

type Props = {
  portfolio: Portfolio | null;
  accounts: Account[];
  options: HoldingDisplayOptions;
  loading: boolean;
  refreshing: boolean;
  error?: string;
  onOptionsChange: (options: HoldingDisplayOptions) => void;
  onRetry: () => void;
};

type HoldingRow = {
  accountName: string;
  position: PortfolioPosition;
  merged: boolean;
};

const periodLabels: Record<PortfolioPeriod, string> = {
  "24h": "近 24 小时",
  week_to_date: "本周至今",
  month_to_date: "本月至今",
  "30d": "近 30 天",
  "90d": "近 90 天",
  year_to_date: "年初至今",
  "365d": "近 365 天",
};

function decimalParts(value: string | null | undefined) {
  const text = String(value ?? "0");
  const negative = text.startsWith("-");
  const unsigned = negative || text.startsWith("+") ? text.slice(1) : text;
  const [whole = "0", fraction = ""] = unsigned.split(".");
  return { negative, whole: whole || "0", fraction };
}

function decimalAdd(left: string | null | undefined, right: string | null | undefined): string {
  const a = decimalParts(left); const b = decimalParts(right);
  const scale = Math.max(a.fraction.length, b.fraction.length);
  const factor = 10n ** BigInt(scale);
  const toScaled = (parts: ReturnType<typeof decimalParts>) => {
    const digits = `${parts.whole}${parts.fraction.padEnd(scale, "0")}`;
    const value = BigInt(digits || "0");
    return parts.negative ? -value : value;
  };
  const value = toScaled(a) + toScaled(b);
  const negative = value < 0n;
  const absolute = (negative ? -value : value).toString().padStart(scale + 1, "0");
  if (!scale) return `${negative ? "-" : ""}${absolute}`;
  const split = absolute.length - scale;
  return `${negative ? "-" : ""}${absolute.slice(0, split)}.${absolute.slice(split)}`.replace(/\.0+$/, "");
}

function signedDigits(value: string) {
  const parts = decimalParts(value);
  const digits = BigInt(`${parts.whole}${parts.fraction}` || "0");
  return { value: parts.negative ? -digits : digits, scale: parts.fraction.length };
}

function formatScaled(value: bigint, scale: number) {
  const negative = value < 0n;
  const absolute = (negative ? -value : value).toString().padStart(scale + 1, "0");
  if (!scale) return `${negative ? "-" : ""}${absolute}`;
  const split = absolute.length - scale;
  return `${negative ? "-" : ""}${absolute.slice(0, split)}.${absolute.slice(split)}`.replace(/\.0+$/, "");
}

function decimalSign(value: string | null | undefined): number | null {
  if (value === null || value === undefined || value === "") return null;
  const { value: digits } = signedDigits(value);
  return digits === 0n ? 0 : digits < 0n ? -1 : 1;
}

function decimalCompare(left: string | null | undefined, right: string | null | undefined): number | null {
  if (left === null || left === undefined || right === null || right === undefined) return null;
  const a = signedDigits(left); const b = signedDigits(right); const scale = Math.max(a.scale, b.scale);
  const aScaled = a.value * 10n ** BigInt(scale - a.scale);
  const bScaled = b.value * 10n ** BigInt(scale - b.scale);
  return aScaled === bScaled ? 0 : aScaled < bScaled ? -1 : 1;
}

function decimalAbs(value: string) {
  return value.startsWith("-") ? value.slice(1) : value;
}

function decimalMultiply(left: string | null | undefined, right: string | null | undefined): string | null {
  if (left === null || left === undefined || right === null || right === undefined) return null;
  const a = signedDigits(left); const b = signedDigits(right);
  return formatScaled(a.value * b.value, a.scale + b.scale);
}

function decimalDivide(numerator: string | null | undefined, denominator: string | null | undefined, precision = 18): string | null {
  if (numerator === null || numerator === undefined || denominator === null || denominator === undefined) return null;
  const n = signedDigits(numerator); const d = signedDigits(denominator);
  if (d.value === 0n) return null;
  const negative = (n.value < 0n) !== (d.value < 0n);
  const absoluteNumerator = n.value < 0n ? -n.value : n.value;
  const absoluteDenominator = d.value < 0n ? -d.value : d.value;
  const adjustedNumerator = absoluteNumerator * 10n ** BigInt(d.scale);
  const adjustedDenominator = absoluteDenominator * 10n ** BigInt(n.scale);
  const scaledNumerator = adjustedNumerator * 10n ** BigInt(precision);
  let quotient = scaledNumerator / adjustedDenominator;
  if ((scaledNumerator % adjustedDenominator) * 2n >= adjustedDenominator) quotient += 1n;
  return formatScaled(negative ? -quotient : quotient, precision);
}

function decimalRound(value: string, scale: number) {
  const parts = decimalParts(value);
  if (parts.fraction.length <= scale) return value;
  const digits = BigInt(`${parts.whole}${parts.fraction.slice(0, scale)}` || "0");
  const rounded = parts.fraction[scale] >= "5" ? digits + 1n : digits;
  const signed = parts.negative ? -rounded : rounded;
  return formatScaled(signed, scale);
}

function displayValue(value: string | null | undefined, empty = "—") {
  if (value === null || value === undefined) return empty;
  const rounded = decimalRound(value, 2);
  const [whole, fraction] = rounded.split(".");
  const formattedWhole = whole.replace(/^-?\d+/, (part) => part.replace(/\B(?=(\d{3})+(?!\d))/g, ","));
  return fraction ? `${formattedWhole}.${fraction}` : formattedWhole;
}

function signedValue(value: string | null | undefined, currency?: string | null) {
  if (value === null || value === undefined) return "—";
  const sign = value.startsWith("-") ? "" : "+";
  return `${sign}${displayValue(value)}${currency ? ` ${currency}` : ""}`;
}

function percentage(value: string | null | undefined) {
  const percent = decimalMultiply(value, "100");
  if (percent === null) return "—";
  const rounded = decimalRound(percent, 2);
  return `${decimalSign(rounded) === -1 ? "" : "+"}${displayValue(rounded)}%`;
}

function rateFromProfit(profit: string | null, cost: string | null) {
  if (profit === null || cost === null || decimalSign(cost) === 0) return null;
  return decimalDivide(profit, decimalAbs(cost));
}

function rowMarketValue(position: PortfolioPosition, currency: string) {
  return currency ? position.display_market_value : position.market_value;
}

function accountValue(account: PortfolioAccount, currency: string) {
  return account.positions.reduce<string | null>((total, position) => {
    const value = rowMarketValue(position, currency);
    if (value === null || value === undefined) return null;
    return total === null ? value : decimalAdd(total, value);
  }, "0");
}

function accountProfit(account: PortfolioAccount) {
  return account.positions.reduce<string | null>((total, position) => {
    if (position.is_cash || position.profit === null) return total;
    return total === null ? position.profit : decimalAdd(total, position.profit);
  }, "0");
}

function formatBaselineTime(value: string) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, "0");
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

const quoteSessionLabels: Record<NonNullable<PortfolioPosition["quote_session"]>, string> = {
  pre_market: "盘前", regular: "盘中", post_market: "盘后", overnight: "夜盘", unknown: "时段未知",
};

function formatQuoteTime(value: string | null) {
  if (!value) return "报价时间未知";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "报价时间未知";
  const pad = (part: number) => String(part).padStart(2, "0");
  return `报价于 ${date.getMonth() + 1}月${date.getDate()}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function quoteSessionLabel(value: PortfolioPosition["quote_session"]) {
  return quoteSessionLabels[value ?? "unknown"];
}

function baselineTimes(baselines: PortfolioPeriodBaseline[]) {
  return [...new Set(baselines.map((baseline) => formatBaselineTime(baseline.occurred_at)))];
}

function baselineNotice(baselines: PortfolioPeriodBaseline[]) {
  const times = baselineTimes(baselines);
  return times.length ? `以 ${times.join("、")} 的记录为基准，可能无法反映真实盈亏。` : null;
}

function baselineLabel(baselines: PortfolioPeriodBaseline[]) {
  const times = baselineTimes(baselines);
  return times.length ? `以 ${times.join("、")} 的记录为基准` : null;
}

function mergePositions(rows: HoldingRow[]): HoldingRow[] {
  const merged = new Map<string, HoldingRow>();
  for (const row of rows) {
    const key = `${row.position.ticker.toLowerCase()}|${row.position.cost_currency.toUpperCase()}`;
    const previous = merged.get(key);
    if (!previous) {
      merged.set(key, { ...row, merged: true });
      continue;
    }
    const first = previous.position;
    const next = row.position;
    const shares = decimalAdd(first.shares, next.shares);
    const totalCost = decimalAdd(first.total_cost, next.total_cost);
    const marketValue = first.market_value === null || next.market_value === null
      ? null : decimalAdd(first.market_value, next.market_value);
    const displayMarketValue = first.display_market_value === null || next.display_market_value === null
      ? null : decimalAdd(first.display_market_value, next.display_market_value);
    const profit = first.profit === null || next.profit === null ? null : decimalAdd(first.profit, next.profit);
    const periodProfit = first.period_profit === null || next.period_profit === null
      ? null : decimalAdd(first.period_profit, next.period_profit);
    const weightedPrice = first.current_price !== null && next.current_price !== null
      ? decimalDivide(
          decimalAdd(decimalMultiply(first.current_price, first.shares), decimalMultiply(next.current_price, next.shares)),
          shares,
        )
      : null;
    const periodBaselines = [...(first.period_baselines ?? []), ...(next.period_baselines ?? [])];
    const quoteSamples = [first, next].filter((item) => item.quote_observed_at !== null);
    const latestQuote = quoteSamples.reduce<PortfolioPosition | null>((latest, item) => (
      latest === null || new Date(item.quote_observed_at!).getTime() > new Date(latest.quote_observed_at!).getTime()
        ? item : latest
    ), null);
    const periodRate = periodBaselines.length ? null : first.period_profit_rate && next.period_profit_rate
      ? (() => {
          const av = decimalSign(first.period_profit_rate) !== 0
            ? decimalDivide(first.period_profit, first.period_profit_rate) : null;
          const bv = decimalSign(next.period_profit_rate) !== 0
            ? decimalDivide(next.period_profit, next.period_profit_rate) : null;
          const base = decimalAdd(av, bv);
          return decimalSign(base) !== 0 ? decimalDivide(periodProfit, base) : null;
        })()
      : null;
    merged.set(key, {
      accountName: "多个账户",
      merged: true,
      position: {
        ...first,
        shares,
        total_cost: totalCost,
        current_price: weightedPrice,
        market_value: marketValue,
        display_market_value: displayMarketValue,
        profit,
        period_profit: periodProfit,
        period_profit_rate: periodRate,
        period_baselines: periodBaselines,
        quote_observed_at: latestQuote?.quote_observed_at ?? null,
        quote_session: latestQuote?.quote_session ?? (first.quote_session === next.quote_session ? first.quote_session : "unknown"),
      },
    });
  }
  return [...merged.values()];
}

function metricCurrency(portfolio: Portfolio | null, rows: HoldingRow[], displayCurrency: string) {
  if (displayCurrency) return displayCurrency;
  return rows[0]?.position.quote_currency ?? portfolio?.accounts[0]?.currency ?? "";
}

export function InvestmentHoldings({ portfolio, accounts, options, loading, refreshing, error, onOptionsChange, onRetry }: Props) {
  const [currencyDraft, setCurrencyDraft] = useState(options.currency);
  useEffect(() => setCurrencyDraft(options.currency), [options.currency]);

  const selectedAccount = accounts.find((account) => String(account.id) === options.accountId)?.name;
  const visibleAccounts = selectedAccount
    ? portfolio?.accounts.filter((account) => account.name === selectedAccount) ?? []
    : portfolio?.accounts ?? [];
  const baseRows = visibleAccounts.flatMap((account) => account.positions
    .filter((position) => !position.is_cash)
    .map((position) => ({ accountName: account.name, position, merged: false })));
  const rows = useMemo(() => {
    const result = options.grouping === "merge" ? mergePositions(baseRows) : baseRows;
    return result.sort((a, b) => {
      if (options.sort === "ticker_asc") return a.position.ticker.localeCompare(b.position.ticker);
      const aValue = options.sort === "profit_desc" ? a.position.profit : rowMarketValue(a.position, options.currency);
      const bValue = options.sort === "profit_desc" ? b.position.profit : rowMarketValue(b.position, options.currency);
      if (aValue === null || aValue === undefined) return bValue === null || bValue === undefined ? 0 : 1;
      if (bValue === null || bValue === undefined) return -1;
      return decimalCompare(bValue, aValue) ?? 0;
    });
  }, [baseRows, options.grouping, options.sort, options.currency]);

  const currency = options.currency;
  const totalMarketValue = selectedAccount
    ? visibleAccounts.reduce<string | null>((total, account) => {
        const value = accountValue(account, currency);
        return total === null || value === null ? null : decimalAdd(total, value);
      }, "0")
    : portfolio?.total_market_value ?? null;
  const totalProfit = selectedAccount
    ? visibleAccounts.reduce<string | null>((total, account) => {
        const value = accountProfit(account);
        return total === null || value === null ? null : decimalAdd(total, value);
      }, "0")
    : portfolio?.total_profit ?? null;
  const totalProfitRate = selectedAccount
    ? (() => {
        return decimalDivide(totalProfit, totalMarketValue);
      })()
    : portfolio?.total_profit_rate ?? null;
  const periodProfit = selectedAccount
    ? rows.reduce<string | null>((total, row) => total === null || row.position.period_profit === null ? null : decimalAdd(total, row.position.period_profit), "0")
    : portfolio?.period_profit ?? null;
  const periodProfitRate = selectedAccount ? null : portfolio?.period_profit_rate ?? null;
  const visibleBaselines = (portfolio?.period_baselines ?? []).filter((baseline) => !selectedAccount || baseline.account === selectedAccount);
  const periodBaselineNotice = baselineNotice(visibleBaselines);
  const totalCurrency = metricCurrency(portfolio, rows, currency);

  const update = <K extends keyof HoldingDisplayOptions>(key: K, value: HoldingDisplayOptions[K]) => {
    onOptionsChange({ ...options, [key]: value });
  };

  return <section className="investment-section holdings-section" aria-labelledby="holdings-title">
    <div className="section-head section-head-row">
      <div><h2 id="holdings-title">当前持仓</h2></div>
      <button className="refresh-button" type="button" aria-label="刷新持仓" aria-busy={refreshing} onClick={onRetry}>
        <span className="refresh-ring" aria-hidden="true">↻</span><span>刷新</span>
      </button>
    </div>
    <details className="display-options" open>
      <summary><strong>显示</strong><span className="display-toggle">收起</span></summary>
      <div className="option-grid">
        <label>账户<select aria-label="账户" value={options.accountId} onChange={(event) => update("accountId", event.target.value)}><option value="">全部账户</option>{accounts.map((account) => <option key={account.id} value={String(account.id)}>{account.name}</option>)}</select></label>
        <label>排序<select aria-label="排序" value={options.sort} onChange={(event) => update("sort", event.target.value as HoldingSort)}><option value="market_value_desc">市值倒序</option><option value="profit_desc">浮盈亏倒序</option><option value="ticker_asc">标的名称</option></select></label>
        <label>同一标的<select aria-label="同一标的" value={options.grouping} onChange={(event) => update("grouping", event.target.value as HoldingGrouping)}><option value="split">分开显示</option><option value="merge">合并显示</option></select></label>
        <label>币种<input aria-label="币种" inputMode="text" autoComplete="off" placeholder="原币种" value={currencyDraft} aria-invalid={Boolean(currencyDraft && !/^[A-Za-z]{3}$/.test(currencyDraft))} onChange={(event) => { const value = event.target.value.toUpperCase(); setCurrencyDraft(value); if (!value || /^[A-Z]{3}$/.test(value)) update("currency", value); }} onBlur={() => { if (currencyDraft && !/^[A-Z]{3}$/.test(currencyDraft)) setCurrencyDraft(options.currency); }} /></label>
        <label>时间范围<select aria-label="时间范围" value={options.period} onChange={(event) => update("period", event.target.value as PortfolioPeriod)}>{Object.entries(periodLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      </div>
    </details>
    <div className="overview">
      <article className="metric"><span className="metric-label">总浮盈亏</span><strong className={`metric-value ${decimalSign(totalProfit) === null ? "" : decimalSign(totalProfit)! < 0 ? "negative" : "positive"}`}>{signedValue(totalProfit, totalCurrency)}</strong><small>{percentage(totalProfitRate)}</small></article>
      <article className="metric metric-period"><span className="metric-label">{periodLabels[options.period]}浮盈亏</span><strong className={`metric-value ${decimalSign(periodProfit) === null ? "" : decimalSign(periodProfit)! < 0 ? "negative" : "positive"}`}>{signedValue(periodProfit, totalCurrency)}</strong><small>{percentage(periodProfitRate)}</small>{periodBaselineNotice ? <p className="metric-note">{periodBaselineNotice}</p> : null}</article>
      <article className="metric"><span className="metric-label">当前总市值</span><strong className="metric-value mono">{totalMarketValue === null ? "—" : `${displayValue(totalMarketValue)} ${totalCurrency}`}</strong></article>
    </div>
    {loading ? <div className="holdings-table-wrap holding-loading"><span className="skeleton-cell" /><span className="skeleton-cell" /><span className="skeleton-cell" /></div> : null}
    {error && !portfolio ? <div className="status-view status-error" role="alert"><p>{error}</p><button type="button" onClick={onRetry}>重试</button></div> : null}
    {error && portfolio ? <div className="status-view status-error holdings-refresh-error" role="alert"><p>{error}</p><button type="button" onClick={onRetry}>重试</button></div> : null}
    {!loading && !error && portfolio && rows.length === 0 ? <div className="status-view" role="status"><p>当前没有持仓。</p></div> : null}
    {!loading && portfolio && rows.length ? <div className="holdings-table-wrap"><table className="holdings-table" aria-label="当前持仓"><caption className="sr-only">当前持仓</caption><thead><tr><th scope="col">标的 / 账户</th><th scope="col">当前单价</th><th scope="col">数量</th><th scope="col">当前市值</th><th scope="col">仓位</th><th scope="col">浮盈亏</th><th scope="col">浮盈亏率</th><th scope="col">{periodLabels[options.period]}盈亏</th><th scope="col">{periodLabels[options.period]}盈亏率</th></tr></thead><tbody>{rows.map(({ accountName, position, merged }) => {
      const value = rowMarketValue(position, currency); const rate = rateFromProfit(position.profit, position.total_cost); const period = position.period_profit;
      const weight = decimalDivide(value, totalMarketValue);
      const rowCurrency = currency || position.quote_currency || position.cost_currency;
      const currentPrice = currency && position.current_price !== null
        ? decimalMultiply(position.current_price, position.fx_rate)
        : position.current_price;
      const priceCurrency = currency || position.quote_currency || "";
      const displayedProfit = currency && position.profit !== null
        ? decimalMultiply(position.profit, position.fx_rate)
        : position.profit;
      const displayedPeriod = currency && period !== null
        ? decimalMultiply(period, position.fx_rate)
        : period;
      const pnlCurrency = currency || position.cost_currency;
      const positionBaselineLabel = baselineLabel(position.period_baselines ?? []);
      return <tr key={`${accountName}:${position.ticker}:${position.cost_currency}`}><td className="holding-symbol" data-label="标的 / 账户"><strong>{position.ticker}</strong><small>{merged ? "多个账户" : accountName} · {position.quote_currency ?? position.cost_currency}</small></td><td className="mono holding-price" data-label="当前单价"><span>{currentPrice === null ? "—" : `${displayValue(currentPrice)} ${priceCurrency}`}</span>{!position.is_cash ? <small>{formatQuoteTime(position.quote_observed_at)} · {quoteSessionLabel(position.quote_session)}</small> : null}</td><td className="mono" data-label="数量">{displayValue(position.shares)}</td><td className="mono holding-market" data-label="当前市值">{value === null || value === undefined ? "—" : `${displayValue(value)} ${rowCurrency}`}</td><td className="mono holding-position" data-label="仓位">{percentage(weight)}</td><td className={`mono holding-pnl ${decimalSign(displayedProfit) !== null && decimalSign(displayedProfit)! < 0 ? "negative" : "positive"}`} data-label="浮盈亏">{signedValue(displayedProfit, pnlCurrency)}</td><td className={`mono holding-pnl-rate ${decimalSign(rate) !== null && decimalSign(rate)! < 0 ? "negative" : "positive"}`} data-label="浮盈亏率">{percentage(rate)}</td><td className={`mono holding-period ${decimalSign(displayedPeriod) !== null && decimalSign(displayedPeriod)! < 0 ? "negative" : "positive"}`} data-label={`${periodLabels[options.period]}盈亏`}><span>{signedValue(displayedPeriod, rowCurrency)}</span>{positionBaselineLabel ? <small className="holding-period-note">{positionBaselineLabel}</small> : null}</td><td className={`mono holding-period-rate ${decimalSign(position.period_profit_rate) !== null && decimalSign(position.period_profit_rate)! < 0 ? "negative" : "positive"}`} data-label={`${periodLabels[options.period]}盈亏率`}>{percentage(position.period_profit_rate)}</td></tr>;
    })}</tbody></table></div> : null}
  </section>;
}

import type { InvestmentEvent } from "./api/types";

export const recordTypeLabels: Record<string, string> = {
  funding: "转入资金",
  trade: "买卖",
  income: "收入",
  expense: "支出",
  reversal: "撤销",
  subscription: "认购",
  adjustment: "调整",
  snapshot: "记录",
};

function isCashAsset(ticker: string | null, currency: string): boolean {
  return !ticker || ticker.toUpperCase() === currency.toUpperCase();
}

export function eventTypeLabel(event: InvestmentEvent): string {
  if (event.record_type === "trade") {
    const fromCash = isCashAsset(event.from_asset.ticker, event.currency);
    const toCash = isCashAsset(event.to_asset.ticker, event.currency);
    if (fromCash && !toCash) return "买入";
    if (!fromCash && toCash) return "卖出";
  }
  return recordTypeLabels[event.record_type] ?? "投资记录";
}

const sourceFieldLabels: Record<string, string> = {
  action: "操作",
  side: "方向",
  ticker: "标的",
  symbol: "标的",
  quantity: "数量",
  shares: "数量",
  amount: "金额",
  price: "价格",
  currency: "币种",
  trade_date: "交易日期",
  settlement_date: "结算日期",
  note: "备注",
};

export function sourceFieldLabel(key: string): string | null {
  return sourceFieldLabels[key] ?? null;
}

export function sourceFieldValue(key: string, value: string | number | boolean | string[]): string {
  if (Array.isArray(value)) return value.join("；");
  if ((key === "action" || key === "side") && typeof value === "string") {
    const normalized = value.toUpperCase();
    if (normalized === "BUY" || normalized === "BID") return "买入";
    if (normalized === "SELL" || normalized === "ASK") return "卖出";
  }
  return String(value);
}

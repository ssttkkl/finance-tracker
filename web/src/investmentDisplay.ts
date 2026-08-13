import type { InvestmentAsset, InvestmentEvent } from "./api/types";

type Direction = "outflow" | "inflow" | "neutral";
export type InvestmentAssetLine = { label: "流出" | "流入" | "余额" | "持有"; value: string; direction: Direction };

function decimalParts(value: string | null): { negative: boolean; whole: string; fraction: string } | null {
  if (value === null || !/^[+-]?\d+(?:\.\d+)?$/.test(value)) return null;
  const negative = value.startsWith("-");
  const unsigned = value.replace(/^[+-]/, "");
  const [whole, fraction = ""] = unsigned.split(".");
  return { negative, whole, fraction };
}

function isZero(value: string | null): boolean {
  const parts = decimalParts(value);
  return parts ? BigInt(`${parts.whole}${parts.fraction}` || "0") === 0n : false;
}

function unsigned(value: string): string {
  return value.replace(/^[+-]/, "");
}

export function formatInvestmentAmount(value: string | null, maxFraction = 8): string {
  const parts = decimalParts(value);
  if (!parts) return "—";
  let whole = parts.whole;
  let fraction = parts.fraction;
  if (fraction.length > maxFraction) {
    const kept = fraction.slice(0, maxFraction);
    const discarded = fraction[maxFraction];
    let digits = BigInt(`${whole}${kept}` || "0");
    if (discarded >= "5") digits += 1n;
    const scale = 10n ** BigInt(maxFraction);
    whole = (digits / scale).toString();
    fraction = (digits % scale).toString().padStart(maxFraction, "0");
  }
  fraction = fraction.replace(/0+$/, "");
  const groupedWhole = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const result = fraction ? `${groupedWhole}.${fraction}` : groupedWhole;
  return parts.negative && result !== "0" ? `-${result}` : result;
}

function hasNonZeroAmount(asset: InvestmentAsset): boolean {
  return asset.amount !== null && !isZero(asset.amount);
}

function hasUnknownAmount(asset: InvestmentAsset): boolean {
  return asset.amount === null && Boolean(asset.ticker);
}

function hasAssetValue(asset: InvestmentAsset): boolean {
  return hasNonZeroAmount(asset) || hasUnknownAmount(asset);
}

function snapshotAsset(event: InvestmentEvent): InvestmentAsset {
  return event.to_asset.amount !== null || event.to_asset.ticker ? event.to_asset : event.from_asset;
}

export function investmentAssetLines(event: InvestmentEvent): InvestmentAssetLine[] {
  if (event.record_type === "snapshot") {
    const asset = snapshotAsset(event);
    return [{ label: event.record_subtype === "cash" ? "余额" : "持有", value: formatAssetValue(asset, event.currency, "neutral"), direction: "neutral" }];
  }
  const lines: InvestmentAssetLine[] = [];
  if (hasAssetValue(event.from_asset)) lines.push({ label: "流出", value: formatAssetValue(event.from_asset, event.currency, "outflow"), direction: "outflow" });
  if (hasAssetValue(event.to_asset)) lines.push({ label: "流入", value: formatAssetValue(event.to_asset, event.currency, "inflow"), direction: "inflow" });
  return lines;
}

export function formatAssetValue(asset: InvestmentAsset, currency: string, direction: Direction): string {
  const ticker = (asset.ticker ?? currency).toUpperCase();
  if (asset.amount === null) return `— ${ticker}`;
  const amount = formatInvestmentAmount(unsigned(asset.amount));
  const sign = amount === "0" || direction === "neutral" ? "" : direction === "outflow" ? "-" : "+";
  return `${sign}${amount} ${ticker}`;
}

export function formatCommission(amount: string | null, asset: string | null, currency: string): string {
  if (amount === null || isZero(amount)) return "—";
  return `${formatInvestmentAmount(unsigned(amount))} ${(asset ?? currency).toUpperCase()}`;
}

export function signedRelatedAmount(amount: string, currency: string, direction: string): string {
  const normalizedDirection = direction === "investment_to_cash" ? "inflow" : "outflow";
  return formatAssetValue({ ticker: currency, amount }, currency, normalizedDirection);
}

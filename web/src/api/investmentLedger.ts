import type { Account, InvestmentEvidence, InvestmentFilters, InvestmentPage, Portfolio, PortfolioPeriod } from "./types";

function apiOrigin(): string {
  const origin = import.meta.env.VITE_FT_API_ORIGIN;
  if (!origin || !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin)) {
    throw new Error("api_origin_invalid");
  }
  return origin;
}

async function request<T>(path: string, signal?: AbortSignal, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiOrigin()}${path}`, { ...init, signal });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: { code?: unknown }; code?: unknown } | null;
    const code = payload?.error?.code ?? payload?.code;
    throw new Error(typeof code === "string" ? code : "api_request_failed");
  }
  return response.json() as Promise<T>;
}

function paramsFor(filters: InvestmentFilters, cursor?: string | null, displayCurrency?: string): string {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
  params.set("timezone", Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
  if (cursor) params.set("cursor", cursor);
  if (displayCurrency) params.set("display_currency", displayCurrency);
  return params.toString();
}

export function fetchInvestmentPage(filters: InvestmentFilters, cursor?: string | null, signal?: AbortSignal): Promise<InvestmentPage> {
  const query = paramsFor(filters, cursor);
  return request<InvestmentPage>(`/api/v1/investment-events?${query}`, signal);
}

export function fetchInvestmentAccounts(signal?: AbortSignal): Promise<Account[]> {
  return request<{ items: Account[] }>("/api/v1/accounts?view=investment", signal).then((payload) => payload.items);
}

export function fetchInvestmentEvidence(eventId: string, signal?: AbortSignal): Promise<InvestmentEvidence> {
  return request<InvestmentEvidence>(`/api/v1/evidence/investment-events/${encodeURIComponent(eventId)}`, signal);
}

export function fetchInvestmentPortfolio(displayCurrency?: string, period: PortfolioPeriod = "24h", signal?: AbortSignal, phase: "holdings" | "valuation" = "valuation"): Promise<Portfolio> {
  return request<Portfolio>(`/api/v1/investment-portfolio?${portfolioParams(displayCurrency, period, phase)}`, signal);
}

type PortfolioStreamPayload = { version: number; portfolio?: Portfolio };

function portfolioParams(displayCurrency?: string, period: PortfolioPeriod = "24h", phase?: "holdings" | "valuation") {
  const query = paramsFor({}, null, displayCurrency);
  const params = new URLSearchParams(query);
  params.set("period", period);
  if (phase) params.set("phase", phase);
  return params.toString();
}

export function openInvestmentPortfolioStream(
  displayCurrency: string | undefined,
  period: PortfolioPeriod,
  handlers: { onPortfolio: (portfolio: Portfolio) => void; onRefreshError: () => void },
): EventSource {
  const source = new EventSource(`${apiOrigin()}/api/v1/investment-portfolio/stream?${portfolioParams(displayCurrency, period)}`);
  source.addEventListener("portfolio", (event) => {
    try {
      const payload = JSON.parse((event as MessageEvent<string>).data) as PortfolioStreamPayload;
      if (payload.portfolio) handlers.onPortfolio(payload.portfolio);
    } catch (_error) {
      handlers.onRefreshError();
    }
  });
  source.addEventListener("refresh_error", handlers.onRefreshError);
  return source;
}

export function requestInvestmentPortfolioRefresh(displayCurrency?: string, period: PortfolioPeriod = "24h"): Promise<void> {
  return request<{ accepted: boolean }>(
    `/api/v1/investment-portfolio/refresh?${portfolioParams(displayCurrency, period)}`,
    undefined,
    { method: "POST" },
  ).then(() => undefined);
}

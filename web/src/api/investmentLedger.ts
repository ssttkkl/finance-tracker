import type { Account, InvestmentEvidence, InvestmentFilters, InvestmentPage, Portfolio, PortfolioPeriod } from "@finance-tracker/contracts";
import { apiClient, apiOrigin, authHeaders } from "./access";

export type { Account, InvestmentEvidence, InvestmentFilters, InvestmentPage, Portfolio, PortfolioPeriod } from "@finance-tracker/contracts";

export function fetchInvestmentPage(filters: InvestmentFilters, cursor?: string | null, signal?: AbortSignal): Promise<InvestmentPage> {
  return apiClient().fetchInvestmentPage(filters, cursor, signal);
}

export function fetchInvestmentAccounts(signal?: AbortSignal): Promise<Account[]> {
  return apiClient().fetchInvestmentAccounts(signal);
}

export function fetchInvestmentEvidence(eventId: string, signal?: AbortSignal): Promise<InvestmentEvidence> {
  return apiClient().fetchInvestmentEvidence(eventId, signal);
}

export function fetchInvestmentPortfolio(displayCurrency?: string, period: PortfolioPeriod = "24h", signal?: AbortSignal, phase: "holdings" | "valuation" = "valuation"): Promise<Portfolio> {
  return apiClient().fetchInvestmentPortfolio(displayCurrency, period, signal, phase);
}

type PortfolioStreamPayload = { version: number; portfolio?: Portfolio };
export type PortfolioStream = { close: () => void };

function portfolioParams(displayCurrency?: string, period: PortfolioPeriod = "24h", phase?: "holdings" | "valuation") {
  const params = new URLSearchParams();
  params.set("timezone", Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
  params.set("period", period);
  if (displayCurrency) params.set("display_currency", displayCurrency);
  if (phase) params.set("phase", phase);
  return params.toString();
}

export function openInvestmentPortfolioStream(
  displayCurrency: string | undefined,
  period: PortfolioPeriod,
  handlers: { onPortfolio: (portfolio: Portfolio) => void; onRefreshError: () => void },
): PortfolioStream {
  const controller = new AbortController();
  let closed = false;
  const connection: PortfolioStream = {
    close() {
      closed = true;
      controller.abort();
    },
  };

  const consume = async (response: Response) => {
    if (!response.ok || !response.body) {
      if (!closed) handlers.onRefreshError();
      return;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    const handleFrame = (frame: string) => {
      let event = "message";
      const data: string[] = [];
      for (const line of frame.split(/\r?\n/)) {
        if (line.startsWith("event:")) event = line.slice(6).trim();
        if (line.startsWith("data:")) data.push(line.slice(5).trimStart());
      }
      if (event === "refresh_error") {
        handlers.onRefreshError();
        return;
      }
      if (event !== "portfolio" || data.length === 0) return;
      try {
        const payload = JSON.parse(data.join("\n")) as PortfolioStreamPayload;
        if (payload.portfolio) handlers.onPortfolio(payload.portfolio);
      } catch (_error) {
        handlers.onRefreshError();
      }
    };
    while (!closed) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const frames = buffer.split(/\r?\n\r?\n/);
      buffer = frames.pop() ?? "";
      frames.forEach(handleFrame);
    }
    if (!closed && buffer.trim()) handleFrame(buffer);
  };

  void fetch(`${apiOrigin()}/api/v1/investment-portfolio/stream?${portfolioParams(displayCurrency, period)}`, {
    headers: authHeaders({ Accept: "text/event-stream" }),
    signal: controller.signal,
  }).then(consume).catch((error: unknown) => {
    if (!closed && !(error instanceof DOMException && error.name === "AbortError")) handlers.onRefreshError();
  });
  return connection;
}

export function requestInvestmentPortfolioRefresh(displayCurrency?: string, period: PortfolioPeriod = "24h"): Promise<void> {
  return apiClient().request<{ accepted: boolean }>(`/api/v1/investment-portfolio/refresh?${portfolioParams(displayCurrency, period)}`, { method: "POST" }).then(() => undefined);
}

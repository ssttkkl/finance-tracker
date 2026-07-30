import type { Account, CashFilters, CashPage, Evidence } from "./types";

function apiOrigin(): string {
  const origin = import.meta.env.VITE_FT_API_ORIGIN;
  if (!origin || !/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(origin)) {
    throw new Error("api_origin_invalid");
  }
  return origin;
}

async function request<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${apiOrigin()}${path}`, { signal });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: { code?: unknown }; code?: unknown } | null;
    const code = payload?.error?.code ?? payload?.code;
    throw new Error(typeof code === "string" ? code : "api_request_failed");
  }
  return response.json() as Promise<T>;
}

export function fetchCashPage(filters: CashFilters, cursor?: string | null, signal?: AbortSignal): Promise<CashPage> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
  if (cursor) params.set("cursor", cursor);
  const query = params.toString();
  return request<CashPage>(`/api/v1/cash-projections${query ? `?${query}` : ""}`, signal);
}

export async function fetchCashAccounts(signal?: AbortSignal): Promise<Account[]> {
  const payload = await request<{ items: Account[] }>("/api/v1/accounts?view=cash", signal);
  return payload.items;
}

export function fetchEvidence(id: string, signal?: AbortSignal): Promise<Evidence> {
  return request<Evidence>(`/api/v1/evidence/cash-projections/${encodeURIComponent(id)}`, signal);
}

import type { Account, CashCategory, CashCategoryDirectory, CashFilters, CashPage, CashRecordDetail, CashRecordPage, Evidence, ImportCommitResult, ImportDetection, ImportMappingDecision, ImportPreview, ImportScan, LedgerOptions } from "./types";
import { authHeaders } from "./access";

function apiOrigin(): string {
  const origin = import.meta.env.VITE_FT_API_ORIGIN;
  if (!origin) {
    throw new Error("api_origin_invalid");
  }
  let parsed: URL;
  try { parsed = new URL(origin); } catch { throw new Error("api_origin_invalid"); }
  const localHttp = parsed.protocol === "http:" && ["127.0.0.1", "localhost"].includes(parsed.hostname) && parsed.port !== "";
  const hostedHttps = parsed.protocol === "https:" && parsed.hostname !== "";
  if ((!localHttp && !hostedHttps) || parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new Error("api_origin_invalid");
  }
  return origin.replace(/\/$/, "");
}

async function request<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${apiOrigin()}${path}`, { headers: authHeaders(), signal });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: { code?: unknown }; code?: unknown } | null;
    const code = payload?.error?.code ?? payload?.code;
    throw new Error(typeof code === "string" ? code : "api_request_failed");
  }
  return response.json() as Promise<T>;
}

async function write<T>(path: string, method: string, body: unknown, signal?: AbortSignal): Promise<T> {
  const response = await fetch(`${apiOrigin()}${path}`, {
    method,
    headers: authHeaders({ "Content-Type": "application/json" }),
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: { code?: unknown; message?: unknown } } | null;
    const code = payload?.error?.code;
    throw new Error(typeof code === "string" ? code : "api_request_failed");
  }
  return response.json() as Promise<T>;
}

export function fetchCashPage(filters: CashFilters, cursor?: string | null, signal?: AbortSignal): Promise<CashPage> {
  const params = new URLSearchParams();
  Object.entries(filters).forEach(([key, value]) => { if (value) params.set(key, value); });
  params.set("timezone", Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC");
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

export function fetchLedgerOptions(signal?: AbortSignal): Promise<LedgerOptions> {
  return request<LedgerOptions>("/api/v1/cash-ledger/options", signal);
}

export function fetchCashCategories(signal?: AbortSignal): Promise<CashCategoryDirectory> {
  return request<CashCategoryDirectory>("/api/v1/cash-categories", signal);
}

export function createCashCategory(body: { name: string; parent_id?: string | null; description?: string; expected_revision?: number }, signal?: AbortSignal): Promise<CashCategory> {
  return write<CashCategory>("/api/v1/cash-categories", "POST", body, signal);
}

export function updateCashCategory(id: string, body: { name?: string; parent_id?: string | null; description?: string; expected_revision?: number }, signal?: AbortSignal): Promise<CashCategory> {
  return write<CashCategory>(`/api/v1/cash-categories/${encodeURIComponent(id)}`, "PATCH", body, signal);
}

export function reorderCashCategory(id: string, direction: "before" | "after", expected_revision: number, signal?: AbortSignal): Promise<CashCategory> {
  return write<CashCategory>(`/api/v1/cash-categories/${encodeURIComponent(id)}/reorder`, "POST", { direction, expected_revision }, signal);
}

export function fetchCashCategoryDeletionImpact(id: string, signal?: AbortSignal): Promise<{ category_id: string; revision: number; category_revision: number; child_count: number; direct_usage_count: number }> {
  return request(`/api/v1/cash-categories/${encodeURIComponent(id)}/deletion-impact`, signal);
}

export function deleteCashCategory(id: string, body: { expected_revision: number; expected_category_revision: number; expected_usage_count: number; confirmed: boolean }, signal?: AbortSignal): Promise<{ category_id: string; cleared_transaction_count: number; revision: number }> {
  return write(`/api/v1/cash-categories/${encodeURIComponent(id)}`, "DELETE", body, signal);
}

export function classifyCashProjection(id: string, projection_version: number, category_id: string | null, signal?: AbortSignal): Promise<{ projection_version: number; projection_count: number; updated_transaction_count: number; category_id: string | null }> {
  return write("/api/v1/cash-projections/categories", "PUT", { projection_ids: [id], projection_version, category_id }, signal);
}

export function classifyCashProjections(projection_ids: string[], projection_version: number, category_id: string | null, signal?: AbortSignal): Promise<{ projection_version: number; projection_count: number; updated_transaction_count: number; category_id: string | null }> {
  return write("/api/v1/cash-projections/categories", "PUT", { projection_ids, projection_version, category_id }, signal);
}

export function fetchCashRecord(id: string, signal?: AbortSignal): Promise<CashRecordDetail> {
  return request<CashRecordDetail>(`/api/v1/cash-records/${encodeURIComponent(id)}`, signal);
}

export function fetchCashRecords(
  values: { query?: string; excludeId?: string; dateFrom?: string; dateTo?: string; timezone?: string; cursor?: string | null; limit?: number } = {},
  signal?: AbortSignal,
): Promise<CashRecordPage> {
  const params = new URLSearchParams();
  if (values.query) params.set("query", values.query);
  if (values.excludeId) params.set("exclude_id", values.excludeId);
  if (values.dateFrom) params.set("date_from", values.dateFrom);
  if (values.dateTo) params.set("date_to", values.dateTo);
  if (values.timezone) params.set("timezone", values.timezone);
  if (values.cursor) params.set("cursor", values.cursor);
  params.set("limit", String(values.limit ?? 20));
  return request<CashRecordPage>(`/api/v1/cash-records?${params.toString()}`, signal);
}

export function createCashRecord(body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return write<CashRecordDetail>("/api/v1/cash-records", "POST", body, signal);
}

export function updateCashRecord(id: string, body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return write<CashRecordDetail>(`/api/v1/cash-records/${encodeURIComponent(id)}`, "PUT", body, signal);
}

export function deleteCashRecord(id: string, mode: "delete_all" | "delete_current_dissolve", signal?: AbortSignal): Promise<{ deleted: boolean; related_count: number; deleted_fact_ids: string[] }> {
  return write<{ deleted: boolean; related_count: number; deleted_fact_ids: string[] }>(`/api/v1/cash-records/${encodeURIComponent(id)}`, "DELETE", { mode }, signal);
}

export function createCashRelation(body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return write<CashRecordDetail>("/api/v1/cash-relations", "POST", body, signal);
}

export function updateCashRelation(id: string, body: Record<string, unknown>, signal?: AbortSignal): Promise<CashRecordDetail> {
  return write<CashRecordDetail>(`/api/v1/cash-relations/${encodeURIComponent(id)}`, "PUT", body, signal);
}

export function cancelCashRelation(id: string, signal?: AbortSignal): Promise<unknown> {
  return write<unknown>(`/api/v1/cash-relations/${encodeURIComponent(id)}`, "DELETE", {}, signal);
}

export function dissolveCashRelations(factId: string, signal?: AbortSignal): Promise<CashRecordDetail> {
  return write<CashRecordDetail>("/api/v1/cash-relations/dissolve", "POST", { fact_id: factId }, signal);
}

const importChannelLabels: Record<string, string> = {
  alipay: "支付宝", wechat: "微信", icbc: "工行信用卡", "icbc-debit": "工行借记卡",
  "ccb-debit": "建行借记卡", "icbc-asia": "工银亚洲",
};
export { importChannelLabels };

async function importRequest<T>(path: string, file: File, values: { source?: string; currency?: string; password?: string; previewDigest?: string; previewChannel?: string; relations?: string; mapping?: ImportMappingDecision[] } = {}): Promise<T> {
  const params = new URLSearchParams({ source: values.source ?? "", filename: file.name });
  if (values.currency) params.set("currency", values.currency);
  if (values.previewDigest) params.set("preview_digest", values.previewDigest);
  if (values.previewChannel) params.set("preview_channel", values.previewChannel);
  if (values.relations) params.set("relations", values.relations);
  if (values.mapping) params.set("mapping", JSON.stringify(values.mapping));
  const headers = authHeaders({ "Content-Type": "application/octet-stream" });
  if (values.password) headers.set("X-FT-Statement-Password", values.password);
  const response = await fetch(`${apiOrigin()}${path}?${params.toString()}`, {
    method: "POST", headers, body: file,
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => null) as { error?: { code?: unknown } } | null;
    const code = payload?.error?.code;
    throw new Error(typeof code === "string" ? code : "api_request_failed");
  }
  return response.json() as Promise<T>;
}

export function detectCashImport(file: File, currency?: string, password?: string): Promise<ImportDetection> {
  return importRequest<ImportDetection>("/api/v1/cash-import/detect", file, { currency, password });
}

export function scanCashImport(file: File, currency?: string, password?: string): Promise<ImportScan> {
  return importRequest<ImportScan>("/api/v1/cash-import/scan", file, { currency, password });
}

export function previewCashImport(file: File, source = "", currency?: string, password?: string, mapping?: ImportMappingDecision[]): Promise<ImportPreview> {
  return importRequest<ImportPreview>("/api/v1/cash-import/preview", file, { source, currency, password, mapping });
}

export function commitCashImport(
  file: File,
  source = "",
  currency?: string,
  options: { password?: string; previewDigest?: string; previewChannel?: string; relations?: Record<string, unknown>[]; mapping?: ImportMappingDecision[] } = {},
): Promise<ImportCommitResult> {
  return importRequest<ImportCommitResult>("/api/v1/cash-import/commit", file, {
    source,
    currency,
    password: options.password,
    previewDigest: options.previewDigest,
    previewChannel: options.previewChannel,
    relations: options.relations ? JSON.stringify(options.relations) : undefined,
    mapping: options.mapping,
  });
}

import type {
  Account,
  ApiErrorCode,
  ApiErrorPayload,
  AuthResponse,
  CashCategory,
  CashCategoryDirectory,
  CashFilters,
  CashPage,
  CashProjectionDeleteImpact,
  CashProjectionDeleteResult,
  CashRecordDetail,
  CashRecordPage,
  Evidence,
  FileSource,
  ImportCommitResult,
  ImportDetection,
  ImportMappingDecision,
  ImportPreview,
  ImportScan,
  InvitationPreview,
  LedgerOptions,
  Member,
  Portfolio,
  PortfolioPeriod,
  Role,
  Session,
  TokenStore,
  InvestmentEvidence,
  InvestmentFilters,
  InvestmentPage,
} from "@finance-tracker/contracts";

export type FetchRequestInit = {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
  signal?: unknown;
};

export type FetchResponse = {
  ok: boolean;
  status: number;
  json(): Promise<unknown>;
};

export type FetchLike = (url: string, init?: FetchRequestInit) => Promise<FetchResponse>;

export type ApiClientOptions = {
  baseUrl: string | (() => string);
  fetch: FetchLike;
  tokenStore: TokenStore;
  timezone?: string;
  /** Native development builds may connect to a LAN HTTP origin; production remains HTTPS-only. */
  allowInsecureHttp?: boolean;
};

export class ApiError extends Error {
  readonly code: ApiErrorCode;
  readonly status: number;
  importToken?: string;

  constructor(code: ApiErrorCode, status: number) {
    super(code);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

type RequestValues = FetchRequestInit & { headers?: Record<string, string> };
type ImportRequestValues = {
  source?: string;
  currency?: string;
  password?: string;
  previewDigest?: string;
  previewRelationDigest?: string;
  previewChannel?: string;
  relations?: Record<string, unknown>[];
  mapping?: ImportMappingDecision[];
  importToken?: string;
  idempotencyKey?: string;
};

type ImportRequestError = ApiError & { importToken?: string };

export type ApiClient = {
  request<T>(path: string, init?: RequestValues): Promise<T>;
  session(): Promise<Session>;
  login(email: string, password: string): Promise<Session>;
  register(email: string, password: string): Promise<Session>;
  logout(): Promise<{ ok: boolean }>;
  selectWorkspace(id: string): Promise<Session>;
  createWorkspace(name: string): Promise<Session>;
  invitationPreview(token: string): Promise<InvitationPreview>;
  acceptInvitation(token: string): Promise<Session>;
  members(): Promise<{ workspace: { id: string; name: string }; members: Member[] }>;
  workspaceDetails(): Promise<{ workspace: { id: string; name: string }; members: Member[] }>;
  updateWorkspace(name: string): Promise<Session>;
  deleteWorkspace(name: string): Promise<Session>;
  updateMember(id: string, role: Role): Promise<unknown>;
  removeMember(id: string): Promise<{ ok: boolean }>;
  invite(role: "editor" | "viewer"): Promise<{ token: string }>;
  fetchCashPage(filters: CashFilters, cursor?: string | null, signal?: unknown): Promise<CashPage>;
  fetchCashAccounts(signal?: unknown): Promise<Account[]>;
  fetchEvidence(id: string, signal?: unknown): Promise<Evidence>;
  fetchLedgerOptions(signal?: unknown): Promise<LedgerOptions>;
  fetchCashCategories(signal?: unknown): Promise<CashCategoryDirectory>;
  createCashCategory(body: Record<string, unknown>, signal?: unknown): Promise<CashCategory>;
  updateCashCategory(id: string, body: Record<string, unknown>, signal?: unknown): Promise<CashCategory>;
  reorderCashCategory(id: string, direction: "before" | "after", expectedRevision: number, signal?: unknown): Promise<CashCategory>;
  fetchCashCategoryDeletionImpact(id: string, signal?: unknown): Promise<Record<string, unknown>>;
  deleteCashCategory(id: string, body: Record<string, unknown>, signal?: unknown): Promise<Record<string, unknown>>;
  classifyCashProjections(projectionIds: string[], projectionVersion: number, categoryId: string | null, signal?: unknown): Promise<Record<string, unknown>>;
  fetchCashProjectionDeleteImpact(projectionIds: string[], projectionVersion: number, signal?: unknown): Promise<CashProjectionDeleteImpact>;
  deleteCashProjections(projectionIds: string[], projectionVersion: number, signal?: unknown): Promise<CashProjectionDeleteResult>;
  fetchCashRecord(id: string, signal?: unknown): Promise<CashRecordDetail>;
  fetchCashRecords(values?: Record<string, string | number | null | undefined>, signal?: unknown): Promise<CashRecordPage>;
  createCashRecord(body: Record<string, unknown>, signal?: unknown): Promise<CashRecordDetail>;
  updateCashRecord(id: string, body: Record<string, unknown>, signal?: unknown): Promise<CashRecordDetail>;
  deleteCashRecord(id: string, mode: "delete_all" | "delete_current_dissolve", signal?: unknown): Promise<{ deleted: boolean; related_count: number; deleted_fact_ids: string[] }>;
  createCashRelation(body: Record<string, unknown>, signal?: unknown): Promise<CashRecordDetail>;
  updateCashRelation(id: string, body: Record<string, unknown>, signal?: unknown): Promise<CashRecordDetail>;
  cancelCashRelation(id: string, signal?: unknown): Promise<unknown>;
  dissolveCashRelations(factId: string, signal?: unknown): Promise<CashRecordDetail>;
  detectCashImport(file: FileSource, currency?: string, password?: string): Promise<ImportDetection>;
  scanCashImport(file: FileSource, currency?: string, password?: string, importToken?: string): Promise<ImportScan>;
  previewCashImport(file: FileSource | undefined, source?: string, currency?: string, password?: string, mapping?: ImportMappingDecision[], importToken?: string): Promise<ImportPreview>;
  commitCashImport(file: FileSource | undefined, source?: string, currency?: string, options?: ImportRequestValues): Promise<ImportCommitResult>;
  fetchInvestmentPage(filters: InvestmentFilters, cursor?: string | null, signal?: unknown): Promise<InvestmentPage>;
  fetchInvestmentAccounts(signal?: unknown): Promise<Account[]>;
  fetchInvestmentEvidence(eventId: string, signal?: unknown): Promise<InvestmentEvidence>;
  fetchInvestmentPortfolio(displayCurrency?: string, period?: PortfolioPeriod, signal?: unknown, phase?: "holdings" | "valuation"): Promise<Portfolio>;
};

type UrlLike = {
  protocol: string;
  hostname: string;
  port: string;
  username: string;
  password: string;
  pathname: string;
  search: string;
  hash: string;
};
type UrlConstructor = new (value: string) => UrlLike;

function urlConstructor(): UrlConstructor | null {
  return (globalThis as unknown as { URL?: UrlConstructor }).URL ?? null;
}

function normalizeBaseUrl(value: string, allowInsecureHttp = false): string {
  const Constructor = urlConstructor();
  if (!Constructor) throw new ApiError("api_origin_invalid", 0);
  let parsed: UrlLike;
  try {
    parsed = new Constructor(value);
  } catch {
    throw new ApiError("api_origin_invalid", 0);
  }
  const localHttp = parsed.protocol === "http:" && parsed.hostname !== "" && parsed.port !== "" && (allowInsecureHttp || ["127.0.0.1", "localhost"].includes(parsed.hostname));
  const hostedHttps = parsed.protocol === "https:" && parsed.hostname !== "";
  const hasUnexpectedPath = parsed.pathname !== "/" && parsed.pathname !== "";
  if ((!localHttp && !hostedHttps) || parsed.username || parsed.password || hasUnexpectedPath || parsed.search || parsed.hash) {
    throw new ApiError("api_origin_invalid", 0);
  }
  return value.replace(/\/$/, "");
}

function appendQuery(path: string, values: Record<string, string | number | null | undefined>): string {
  const query = Object.entries(values)
    .filter(([, value]) => value !== undefined && value !== null && value !== "")
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
    .join("&");
  return query ? `${path}${path.includes("?") ? "&" : "?"}${query}` : path;
}

function defaultTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
  } catch {
    return "UTC";
  }
}

function errorCode(payload: unknown): string {
  if (!payload || typeof payload !== "object") return "request_failed";
  const value = payload as ApiErrorPayload;
  const nested = value.error && typeof value.error === "object" ? value.error.code : undefined;
  if (typeof nested === "string") return nested;
  return typeof value.code === "string" ? value.code : "request_failed";
}

function base64FromBytes(bytes: Uint8Array): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  let output = "";
  for (let index = 0; index < bytes.length; index += 3) {
    const first = bytes[index] ?? 0;
    const second = bytes[index + 1];
    const third = bytes[index + 2];
    output += alphabet[first >> 2];
    output += alphabet[((first & 3) << 4) | ((second ?? 0) >> 4)];
    output += second === undefined ? "=" : alphabet[((second & 15) << 2) | ((third ?? 0) >> 6)];
    output += third === undefined ? "=" : alphabet[third & 63];
  }
  return output;
}

function pathWithImportQuery(path: string, values: ImportRequestValues, file: FileSource): string {
  return appendQuery(path, {
    source: values.source ?? "",
    filename: file.name,
    currency: values.currency,
    preview_digest: values.previewDigest,
    preview_relation_digest: values.previewRelationDigest,
    preview_channel: values.previewChannel,
    relations: values.relations ? JSON.stringify(values.relations) : undefined,
    mapping: values.mapping ? JSON.stringify(values.mapping) : undefined,
  });
}

export function createApiClient(options: ApiClientOptions): ApiClient {
  const getBaseUrl = () => normalizeBaseUrl(
    typeof options.baseUrl === "function" ? options.baseUrl() : options.baseUrl,
    options.allowInsecureHttp,
  );
  const timezone = options.timezone ?? defaultTimezone();

  async function request<T>(path: string, init: RequestValues = {}): Promise<T> {
    const baseUrl = getBaseUrl();
    const token = await options.tokenStore.get();
    const headers: Record<string, string> = {
      ...(init.headers ?? {}),
    };
    if (token) headers.Authorization = `Bearer ${token}`;
    const response = await options.fetch(`${baseUrl}${path}`, { ...init, headers });
    if (!response.ok) {
      const payload = await response.json().catch(() => null);
      throw new ApiError(errorCode(payload), response.status);
    }
    if (response.status === 204) return undefined as T;
    return await response.json() as T;
  }

  async function jsonRequest<T>(path: string, body: unknown, init: RequestValues = {}): Promise<T> {
    return request<T>(path, {
      ...init,
      method: init.method ?? "POST",
      headers: { "Content-Type": "application/json", ...(init.headers ?? {}) },
      body: JSON.stringify(body),
    });
  }

  async function authenticate(path: "/api/v1/auth/login" | "/api/v1/auth/register", email: string, password: string): Promise<Session> {
    const value = await jsonRequest<AuthResponse>(path, { email, password });
    await options.tokenStore.set(value.access_token);
    return value;
  }

  async function importFailure(response: FetchResponse): Promise<never> {
    const payload = await response.json().catch(() => null);
    const error = new ApiError(errorCode(payload), response.status) as ImportRequestError;
    if (payload && typeof payload === "object" && typeof (payload as { import_token?: unknown }).import_token === "string") {
      error.importToken = (payload as { import_token: string }).import_token;
    }
    throw error;
  }

  async function importRequest<T>(path: string, file: FileSource | undefined, values: ImportRequestValues = {}): Promise<T> {
    const baseUrl = getBaseUrl();
    const token = await options.tokenStore.get();
    const headers: Record<string, string> = { Authorization: token ? `Bearer ${token}` : "" };
    if (!token) delete headers.Authorization;
    if (values.password) headers["X-FT-Statement-Password"] = values.password;
    if (values.importToken) {
      headers["Content-Type"] = "application/json";
      if (values.idempotencyKey) headers["Idempotency-Key"] = values.idempotencyKey;
      const response = await options.fetch(`${baseUrl}${path}`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          import_token: values.importToken,
          source: values.source ?? "",
          currency: values.currency ?? null,
          preview_digest: values.previewDigest ?? null,
          ...(values.previewRelationDigest ? { preview_relation_digest: values.previewRelationDigest } : {}),
          preview_channel: values.previewChannel ?? null,
          relations: values.relations ?? null,
          mapping: values.mapping ?? null,
        }),
      });
      if (!response.ok) await importFailure(response);
      return await response.json() as T;
    }
    if (!file) throw new ApiError("request_failed", 0);
    const bytes = await file.read();
    if (path.endsWith("/commit")) {
      headers["Content-Type"] = "application/json";
      const response = await options.fetch(`${baseUrl}${path}`, {
        method: "POST",
        headers,
        body: JSON.stringify({
          content_base64: base64FromBytes(bytes),
          source: values.source ?? "",
          currency: values.currency ?? null,
          filename: file.name,
          preview_digest: values.previewDigest ?? null,
          ...(values.previewRelationDigest ? { preview_relation_digest: values.previewRelationDigest } : {}),
          preview_channel: values.previewChannel ?? null,
          relations: values.relations ?? null,
          mapping: values.mapping ?? null,
        }),
      });
      if (!response.ok) await importFailure(response);
      return await response.json() as T;
    }
    headers["Content-Type"] = "application/octet-stream";
    const response = await options.fetch(`${baseUrl}${pathWithImportQuery(path, values, file)}`, {
      method: "POST",
      headers,
      body: bytes,
    });
    if (!response.ok) await importFailure(response);
    return await response.json() as T;
  }

  return {
    request,
    session: () => request<Session>("/api/v1/auth/session"),
    login: (email, password) => authenticate("/api/v1/auth/login", email, password),
    register: (email, password) => authenticate("/api/v1/auth/register", email, password),
    async logout() {
      try {
        return await jsonRequest<{ ok: boolean }>("/api/v1/auth/logout", null);
      } finally {
        await options.tokenStore.clear();
      }
    },
    selectWorkspace: (id) => jsonRequest<Session>(`/api/v1/auth/workspaces/${encodeURIComponent(id)}/select`, null),
    createWorkspace: (name) => jsonRequest<Session>("/api/v1/auth/workspaces", { name }),
    invitationPreview: (token) => request<InvitationPreview>(`/api/v1/auth/invitations/${encodeURIComponent(token)}`),
    acceptInvitation: (token) => jsonRequest<Session>(`/api/v1/auth/invitations/${encodeURIComponent(token)}/accept`, null),
    members: () => request("/api/v1/auth/members"),
    workspaceDetails: () => request("/api/v1/auth/workspace"),
    updateWorkspace: (name) => jsonRequest<Session>("/api/v1/auth/workspace", { name }, { method: "PUT" }),
    deleteWorkspace: (name) => jsonRequest<Session>("/api/v1/auth/workspace", { name }, { method: "DELETE" }),
    updateMember: (id, role) => jsonRequest<unknown>(`/api/v1/auth/members/${encodeURIComponent(id)}`, { role }, { method: "PUT" }),
    removeMember: (id) => request(`/api/v1/auth/members/${encodeURIComponent(id)}`, { method: "DELETE" }),
    invite: (role) => jsonRequest("/api/v1/auth/invitations", { role }),
    fetchCashPage: (filters, cursor, signal) => request<CashPage>(appendQuery("/api/v1/cash-projections", { ...filters, timezone, cursor }), { signal }),
    fetchCashAccounts: (signal) => request<{ items: Account[] }>("/api/v1/accounts?view=cash", { signal }).then((payload) => payload.items),
    fetchEvidence: (id, signal) => request<Evidence>(`/api/v1/evidence/cash-projections/${encodeURIComponent(id)}`, { signal }),
    fetchLedgerOptions: (signal) => request<LedgerOptions>("/api/v1/cash-ledger/options", { signal }),
    fetchCashCategories: (signal) => request<CashCategoryDirectory>("/api/v1/cash-categories", { signal }),
    createCashCategory: (body, signal) => jsonRequest<CashCategory>("/api/v1/cash-categories", body, { signal }),
    updateCashCategory: (id, body, signal) => jsonRequest<CashCategory>(`/api/v1/cash-categories/${encodeURIComponent(id)}`, body, { method: "PATCH", signal }),
    reorderCashCategory: (id, direction, expectedRevision, signal) => jsonRequest<CashCategory>(`/api/v1/cash-categories/${encodeURIComponent(id)}/reorder`, { direction, expected_revision: expectedRevision }, { signal }),
    fetchCashCategoryDeletionImpact: (id, signal) => request(`/api/v1/cash-categories/${encodeURIComponent(id)}/deletion-impact`, { signal }),
    deleteCashCategory: (id, body, signal) => jsonRequest(`/api/v1/cash-categories/${encodeURIComponent(id)}`, body, { method: "DELETE", signal }),
    classifyCashProjections: (projectionIds, projectionVersion, categoryId, signal) => jsonRequest("/api/v1/cash-projections/categories", { projection_ids: projectionIds, projection_version: projectionVersion, category_id: categoryId }, { method: "PUT", signal }),
    fetchCashProjectionDeleteImpact: (projectionIds, projectionVersion, signal) => jsonRequest<CashProjectionDeleteImpact>("/api/v1/cash-projections/delete-impact", { projection_ids: projectionIds, projection_version: projectionVersion }, { signal }),
    deleteCashProjections: (projectionIds, projectionVersion, signal) => jsonRequest<CashProjectionDeleteResult>("/api/v1/cash-projections", { projection_ids: projectionIds, projection_version: projectionVersion, confirmed: true }, { method: "DELETE", signal }),
    fetchCashRecord: (id, signal) => request<CashRecordDetail>(`/api/v1/cash-records/${encodeURIComponent(id)}`, { signal }),
    fetchCashRecords: (values = {}, signal) => request<CashRecordPage>(appendQuery("/api/v1/cash-records", {
      query: values.query as string | undefined,
      exclude_id: values.excludeId as string | undefined,
      date_from: values.dateFrom as string | undefined,
      date_to: values.dateTo as string | undefined,
      timezone: values.timezone as string | undefined,
      cursor: values.cursor as string | null | undefined,
      limit: (values.limit as number | undefined) ?? 20,
    }), { signal }),
    createCashRecord: (body, signal) => jsonRequest<CashRecordDetail>("/api/v1/cash-records", body, { signal }),
    updateCashRecord: (id, body, signal) => jsonRequest<CashRecordDetail>(`/api/v1/cash-records/${encodeURIComponent(id)}`, body, { method: "PUT", signal }),
    deleteCashRecord: (id, mode, signal) => jsonRequest(`/api/v1/cash-records/${encodeURIComponent(id)}`, { mode }, { method: "DELETE", signal }),
    createCashRelation: (body, signal) => jsonRequest<CashRecordDetail>("/api/v1/cash-relations", body, { signal }),
    updateCashRelation: (id, body, signal) => jsonRequest<CashRecordDetail>(`/api/v1/cash-relations/${encodeURIComponent(id)}`, body, { method: "PUT", signal }),
    cancelCashRelation: (id, signal) => jsonRequest(`/api/v1/cash-relations/${encodeURIComponent(id)}`, {}, { method: "DELETE", signal }),
    dissolveCashRelations: (factId, signal) => jsonRequest<CashRecordDetail>("/api/v1/cash-relations/dissolve", { fact_id: factId }, { signal }),
    detectCashImport: (file, currency, password) => importRequest<ImportDetection>("/api/v1/cash-import/detect", file, { currency, password }),
    scanCashImport: (file, currency, password, importToken) => importRequest<ImportScan>("/api/v1/cash-import/scan", file, { currency, password, importToken }),
    previewCashImport: (file, source, currency, password, mapping, importToken) => importRequest<ImportPreview>("/api/v1/cash-import/preview", file, { source, currency, password, mapping, importToken }),
    commitCashImport: (file, source, currency, options = {}) => importRequest<ImportCommitResult>("/api/v1/cash-import/commit", file, { source, currency, ...options }),
    fetchInvestmentPage: (filters, cursor, signal) => request<InvestmentPage>(appendQuery("/api/v1/investment-events", { ...filters, timezone, cursor }), { signal }),
    fetchInvestmentAccounts: (signal) => request<{ items: Account[] }>("/api/v1/accounts?view=investment", { signal }).then((payload) => payload.items),
    fetchInvestmentEvidence: (eventId, signal) => request<InvestmentEvidence>(`/api/v1/evidence/investment-events/${encodeURIComponent(eventId)}`, { signal }),
    fetchInvestmentPortfolio: (displayCurrency, period = "24h", signal, phase = "valuation") => request<Portfolio>(appendQuery("/api/v1/investment-portfolio", { ...{}, timezone, display_currency: displayCurrency, period, phase }), { signal }),
  };
}

import { createApiClient, type FetchRequestInit } from "@finance-tracker/api-client";
import {
  roleLabel,
  SESSION_TOKEN_STORAGE_KEY,
  type InvitationPreview,
  type Member,
  type Role,
  type Session,
} from "@finance-tracker/contracts";

export type { InvitationPreview, Member, Role, Session } from "@finance-tracker/contracts";
export { roleLabel, SESSION_TOKEN_STORAGE_KEY } from "@finance-tracker/contracts";

export function apiOrigin(): string {
  const value = import.meta.env.VITE_FT_API_ORIGIN;
  if (!value) throw new Error("api_origin_invalid");
  let parsed: URL;
  try { parsed = new URL(value); } catch { throw new Error("api_origin_invalid"); }
  const localHttp = parsed.protocol === "http:" && ["127.0.0.1", "localhost"].includes(parsed.hostname) && parsed.port !== "";
  const hostedHttps = parsed.protocol === "https:" && parsed.hostname !== "";
  if ((!localHttp && !hostedHttps) || parsed.username || parsed.password || parsed.pathname !== "/" || parsed.search || parsed.hash) {
    throw new Error("api_origin_invalid");
  }
  return value.replace(/\/$/, "");
}

const webTokenStore = {
  async get(): Promise<string | null> {
    try { return window.localStorage.getItem(SESSION_TOKEN_STORAGE_KEY); } catch { return null; }
  },
  async set(value: string): Promise<void> {
    try { window.localStorage.setItem(SESSION_TOKEN_STORAGE_KEY, value); } catch { /* 存储不可用时由当前页面继续使用会话。 */ }
  },
  async clear(): Promise<void> {
    try { window.localStorage.removeItem(SESSION_TOKEN_STORAGE_KEY); } catch { /* 存储不可用时忽略清理失败。 */ }
  },
};

function webFetch(input: string, init?: FetchRequestInit): Promise<Response> {
  return fetch(input, {
    method: init?.method,
    headers: init?.headers,
    body: init?.body as BodyInit | null | undefined,
    signal: init?.signal as AbortSignal | undefined,
  });
}

export function apiClient() {
  return createApiClient({ baseUrl: apiOrigin, fetch: webFetch, tokenStore: webTokenStore });
}

export function authHeaders(init?: HeadersInit): Headers {
  const headers = new Headers(init);
  const token = (() => {
    try { return window.localStorage.getItem(SESSION_TOKEN_STORAGE_KEY); } catch { return null; }
  })();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}

export const session = (): Promise<Session> => apiClient().session();
export const login = (email: string, password: string): Promise<Session> => apiClient().login(email, password);
export const register = (email: string, password: string): Promise<Session> => apiClient().register(email, password);
export const logout = (): Promise<{ ok: boolean }> => apiClient().logout();
export const selectWorkspace = (id: string): Promise<Session> => apiClient().selectWorkspace(id);
export const createWorkspace = (name: string): Promise<Session> => apiClient().createWorkspace(name);
export const invitationPreview = (token: string): Promise<InvitationPreview> => apiClient().invitationPreview(token);
export const acceptInvitation = (token: string): Promise<Session> => apiClient().acceptInvitation(token);
export const members = (): Promise<{ workspace: { id: string; name: string }; members: Member[] }> => apiClient().members();
export const workspaceDetails = (): Promise<{ workspace: { id: string; name: string }; members: Member[] }> => apiClient().workspaceDetails();
export const updateWorkspace = (name: string): Promise<Session> => apiClient().updateWorkspace(name);
export const deleteWorkspace = (name: string): Promise<Session> => apiClient().deleteWorkspace(name);
export const updateMember = (id: string, role: Role): Promise<unknown> => apiClient().updateMember(id, role);
export const removeMember = (id: string): Promise<{ ok: boolean }> => apiClient().removeMember(id);
export const invite = (role: "editor" | "viewer"): Promise<{ token: string }> => apiClient().invite(role);

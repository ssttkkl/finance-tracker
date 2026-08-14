export type Role = "admin" | "editor" | "viewer";
export type Workspace = { id: string; name: string; role: Role };
export type Session = { user: { email: string }; active_workspace_id: string | null; workspaces: Workspace[] };
type AuthResponse = Session & { access_token: string };
export type Member = { user_id: string; email: string; role: Role; is_self: boolean };
export type InvitationPreview = { workspace: { name: string }; role: "editor" | "viewer"; valid: true };
export const SESSION_TOKEN_STORAGE_KEY = "finance-tracker:session-token";
export function apiOrigin() {
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
async function call<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("Content-Type", "application/json");
  const token = readSessionToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${apiOrigin()}${path}`, { ...init, headers });
  if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data?.error?.code ?? "request_failed"); }
  return response.json() as Promise<T>;
}
function readSessionToken(): string | null {
  try { return window.localStorage.getItem(SESSION_TOKEN_STORAGE_KEY); } catch { return null; }
}
function saveSessionToken(token: string): void {
  try { window.localStorage.setItem(SESSION_TOKEN_STORAGE_KEY, token); } catch { /* 存储不可用时由当前页面继续使用会话。 */ }
}
function clearSessionToken(): void {
  try { window.localStorage.removeItem(SESSION_TOKEN_STORAGE_KEY); } catch { /* 存储不可用时忽略清理失败。 */ }
}
export function authHeaders(init?: HeadersInit): Headers {
  const headers = new Headers(init);
  const token = readSessionToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);
  return headers;
}
export const roleLabel: Record<Role, string> = { admin: "管理员", editor: "可编辑", viewer: "仅可查看" };
export const session = () => call<Session>("/api/v1/auth/session");
async function authenticate(path: "/api/v1/auth/login" | "/api/v1/auth/register", email: string, password: string): Promise<Session> {
  const value = await call<AuthResponse>(path, { method: "POST", body: JSON.stringify({ email, password }) });
  saveSessionToken(value.access_token);
  return value;
}
export const login = (email: string, password: string) => authenticate("/api/v1/auth/login", email, password);
export const register = (email: string, password: string) => authenticate("/api/v1/auth/register", email, password);
export async function logout(): Promise<{ ok: boolean }> {
  try { return await call<{ ok: boolean }>("/api/v1/auth/logout", { method: "POST" }); }
  finally { clearSessionToken(); }
}
export const selectWorkspace = (id: string) => call<Session>(`/api/v1/auth/workspaces/${encodeURIComponent(id)}/select`, { method: "POST" });
export const createWorkspace = (name: string) => call<Session>("/api/v1/auth/workspaces", { method: "POST", body: JSON.stringify({ name }) });
export const invitationPreview = (token: string) => call<InvitationPreview>(`/api/v1/auth/invitations/${encodeURIComponent(token)}`);
export const acceptInvitation = (token: string) => call<Session>(`/api/v1/auth/invitations/${encodeURIComponent(token)}/accept`, { method: "POST" });
export const members = () => call<{ workspace: { id: string; name: string }; members: Member[] }>("/api/v1/auth/members");
export const workspaceDetails = () => call<{ workspace: { id: string; name: string }; members: Member[] }>("/api/v1/auth/workspace");
export const updateWorkspace = (name: string) => call<Session>("/api/v1/auth/workspace", { method: "PUT", body: JSON.stringify({ name }) });
export const updateMember = (id: string, role: Role) => call<unknown>(`/api/v1/auth/members/${encodeURIComponent(id)}`, { method: "PUT", body: JSON.stringify({ role }) });
export const removeMember = (id: string) => call<{ ok: boolean }>(`/api/v1/auth/members/${encodeURIComponent(id)}`, { method: "DELETE" });
export const invite = (role: "editor" | "viewer") => call<{ token: string }>("/api/v1/auth/invitations", { method: "POST", body: JSON.stringify({ role }) });

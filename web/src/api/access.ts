export type Role = "admin" | "editor" | "viewer";
export type Workspace = { id: string; name: string; role: Role };
export type Session = { user: { email: string }; active_workspace_id: string | null; workspaces: Workspace[] };
export type Member = { user_id: string; email: string; role: Role; is_self: boolean };
export type InvitationPreview = { workspace: { name: string }; role: "editor" | "viewer"; valid: true };
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
  const response = await fetch(`${apiOrigin()}${path}`, { credentials: "include", ...init, headers: { "Content-Type": "application/json", ...init.headers } });
  if (!response.ok) { const data = await response.json().catch(() => ({})); throw new Error(data?.error?.code ?? "request_failed"); }
  return response.json() as Promise<T>;
}
export const roleLabel: Record<Role, string> = { admin: "管理员", editor: "可编辑", viewer: "仅可查看" };
export const session = () => call<Session>("/api/v1/auth/session");
export const login = (email: string, password: string) => call<Session>("/api/v1/auth/login", { method: "POST", body: JSON.stringify({ email, password }) });
export const register = (email: string, password: string) => call<Session>("/api/v1/auth/register", { method: "POST", body: JSON.stringify({ email, password }) });
export const logout = () => call<{ ok: boolean }>("/api/v1/auth/logout", { method: "POST" });
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

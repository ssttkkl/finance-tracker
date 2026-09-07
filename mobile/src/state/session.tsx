import { createContext, useContext, useEffect, useMemo, useReducer, type PropsWithChildren } from "react";
import type { ApiClient } from "@finance-tracker/api-client";
import { ApiError } from "@finance-tracker/api-client";
import type { ApiErrorCode, Role, Session } from "@finance-tracker/contracts";
import { initialSessionState, sessionReducer, type SessionState } from "@finance-tracker/core";
import { mobileApiClient } from "@/platform/api";

type SessionContextValue = {
  client: ApiClient;
  state: SessionState;
  login(email: string, password: string): Promise<Session>;
  register(email: string, password: string): Promise<Session>;
  createWorkspace(name: string): Promise<Session>;
  selectWorkspace(id: string): Promise<Session>;
  refresh(): Promise<Session>;
  logout(): Promise<void>;
  activeRole: Role | null;
};

const SessionContext = createContext<SessionContextValue | null>(null);

function errorCode(cause: unknown): ApiErrorCode {
  if (cause instanceof ApiError) return cause.code;
  if (cause instanceof Error && cause.message) return cause.message;
  return "request_failed";
}

export function SessionProvider({ children }: PropsWithChildren) {
  const [state, dispatch] = useReducer(sessionReducer, initialSessionState);
  const client = useMemo(() => mobileApiClient, []);

  useEffect(() => {
    let active = true;
    dispatch({ type: "request_started" });
    client.session().then((session) => {
      if (active) dispatch({ type: "request_succeeded", session });
    }).catch((cause: unknown) => {
      if (active) dispatch({ type: "request_failed", errorCode: errorCode(cause) });
    });
    return () => { active = false; };
  }, [client]);

  async function run(operation: () => Promise<Session>): Promise<Session> {
    dispatch({ type: "request_started" });
    try {
      const session = await operation();
      dispatch({ type: "request_succeeded", session });
      return session;
    } catch (cause) {
      dispatch({ type: "request_failed", errorCode: errorCode(cause) });
      throw cause;
    }
  }

  const value: SessionContextValue = {
    client,
    state,
    login: (email, password) => run(() => client.login(email, password)),
    register: (email, password) => run(() => client.register(email, password)),
    createWorkspace: (name) => run(() => client.createWorkspace(name)),
    selectWorkspace: (id) => run(() => client.selectWorkspace(id)),
    refresh: () => run(() => client.session()),
    async logout() {
      try {
        await client.logout();
      } catch {
        // 本地令牌已由 API client 清理，网络失败不应阻止本地退出。
      } finally {
        dispatch({ type: "signed_out" });
      }
    },
    activeRole: state.session?.workspaces.find(({ id }) => id === state.session?.active_workspace_id)?.role ?? null,
  };

  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext);
  if (!value) throw new Error("session_provider_missing");
  return value;
}

export function errorMessage(code: ApiErrorCode | null): string {
  if (code === "api_origin_invalid") return "应用暂不可用，请稍后重试。";
  if (code === "authentication_required") return "登录状态已失效，请重新登录。";
  if (code === "workspace_forbidden") return "当前工作区没有操作权限。";
  if (code === "request_timeout") return "请求未确认，请回到账本核对。";
  return "请求失败，请稍后重试。";
}

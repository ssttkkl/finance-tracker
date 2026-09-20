import { isAuthenticationError, type ApiClient } from "@finance-tracker/api-client";
import type { Session } from "@finance-tracker/contracts";
import { restoreSession, type SessionRestoreOptions } from "@finance-tracker/core";

export function shouldRestoreNativeSession(token: string | null): boolean {
  return Boolean(token);
}

export function restoreNativeSession(
  client: Pick<ApiClient, "session">,
  options: Pick<SessionRestoreOptions, "wait" | "attempts"> = {},
): Promise<Session> {
  return restoreSession(() => client.session(), { isAuthenticationFailure: isAuthenticationError, ...options });
}

import { describe, expect, it, vi } from "vitest";
import {
  buildCashRecordPayload,
  createImportSession,
  importSessionReducer,
  restoreSession,
  sessionReducer,
} from "./index";

describe("shared finance state", () => {
  it("keeps decimal input as an exact string when building a cash payload", () => {
    const payload = buildCashRecordPayload({
      accountName: "支付宝余额",
      amount: "00032.50",
      currency: "CNY",
      occurredAt: "2026-09-05T10:00:00+08:00",
      recordType: "expense",
      recordSubtype: "purchase",
      counterparty: "便利店",
      counterpartyAccount: "",
      note: "早餐",
    });

    expect(payload.amount).toBe("00032.50");
    expect(typeof payload.amount).toBe("string");
    expect(payload.account_name).toBe("支付宝余额");
    expect(payload).not.toHaveProperty("account_id");
  });

  it("does not mark an import as started when file selection is cancelled", () => {
    const initial = createImportSession();
    expect(importSessionReducer(initial, { type: "file_cancelled" })).toEqual(initial);
  });

  it("moves a session to an explicit error instead of a local success", () => {
    const loading = sessionReducer({ status: "loading", session: null, errorCode: null }, { type: "request_failed", errorCode: "request_failed" });
    expect(loading).toEqual({ status: "error", session: null, errorCode: "request_failed" });
  });

  it("moves a signed-out session to an explicit unauthenticated state", () => {
    const authenticated = {
      status: "authenticated" as const,
      session: { user: { email: "owner@example.com" }, active_workspace_id: null, workspaces: [] },
      errorCode: null,
    };
    expect(sessionReducer(authenticated, { type: "signed_out" })).toEqual({
      status: "signed_out",
      session: null,
      errorCode: null,
    });
  });

  it("stops session restoration immediately for an authentication failure", async () => {
    const request = vi.fn(async () => { throw new Error("authentication_required"); });
    const wait = vi.fn(async () => undefined);

    await expect(restoreSession(request, { isAuthenticationFailure: () => true, wait })).rejects.toThrow("authentication_required");
    expect(request).toHaveBeenCalledTimes(1);
    expect(wait).not.toHaveBeenCalled();
  });

  it("retries temporary session failures and succeeds on the third request", async () => {
    let attempts = 0;
    const request = vi.fn(async () => {
      attempts += 1;
      if (attempts < 3) throw new Error("request_failed");
      return "session";
    });
    const wait = vi.fn(async () => undefined);

    await expect(restoreSession(request, { isAuthenticationFailure: () => false, wait })).resolves.toBe("session");
    expect(request).toHaveBeenCalledTimes(3);
    expect(wait).toHaveBeenCalledTimes(2);
  });

  it("stops after three temporary session failures", async () => {
    const request = vi.fn(async () => { throw new Error("request_failed"); });
    const wait = vi.fn(async () => undefined);

    await expect(restoreSession(request, { isAuthenticationFailure: () => false, wait })).rejects.toThrow("request_failed");
    expect(request).toHaveBeenCalledTimes(3);
    expect(wait).toHaveBeenCalledTimes(2);
  });
});

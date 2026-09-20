import { describe, expect, it, vi } from "vitest";
import { ApiError } from "@finance-tracker/api-client";
import type { Session } from "@finance-tracker/contracts";
import { restoreNativeSession, shouldRestoreNativeSession } from "./session-restore";

const session: Session = {
  user: { email: "member@example.com" },
  active_workspace_id: "workspace-1",
  workspaces: [{ id: "workspace-1", name: "家庭账本", role: "editor" }],
};

describe("Native session restoration", () => {
  it("skips restoration when no token is stored", () => {
    expect(shouldRestoreNativeSession(null)).toBe(false);
    expect(shouldRestoreNativeSession("")).toBe(false);
    expect(shouldRestoreNativeSession("stored-token")).toBe(true);
  });

  it("does not retry an invalid token", async () => {
    const sessionRequest = vi.fn(async () => { throw new ApiError("authentication_required", 401); });

    await expect(restoreNativeSession({ session: sessionRequest }, { wait: async () => undefined })).rejects.toMatchObject({ status: 401 });
    expect(sessionRequest).toHaveBeenCalledTimes(1);
  });

  it("restores after two temporary failures", async () => {
    let attempts = 0;
    const sessionRequest = vi.fn(async () => {
      attempts += 1;
      if (attempts < 3) throw new ApiError("request_failed", 503);
      return session;
    });

    await expect(restoreNativeSession({ session: sessionRequest }, { wait: async () => undefined })).resolves.toEqual(session);
    expect(sessionRequest).toHaveBeenCalledTimes(3);
  });
});

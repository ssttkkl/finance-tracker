import { describe, expect, it } from "vitest";
import {
  canWrite,
  createImportSession,
  importSessionReducer,
  selectActiveWorkspace,
  sessionReducer,
} from "@finance-tracker/core";

describe("native route and write guards", () => {
  it("only grants writes to editor and admin workspaces", () => {
    expect(canWrite("admin")).toBe(true);
    expect(canWrite("editor")).toBe(true);
    expect(canWrite("viewer")).toBe(false);
    expect(canWrite(null)).toBe(false);
  });

  it("selects the server-declared active workspace", () => {
    const session = {
      user: { email: "owner@example.com" },
      active_workspace_id: "workspace-2",
      workspaces: [
        { id: "workspace-1", name: "家庭", role: "viewer" as const },
        { id: "workspace-2", name: "工作", role: "editor" as const },
      ],
    };
    expect(selectActiveWorkspace(session)?.name).toBe("工作");
    expect(selectActiveWorkspace({ ...session, active_workspace_id: "missing" })).toBeNull();
  });

  it("clears protected session state when signing out", () => {
    const authenticated = {
      status: "authenticated" as const,
      session: { user: { email: "owner@example.com" }, active_workspace_id: null, workspaces: [] },
      errorCode: null,
    };
    expect(sessionReducer(authenticated, { type: "signed_out" })).toMatchObject({ status: "signed_out", session: null });
  });

  it("keeps cancelled imports idle and preserves an import token on failure", () => {
    const initial = createImportSession();
    expect(importSessionReducer(initial, { type: "file_cancelled" })).toEqual(initial);
    expect(importSessionReducer(initial, {
      type: "request_failed",
      errorCode: "import_password_required",
      importToken: "import-session-1",
    })).toMatchObject({ status: "error", importToken: "import-session-1" });
  });
});

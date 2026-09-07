import { describe, expect, it } from "vitest";
import type { Session, Workspace } from "./index";
import { SESSION_TOKEN_STORAGE_KEY, roleLabel } from "./index";

describe("shared contracts", () => {
  it("keeps session roles and storage keys platform-neutral", () => {
    const workspace: Workspace = { id: "workspace-1", name: "个人账本", role: "editor" };
    const session: Session = {
      user: { email: "owner@example.com" },
      active_workspace_id: workspace.id,
      workspaces: [workspace],
    };

    expect(session.workspaces[0]?.role).toBe("editor");
    expect(roleLabel.viewer).toBe("仅可查看");
    expect(SESSION_TOKEN_STORAGE_KEY).toBe("finance-tracker:session-token");
  });
});

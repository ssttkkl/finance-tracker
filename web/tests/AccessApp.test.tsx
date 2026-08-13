import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AccessApp } from "../src/AccessApp";

const session = {
  user: { email: "member@example.com" },
  active_workspace_id: "workspace-1",
  workspaces: [{ id: "workspace-1", name: "家庭账本", role: "editor" as const }],
};

function json(value: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(value), {
    status, headers: { "Content-Type": "application/json" },
  }));
}

beforeEach(() => {
  vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000");
  history.replaceState({}, "", "/?invite=invite-token");
});
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); history.replaceState({}, "", "/"); });

describe("AccessApp", () => {
  it("展示邀请指定的冻结角色，而不提供角色选择", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json({ error: { code: "authentication_required" } }, 401)
      : input.includes("/auth/invitations/invite-token")
        ? json({ workspace: { name: "家庭账本" }, role: "viewer", valid: true })
        : json({})));

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "加入家庭账本" })).toBeInTheDocument();
    expect(screen.getByText("仅可查看")).toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
  });

  it("接受邀请后清除邀请参数并回到账本", async () => {
    const fetch = vi.fn((input: string) => input.includes("/auth/session")
      ? json(session)
      : input.includes("/auth/invitations/invite-token/accept")
        ? json(session)
        : input.includes("/auth/invitations/invite-token")
          ? json({ workspace: { name: "家庭账本" }, role: "editor", valid: true })
          : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);

    render(<AccessApp />);
    fireEvent.click(await screen.findByRole("button", { name: "接受邀请" }));

    await waitFor(() => expect(location.search).toBe(""));
    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
  });

  it("在登录后的工作区壳中使用统一账本路由", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json(session)
      : input.includes("/cash-categories")
        ? json({ items: [], revision: 0 })
        : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} })));
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    fireEvent.click(await screen.findByRole("link", { name: "分类管理" }));
    expect(await screen.findByRole("heading", { name: "分类管理" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("link", { name: "投资事件" }));
    expect(await screen.findByRole("heading", { name: "投资事件", level: 1 })).toBeInTheDocument();
  });
});

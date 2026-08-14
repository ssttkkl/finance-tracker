import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AccessApp } from "../src/AccessApp";

const session = {
  user: { email: "member@example.com" },
  active_workspace_id: "workspace-1",
  workspaces: [{ id: "workspace-1", name: "家庭账本", role: "editor" as const }],
};

const multiWorkspaceSession = {
  ...session,
  workspaces: [
    ...session.workspaces,
    { id: "workspace-2", name: "旅行账本", role: "editor" as const },
  ],
};

const adminSession = {
  user: { email: "admin@example.com" },
  active_workspace_id: "workspace-1",
  workspaces: [{ id: "workspace-1", name: "家庭账本", role: "admin" as const }],
};

const workspaceDetails = {
  workspace: { id: "workspace-1", name: "家庭账本" },
  members: [
    { user_id: "admin-1", email: "admin@example.com", role: "admin" as const, is_self: true },
    { user_id: "editor-1", email: "editor@example.com", role: "editor" as const, is_self: false },
  ],
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

  it("切换工作区失败时保留当前账本并提供重试提示", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json(multiWorkspaceSession)
      : input.includes("/auth/workspaces/workspace-2/select")
        ? json({ error: { code: "storage.busy" } }, 503)
        : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} })));
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    const switcher = await screen.findByRole("combobox", { name: "当前工作区" });
    fireEvent.change(switcher, { target: { value: "workspace-2" } });
    expect(await screen.findByRole("alert")).toHaveTextContent("无法切换工作区，请稍后重试。");
    expect(switcher).toHaveValue("workspace-1");
  });

  it("管理员在一级工作区管理页面完成名称、成员和邀请操作", async () => {
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/auth/session")) return json(adminSession);
      if (input.includes("/auth/workspace") && init?.method === "PUT") return json(adminSession);
      if (input.includes("/auth/workspace")) return json(workspaceDetails);
      if (input.includes("/auth/invitations")) return json({ token: "invite-token" });
      if (input.includes("/auth/members/editor-1") && init?.method === "DELETE") return json({ ok: true });
      if (input.includes("/auth/members/editor-1")) return json({ ok: true });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    const writeText = vi.fn(() => Promise.resolve());
    Object.assign(navigator, { clipboard: { writeText } });
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    fireEvent.click(await screen.findByRole("link", { name: "工作区管理" }));
    expect(await screen.findByRole("heading", { name: "工作区管理", level: 1 })).toBeInTheDocument();
    expect(location.pathname).toBe("/workspace-management");
    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    expect(within(navigation).getAllByRole("link").filter(link => link.hasAttribute("aria-current"))).toHaveLength(1);
    expect(within(navigation).getByRole("link", { name: "工作区管理" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("link", { name: "返回账本" })).not.toBeInTheDocument();
    expect(screen.queryByText("管理工作区信息、成员和邀请。")).not.toBeInTheDocument();
    expect(screen.getByText("固定 ID")).toBeInTheDocument();

    fireEvent.click(within(navigation).getByRole("link", { name: "收支账本" }));
    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(location.pathname).toBe("/");
    fireEvent.click(within(screen.getByRole("navigation", { name: "主要导航" })).getByRole("link", { name: "工作区管理" }));
    await screen.findByRole("heading", { name: "工作区管理", level: 1 });

    fireEvent.change(screen.getByRole("textbox", { name: "工作区名称" }), { target: { value: "新的账本" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspace"), expect.objectContaining({ method: "PUT" })));
    expect(await screen.findByRole("status")).toHaveTextContent("已保存");

    fireEvent.click(screen.getByRole("button", { name: "复制" }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith("workspace-1"));
    expect(screen.getByRole("status")).toHaveTextContent("已复制");

    fireEvent.change(screen.getByRole("combobox", { name: "editor@example.com的权限" }), { target: { value: "viewer" } });
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/members/editor-1"), expect.objectContaining({ method: "PUT" })));
    fireEvent.click(screen.getByRole("button", { name: "移除" }));
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/members/editor-1"), expect.objectContaining({ method: "DELETE" })));

    fireEvent.click(screen.getByRole("button", { name: "创建链接" }));
    expect(await screen.findByRole("textbox", { name: "邀请链接" })).toHaveValue("http://localhost:3000/workspace-management?invite=invite-token");
  });

  it("非管理员可以查看工作区管理页面但不能写入", async () => {
    const viewerSession = { ...session, user: { email: "viewer@example.com" }, workspaces: [{ ...session.workspaces[0], role: "viewer" as const }] };
    const fetch = vi.fn((input: string) => input.includes("/auth/session")
      ? json(viewerSession)
      : input.includes("/auth/workspace")
        ? json({ ...workspaceDetails, members: workspaceDetails.members.map(member => member.is_self ? { ...member, email: "viewer@example.com", role: "viewer" as const } : member) })
        : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/");

    render(<AccessApp />);
    fireEvent.click(await screen.findByRole("link", { name: "工作区管理" }));
    await screen.findByRole("heading", { name: "工作区管理", level: 1 });
    expect(screen.getByRole("textbox", { name: "工作区名称" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "保存" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "创建链接" })).toBeDisabled();
    expect(screen.queryByRole("button", { name: "移除" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "删除工作区" })).not.toBeInTheDocument();
  });

  it("管理员必须输入当前工作区名称后才能删除并离开管理页", async () => {
    const deletedSession = {
      ...adminSession,
      active_workspace_id: "workspace-2",
      workspaces: [
        { id: "workspace-2", name: "另一个账本", role: "admin" as const },
      ],
    };
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/auth/session")) return json(adminSession);
      if (input.includes("/auth/workspace") && init?.method === "DELETE") return json(deletedSession);
      if (input.includes("/auth/workspace")) return json(workspaceDetails);
      if (input.includes("/api/v1/accounts")) return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/workspace-management");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "工作区管理", level: 1 })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "删除工作区" }));
    const dialog = screen.getByRole("alertdialog", { name: "删除工作区？" });
    expect(dialog).toBeInTheDocument();
    const confirm = within(dialog).getByRole("button", { name: /^删除工作区$/ });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "输入工作区名称" }), { target: { value: "家庭账本 " } });
    expect(confirm).toBeDisabled();
    fireEvent.change(within(dialog).getByRole("textbox", { name: "输入工作区名称" }), { target: { value: "家庭账本" } });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspace"), expect.objectContaining({ method: "DELETE" })));
    const request = fetch.mock.calls.find(([input, init]) => input.includes("/auth/workspace") && init?.method === "DELETE");
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ name: "家庭账本" });
    expect(location.pathname).toBe("/");
    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
  });

  it("删除唯一工作区后进入创建工作区页面", async () => {
    const deletedSession = { ...adminSession, active_workspace_id: null, workspaces: [] };
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/auth/session")) return json(adminSession);
      if (input.includes("/auth/workspace") && init?.method === "DELETE") return json(deletedSession);
      if (input.includes("/auth/workspace")) return json(workspaceDetails);
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/workspace-management");

    render(<AccessApp />);

    await screen.findByRole("heading", { name: "工作区管理", level: 1 });
    fireEvent.click(screen.getByRole("button", { name: "删除工作区" }));
    const dialog = screen.getByRole("alertdialog", { name: "删除工作区？" });
    fireEvent.change(within(dialog).getByRole("textbox", { name: "输入工作区名称" }), { target: { value: "家庭账本" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /^删除工作区$/ }));

    expect(await screen.findByRole("heading", { name: "创建工作区" })).toBeInTheDocument();
  });
});

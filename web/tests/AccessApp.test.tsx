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

function cashItem(counterparty: string) {
  return {
    projection_id: `projection-${counterparty}`,
    occurred_at: "2026-07-03T09:00:00+08:00",
    account: { id: 101, name: "日常账户", type: "cash", active: true },
    counterparty,
    category: null,
    note: "",
    amount: "-12.50",
    currency: "CNY",
    economic_type: "expense" as const,
    transfer_subtype: null,
    composition: [],
    member_count: 1,
    accepted_relation_summary: [],
    source_type: "wechat",
    source_types: ["wechat"],
    record_id: `record-${counterparty}`,
    visible: true,
    hidden_reason: null,
  };
}

beforeEach(() => {
  vi.stubEnv("VITE_FT_API_ORIGIN", "http://127.0.0.1:8000");
  history.replaceState({}, "", "/?invite=invite-token");
});
afterEach(() => { cleanup(); localStorage.clear(); vi.unstubAllGlobals(); vi.unstubAllEnvs(); history.replaceState({}, "", "/"); });

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

  it("登录响应没有活动工作区时自动进入已有工作区", async () => {
    const loginSession = { ...session, active_workspace_id: null };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json({ error: { code: "authentication_required" } }, 401);
      if (input.includes("/auth/login")) return json({ ...loginSession, access_token: "login-token" });
      if (input.includes("/auth/workspaces/workspace-1/select")) return json(session);
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    localStorage.clear();
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    fireEvent.change(await screen.findByLabelText("邮箱"), { target: { value: "member@example.com" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "a-secure-password" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-1/select"), expect.anything());
  });

  it("恢复会话没有活动工作区时自动进入已有工作区", async () => {
    const restoredSession = { ...session, active_workspace_id: null };
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(restoredSession);
      if (input.includes("/auth/workspaces/workspace-1/select")) return json(session);
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-1/select"), expect.anything());
  });

  it("已有工作区选择失败时不进入创建页而提供重试", async () => {
    const restoredSession = { ...session, active_workspace_id: null };
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json(restoredSession)
      : input.includes("/auth/workspaces/workspace-1/select")
        ? json({ error: { code: "storage.busy" } }, 503)
        : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} })));
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    expect(await screen.findByRole("alert")).toHaveTextContent("无法打开工作区，请稍后重试。");
    expect(screen.getByRole("button", { name: "重试" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "创建工作区" })).not.toBeInTheDocument();
  });

  it("没有工作区时显示创建页但不显示无效返回按钮", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json({ user: { email: "new@example.com" }, active_workspace_id: null, workspaces: [] })
      : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} })));
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "创建工作区" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^返回$/ })).not.toBeInTheDocument();
  });

  it("从已有工作区创建新工作区时保留可用的返回按钮", async () => {
    vi.stubGlobal("fetch", vi.fn((input: string) => input.includes("/auth/session")
      ? json(session)
      : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} })));
    history.replaceState({}, "", "/");

    render(<AccessApp />);

    fireEvent.change(await screen.findByRole("combobox", { name: "当前工作区" }), { target: { value: "__create__" } });
    expect(await screen.findByRole("heading", { name: "创建工作区" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /^返回$/ }));
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

  it("直接打开工作区子页面时选择对应工作区并保留深链接", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(multiWorkspaceSession);
      if (input.includes("/auth/workspaces/workspace-2/select")) return json({ ...multiWorkspaceSession, active_workspace_id: "workspace-2" });
      if (input.includes("/cash-categories")) return json({ items: [], revision: 0 });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-2/cash-categories");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-2/select"), expect.anything());
    expect(location.pathname).toBe("/w/workspace-2/cash-categories");
  });

  it("直接打开工作区根路径时渲染对应账本", async () => {
    const fetch = vi.fn((input: string) => input.includes("/auth/session")
      ? json(session)
      : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-1/");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "收支账本", level: 1 })).toBeInTheDocument();
    expect(location.pathname).toBe("/w/workspace-1/");
  });

  it("无权打开工作区深链接时回到当前工作区并提示错误", async () => {
    const fetch = vi.fn((input: string) => input.includes("/auth/session")
      ? json(multiWorkspaceSession)
      : input.includes("/auth/workspaces/workspace-2/select")
        ? json({ error: { code: "workspace_forbidden" } }, 403)
        : input.includes("/cash-categories")
          ? json({ items: [], revision: 0 })
          : json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} }));
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-2/cash-categories");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("无法打开该工作区，请检查权限后重试。");
    expect(location.pathname).toBe("/w/workspace-1/cash-categories");
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

  it("切换成功后立即按新工作区重载收支账本并更新 URL", async () => {
    let selectedWorkspace = "workspace-1";
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(multiWorkspaceSession);
      if (input.includes("/auth/workspaces/workspace-2/select")) {
        selectedWorkspace = "workspace-2";
        return json({ ...multiWorkspaceSession, active_workspace_id: "workspace-2" });
      }
      if (input.includes("/cash-projections")) {
        return json({
          items: [cashItem(selectedWorkspace === "workspace-1" ? "家庭流水" : "旅行流水")],
          projection_version: selectedWorkspace === "workspace-1" ? 1 : 2,
          next_cursor: null,
          page_size: 50,
          filters: {},
          filter_options: { categories: [], currencies: [], economic_types: [] },
          monthly_summaries: [],
        });
      }
      if (input.includes("/accounts")) return json({ items: [] });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-1/");

    render(<AccessApp />);

    expect(await screen.findByText("家庭流水")).toBeInTheDocument();
    fireEvent.change(await screen.findByRole("combobox", { name: "当前工作区" }), { target: { value: "workspace-2" } });
    expect(await screen.findByText("旅行流水")).toBeInTheDocument();
    expect(screen.queryByText("家庭流水")).not.toBeInTheDocument();
    expect(location.pathname).toBe("/w/workspace-2/");
  });

  it("直接打开工作区深链接时先选择成员工作区并渲染对应页面", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(multiWorkspaceSession);
      if (input.includes("/auth/workspaces/workspace-2/select")) return json({ ...multiWorkspaceSession, active_workspace_id: "workspace-2" });
      if (input.includes("/cash-categories")) return json({ items: [], revision: 0 });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-2/cash-categories");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-2/select"), expect.anything());
    expect(location.pathname).toBe("/w/workspace-2/cash-categories");
  });

  it("无权打开工作区深链接时保留当前工作区并规范化 URL", async () => {
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(multiWorkspaceSession);
      if (input.includes("/auth/workspaces/workspace-2/select")) return json({ error: { code: "workspace_forbidden" } }, 403);
      if (input.includes("/cash-categories")) return json({ items: [], revision: 0 });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-2/cash-categories");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("无法打开该工作区，请检查权限后重试。");
    expect(location.pathname).toBe("/w/workspace-1/cash-categories");
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-2/select"), expect.anything());
  });

  it("在子页面切换工作区后保留当前子路由并立即显示新会话", async () => {
    let selectedWorkspace = "workspace-1";
    const fetch = vi.fn((input: string) => {
      if (input.includes("/auth/session")) return json(multiWorkspaceSession);
      if (input.includes("/auth/workspaces/workspace-2/select")) {
        selectedWorkspace = "workspace-2";
        return json({ ...multiWorkspaceSession, active_workspace_id: "workspace-2" });
      }
      if (input.includes("/cash-categories")) return json({ items: [], revision: selectedWorkspace === "workspace-1" ? 1 : 2 });
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-1/cash-categories");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    fireEvent.change(await screen.findByRole("combobox", { name: "当前工作区" }), { target: { value: "workspace-2" } });

    await waitFor(() => expect(location.pathname).toBe("/w/workspace-2/cash-categories"));
    expect(await screen.findByRole("heading", { name: "分类管理", level: 1 })).toBeInTheDocument();
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspaces/workspace-2/select"), expect.anything());
  });

  it("管理员在一级工作区管理页面按顺序完成名称、成员和邀请操作", async () => {
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
    expect(location.pathname).toBe("/w/workspace-1/workspace-management");
    const navigation = screen.getByRole("navigation", { name: "主要导航" });
    expect(within(navigation).getAllByRole("link").filter(link => link.hasAttribute("aria-current"))).toHaveLength(1);
    expect(within(navigation).getByRole("link", { name: "工作区管理" })).toHaveAttribute("aria-current", "page");
    expect(screen.queryByRole("link", { name: "返回账本" })).not.toBeInTheDocument();
    expect(screen.queryByText("管理工作区信息、成员和邀请。")).not.toBeInTheDocument();
    expect(screen.getByText("固定 ID")).toBeInTheDocument();
    const main = document.querySelector("main.workspace-management-page");
    if (!main) throw new Error("工作区管理页面未渲染");
    expect([...main.querySelectorAll(":scope > section h2")].map(node => node.textContent)).toEqual([
      "工作区信息", "成员", "邀请成员", "删除工作区",
    ]);
    const dangerSection = main.querySelector('[aria-labelledby="workspace-delete-title"]');
    expect([...dangerSection!.querySelectorAll(":scope > h2, :scope > button")].map(node => node.tagName)).toEqual(["H2", "BUTTON"]);

    fireEvent.click(within(navigation).getByRole("link", { name: "收支账本" }));
    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(location.pathname).toBe("/w/workspace-1/");
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
    expect(await screen.findByRole("textbox", { name: "邀请链接" })).toHaveValue("http://localhost:3000/w/workspace-1/workspace-management?invite=invite-token");
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
    const deleteInput = within(dialog).getByRole("textbox", { name: "输入工作区名称" });
    const cancel = within(dialog).getByRole("button", { name: "取消" });
    expect(deleteInput).toHaveFocus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(deleteInput).toHaveFocus();
    fireEvent.click(cancel);
    expect(screen.getByRole("button", { name: "删除工作区" })).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "删除工作区" }));
    const reopenedDialog = screen.getByRole("alertdialog", { name: "删除工作区？" });
    const confirm = within(reopenedDialog).getByRole("button", { name: /^删除工作区$/ });
    expect(confirm).toBeDisabled();
    const reopenedInput = within(reopenedDialog).getByRole("textbox", { name: "输入工作区名称" });
    fireEvent.change(reopenedInput, { target: { value: "家庭账本 " } });
    expect(confirm).toBeDisabled();
    fireEvent.change(reopenedInput, { target: { value: "家庭账本" } });
    expect(confirm).not.toBeDisabled();
    fireEvent.click(confirm);

    await waitFor(() => expect(fetch).toHaveBeenCalledWith(expect.stringContaining("/auth/workspace"), expect.objectContaining({ method: "DELETE" })));
    const request = fetch.mock.calls.find(([input, init]) => input.includes("/auth/workspace") && init?.method === "DELETE");
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ name: "家庭账本" });
    expect(location.pathname).toBe("/w/workspace-2/");
    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
  });

  it("删除工作区失败时保留确认框并允许重试", async () => {
    const deletedSession = {
      ...adminSession,
      active_workspace_id: "workspace-2",
      workspaces: [{ id: "workspace-2", name: "另一个账本", role: "admin" as const }],
    };
    let deleteCalls = 0;
    const fetch = vi.fn((input: string, init?: RequestInit) => {
      if (input.includes("/auth/session")) return json(adminSession);
      if (input.includes("/auth/workspace") && init?.method === "DELETE") {
        deleteCalls += 1;
        return deleteCalls === 1 ? json({ error: { code: "storage.busy" } }, 503) : json(deletedSession);
      }
      if (input.includes("/auth/workspace")) return json(workspaceDetails);
      return json({ items: [], projection_version: 1, next_cursor: null, page_size: 50, filters: {} });
    });
    vi.stubGlobal("fetch", fetch);
    history.replaceState({}, "", "/w/workspace-1/workspace-management");

    render(<AccessApp />);

    expect(await screen.findByRole("heading", { name: "工作区管理", level: 1 })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "删除工作区" }));
    const dialog = screen.getByRole("alertdialog", { name: "删除工作区？" });
    fireEvent.change(within(dialog).getByRole("textbox", { name: "输入工作区名称" }), { target: { value: "家庭账本" } });
    fireEvent.click(within(dialog).getByRole("button", { name: /^删除工作区$/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("无法删除工作区，请检查名称后重试。");
    expect(screen.getByRole("alertdialog", { name: "删除工作区？" })).toBeInTheDocument();
    const retryDialog = screen.getByRole("alertdialog", { name: "删除工作区？" });
    const retry = within(retryDialog).getByRole("button", { name: /^删除工作区$/ });
    expect(retry).not.toBeDisabled();
    fireEvent.click(retry);

    expect(await screen.findByRole("heading", { name: "收支账本" })).toBeInTheDocument();
    expect(deleteCalls).toBe(2);
    expect(location.pathname).toBe("/w/workspace-2/");
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

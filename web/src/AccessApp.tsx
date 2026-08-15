import { FormEvent, useEffect, useRef, useState } from "react";
import { App } from "./App";
import * as access from "./api/access";
import type { InvitationPreview, Member, Role, Session } from "./api/access";
import { parseWorkspacePath, workspaceChildPath, workspacePath, workspaceUrl } from "./routing";

function routeForPath(pathname: string): "ledger" | "members" {
  return workspaceChildPath(pathname) === "/workspace-management" ? "members" : "ledger";
}

function Icon({ name }: { name: "arrow-left" | "copy" | "link" | "logout" | "plus" | "save" | "trash" | "users" }) {
  const paths = {
    "arrow-left": <path d="m14 6-6 6 6 6M8 12h10" />,
    copy: <><rect x="9" y="9" width="10" height="10" rx="1" /><path d="M15 9V5H5v10h4" /></>,
    link: <><path d="M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1" /><path d="M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1" /></>,
    logout: <><path d="M10 5H5v14h5" /><path d="m14 8 4 4-4 4M18 12H9" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    save: <><path d="M5 4h11l3 3v13H5z" /><path d="M8 4v6h8V4M8 20v-5h8v5" /></>,
    trash: <><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></>,
    users: <><path d="M16 20v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1" /><circle cx="9" cy="7" r="4" /><path d="M22 20v-1a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></>,
  }[name];
  return <svg className="access-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>;
}

function BackButton({ onClick, label = "返回" }: { onClick: () => void; label?: string }) {
  return <button type="button" className="access-back" onClick={onClick}><Icon name="arrow-left" />{label}</button>;
}

function Auth({ onSession }: { onSession: (value: Session) => void }) {
  const [registering, setRegistering] = useState(false); const [error, setError] = useState(""); const [loading, setLoading] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const form = new FormData(event.currentTarget); setLoading(true); setError("");
    try { onSession(await (registering ? access.register : access.login)(String(form.get("email")), String(form.get("password")))); }
    catch { setError("邮箱或密码不正确。请检查后重试。"); } finally { setLoading(false); }
  }
  return <main className="access-centered"><section className="access-card"><p className="access-eyebrow">工作区访问</p><h1>{registering ? "创建你的账户" : "登录到你的账本"}</h1><form className="access-form" onSubmit={submit}><label>邮箱<input required name="email" type="email" autoComplete="email" /></label><label>密码<input required minLength={12} name="password" type="password" autoComplete={registering ? "new-password" : "current-password"} /></label><button className="button-primary" disabled={loading}>{loading ? "正在处理…" : registering ? "注册" : "登录"}</button></form>{error && <p className="access-error" role="alert">{error}</p>}<button type="button" className="text-button" onClick={() => { setRegistering(!registering); setError(""); }}>{registering ? "已有账户？登录" : "还没有账户？注册"}</button></section></main>;
}

function Invite({ token, session, onSession, onSignIn }: { token: string; session: Session | null; onSession: (value: Session) => void; onSignIn: () => void }) {
  const [preview, setPreview] = useState<InvitationPreview | null>(null); const [error, setError] = useState(""); const [loading, setLoading] = useState(true); const [accepting, setAccepting] = useState(false);
  useEffect(() => { setLoading(true); access.invitationPreview(token).then(setPreview).catch(() => setError("此邀请无效、已被使用或已过期。")).finally(() => setLoading(false)); }, [token]);
  const leave = () => { history.replaceState({}, "", location.pathname); onSignIn(); };
  if (loading) return <main className="access-centered"><section className="access-card"><p className="access-muted">正在确认邀请…</p></section></main>;
  if (!preview) return <main className="access-centered"><section className="access-card"><p className="access-eyebrow">工作区邀请</p><h1>邀请不可用</h1><p className="access-error" role="alert">{error}</p><button className="button-primary" onClick={leave}>返回登录</button></section></main>;
  const description = preview.role === "editor" ? "可查看和修改账本、导入和关联关系。" : "可浏览账本及相关信息，不能修改内容。";
  return <main className="access-centered"><section className="access-card"><p className="access-eyebrow">工作区邀请</p><h1>加入{preview.workspace.name}</h1><p className="access-muted">管理员已在创建邀请时确定你的权限，接受后不能自行更改。</p><div className="access-permission"><b>{access.roleLabel[preview.role]}</b><small>{description}</small></div>{session ? <button className="button-primary" disabled={accepting} onClick={async () => { setAccepting(true); setError(""); try { const value = await access.acceptInvitation(token); history.replaceState({}, "", location.pathname); onSession(value); } catch { setError("此邀请无效、已被使用或已过期。"); } finally { setAccepting(false); } }}>{accepting ? "正在加入…" : "接受邀请"}</button> : <button className="button-primary" onClick={onSignIn}>登录后接受邀请</button>}<button type="button" className="text-button access-cancel" onClick={leave}>暂不加入</button>{error && <p className="access-error" role="alert">{error}</p>}</section></main>;
}

function Create({ onSession, onBack, showBack }: { onSession: (value: Session) => void; onBack: () => void; showBack: boolean }) {
  const [name, setName] = useState(""); const [error, setError] = useState(""); const [loading, setLoading] = useState(false);
  return <main className="access-page">{showBack && <BackButton onClick={onBack} />}<p className="access-eyebrow">新工作区</p><h1>创建工作区</h1><p className="access-muted">创建后你将成为首位管理员，可以再邀请其他成员。</p><section className="access-panel"><form className="access-form" onSubmit={async e => { e.preventDefault(); setLoading(true); setError(""); try { onSession(await access.createWorkspace(name)); } catch { setError("无法创建工作区，请检查名称后重试。"); } finally { setLoading(false); } }}><label>工作区名称<input value={name} onChange={e => setName(e.target.value)} required maxLength={255} /></label><button className="button-primary" disabled={loading}>{loading ? "正在创建…" : "创建工作区"}</button>{error && <p className="access-error" role="alert">{error}</p>}</form></section></main>;
}

function WorkspaceSelectionError({ onRetry }: { onRetry: () => void }) {
  return <main className="access-centered"><section className="access-card"><p className="access-eyebrow">工作区访问</p><h1>无法打开工作区</h1><p className="access-error" role="alert">无法打开工作区，请稍后重试。</p><button type="button" className="button-primary" onClick={onRetry}>重试</button></section></main>;
}

function WorkspaceManagement({ activeRole, onSession, onWorkspaceDeleted }: { activeRole: Role; onSession: (value: Session) => void; onWorkspaceDeleted: (value: Session) => void }) {
  const [data, setData] = useState<{ workspace: { id: string; name: string }; members: Member[] } | null>(null);
  const [role, setRole] = useState<"editor" | "viewer">("editor");
  const [link, setLink] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [loading, setLoading] = useState(true);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleteName, setDeleteName] = useState("");
  const [deleteError, setDeleteError] = useState("");
  const [deleting, setDeleting] = useState(false);
  const deleteInput = useRef<HTMLInputElement>(null);
  const deleteTrigger = useRef<HTMLButtonElement>(null);
  const deleteDialog = useRef<HTMLElement>(null);
  const admin = activeRole === "admin";

  const refresh = async () => {
    setLoading(true);
    try {
      const value = await access.workspaceDetails();
      setData(value);
      setName(value.workspace.name);
      setError("");
    } catch {
      setError("无法读取工作区信息，请稍后重试。");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => { void refresh(); }, []);
  useEffect(() => {
    if (!deleteOpen) return;
    deleteInput.current?.focus();
    const handleDialogKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape" && !deleting) {
        setDeleteOpen(false);
        deleteTrigger.current?.focus();
        return;
      }
      if (event.key !== "Tab") return;
      const focusable = Array.from(deleteDialog.current?.querySelectorAll<HTMLElement>(
        "button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href]",
      ) ?? []);
      if (focusable.length === 0) return;
      const currentIndex = focusable.indexOf(document.activeElement as HTMLElement);
      const nextIndex = event.shiftKey
        ? currentIndex <= 0 ? focusable.length - 1 : currentIndex - 1
        : currentIndex === -1 || currentIndex === focusable.length - 1 ? 0 : currentIndex + 1;
      event.preventDefault();
      focusable[nextIndex].focus();
    };
    document.addEventListener("keydown", handleDialogKeyDown);
    return () => document.removeEventListener("keydown", handleDialogKeyDown);
  }, [deleteOpen, deleting]);

  const copy = async (value: string) => {
    try { await navigator.clipboard.writeText(value); } catch { /* 保留只读值供手动复制 */ }
    setFeedback("已复制");
  };
  const saveName = async () => {
    try {
      onSession(await access.updateWorkspace(name.trim()));
      await refresh();
      setFeedback("已保存");
    } catch {
      setError("无法保存，请稍后重试。");
    }
  };
  const updateMember = async (member: Member, value: Role) => {
    try { await access.updateMember(member.user_id, value); await refresh(); }
    catch { setError("无法更新成员权限，请稍后重试。"); }
  };
  const removeMember = async (member: Member) => {
    try { await access.removeMember(member.user_id); await refresh(); }
    catch { setError("无法移除该成员，请稍后重试。"); }
  };
  const createLink = async () => {
    try {
      const invitation = await access.invite(role);
      setLink(`${location.origin}${location.pathname}?invite=${invitation.token}`);
      setFeedback("链接已生成");
    } catch {
      setError("无法创建邀请链接，请稍后重试。");
    }
  };
  const openDelete = () => { setDeleteName(""); setDeleteError(""); setDeleteOpen(true); };
  const closeDelete = () => {
    if (!deleting) {
      setDeleteOpen(false);
      deleteTrigger.current?.focus();
    }
  };
  const deleteCurrentWorkspace = async () => {
    if (!data || deleteName !== data.workspace.name) return;
    setDeleting(true);
    setDeleteError("");
    try {
      onWorkspaceDeleted(await access.deleteWorkspace(deleteName));
    } catch {
      setDeleteError("无法删除工作区，请检查名称后重试。");
      setDeleting(false);
    }
  };

  if (loading && !data) return <main className="workspace-management-page"><p className="workspace-management-loading" role="status">正在读取工作区信息…</p></main>;
  if (!data) return <main className="workspace-management-page"><p className="access-error" role="alert">{error}</p></main>;
  const otherMembers = data.members.filter(member => !member.is_self);

  return <main className="workspace-management-page">
    <h1>工作区管理</h1>
    <section className="workspace-management-section workspace-identity" aria-labelledby="workspace-identity-title">
      <div className="workspace-section-head"><h2 id="workspace-identity-title">工作区信息</h2></div>
      <div className="workspace-identity-fields"><label className="workspace-field">工作区名称<div className="workspace-field-row"><input aria-label="工作区名称" value={name} disabled={!admin} onChange={event => setName(event.target.value)} maxLength={255} /><button className="button-primary" disabled={!admin || !name.trim() || name.trim() === data.workspace.name} onClick={() => void saveName()}><Icon name="save" />保存</button></div></label>
      <div className="workspace-field">固定 ID<div className="workspace-id-row"><code>{data.workspace.id}</code><button type="button" onClick={() => void copy(data.workspace.id)}><Icon name="copy" />复制</button></div></div></div>
    </section>
    {feedback && <p className="workspace-feedback" role="status">{feedback}</p>}
    {error && <p className="access-error workspace-feedback" role="alert">{error}</p>}
    <section className="workspace-management-section workspace-members" aria-labelledby="workspace-members-title"><div className="workspace-section-head"><h2 id="workspace-members-title">成员</h2><span>{data.members.length} 位成员</span></div><div className="workspace-member-list">{otherMembers.length === 0 && <p className="workspace-empty">暂无其他成员</p>}{data.members.map(member => <div className="workspace-member" key={member.user_id}><div className="workspace-member-copy"><b>{member.is_self ? "你" : member.email}</b><small>{member.is_self ? member.email : ""}</small></div>{member.is_self ? <span>{access.roleLabel[member.role]}</span> : <>{admin ? <select aria-label={`${member.email}的权限`} value={member.role} onChange={event => void updateMember(member, event.target.value as Role)}><option value="admin">管理员</option><option value="editor">可编辑</option><option value="viewer">仅可查看</option></select> : <span>{access.roleLabel[member.role]}</span>}{admin && <button type="button" className="text-button workspace-remove" onClick={() => void removeMember(member)}><Icon name="trash" />移除</button>}</>}</div>)}</div></section>
    <section className="workspace-management-section workspace-invite" aria-labelledby="workspace-invite-title"><div className="workspace-section-head"><h2 id="workspace-invite-title">邀请成员</h2></div><div className="workspace-invite-form"><label className="workspace-field">权限<select aria-label="邀请权限" disabled={!admin} value={role} onChange={event => setRole(event.target.value as "editor" | "viewer")}><option value="editor">可编辑</option><option value="viewer">仅可查看</option></select></label><button type="button" className="button-primary" disabled={!admin} onClick={() => void createLink()}><Icon name="link" />创建链接</button></div>{link && <label className="workspace-link-field">邀请链接<input readOnly value={link} aria-label="邀请链接" onFocus={event => event.currentTarget.select()} onClick={event => void copy(event.currentTarget.value)} /></label>}</section>
    {admin && <section className="workspace-management-section workspace-danger-zone" aria-labelledby="workspace-delete-title"><h2 id="workspace-delete-title">删除工作区</h2><button ref={deleteTrigger} type="button" className="button-danger" onClick={openDelete}><Icon name="trash" />删除工作区</button></section>}
    {deleteOpen && <div className="workspace-confirm-layer" role="alertdialog" aria-modal="true" aria-labelledby="workspace-delete-confirm-title"><section ref={deleteDialog} className="workspace-confirm-card"><h2 id="workspace-delete-confirm-title">删除工作区？</h2><p>将永久删除当前工作区及其全部账本数据、成员和邀请。</p><label className="workspace-field">输入工作区名称<input ref={deleteInput} aria-label="输入工作区名称" autoComplete="off" value={deleteName} onChange={event => setDeleteName(event.target.value)} /></label>{deleteError && <p className="access-error" role="alert">{deleteError}</p>}<div className="workspace-confirm-actions"><button type="button" disabled={deleting} onClick={closeDelete}>取消</button><button type="button" className="button-danger" disabled={deleting || deleteName !== data.workspace.name} onClick={() => void deleteCurrentWorkspace()}>{deleting ? "正在删除…" : "删除工作区"}</button></div></section></div>}
  </main>;
}

export function AccessApp() {
  const [state, setState] = useState<Session | null>(null); const [route, setRoute] = useState<"ledger" | "create" | "members">(() => routeForPath(location.pathname)); const [inviteToken, setInviteToken] = useState(() => new URLSearchParams(location.search).get("invite")); const [signInForInvite, setSignInForInvite] = useState(false); const [mobileAccountOpen, setMobileAccountOpen] = useState(false); const [workspaceError, setWorkspaceError] = useState("");
  const applySession = async (value: Session) => {
    let next = value;
    const requestedWorkspace = parseWorkspacePath(location.pathname)?.workspaceId;
    if (requestedWorkspace && requestedWorkspace !== value.active_workspace_id) {
      try {
        next = await access.selectWorkspace(requestedWorkspace);
        setWorkspaceError("");
      } catch {
        setWorkspaceError("无法打开该工作区，请检查权限后重试。");
      }
    }
    if (!next.active_workspace_id && next.workspaces.length > 0) {
      try {
        next = await access.selectWorkspace(next.workspaces[0].id);
      } catch {
        setWorkspaceError("无法打开该工作区，请稍后重试。");
      }
    }
    setState(next);
    setRoute(routeForPath(location.pathname));
    setSignInForInvite(false);
    if (next.active_workspace_id) {
      const target = `${workspaceUrl(next.active_workspace_id, location.pathname)}${location.search}${location.hash}`;
      const current = `${location.pathname}${location.search}${location.hash}`;
      if (target !== current) history.replaceState({}, "", target);
    }
  };
  useEffect(() => { access.session().then(value => void applySession(value)).catch(() => undefined); const close = () => setMobileAccountOpen(false); const syncRoute = () => setRoute(routeForPath(location.pathname)); window.addEventListener("mobile-menu-toggled", close); window.addEventListener("popstate", syncRoute); return () => { window.removeEventListener("mobile-menu-toggled", close); window.removeEventListener("popstate", syncRoute); }; }, []);
  const clearInvite = () => { history.replaceState({}, "", location.pathname); setInviteToken(null); setSignInForInvite(false); };
  if (inviteToken && !signInForInvite) return <Invite token={inviteToken} session={state} onSession={value => { setInviteToken(null); void applySession(value); }} onSignIn={() => { if (state) clearInvite(); else setSignInForInvite(true); }} />;
  if (!state) return <Auth onSession={value => void applySession(value)} />;
  if (!state.active_workspace_id && state.workspaces.length > 0) return <WorkspaceSelectionError onRetry={() => void applySession(state)} />;
  if (route === "create") return <Create onSession={value => void applySession(value)} onBack={() => setRoute("ledger")} showBack />;
  const active = state.workspaces.find(w => w.id === state.active_workspace_id);
  const footer = <div className="sidebar-footer"><div className="workspace-panel"><div className="account-line"><span className="account-avatar" aria-hidden="true">{state.user.email.slice(0, 1).toUpperCase()}</span><span className="account-email">{state.user.email}</span></div><label className="workspace-switcher"><span>当前工作区</span><select aria-label="当前工作区" value={state.active_workspace_id ?? ""} onChange={async e => { if (e.target.value === "__create__") { setRoute("create"); return; } setWorkspaceError(""); try { const next = await access.selectWorkspace(e.target.value); if (!next.active_workspace_id) throw new Error("workspace_missing"); const target = workspacePath(next.active_workspace_id, workspaceChildPath(location.pathname) || "/"); history.pushState({}, "", target); setState(next); setRoute(routeForPath(target)); window.dispatchEvent(new PopStateEvent("popstate")); } catch { setWorkspaceError("无法切换工作区，请稍后重试。"); } }}>{state.workspaces.map(w => <option key={w.id} value={w.id}>{w.name} · {access.roleLabel[w.role]}</option>)}<option value="__create__">＋ 创建工作区</option></select></label>{workspaceError && <p className="access-error" role="alert">{workspaceError}</p>}<button type="button" className="logout-link" onClick={async () => { await access.logout(); setState(null); }}><Icon name="logout" />退出登录</button></div></div>;
  const openWorkspaceManagement = () => { if (!state.active_workspace_id) return; const target = workspacePath(state.active_workspace_id, "/workspace-management"); history.pushState({}, "", target); setRoute("members"); window.dispatchEvent(new PopStateEvent("popstate")); };
  const leaveWorkspaceManagement = () => { if (route === "members") setRoute("ledger"); };
  const workspacePage = <WorkspaceManagement activeRole={active?.role ?? "viewer"} onSession={value => void applySession(value)} onWorkspaceDeleted={value => { setRoute("ledger"); setState(value); if (value.active_workspace_id) { history.replaceState({}, "", workspacePath(value.active_workspace_id, "/")); window.dispatchEvent(new PopStateEvent("popstate")); } else history.replaceState({}, "", "/"); }} />;
  const mobileAccount = <div className="mobile-account-control"><button type="button" className="mobile-account-button" aria-expanded={mobileAccountOpen} aria-controls="mobile-account-panel" aria-label={`账户 ${state.user.email}`} onClick={() => { const menu = document.querySelector<HTMLButtonElement>(".menu-toggle"); if (menu?.getAttribute("aria-expanded") === "true") menu.click(); setMobileAccountOpen(open => !open); }}>{state.user.email.slice(0, 1).toUpperCase()}</button>{mobileAccountOpen && <div id="mobile-account-panel" className="mobile-account-panel"><div className="mobile-account-email">{state.user.email}</div>{footer}<button type="button" className="mobile-panel-close" onClick={() => setMobileAccountOpen(false)}>关闭</button></div>}</div>;
  return <>{state.active_workspace_id ? <App key={state.active_workspace_id} workspaceId={state.active_workspace_id} sidebarFooter={footer} mobileAccount={mobileAccount} workspacePage={workspacePage} workspaceManagementActive={route === "members"} onWorkspaceManagement={openWorkspaceManagement} onLedgerNavigation={leaveWorkspaceManagement} /> : <Create onSession={value => void applySession(value)} onBack={() => setRoute("ledger")} showBack={false} />}</>;
}

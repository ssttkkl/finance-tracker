import { FormEvent, useEffect, useState } from "react";
import { App } from "./App";
import * as access from "./api/access";
import type { InvitationPreview, Member, Role, Session } from "./api/access";

function Icon({ name }: { name: "arrow-left" | "link" | "logout" | "plus" | "users" }) {
  const paths = {
    "arrow-left": <path d="m14 6-6 6 6 6M8 12h10" />,
    link: <><path d="M10 13a5 5 0 0 0 7.1.1l2-2a5 5 0 0 0-7.1-7.1l-1.1 1.1" /><path d="M14 11a5 5 0 0 0-7.1-.1l-2 2A5 5 0 0 0 12 20l1.1-1.1" /></>,
    logout: <><path d="M10 5H5v14h5" /><path d="m14 8 4 4-4 4M18 12H9" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    users: <><path d="M16 20v-1a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v1" /><circle cx="9" cy="7" r="4" /><path d="M22 20v-1a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8" /></>,
  }[name];
  return <svg className="access-icon" aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{paths}</svg>;
}

function BackButton({ onClick, label = "返回账本" }: { onClick: () => void; label?: string }) {
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

function Create({ onSession, onBack }: { onSession: (value: Session) => void; onBack: () => void }) {
  const [name, setName] = useState(""); const [error, setError] = useState(""); const [loading, setLoading] = useState(false);
  return <main className="access-page"><BackButton onClick={onBack} /><p className="access-eyebrow">新工作区</p><h1>创建工作区</h1><p className="access-muted">创建后你将成为首位管理员，可以再邀请其他成员。</p><section className="access-panel"><form className="access-form" onSubmit={async e => { e.preventDefault(); setLoading(true); setError(""); try { onSession(await access.createWorkspace(name)); } catch { setError("无法创建工作区，请检查名称后重试。"); } finally { setLoading(false); } }}><label>工作区名称<input value={name} onChange={e => setName(e.target.value)} required maxLength={255} /></label><button className="button-primary" disabled={loading}>{loading ? "正在创建…" : "创建工作区"}</button>{error && <p className="access-error" role="alert">{error}</p>}</form></section></main>;
}

function Members({ onBack }: { onBack: () => void }) {
  const [data, setData] = useState<{ workspace: { name: string }; members: Member[] } | null>(null); const [role, setRole] = useState<"editor" | "viewer">("editor"); const [link, setLink] = useState(""); const [error, setError] = useState("");
  const refresh = () => access.members().then(setData).catch(() => setError("无法读取成员信息，请稍后重试。"));
  useEffect(() => { refresh(); }, []);
  if (!data) return <main className="access-page"><BackButton onClick={onBack} /><p className="access-muted">正在读取成员信息…</p>{error && <p className="access-error" role="alert">{error}</p>}</main>;
  return <main className="access-page"><BackButton onClick={onBack} /><p className="access-eyebrow">成员管理</p><h1>{data.workspace.name}的成员</h1><p className="access-muted">管理员可以调整成员权限、移除成员并创建邀请链接。</p><section className="access-panel"><div className="access-member-list">{data.members.map(member => <div className="access-member" key={member.user_id}><div><b>{member.is_self ? "你" : member.email}</b><small>{member.is_self ? member.email : ""}</small></div>{member.is_self ? <span>{access.roleLabel[member.role]}</span> : <><select aria-label={`${member.email}的权限`} value={member.role} onChange={async e => { try { await access.updateMember(member.user_id, e.target.value as Role); refresh(); } catch { setError("无法更新成员权限。"); } }}><option value="admin">管理员</option><option value="editor">可编辑</option><option value="viewer">仅可查看</option></select><button className="text-button" onClick={async () => { try { await access.removeMember(member.user_id); refresh(); } catch { setError("无法移除该成员。"); } }}>移除</button></>}</div>)}</div><div className="access-invite"><div><span className="access-invite-title"><Icon name="link" />创建邀请链接</span><small>邀请仅能使用一次，并在 7 天后失效。</small></div><select aria-label="邀请权限" value={role} onChange={e => setRole(e.target.value as "editor" | "viewer")}><option value="editor">可编辑</option><option value="viewer">仅可查看</option></select><button className="button-primary" onClick={async () => { try { setLink(`${location.origin}${location.pathname}?invite=${(await access.invite(role)).token}`); } catch { setError("无法创建邀请链接。"); } }}>创建链接</button></div>{link && <label className="access-link-field">邀请链接<input readOnly value={link} aria-label="邀请链接" onFocus={e => e.currentTarget.select()} /></label>}{error && <p className="access-error" role="alert">{error}</p>}</section></main>;
}

export function AccessApp() {
  const [state, setState] = useState<Session | null>(null); const [route, setRoute] = useState<"ledger" | "create" | "members">("ledger"); const [inviteToken, setInviteToken] = useState(() => new URLSearchParams(location.search).get("invite")); const [signInForInvite, setSignInForInvite] = useState(false);
  useEffect(() => { access.session().then(setState).catch(() => undefined); }, []);
  const clearInvite = () => { history.replaceState({}, "", location.pathname); setInviteToken(null); setSignInForInvite(false); };
  if (inviteToken && !signInForInvite) return <Invite token={inviteToken} session={state} onSession={value => { setState(value); setInviteToken(null); setRoute("ledger"); }} onSignIn={() => { if (state) clearInvite(); else setSignInForInvite(true); }} />;
  if (!state) return <Auth onSession={value => { setState(value); setSignInForInvite(false); }} />;
  if (route === "create") return <Create onSession={value => { setState(value); setRoute("ledger"); }} onBack={() => setRoute("ledger")} />;
  if (route === "members") return <Members onBack={() => setRoute("ledger")} />;
  const active = state.workspaces.find(w => w.id === state.active_workspace_id);
  return <><div className="workspace-bar"><select aria-label="当前工作区" value={state.active_workspace_id ?? ""} onChange={async e => setState(await access.selectWorkspace(e.target.value))}>{state.workspaces.map(w => <option key={w.id} value={w.id}>{w.name} · {access.roleLabel[w.role]}</option>)}</select><button type="button" onClick={() => setRoute("create")}><Icon name="plus" />创建工作区</button>{active?.role === "admin" && <button type="button" onClick={() => setRoute("members")}><Icon name="users" />管理成员</button>}<button type="button" onClick={async () => { await access.logout(); setState(null); }}><Icon name="logout" />退出登录</button></div>{state.active_workspace_id ? <App /> : <Create onSession={setState} onBack={() => setRoute("ledger")} />}</>;
}

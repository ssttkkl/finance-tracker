import type { Account, CashRecordDetail, Evidence, EvidenceMember, LedgerOptions } from "../api/types";
import { useEffect, useRef, useState } from "react";
import { formatOccurredAt } from "../format";
import { RecordDrawer } from "./RecordDrawer";
import { UiIcon } from "./UiIcon";

type Props = {
  evidence: Evidence | null;
  loading: boolean;
  error: boolean;
  editing?: boolean;
  editMode?: "new" | "edit";
  editDetail?: CashRecordDetail | null;
  editAccounts?: Account[];
  editOptions?: LedgerOptions;
  editRelationOpen?: boolean;
  editLoading?: boolean;
  editLoadError?: boolean;
  onClose: () => void;
  onRetry: () => void;
  onEditRetry?: () => void;
  onEditRecord?: (id: string) => void;
  onAddRelation?: (id: string) => void;
  onCancelRelation?: (id: string) => void;
  onRecordSaved?: (detail: CashRecordDetail, created: boolean) => void;
  onRecordDeleted?: (id: string) => void;
};
const CLOSE_ANIMATION_MS = 160;

function isBankSecurityTransfer(projection: Evidence["projection"]): boolean {
  return projection.transfer_subtype === "bank_security_transfer";
}

function isRelatedProjection(projection: Evidence["projection"]): boolean {
  return isBankSecurityTransfer(projection) || projection.member_count !== 1 || projection.composition.length > 0;
}

function economicTypeLabel(projection: Evidence["projection"]): string {
  if (isBankSecurityTransfer(projection)) return "银证转账";
  return projection.economic_type === "expense" ? "消费" : projection.economic_type === "income" ? "收入" : projection.economic_type === "internal_transfer" ? "个人转账" : "未提供";
}

function relatedRecordLabel(member: EvidenceMember): string {
  if (member.roles.includes("refund")) return "退款";
  if (member.roles.includes("mirror")) return "同笔支付";
  if (member.roles.includes("transfer")) return "个人转账";
  return "关联记录";
}

function relatedRecordImpact(member: EvidenceMember): string {
  if (member.roles.includes("refund")) return "已冲销本次消费。";
  if (member.roles.includes("mirror")) return "已合并到本次收支，不重复计入。";
  if (member.roles.includes("transfer")) return "已按个人转账合并。";
  return "已合并到本次收支。";
}

function signedAmount(amount: string): string {
  return amount.startsWith("-") || amount.startsWith("+") || amount === "0" ? amount : `+${amount}`;
}

function sourceLabel(sourceType: string | null): string {
  return sourceType || "-";
}

function projectionSourceLabel(evidence: Evidence): string {
  if (!isRelatedProjection(evidence.projection)) return sourceLabel(evidence.root_record.source_type);
  const sourceTypes = Array.from(new Set(
    evidence.members.flatMap((member) => member.source_type ? [member.source_type] : []),
  ));
  return sourceTypes.length ? sourceTypes.join("、") : "-";
}

function projectionRelationLabel(projection: Evidence["projection"]): string {
  return isBankSecurityTransfer(projection) ? "银证转账" : "关联记录合并";
}

export function EvidenceDetail({ evidence, loading, error, editing = false, editMode = "edit", editDetail, editAccounts = [], editOptions = { record_types: [], relation_types: [] }, editRelationOpen = false, editLoading = false, editLoadError = false, onClose, onRetry, onEditRetry, onEditRecord, onAddRelation, onCancelRelation, onRecordSaved, onRecordDeleted }: Props) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const closeTimer = useRef<number | null>(null);
  const [closing, setClosing] = useState(false);
  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => () => { if (closeTimer.current !== null) window.clearTimeout(closeTimer.current); }, []);
  const requestClose = () => {
    if (editing) { onClose(); return; }
    if (closing) return;
    setClosing(true);
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { onClose(); return; }
    closeTimer.current = window.setTimeout(onClose, CLOSE_ANIMATION_MS);
  };
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); requestClose(); return; }
      if (event.key !== "Tab") return;
      const focusable = Array.from(node.querySelectorAll<HTMLElement>("button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])"));
      if (!focusable.length) return;
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    node.addEventListener("keydown", handleKeyDown);
    return () => { node.removeEventListener("keydown", handleKeyDown); };
  }, [closing, editing, onClose]);
  const root = evidence?.root_record;
  const relatedMembers = evidence?.members?.filter((member) => member.id !== root?.id) ?? [];

  return <div className={`evidence-layer${closing ? " is-closing" : ""}`}>
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭收支详情" tabIndex={-1} onPointerDown={(event) => event.preventDefault()} onClick={requestClose} />
    <aside ref={dialog} className="evidence evidence-panel" data-focus-trap="active" data-state={closing ? "closing" : "open"} role="dialog" aria-modal="true" aria-label={editing ? (editMode === "new" ? "新建流水" : "编辑流水") : "收支详情"}>
      {editing ? <RecordDrawer embedded mode={editMode} detail={editDetail} accounts={editAccounts} options={editOptions} initialRelationOpen={editRelationOpen} loading={editLoading} loadError={editLoadError} onRetry={onEditRetry} onClose={onClose} onSaved={onRecordSaved ?? (() => undefined)} onDeleted={onRecordDeleted ?? (() => undefined)} /> : <>
      <header><div><p className="evidence-eyebrow">收支账本</p><h2>收支详情</h2></div><div className="drawer-header-actions">{root ? <button type="button" className="icon-only-button" aria-label="编辑" title="编辑" onClick={() => onEditRecord?.(root.id)}><UiIcon name="pencil" /></button> : null}<button ref={closeButton} type="button" className="icon-only-button" aria-label="关闭收支详情" title="关闭" onClick={requestClose}><UiIcon name="x" /></button></div></header>
      {loading ? <p className="evidence-state" role="status">正在读取收支详情…</p> : null}
      {error ? <div className="evidence-state evidence-state-error" role="alert"><p>无法读取收支详情。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {evidence && !root ? <div className="evidence-state evidence-state-error" role="alert"><p>收支详情不完整，请重试。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {root && evidence ? <div className="evidence-content">
        <section className="evidence-section evidence-summary" aria-label="收支详情">
          <p className={`evidence-amount ${evidence.projection.economic_type === "income" ? "inflow" : "outflow"}`}>{evidence.projection.amount} <span>{evidence.projection.currency}</span></p>
          <p className="evidence-economic-type">{economicTypeLabel(evidence.projection)}</p>
          {isRelatedProjection(evidence.projection) ? <p className="projection-source-detail">{projectionRelationLabel(evidence.projection)}</p> : null}
          <dl><dt>交易对方</dt><dd>{root.counterparty || "-"}</dd><dt>发生时间</dt><dd className="mono">{formatOccurredAt(root.occurred_at)}</dd><dt>账户</dt><dd>{root.account.name}</dd><dt>分类</dt><dd>{root.category || "未分类"}</dd><dt>备注</dt><dd>{root.note || "-"}</dd><dt>来源</dt><dd>{projectionSourceLabel(evidence)}</dd></dl>
        </section>
        {relatedMembers.length ? <section className="evidence-section evidence-related" aria-label="关联记录">
          <div className="section-heading"><h3>关联记录</h3>{root ? <button type="button" className="icon-only-button icon-quiet-button" aria-label="添加关联" title="添加关联" onClick={() => onAddRelation?.(root.id)}><UiIcon name="plus" /></button> : null}</div>
          <ul className="evidence-record-list">{relatedMembers.map((member) => { const relation = evidence.accepted_relations.find((item) => item.primary_record?.id === member.id || item.secondary_record?.id === member.id); return <li className={`evidence-record${member.roles.includes("refund") ? " is-refund" : ""}`} key={member.id}><dl><dt>关联类型</dt><dd>{relatedRecordLabel(member)}</dd><dt>金额</dt><dd className="mono">{signedAmount(member.amount)} {member.currency}</dd><dt>发生时间</dt><dd className="mono">{formatOccurredAt(member.occurred_at)}</dd><dt>账户</dt><dd>{member.account.name}</dd><dt>交易对方</dt><dd>{member.counterparty || "-"}</dd><dt>分类</dt><dd>{member.category || "未分类"}</dd><dt>备注</dt><dd>{member.note || "-"}</dd><dt>来源</dt><dd>{sourceLabel(member.source_type)}</dd><dt>影响</dt><dd>{relatedRecordImpact(member)}</dd></dl><div className="related-actions"><button type="button" className="icon-only-button icon-quiet-button" aria-label="编辑流水" title="编辑流水" onClick={() => onEditRecord?.(member.id)}><UiIcon name="pencil" /></button>{relation ? <button type="button" className="text-button" onClick={() => onCancelRelation?.(relation.id)}>取消关联</button> : null}</div></li>; })}</ul>
        </section> : null}
        {!relatedMembers.length && root ? <section className="evidence-section evidence-related" aria-label="关联记录"><div className="section-heading"><h3>关联记录</h3><button type="button" className="icon-only-button icon-quiet-button" aria-label="添加关联" title="添加关联" onClick={() => onAddRelation?.(root.id)}><UiIcon name="plus" /></button></div><p className="empty-related">暂无关联记录</p></section> : null}
      </div> : null}
      </>}
    </aside>
  </div>;
}

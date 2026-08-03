import type { Evidence, EvidenceMember } from "../api/types";
import { useEffect, useRef, useState } from "react";
import { formatOccurredAt } from "../format";

type Props = { evidence: Evidence | null; loading: boolean; error: boolean; onClose: () => void; onRetry: () => void };
const CLOSE_ANIMATION_MS = 160;

function isRelatedProjection(projection: Evidence["projection"]): boolean {
  return projection.member_count !== 1 || projection.composition.length > 0;
}

function economicTypeLabel(type: Evidence["projection"]["economic_type"]): string {
  return type === "expense" ? "消费" : type === "income" ? "收入" : type === "internal_transfer" ? "个人转账" : "未提供";
}

function relatedRecordLabel(member: EvidenceMember): string {
  if (member.roles.includes("refund")) return "退款";
  if (member.roles.includes("mirror")) return "同笔支付";
  if (member.roles.includes("transfer")) return "内部资金移动";
  return "关联记录";
}

function relatedRecordImpact(member: EvidenceMember): string {
  if (member.roles.includes("refund")) return "已冲销本次消费。";
  if (member.roles.includes("mirror")) return "已归并到本次收支，不重复计入。";
  if (member.roles.includes("transfer")) return "已按内部资金移动处理。";
  return "已纳入本次收支的形成过程。";
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

export function EvidenceDetail({ evidence, loading, error, onClose, onRetry }: Props) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const closeTimer = useRef<number | null>(null);
  const [closing, setClosing] = useState(false);
  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => () => { if (closeTimer.current !== null) window.clearTimeout(closeTimer.current); }, []);
  const requestClose = () => {
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
  }, [closing, onClose]);
  const root = evidence?.root_record;
  const relatedMembers = evidence?.members?.filter((member) => member.id !== root?.id) ?? [];

  return <div className={`evidence-layer${closing ? " is-closing" : ""}`}>
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭证据详情" tabIndex={-1} onPointerDown={(event) => event.preventDefault()} onClick={requestClose} />
    <aside ref={dialog} className="evidence evidence-panel" data-focus-trap="active" data-state={closing ? "closing" : "open"} role="dialog" aria-modal="true" aria-label="证据详情">
      <header><div><p className="evidence-eyebrow">收支账本</p><h2>收支详情</h2></div><button ref={closeButton} type="button" aria-label="关闭证据详情" onClick={requestClose}>关闭</button></header>
      {loading ? <p className="evidence-state" role="status">正在读取收支详情…</p> : null}
      {error ? <div className="evidence-state evidence-state-error" role="alert"><p>无法读取收支详情。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {evidence && !root ? <div className="evidence-state evidence-state-error" role="alert"><p>证据详情不完整，请重试或检查收支投影。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {root && evidence ? <div className="evidence-content">
        <section className="evidence-section evidence-summary" aria-label="收支详情">
          <p className={`evidence-amount ${evidence.projection.economic_type === "income" ? "inflow" : "outflow"}`}>{evidence.projection.amount} <span>{evidence.projection.currency}</span></p>
          <p className="evidence-economic-type">{economicTypeLabel(evidence.projection.economic_type)}</p>
          {isRelatedProjection(evidence.projection) ? <p className="projection-source-detail">关系投影</p> : null}
          <dl><dt>交易对方</dt><dd>{root.counterparty || "-"}</dd><dt>发生时间</dt><dd className="mono">{formatOccurredAt(root.occurred_at)}</dd><dt>账户</dt><dd>{root.account.name}</dd><dt>分类</dt><dd>{root.category || "未分类"}</dd><dt>备注</dt><dd>{root.note || "-"}</dd><dt>来源</dt><dd>{projectionSourceLabel(evidence)}</dd></dl>
        </section>
        {relatedMembers.length ? <section className="evidence-section evidence-related" aria-label="关联记录">
          <h3>关联记录</h3>
          <ul className="evidence-record-list">{relatedMembers.map((member) => <li className={`evidence-record${member.roles.includes("refund") ? " is-refund" : ""}`} key={member.id}><dl><dt>关联类型</dt><dd>{relatedRecordLabel(member)}</dd><dt>金额</dt><dd className="mono">{signedAmount(member.amount)} {member.currency}</dd><dt>发生时间</dt><dd className="mono">{formatOccurredAt(member.occurred_at)}</dd><dt>账户</dt><dd>{member.account.name}</dd><dt>交易对方</dt><dd>{member.counterparty || "-"}</dd><dt>分类</dt><dd>{member.category || "未分类"}</dd><dt>备注</dt><dd>{member.note || "-"}</dd><dt>来源</dt><dd>{sourceLabel(member.source_type)}</dd><dt>影响</dt><dd>{relatedRecordImpact(member)}</dd></dl></li>)}</ul>
        </section> : null}
      </div> : null}
    </aside>
  </div>;
}

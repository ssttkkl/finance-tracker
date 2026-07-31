import type { Evidence, EvidenceRecord, InactiveRelationHint } from "../api/types";
import { Fragment, useEffect, useRef } from "react";
import { formatOccurredAt } from "../format";

type Props = { evidence: Evidence | null; loading: boolean; error: boolean; onClose: () => void; onRetry: () => void };

const relationKinds: Record<string, string> = {
  payment_mirror: "同笔支付关系",
  transfer_pair: "转账配对关系",
  refund_offset: "退款冲销关系",
  credit_repayment: "信用账户还款关系",
};
const relationStatuses: Record<InactiveRelationHint["status"], string> = {
  pending_review: "待审核",
  rejected: "已驳回",
  superseded: "已替代",
};
const memberRoles: Record<string, string> = { root: "主记录", mirror: "同笔支付", refund: "退款", transfer: "内部转账" };

function recordSummary(record: EvidenceRecord | null): string {
  if (!record) return "未提供关联记录摘要";
  return `${record.record_id}，${formatOccurredAt(record.occurred_at)}，${record.account.name}，${record.amount} ${record.currency}`;
}

function snapshotEntries(snapshot: Evidence["root_record"]["source_snapshot"]) {
  if (!snapshot || !Object.keys(snapshot).length) return <p>此记录未提供来源行快照。</p>;
  return <dl>{Object.entries(snapshot).map(([key, value]) => <Fragment key={key}><dt>{key}</dt><dd>{String(value)}</dd></Fragment>)}</dl>;
}

export function EvidenceDetail({ evidence, loading, error, onClose, onRetry }: Props) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      if (event.key !== "Tab") return;
      const focusable = Array.from(node.querySelectorAll<HTMLElement>("button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex='-1'])"));
      if (!focusable.length) return;
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    const keepPointerFocus = (event: PointerEvent) => {
      if (node.contains(event.target as Node)) return;
      event.preventDefault(); closeButton.current?.focus();
    };
    node.addEventListener("keydown", handleKeyDown);
    document.addEventListener("pointerdown", keepPointerFocus, true);
    return () => { node.removeEventListener("keydown", handleKeyDown); document.removeEventListener("pointerdown", keepPointerFocus, true); };
  }, [onClose]);
  const root = evidence?.root_record;
  return <aside ref={dialog} className="evidence evidence-panel" data-focus-trap="active" role="dialog" aria-modal="true" aria-label="证据详情">
    <header><h2>证据详情</h2><button ref={closeButton} type="button" aria-label="关闭证据详情" onClick={onClose}>关闭</button></header>
    {loading ? <p role="status">正在读取证据详情…</p> : null}
    {error ? <div role="alert"><p>无法读取证据详情。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
    {evidence && !root ? <div role="alert"><p>证据详情不完整，请重试或检查收支投影。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
    {root ? <div className="evidence-content">
      <section className="evidence-section" aria-label="投影结果"><h3>投影结果</h3><dl><dt>投影标识</dt><dd className="mono">{evidence.projection.projection_id}</dd><dt>净额</dt><dd className="mono">{evidence.projection.amount} {evidence.projection.currency}</dd><dt>经济类型</dt><dd>{evidence.projection.economic_type === "expense" ? "消费" : evidence.projection.economic_type === "income" ? "收入" : "内部转账"}</dd></dl></section>
      <section className="evidence-section" aria-label="主记录"><h3>主记录</h3><dl><dt>发生时间</dt><dd className="mono">{formatOccurredAt(root.occurred_at)}</dd><dt>账户</dt><dd>{root.account.name}</dd><dt>交易对方</dt><dd>{root.counterparty || "未提供"}</dd><dt>分类</dt><dd>{root.category || "未分类"}</dd><dt>备注</dt><dd>{root.note || "未提供"}</dd><dt>金额</dt><dd className="mono">{root.amount} {root.currency}</dd><dt>导入渠道</dt><dd>{root.source_type || "未提供"}</dd><dt>业务行标识</dt><dd className="mono">{root.record_id || "未提供"}</dd></dl></section>
      <section className="evidence-section" aria-label="来源行快照"><h3>来源行快照</h3>{snapshotEntries(root.source_snapshot)}</section>
      <section className="evidence-section" aria-label="成员流水"><h3>成员流水</h3>{evidence.members.length ? <ul>{evidence.members.map((member) => <li key={member.id}>{formatOccurredAt(member.occurred_at)}，{member.account.name}，{member.amount} {member.currency}（{member.roles.map((role) => memberRoles[role] ?? "未识别角色").join("、")}）</li>)}</ul> : <p>此投影未提供成员流水。</p>}</section>
      <section className="evidence-section" aria-label="已采用关系"><h3>已采用关系</h3>{evidence.accepted_relations.length ? <ul>{evidence.accepted_relations.map((relation) => <li key={relation.id}>{relationKinds[relation.kind] ?? "未识别的关系类型"}{relation.rule_id ? `（${relation.rule_id}）` : ""}</li>)}</ul> : <p>此投影没有已采用关系。</p>}</section>
      <section className="evidence-section" aria-label="未生效关系提示"><h3>未生效关系提示</h3>{evidence.inactive_relation_hints.length ? <ul>{evidence.inactive_relation_hints.map((relation) => <li key={relation.id}>{relationKinds[relation.kind] ?? "未识别的关系类型"}：{relationStatuses[relation.status]}。{recordSummary(relation.primary_record)}；{recordSummary(relation.secondary_record)}</li>)}</ul> : <p>此投影没有未生效关系提示。</p>}</section>
      <section className="evidence-section" aria-label="退款时间线"><h3>退款时间线</h3>{evidence.refund_timeline.length ? <ul>{evidence.refund_timeline.map((refund) => <li key={refund.record_id}>{formatOccurredAt(refund.occurred_at)}，{refund.amount} {refund.currency}，{refund.source_type || "未提供"}</li>)}</ul> : <p>此投影没有退款。</p>}</section>
    </div> : null}
  </aside>;
}

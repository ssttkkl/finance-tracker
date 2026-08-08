import { Fragment, useEffect, useRef, useState } from "react";
import type { InvestmentEvidence } from "../api/types";
import { formatOccurredAt } from "../format";
import { eventTypeLabel, sourceFieldLabel, sourceFieldValue } from "../investmentLabels";

type Props = { evidence: InvestmentEvidence | null; loading: boolean; error: boolean; onClose: () => void; onRetry: () => void };

function asset(asset: { ticker: string | null; amount: string | null }, currency: string) {
  return asset.amount ? `${asset.amount} ${asset.ticker ?? currency}` : "—";
}

export function InvestmentEvidenceDetail({ evidence, loading, error, onClose, onRetry }: Props) {
  const closeButton = useRef<HTMLButtonElement>(null);
  const dialog = useRef<HTMLElement>(null);
  const [closing, setClosing] = useState(false);
  useEffect(() => { closeButton.current?.focus(); }, []);
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); onClose(); return; }
      if (event.key !== "Tab") return;
      const focusable = Array.from(node.querySelectorAll<HTMLElement>("button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), [tabindex]:not([tabindex='-1'])"));
      if (!focusable.length) return;
      const first = focusable[0]; const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    node.addEventListener("keydown", onKeyDown);
    return () => node.removeEventListener("keydown", onKeyDown);
  }, [onClose]);
  const requestClose = () => { if (!closing) { setClosing(true); onClose(); } };
  return <div className={`evidence-layer${closing ? " is-closing" : ""}`}>
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭详情" onClick={requestClose} />
    <aside ref={dialog} className="evidence evidence-panel investment-evidence" role="dialog" aria-modal="true" aria-label="投资详情">
      <header><div><h2>投资详情</h2></div><button ref={closeButton} type="button" onClick={requestClose}>关闭</button></header>
      {loading ? <p className="evidence-state" role="status">正在读取详情…</p> : null}
      {error ? <div className="evidence-state evidence-state-error" role="alert"><p>无法读取详情。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {evidence ? <div className="evidence-content"><section className="evidence-section" aria-label="投资信息"><p className="evidence-event-type">{eventTypeLabel(evidence.event)}</p><dl><dt>发生时间</dt><dd className="mono">{formatOccurredAt(evidence.event.occurred_at)}</dd><dt>账户</dt><dd>{evidence.event.account.name}</dd><dt>付出资产</dt><dd className="mono">{asset(evidence.event.from_asset, evidence.event.currency)}</dd><dt>换入资产</dt><dd className="mono">{asset(evidence.event.to_asset, evidence.event.currency)}</dd><dt>手续费</dt><dd className="mono">{asset({ ticker: evidence.event.commission.asset, amount: evidence.event.commission.amount }, evidence.event.currency)}</dd><dt>备注</dt><dd>{evidence.event.note || (evidence.event.source_type ? "导入记录" : "—")}</dd></dl></section><section className="evidence-section" aria-label="更多信息"><h3>更多信息</h3>{evidence.source_snapshot ? <dl>{Object.entries(evidence.source_snapshot).filter(([key]) => sourceFieldLabel(key)).map(([key, value]) => <Fragment key={key}><dt>{sourceFieldLabel(key)}</dt><dd className="mono">{sourceFieldValue(key, value)}</dd></Fragment>)}</dl> : <p className="muted">没有更多信息。</p>}</section><section className="evidence-section" aria-label="资金流向"><h3>资金流向</h3>{evidence.relations.length ? evidence.relations.map((relation) => <div className="investment-relation" key={`${relation.kind}:${relation.cash_record_id}`}><dl><dt>现金账户</dt><dd>{relation.cash_account.name}</dd><dt>金额</dt><dd className="mono">{relation.cash_amount} {relation.cash_currency}</dd><dt>发生时间</dt><dd className="mono">{formatOccurredAt(relation.cash_occurred_at)}</dd></dl></div>) : <p className="muted">没有对应的现金记录。</p>}</section></div> : null}
    </aside>
  </div>;
}

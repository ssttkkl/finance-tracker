import { useEffect, useRef, useState } from "react";
import type { InvestmentEvidence } from "../api/types";
import { formatOccurredAt } from "../format";
import { formatCommission, investmentAssetLines, signedRelatedAmount } from "../investmentDisplay";
import { eventTypeLabel } from "../investmentLabels";

type Props = { evidence: InvestmentEvidence | null; loading: boolean; error: boolean; onClose: () => void; onRetry: () => void };
type DetailFact = { label: string; value: string };

function sameInstant(left: string, right: string): boolean {
  const leftTime = new Date(left).getTime();
  const rightTime = new Date(right).getTime();
  return Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime === rightTime;
}

function absoluteAmount(value: string | null): string {
  return value?.replace(/^[+-]/, "") ?? "";
}

function cashFacts(evidence: InvestmentEvidence): DetailFact[] {
  const facts: DetailFact[] = [];
  const seenAccounts = new Set<string>();
  const eventAmounts = [evidence.event.from_asset.amount, evidence.event.to_asset.amount].filter((value): value is string => Boolean(value)).map(absoluteAmount);
  for (const relation of evidence.relations) {
    if (!seenAccounts.has(relation.cash_account.name)) {
      seenAccounts.add(relation.cash_account.name);
      facts.push({ label: "现金账户", value: relation.cash_account.name });
    }
    if (!eventAmounts.includes(absoluteAmount(relation.cash_amount))) {
      facts.push({ label: "现金金额", value: signedRelatedAmount(relation.cash_amount, relation.cash_currency, relation.direction) });
    }
    if (!sameInstant(relation.cash_occurred_at, evidence.event.occurred_at)) {
      facts.push({ label: "现金时间", value: formatOccurredAt(relation.cash_occurred_at) });
    }
  }
  const note = evidence.event.note || (evidence.event.source_type ? "导入记录" : "");
  if (note) facts.push({ label: "备注", value: note });
  return facts;
}

function CloseIcon() {
  return <svg className="ui-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18" /></svg>;
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
  const title = evidence ? eventTypeLabel(evidence.event) : "投资详情";
  const detailFacts = evidence ? cashFacts(evidence) : [];
  const detailLines = evidence ? investmentAssetLines(evidence.event) : [];
  return <div className={`evidence-layer${closing ? " is-closing" : ""}`}>
    <button type="button" className="evidence-backdrop" aria-label="点击遮罩关闭详情" onClick={requestClose} />
    <aside ref={dialog} className="evidence evidence-panel investment-evidence" role="dialog" aria-modal="true" aria-labelledby="investment-detail-title">
      <header><div><h2 id="investment-detail-title">{title}</h2>{evidence ? <p className="investment-detail-meta"><time dateTime={evidence.event.occurred_at}>{formatOccurredAt(evidence.event.occurred_at)}</time><span aria-hidden="true">·</span><span>{evidence.event.account.name}</span></p> : null}</div><button ref={closeButton} className="icon-button investment-detail-close" type="button" aria-label="关闭" onClick={requestClose}><CloseIcon /></button></header>
      {loading ? <p className="evidence-state" role="status">正在读取详情…</p> : null}
      {error ? <div className="evidence-state evidence-state-error" role="alert"><p>无法读取详情。</p><button type="button" onClick={onRetry}>重试</button></div> : null}
      {evidence ? <div className="evidence-content"><section className="evidence-section investment-detail-changes" aria-labelledby="investment-detail-changes-title"><h3 id="investment-detail-changes-title">资产变动</h3><dl>{detailLines.map((line) => <div className={`investment-detail-line ${line.direction}`} key={line.label}><dt>{line.label}</dt><dd className="mono">{line.value}</dd></div>)}{evidence.event.commission.amount && evidence.event.commission.amount !== "0" ? <div className="investment-detail-line fee"><dt>手续费</dt><dd className="mono">{formatCommission(evidence.event.commission.amount, evidence.event.commission.asset, evidence.event.currency)}</dd></div> : null}</dl></section>{detailFacts.length ? <dl className="investment-detail-supplement">{detailFacts.map((fact) => <div className="investment-detail-fact" key={`${fact.label}:${fact.value}`}><dt>{fact.label}</dt><dd>{fact.value}</dd></div>)}</dl> : null}</div> : null}
    </aside>
  </div>;
}

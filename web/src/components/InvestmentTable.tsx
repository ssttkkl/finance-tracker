import type { InvestmentEvent } from "../api/types";
import { formatOccurredAt } from "../format";
import { formatCommission, investmentAssetLines } from "../investmentDisplay";
import { eventTypeLabel } from "../investmentLabels";

type Props = { items: InvestmentEvent[]; loading?: boolean; onEvidence: (event: InvestmentEvent, source: HTMLButtonElement) => void };

function assetLines(event: InvestmentEvent) {
  return investmentAssetLines(event).map((line) => <span className={`asset-line mono ${line.direction}`} key={line.label}><span className="asset-key">{line.label}</span><span className="asset-value">{line.value}</span></span>);
}

function EvidenceIcon() {
  return <svg className="ui-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M3 12s3.5-5 9-5 9 5 9 5-3.5 5-9 5-9-5-9-5Z" /><circle cx="12" cy="12" r="2.5" /></svg>;
}

export function InvestmentTable({ items, loading = false, onEvidence }: Props) {
  return <div className="table-wrap investment-table-wrap"><table className="investment-table">
    <caption className="sr-only">投资事件列表</caption>
    <thead><tr><th scope="col">发生时间</th><th scope="col">账户</th><th scope="col">事件</th><th scope="col">资产变化</th><th scope="col">手续费</th><th scope="col">备注</th><th scope="col"><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" key={index}>{Array.from({ length: 7 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}</tr>) : items.map((event) => <tr className="investment-row" key={event.event_id} onClick={(click) => {
      if (window.matchMedia("(max-width: 820px)").matches && !(click.target instanceof Element && click.target.closest("button"))) {
        const trigger = click.currentTarget.querySelector<HTMLButtonElement>(".evidence-trigger");
        if (trigger) onEvidence(event, trigger);
      }
    }}>
      <td className="occurred-at mono" data-label="发生时间">{formatOccurredAt(event.occurred_at)}</td>
      <td className="account" data-label="账户">{event.account.name}</td>
      <td className="event-type" data-label="事件"><span>{eventTypeLabel(event)}</span></td>
      <td className="asset-direction" data-label="资产变化">{assetLines(event)}</td>
      <td className="commission mono" data-label="手续费">{formatCommission(event.commission.amount, event.commission.asset, event.currency)}</td>
      <td className="event-note" data-label="备注"><span>{event.note || (event.source_type ? "导入记录" : "—")}</span></td>
      <td className="action"><button className="icon-button evidence-trigger" type="button" aria-label={`查看${event.note || eventTypeLabel(event)}的详情`} onClick={(source) => onEvidence(event, source.currentTarget)}><EvidenceIcon /></button></td>
    </tr>)}</tbody>
  </table></div>;
}

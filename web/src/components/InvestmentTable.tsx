import type { InvestmentEvent } from "../api/types";
import { formatOccurredAt } from "../format";
import { eventTypeLabel } from "../investmentLabels";

type Props = { items: InvestmentEvent[]; loading?: boolean; onEvidence: (event: InvestmentEvent, source: HTMLButtonElement) => void };

function assetLabel(asset: { ticker: string | null; amount: string | null }, currency: string, empty = "—") {
  if (!asset.ticker && !asset.amount) return empty;
  return `${asset.amount ?? "—"} ${asset.ticker ?? currency}`;
}

export function InvestmentTable({ items, loading = false, onEvidence }: Props) {
  return <div className="table-wrap investment-table-wrap"><table className="investment-table">
    <caption className="sr-only">投资事件列表</caption>
    <thead><tr><th>发生时间</th><th>账户</th><th>操作</th><th>资产变化</th><th>手续费</th><th>备注</th><th><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" key={index}>{Array.from({ length: 7 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}</tr>) : items.map((event) => <tr className="investment-row" key={event.event_id}>
      <td className="occurred-at mono" data-label="发生时间">{formatOccurredAt(event.occurred_at)}</td>
      <td className="account" data-label="账户">{event.account.name}</td>
      <td className="event-type" data-label="操作"><span>{eventTypeLabel(event)}</span></td>
      <td className="asset-direction" data-label="资产变化"><span className="asset-line mono">付出 {assetLabel(event.from_asset, event.currency)}</span><span className="asset-line mono">换入 {assetLabel(event.to_asset, event.currency)}</span></td>
      <td className="commission mono" data-label="手续费">{assetLabel({ ticker: event.commission.asset, amount: event.commission.amount }, event.currency)}</td>
      <td className="event-note" data-label="备注"><span>{event.note || (event.source_type ? "导入记录" : "—")}</span></td>
      <td className="action"><button className="icon-button evidence-trigger" type="button" aria-label={`查看${event.note || "这笔投资"}的详情`} onClick={(source) => onEvidence(event, source.currentTarget)}>查看详情</button></td>
    </tr>)}</tbody>
  </table></div>;
}

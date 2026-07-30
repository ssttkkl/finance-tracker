import type { CashProjection } from "../api/types";
import { formatOccurredAt } from "../format";

type Props = { items: CashProjection[]; loading?: boolean; onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void };

export function CashTable({ items, loading = false, onEvidence }: Props) {
  return <div className="table-wrap"><table>
    <caption className="sr-only">收支账本视图中的投影条目</caption>
    <thead className="table-head"><tr><th scope="col">发生时间</th><th scope="col">账户</th><th scope="col">交易对方</th><th scope="col">备注</th><th scope="col">分类</th><th scope="col" className="amount">金额</th><th scope="col">来源</th><th scope="col"><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>
      {Array.from({ length: 8 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}
    </tr>) : items.map((item) => <tr className="cash-row" key={item.projection_id}>
      <td className="occurred-at mono" data-label="发生时间">{formatOccurredAt(item.occurred_at)}</td>
      <td className="account" data-label="账户">{item.account.name}</td><td className="counterparty" data-label="交易对方">{item.counterparty || "未提供"}</td><td className="note" data-label="备注">{item.note || "未提供"}</td><td className="category" data-label="分类">{item.category || "未分类"}</td>
      <td className={`amount mono ${item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-label="金额">{item.amount} {item.currency}</td>
      <td className="source" data-label="来源">{item.source_type || "未提供"}</td>
      <td className="action"><button className="icon-button" type="button" aria-label={`查看${item.counterparty || "该记录"}的证据详情`} onClick={(event) => onEvidence(item, event.currentTarget)}>查看</button></td>
    </tr>)}</tbody>
  </table></div>;
}

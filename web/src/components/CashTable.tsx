import type { CashProjection } from "../api/types";
import { formatOccurredAt } from "../format";

type Props = { items: CashProjection[]; loading?: boolean; onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void };

export function CashTable({ items, loading = false, onEvidence }: Props) {
  return <div className="table-wrap"><table className="cash-table">
    <caption className="sr-only">收支账本中的收支记录</caption>
    <thead className="table-head"><tr><th id="cash-column-occurred-at" scope="col">发生时间</th><th id="cash-column-account" scope="col">账户</th><th id="cash-column-counterparty" scope="col">交易对方</th><th id="cash-column-note" scope="col">备注</th><th id="cash-column-category" scope="col">分类</th><th id="cash-column-amount" scope="col" className="amount">金额</th><th id="cash-column-source" scope="col">来源</th><th id="cash-column-action" scope="col"><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>
      {Array.from({ length: 8 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}
    </tr>) : items.map((item) => <tr className="cash-row" data-projection-id={item.projection_id} key={item.projection_id}>
      <td className="occurred-at mono" data-label="发生时间" headers="cash-column-occurred-at">{formatOccurredAt(item.occurred_at)}</td>
      <td className="account" data-label="账户" headers="cash-column-account">{item.account.name}</td><td className="counterparty" data-label="交易对方" headers="cash-column-counterparty">{item.counterparty || "未提供"}</td><td className="note" data-label="备注" headers="cash-column-note">{item.note || "未提供"}</td><td className="category" data-label="分类" headers="cash-column-category"><span className="mobile-field-label">分类：</span>{item.category || "未分类"}</td>
      <td className={`amount mono ${item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-direction={item.amount.startsWith("-") ? "支出" : "收入"} data-label="金额" headers="cash-column-amount">{item.amount} {item.currency}</td>
      <td className="source" data-label="来源" headers="cash-column-source"><span className="mobile-field-label">导入渠道：</span>{item.source_type || "未提供"}</td>
      <td className="action" headers="cash-column-action"><button className="icon-button evidence-trigger" type="button" aria-label={`查看${item.counterparty || "该记录"}的证据详情`} onClick={(event) => onEvidence(item, event.currentTarget)}>查看</button></td>
    </tr>)}</tbody>
  </table></div>;
}

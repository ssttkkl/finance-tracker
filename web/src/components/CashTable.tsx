import type { CashProjection } from "../api/types";
import { formatOccurredAt } from "../format";

type Props = { items: CashProjection[]; loading?: boolean; onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void };

const relationKinds: Record<string, string> = {
  payment_mirror: "同笔支付关系",
  transfer_pair: "转账配对关系",
  refund_offset: "退款冲销关系",
  credit_repayment: "信用账户还款关系",
};

function relationSummary(item: CashProjection): string {
  const summary = item.accepted_relation_summary;
  if (!summary.length) return "单成员";
  return summary.map((relation) => {
    const kind = relationKinds[relation.kind] ?? "未识别的关系类型";
    return `${kind}（${relation.count}）`;
  }).join("；");
}

export function CashTable({ items, loading = false, onEvidence }: Props) {
  return <div className="table-wrap"><table>
    <caption className="sr-only">收支账本视图中的投影条目</caption>
    <thead><tr><th>发生时间</th><th>账户</th><th>交易对方</th><th>分类</th><th className="amount">金额</th><th>组成方式</th><th>来源</th><th><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>
      {Array.from({ length: 8 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}
    </tr>) : items.map((item) => <tr className="cash-row" key={item.projection_id}>
      <td className="occurred-at mono" data-label="发生时间">{formatOccurredAt(item.occurred_at)}</td>
      <td className="account" data-label="账户">{item.account.name}</td><td className="counterparty" data-label="交易对方">{item.counterparty || "未提供"}</td><td className="category" data-label="分类">{item.category || "未分类"}</td>
      <td className={`amount mono ${item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-label="金额">{item.amount} {item.currency}</td>
      <td className="relation-summary" data-label="组成方式">{relationSummary(item)}</td>
      <td className="source" data-label="来源">{item.source_type || "未提供"}</td>
      <td className="action"><button className="icon-button" type="button" aria-label={`查看${item.counterparty || "该记录"}的证据详情`} onClick={(event) => onEvidence(item, event.currentTarget)}>查看</button></td>
    </tr>)}</tbody>
  </table></div>;
}

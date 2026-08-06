import type { CashMonthlySummary, CashProjection } from "../api/types";
import { formatOccurredAt } from "../format";

type Props = { items: CashProjection[]; monthlySummaries?: CashMonthlySummary[]; loading?: boolean; onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void };

const monthKeyFormatter = new Intl.DateTimeFormat("en-US", { year: "numeric", month: "2-digit", timeZone: "Asia/Shanghai" });

function isBankSecurityTransfer(item: CashProjection): boolean {
  return item.transfer_subtype === "bank_security_transfer";
}

function economicTypeLabel(item: CashProjection): string {
  if (isBankSecurityTransfer(item)) return "银证转账";
  return item.economic_type === "expense" ? "消费" : item.economic_type === "income" ? "收入" : item.economic_type === "internal_transfer" ? "个人转账" : "未提供";
}

function transferFor(item: CashProjection) {
  return item.economic_type === "internal_transfer" ? item.transfer ?? null : null;
}

function accountLabel(item: CashProjection): string {
  const transfer = transferFor(item);
  return transfer ? `${transfer.from_account.name} → ${transfer.to_account.name}` : item.account.name;
}

function unsignedAmount(amount: string): string {
  return amount.startsWith("-") || amount.startsWith("+") ? amount.slice(1) : amount;
}

function amountLabel(item: CashProjection): string {
  const transfer = transferFor(item);
  if (transfer) {
    const fromAmount = unsignedAmount(transfer.from_amount);
    const toAmount = unsignedAmount(transfer.to_amount);
    return transfer.from_currency === transfer.to_currency
      ? `${fromAmount} ${transfer.from_currency}`
      : `${fromAmount} ${transfer.from_currency} → ${toAmount} ${transfer.to_currency}`;
  }
  const amount = item.amount.startsWith("-") || item.amount.startsWith("+") || item.amount === "0" ? item.amount : `+${item.amount}`;
  return `${amount} ${item.currency}`;
}

function monthKey(item: CashProjection): string {
  const parts = monthKeyFormatter.formatToParts(new Date(item.occurred_at));
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  return year && month ? `${year}-${month}` : "";
}

function monthLabel(month: string): string {
  const [year, monthNumber] = month.split("-");
  return `${year}年${Number(monthNumber)}月`;
}

function summaryAmount(kind: "income" | "expense", amount: string): string {
  if (amount === "0") return "0";
  return `${kind === "income" ? "+" : "-"}${unsignedAmount(amount)}`;
}

function projectionSource(item: CashProjection) {
  const bankSecurityTransfer = isBankSecurityTransfer(item);
  if (!bankSecurityTransfer && item.member_count === 1 && item.composition.length === 0) return null;
  const label = bankSecurityTransfer ? "银证转账关系" : "关系投影";
  return <span className="projection-source is-related" aria-label={label}>
    <span className="projection-source-kind">{label}</span>
  </span>;
}

function sourceLabel(item: CashProjection): string { return item.source_types.length ? item.source_types.join("、") : item.source_type || "-"; }

function transactionInfo(item: CashProjection) {
  return <td className="counterparty" data-label="交易信息" headers="cash-column-transaction-info">
    <span className="counterparty-primary">{item.counterparty || "-"}</span>
    <span className="note" data-label="备注">{item.note || "-"}</span>
    {projectionSource(item)}
  </td>;
}
function sourceInfo(item: CashProjection) { return <td className="source" data-label="来源" headers="cash-column-source">{sourceLabel(item)}</td>; }

function MonthDivider({ month, summary }: { month: string; summary?: CashMonthlySummary }) {
  return <tr className="month-divider" data-month={month}>
    <th colSpan={7} scope="rowgroup">
      <span className="month-divider-content">
        <span className="month-divider-label">{monthLabel(month)}</span>
        <span className="month-summary-list">
          {summary?.currencies.length ? summary.currencies.map((currency) => <span className="month-currency-summary" key={currency.currency}>
            <span>收入 <strong>{summaryAmount("income", currency.income)} {currency.currency}</strong></span>
            <span>支出 <strong>{summaryAmount("expense", currency.expense)} {currency.currency}</strong></span>
          </span>) : <span className="month-summary-empty">无收支</span>}
        </span>
      </span>
    </th>
  </tr>;
}

export function CashTable({ items, monthlySummaries = [], loading = false, onEvidence }: Props) {
  const summaries = new Map(monthlySummaries.map((summary) => [summary.month, summary]));
  let previousMonth: string | undefined;
  const tableRows = monthlySummaries.length ? items.flatMap((item) => {
    const month = monthKey(item);
    const divider = month !== previousMonth ? <MonthDivider key={`month-${month}`} month={month} summary={summaries.get(month)} /> : null;
    previousMonth = month;
    return [divider, <tr className="cash-row" data-projection-id={item.projection_id} key={item.projection_id}>
      <td className="occurred-at mono" data-label="发生时间" headers="cash-column-occurred-at">{formatOccurredAt(item.occurred_at)}</td>
      <td className="account" data-label="账户" headers="cash-column-account">{accountLabel(item)}</td>{transactionInfo(item)}{sourceInfo(item)}<td className="economic-type" data-label="经济类型" headers="cash-column-economic-type"><span className="mobile-field-label">经济类型：</span>{economicTypeLabel(item)}</td>
      <td className={`amount mono ${transferFor(item) ? "transfer" : item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-direction={transferFor(item) ? "转账" : item.amount.startsWith("-") ? "支出" : "收入"} data-label="金额" headers="cash-column-amount"><span className="amount-value">{amountLabel(item)}</span></td>
      <td className="action" headers="cash-column-action"><button className="icon-button evidence-trigger" type="button" aria-label={`查看${item.counterparty || "该记录"}的证据详情`} onClick={(event) => onEvidence(item, event.currentTarget)}>查看</button></td>
    </tr>];
  }) : items.map((item) => <tr className="cash-row" data-projection-id={item.projection_id} key={item.projection_id}>
    <td className="occurred-at mono" data-label="发生时间" headers="cash-column-occurred-at">{formatOccurredAt(item.occurred_at)}</td>
    <td className="account" data-label="账户" headers="cash-column-account">{accountLabel(item)}</td>{transactionInfo(item)}{sourceInfo(item)}<td className="economic-type" data-label="经济类型" headers="cash-column-economic-type"><span className="mobile-field-label">经济类型：</span>{economicTypeLabel(item)}</td>
    <td className={`amount mono ${transferFor(item) ? "transfer" : item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-direction={transferFor(item) ? "转账" : item.amount.startsWith("-") ? "支出" : "收入"} data-label="金额" headers="cash-column-amount"><span className="amount-value">{amountLabel(item)}</span></td>
    <td className="action" headers="cash-column-action"><button className="icon-button evidence-trigger" type="button" aria-label={`查看${item.counterparty || "该记录"}的证据详情`} onClick={(event) => onEvidence(item, event.currentTarget)}>查看</button></td>
  </tr>);
  return <div className="table-wrap"><table className="cash-table">
    <caption className="sr-only">收支账本中的收支记录</caption>
    <colgroup>
      <col className="occurred-at-column" />
      <col className="account-column" />
      <col className="transaction-info-column" />
      <col className="source-column" />
      <col className="economic-type-column" />
      <col className="amount-column" />
      <col className="action-column" />
    </colgroup>
    <thead className="table-head"><tr><th id="cash-column-occurred-at" scope="col">发生时间</th><th id="cash-column-account" scope="col">账户</th><th id="cash-column-transaction-info" scope="col">交易信息</th><th id="cash-column-source" scope="col">来源</th><th id="cash-column-economic-type" scope="col">经济类型</th><th id="cash-column-amount" scope="col" className="amount">金额</th><th id="cash-column-action" scope="col"><span className="sr-only">操作</span></th></tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>
      {Array.from({ length: 7 }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}
    </tr>) : tableRows}</tbody>
  </table></div>;
}

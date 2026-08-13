import { useRef } from "react";
import type { CashCategory, CashMonthlySummary, CashProjection } from "../api/types";
import { formatOccurredAt } from "../format";
import { UiIcon } from "./UiIcon";

type Props = {
  items: CashProjection[];
  monthlySummaries?: CashMonthlySummary[];
  loading?: boolean;
  selectable?: boolean;
  selectedIds?: Set<string>;
  onToggleSelection?: (projection: CashProjection) => void;
  onToggleAll?: (checked: boolean) => void;
  onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void;
};

const monthKeyFormatter = new Intl.DateTimeFormat("en-US", { year: "numeric", month: "2-digit" });

function isBankSecurityTransfer(item: CashProjection): boolean { return item.transfer_subtype === "bank_security_transfer"; }
function economicTypeLabel(item: CashProjection): string {
  if (isBankSecurityTransfer(item)) return "银证转账";
  return item.economic_type === "expense" ? "消费" : item.economic_type === "income" ? "收入" : item.economic_type === "internal_transfer" ? "已合并" : "未提供";
}
function transferFor(item: CashProjection) { return item.economic_type === "internal_transfer" ? item.transfer ?? null : null; }
function accountLabel(item: CashProjection): string {
  const transfer = transferFor(item);
  return transfer ? `${transfer.from_account.name} → ${transfer.to_account.name}` : item.account.name;
}
function unsignedAmount(amount: string): string { return amount.startsWith("-") || amount.startsWith("+") ? amount.slice(1) : amount; }
function amountLabel(item: CashProjection): string {
  const transfer = transferFor(item);
  if (transfer) {
    const fromAmount = unsignedAmount(transfer.from_amount);
    const toAmount = unsignedAmount(transfer.to_amount);
    return transfer.from_currency === transfer.to_currency ? `${fromAmount} ${transfer.from_currency}` : `${fromAmount} ${transfer.from_currency} → ${toAmount} ${transfer.to_currency}`;
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
function monthLabel(month: string): string { const [year, monthNumber] = month.split("-"); return `${year}年${Number(monthNumber)}月`; }
function summaryAmount(kind: "income" | "expense", amount: string): string { return amount === "0" ? "0" : `${kind === "income" ? "+" : "-"}${unsignedAmount(amount)}`; }
function projectionSource(item: CashProjection) {
  const bankSecurityTransfer = isBankSecurityTransfer(item);
  if (!bankSecurityTransfer && item.member_count === 1 && item.composition.length === 0) return null;
  const label = bankSecurityTransfer ? "银证转账" : "已合并";
  return <span className="projection-source is-related" aria-label={label}><span className="projection-source-kind">{label}</span></span>;
}
function sourceLabel(item: CashProjection): string { return item.source_types.length ? item.source_types.join("、") : item.source_type || "-"; }
function categoryLabel(category: CashCategory | string | null | undefined): string {
  if (typeof category === "string") return category || "未分类";
  return category?.path?.map((item) => item.name).join(" / ") || "未分类";
}

function MonthDivider({ month, summary, colSpan }: { month: string; summary?: CashMonthlySummary; colSpan: number }) {
  return <tr className="month-divider" data-month={month}><th colSpan={colSpan} scope="rowgroup"><span className="month-divider-content"><span className="month-divider-label">{monthLabel(month)}</span><span className="month-summary-list">{summary?.currencies.length ? summary.currencies.map((currency) => <span className="month-currency-summary" key={currency.currency}><span>收入 <strong>{summaryAmount("income", currency.income)} {currency.currency}</strong></span><span>支出 <strong>{summaryAmount("expense", currency.expense)} {currency.currency}</strong></span></span>) : <span className="month-summary-empty">无收支</span>}</span></span></th></tr>;
}

export function CashTable({ items, monthlySummaries = [], loading = false, selectable = false, selectedIds = new Set<string>(), onToggleSelection, onToggleAll, onEvidence }: Props) {
  const selectAllRef = useRef<HTMLInputElement>(null);
  const selectedCount = items.filter((item) => selectedIds.has(item.projection_id)).length;
  const allSelected = items.length > 0 && selectedCount === items.length;
  if (selectAllRef.current) selectAllRef.current.indeterminate = selectedCount > 0 && !allSelected;
  const columnCount = selectable ? 9 : 8;
  const summaries = new Map(monthlySummaries.map((summary) => [summary.month, summary]));
  let previousMonth: string | undefined;

  const rows = items.flatMap((item) => {
    const month = monthKey(item);
    const divider = monthlySummaries.length && month !== previousMonth ? <MonthDivider key={`month-${month}`} month={month} summary={summaries.get(month)} colSpan={columnCount} /> : null;
    previousMonth = month;
    return [divider, <CashRow key={item.projection_id} item={item} selectable={selectable} selected={selectedIds.has(item.projection_id)} onToggleSelection={onToggleSelection} onEvidence={onEvidence} />];
  });

  return <div className="table-wrap"><table className="cash-table"><caption className="sr-only">收支账本中的收支记录</caption>
    <colgroup>{selectable ? <col className="selection-column" /> : null}<col className="occurred-at-column" /><col className="account-column" /><col className="transaction-info-column" /><col className="source-column" /><col className="economic-type-column" /><col className="category-column" /><col className="amount-column" /><col className="action-column" /></colgroup>
    <thead className="table-head"><tr>
      {selectable ? <th id="cash-column-selection" scope="col"><span className="sr-only">选择</span><input ref={selectAllRef} type="checkbox" aria-label="选择当前已加载记录" checked={allSelected} onChange={(event) => onToggleAll?.(event.target.checked)} disabled={!items.length} /></th> : null}
      <th id="cash-column-occurred-at" scope="col">发生时间</th><th id="cash-column-account" scope="col">账户</th><th id="cash-column-transaction-info" scope="col">交易信息</th><th id="cash-column-source" scope="col">来源</th><th id="cash-column-economic-type" scope="col">流水类型</th><th id="cash-column-category" scope="col">分类</th><th id="cash-column-amount" scope="col" className="amount">金额</th><th id="cash-column-action" scope="col"><span className="sr-only">操作</span></th>
    </tr></thead>
    <tbody>{loading ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>{Array.from({ length: columnCount }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}</tr>) : rows}</tbody>
  </table></div>;
}

function CashRow({ item, selectable, selected, onToggleSelection, onEvidence }: { item: CashProjection; selectable: boolean; selected: boolean; onToggleSelection?: (projection: CashProjection) => void; onEvidence: (projection: CashProjection, source: HTMLButtonElement) => void }) {
  const transfer = transferFor(item);
  const transaction = <td className="counterparty" data-label="交易信息" headers="cash-column-transaction-info"><span className="counterparty-primary">{item.counterparty || "-"}</span><span className="note" data-label="备注">{item.note || "-"}</span>{projectionSource(item)}</td>;
  return <tr className={`cash-row${selected ? " is-selected" : ""}`} data-projection-id={item.projection_id}>
    {selectable ? <td className="selection" data-label="选择" headers="cash-column-selection"><input type="checkbox" aria-label={`选择${item.counterparty || "该记录"}`} checked={selected} onChange={() => onToggleSelection?.(item)} /></td> : null}
    <td className="occurred-at mono" data-label="发生时间" headers="cash-column-occurred-at">{formatOccurredAt(item.occurred_at)}</td>
    <td className="account" data-label="账户" headers="cash-column-account">{accountLabel(item)}</td>
    {transaction}
    <td className="source" data-label="来源" headers="cash-column-source">{sourceLabel(item)}</td>
    <td className="economic-type" data-label="流水类型" headers="cash-column-economic-type"><span className="mobile-field-label">流水类型：</span>{economicTypeLabel(item)}</td>
    <td className="category" data-label="分类" headers="cash-column-category">{categoryLabel(item.category)}</td>
    <td className={`amount mono ${transfer ? "transfer" : item.amount.startsWith("-") ? "outflow" : "inflow"}`} data-direction={transfer ? "转账" : item.amount.startsWith("-") ? "支出" : "收入"} data-label="金额" headers="cash-column-amount"><span className="amount-value">{amountLabel(item)}</span></td>
    <td className="action" headers="cash-column-action"><button className="icon-button icon-only-button evidence-trigger" type="button" aria-label={`查看${item.counterparty || "该记录"}的收支详情`} title="查看详情" onClick={(event) => onEvidence(item, event.currentTarget)}><UiIcon name="eye" /></button></td>
  </tr>;
}

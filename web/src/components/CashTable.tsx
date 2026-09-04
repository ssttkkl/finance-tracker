import type { CashCategory, CashMonthlySummary, CashProjection } from "../api/types";
import { TransactionTable, type TransactionTableItem } from "./TransactionTable";

type Props = {
  items: CashProjection[];
  monthlySummaries?: CashMonthlySummary[];
  loading?: boolean;
  selectable?: boolean;
  selectedIds?: Set<string>;
  onToggleSelection?: (projection: CashProjection) => void;
  onToggleAll?: (checked: boolean) => void;
  onEvidence: (projection: CashProjection, source: HTMLElement) => void;
  onAction?: (projection: CashProjection, action: CashRowAction, source: HTMLElement) => void;
};

export type CashRowAction = "view" | "edit" | "category" | "delete";

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
function projectionSource(item: CashProjection) {
  const bankSecurityTransfer = isBankSecurityTransfer(item);
  if (!bankSecurityTransfer && item.member_count === 1 && item.composition.length === 0) return null;
  const label = bankSecurityTransfer ? "银证转账" : "已合并";
  return <span className="projection-source is-related" aria-label={label}><span className="projection-source-kind">{label}</span></span>;
}
function categoryLabel(category: CashCategory | string | null | undefined): string {
  if (typeof category === "string") return category || "未分类";
  return category?.path?.map((item) => item.name).join(" / ") || "未分类";
}

function toTableItem(item: CashProjection): TransactionTableItem<CashProjection> {
  const transfer = transferFor(item);
  return {
    id: item.projection_id,
    source: item,
    occurredAt: item.occurred_at,
    accountLabel: accountLabel(item),
    counterparty: item.counterparty,
    note: item.note,
    flowLabel: economicTypeLabel(item),
    direction: transfer ? "transfer" : item.amount.startsWith("-") ? "expense" : item.amount.startsWith("+") || item.amount !== "0" ? "income" : "unknown",
    amountLabel: amountLabel(item),
    categoryLabel: categoryLabel(item.category),
    sourceIndicator: projectionSource(item),
  };
}

export function CashTable({ items, monthlySummaries = [], loading = false, selectable = false, selectedIds = new Set<string>(), onToggleSelection, onToggleAll, onEvidence, onAction }: Props) {
  return <TransactionTable
    items={items.map(toTableItem)}
    variant="ledger"
    monthlySummaries={monthlySummaries}
    groupByMonth={monthlySummaries.length > 0}
    loading={loading}
    selectable={selectable}
    selectedIds={selectedIds}
    showCategory
    onToggleSelection={(item) => { if (item.source) onToggleSelection?.(item.source); }}
    onToggleAll={onToggleAll}
    onEvidence={(item, source) => { if (item.source) onEvidence(item.source, source); }}
    actions={() => [
      { id: "view", label: "查看详情" },
      { id: "edit", label: "编辑" },
      { id: "category", label: "修改分类" },
      { id: "delete", label: "删除", danger: true },
    ]}
    onAction={(item, action, source) => {
      if (!item.source) return;
      if (onAction) onAction(item.source, action as CashRowAction, source);
      else if (action === "view") onEvidence(item.source, source);
    }}
    caption="收支账本中的收支记录"
    columnIdPrefix="cash"
  />;
}

import { useEffect, useRef, useState, type ReactNode } from "react";
import { formatOccurredAt } from "../format";
import { UiIcon } from "./UiIcon";

export type TransactionDirection = "income" | "expense" | "transfer" | "unknown";
export type TransactionStatusTone = "new" | "existing" | "unsupported" | "unresolved";

export type TransactionTableItem<T = unknown> = {
  id: string;
  source?: T;
  occurredAt: string;
  accountLabel: string;
  counterparty: string;
  note: string;
  flowLabel: string;
  direction: TransactionDirection;
  amountLabel: string;
  categoryLabel?: string;
  statusLabel?: string;
  statusTone?: TransactionStatusTone;
  sourceIndicator?: ReactNode;
  summaryAmount?: string;
  summaryCurrency?: string;
};

export type TransactionTableAction = {
  id: string;
  label: string;
  danger?: boolean;
};

export type TransactionMonthlySummary = {
  month: string;
  currencies: Array<{ currency: string; income: string; expense: string }>;
};

type Props<T> = {
  items: TransactionTableItem<T>[];
  variant: "ledger" | "import";
  monthlySummaries?: TransactionMonthlySummary[];
  groupByMonth?: boolean;
  loading?: boolean;
  selectable?: boolean;
  selectedIds?: Set<string>;
  onToggleSelection?: (item: TransactionTableItem<T>) => void;
  onToggleAll?: (checked: boolean) => void;
  onEvidence?: (item: TransactionTableItem<T>, source: HTMLElement) => void;
  actions?: (item: TransactionTableItem<T>) => TransactionTableAction[];
  onAction?: (item: TransactionTableItem<T>, action: string, source: HTMLElement) => void;
  wrapperClassName?: string;
  wrapperProps?: { role?: "region"; "aria-label"?: string; tabIndex?: number };
  caption?: string;
  columnIdPrefix?: string;
  showCategory?: boolean;
  showStatus?: boolean;
};

const monthKeyFormatter = new Intl.DateTimeFormat("en-US", { year: "numeric", month: "2-digit" });

function monthKey(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "unknown";
  const parts = monthKeyFormatter.formatToParts(date);
  const year = parts.find((part) => part.type === "year")?.value;
  const month = parts.find((part) => part.type === "month")?.value;
  return year && month ? `${year}-${month}` : "unknown";
}

function monthLabel(month: string): string {
  if (month === "unknown") return "未提供时间";
  const [year, monthNumber] = month.split("-");
  return year && monthNumber ? `${year}年${Number(monthNumber)}月` : "未提供时间";
}

function unsignedAmount(amount: string): string {
  return amount.startsWith("-") || amount.startsWith("+") ? amount.slice(1) : amount;
}

function summaryAmount(kind: "income" | "expense", amount: string): string {
  return amount === "0" ? "0" : `${kind === "income" ? "+" : "-"}${unsignedAmount(amount)}`;
}

function decimalParts(value: string): { digits: bigint; scale: number } {
  const normalized = unsignedAmount(value.trim());
  const [integerPart, fractionPart = ""] = normalized.split(".");
  const fraction = fractionPart.replace(/[^0-9]/g, "");
  const integer = integerPart.replace(/[^0-9]/g, "") || "0";
  return { digits: BigInt(`${integer}${fraction}` || "0"), scale: fraction.length };
}

function addUnsignedDecimalStrings(current: string, next: string): string {
  const left = decimalParts(current);
  const right = decimalParts(next);
  const scale = Math.max(left.scale, right.scale);
  const total = left.digits * 10n ** BigInt(scale - left.scale) + right.digits * 10n ** BigInt(scale - right.scale);
  if (scale === 0) return total.toString();
  const digits = total.toString().padStart(scale + 1, "0");
  return `${digits.slice(0, -scale)}.${digits.slice(-scale).replace(/0+$/, "") || "0"}`.replace(/\.0$/, "");
}

export function buildTransactionMonthlySummaries<T>(items: TransactionTableItem<T>[]): TransactionMonthlySummary[] {
  const months = new Map<string, Map<string, { income: string; expense: string }>>();
  items.forEach((item) => {
    if (item.direction !== "income" && item.direction !== "expense") return;
    const currency = item.summaryCurrency;
    if (!currency) return;
    const month = monthKey(item.occurredAt);
    const currencies = months.get(month) ?? new Map<string, { income: string; expense: string }>();
    const current = currencies.get(currency) ?? { income: "0", expense: "0" };
    current[item.direction] = addUnsignedDecimalStrings(current[item.direction], item.summaryAmount ?? "0");
    currencies.set(currency, current);
    months.set(month, currencies);
  });
  return Array.from(months.entries()).map(([month, currencies]) => ({
    month,
    currencies: Array.from(currencies.entries()).map(([currency, summary]) => ({ currency, ...summary })),
  }));
}

function MobileFieldMarker({ icon }: { icon: "account" | "receipt" | "tag" }) {
  return <span className="cash-mobile-field-marker" aria-hidden="true"><UiIcon name={icon} /></span>;
}

function MonthDivider({ month, summary, colSpan }: { month: string; summary?: TransactionMonthlySummary; colSpan: number }) {
  return <tr className="month-divider" data-month={month}>
    <th colSpan={colSpan} scope="rowgroup">
      <span className="month-divider-content">
        <span className="month-divider-label">{monthLabel(month)}</span>
        <span className="month-summary-list">
          {summary?.currencies.length
            ? summary.currencies.map((currency) => <span className="month-currency-summary" key={currency.currency}>
                <span>收入 <strong>{summaryAmount("income", currency.income)} {currency.currency}</strong></span>
                <span>支出 <strong>{summaryAmount("expense", currency.expense)} {currency.currency}</strong></span>
              </span>)
            : <span className="month-summary-empty">无收支</span>}
        </span>
      </span>
    </th>
  </tr>;
}

export function TransactionTable<T>({
  items,
  variant,
  monthlySummaries = [],
  groupByMonth = false,
  loading = false,
  selectable = false,
  selectedIds = new Set<string>(),
  onToggleSelection,
  onToggleAll,
  onEvidence,
  actions,
  onAction,
  wrapperClassName,
  wrapperProps,
  caption = "收支流水",
  columnIdPrefix = "transaction",
  showCategory = variant === "ledger",
  showStatus = variant === "import",
}: Props<T>) {
  const selectAllRef = useRef<HTMLInputElement>(null);
  const selectedCount = items.filter((item) => selectedIds.has(item.id)).length;
  const allSelected = items.length > 0 && selectedCount === items.length;
  if (selectAllRef.current) selectAllRef.current.indeterminate = selectedCount > 0 && !allSelected;
  const showActions = Boolean(actions);
  const columnCount = (selectable ? 1 : 0) + 4 + (showCategory ? 1 : 0) + (showStatus ? 1 : 0) + (showActions ? 1 : 0);
  const summaries = new Map(monthlySummaries.map((summary) => [summary.month, summary]));
  let previousMonth: string | undefined;
  const displayItems = groupByMonth
    ? [...items].sort((left, right) => {
        const leftTime = new Date(left.occurredAt).getTime();
        const rightTime = new Date(right.occurredAt).getTime();
        if (Number.isNaN(leftTime) || Number.isNaN(rightTime)) return 0;
        return rightTime - leftTime;
      })
    : items;

  const rows = displayItems.flatMap((item) => {
    const currentMonth = groupByMonth ? monthKey(item.occurredAt) : "";
    const divider = groupByMonth && currentMonth !== previousMonth
      ? <MonthDivider key={`month-${currentMonth}`} month={currentMonth} summary={summaries.get(currentMonth)} colSpan={columnCount} />
      : null;
    previousMonth = currentMonth;
    return [divider, <TransactionRow
      key={item.id}
      item={item}
      variant={variant}
      columnIdPrefix={columnIdPrefix}
      selectable={selectable}
      selected={selectedIds.has(item.id)}
      showCategory={showCategory}
      showStatus={showStatus}
      showActions={showActions}
      onToggleSelection={onToggleSelection}
      onEvidence={onEvidence}
      actions={actions}
      onAction={onAction}
    />];
  });

  return <div className={`table-wrap transaction-table-wrap transaction-table-wrap--${variant}${wrapperClassName ? ` ${wrapperClassName}` : ""}`} {...wrapperProps}>
    <table className={`cash-table transaction-table transaction-table--${variant}`}>
      <caption className="sr-only">{caption}</caption>
      <colgroup>
        {selectable ? <col className="selection-column" /> : null}
        <col className="occurred-at-column" />
        <col className="account-column" />
        <col className="transaction-info-column" />
        {showCategory ? <col className="category-column" /> : null}
        <col className="economic-type-column" />
        {showStatus ? <col className="status-column" /> : null}
        <col className="amount-column" />
        {showActions ? <col className="action-column" /> : null}
      </colgroup>
      <thead className="table-head">
        <tr>
          {selectable ? <th id={`${columnIdPrefix}-column-selection`} scope="col"><span className="sr-only">选择</span><input ref={selectAllRef} type="checkbox" aria-label="选择当前已加载记录" checked={allSelected} onChange={(event) => onToggleAll?.(event.target.checked)} disabled={!items.length} /></th> : null}
          <th id={`${columnIdPrefix}-column-occurred-at`} scope="col">发生时间</th>
          <th id={`${columnIdPrefix}-column-account`} scope="col">账户</th>
          <th id={`${columnIdPrefix}-column-transaction-info`} scope="col">交易信息</th>
          {showCategory ? <th id={`${columnIdPrefix}-column-category`} scope="col">分类</th> : null}
          <th id={`${columnIdPrefix}-column-economic-type`} scope="col">流水类型</th>
          {showStatus ? <th id={`${columnIdPrefix}-column-status`} scope="col">状态</th> : null}
          <th id={`${columnIdPrefix}-column-amount`} scope="col" className="amount">金额</th>
          {showActions ? <th id={`${columnIdPrefix}-column-action`} scope="col"><span className="sr-only">操作</span></th> : null}
        </tr>
      </thead>
      <tbody>
        {loading
          ? Array.from({ length: 3 }, (_value, index) => <tr className="loading-row" data-testid="现金流水骨架行" key={index}>{Array.from({ length: columnCount }, (_cell, cellIndex) => <td key={cellIndex}><span className="skeleton-cell" aria-hidden="true" /></td>)}</tr>)
          : rows}
      </tbody>
    </table>
  </div>;
}

function TransactionRow<T>({
  item,
  variant,
  columnIdPrefix,
  selectable,
  selected,
  showCategory,
  showStatus,
  showActions,
  onToggleSelection,
  onEvidence,
  actions,
  onAction,
}: {
  item: TransactionTableItem<T>;
  variant: "ledger" | "import";
  columnIdPrefix: string;
  selectable: boolean;
  selected: boolean;
  showCategory: boolean;
  showStatus: boolean;
  showActions: boolean;
  onToggleSelection?: (item: TransactionTableItem<T>) => void;
  onEvidence?: (item: TransactionTableItem<T>, source: HTMLElement) => void;
  actions?: (item: TransactionTableItem<T>) => TransactionTableAction[];
  onAction?: (item: TransactionTableItem<T>, action: string, source: HTMLElement) => void;
}) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuTrigger = useRef<HTMLButtonElement | null>(null);
  const menu = useRef<HTMLDivElement | null>(null);
  const canOpenEvidence = Boolean(onEvidence);
  const open = (source: HTMLElement) => onEvidence?.(item, source);
  const actionItems = actions?.(item) ?? [];

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnOutside = (event: PointerEvent) => {
      if (!menu.current?.contains(event.target as Node) && !menuTrigger.current?.contains(event.target as Node)) setMenuOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") { event.preventDefault(); setMenuOpen(false); menuTrigger.current?.focus(); }
    };
    document.addEventListener("pointerdown", closeOnOutside);
    document.addEventListener("keydown", closeOnEscape);
    requestAnimationFrame(() => menu.current?.querySelector<HTMLButtonElement>("button")?.focus());
    return () => { document.removeEventListener("pointerdown", closeOnOutside); document.removeEventListener("keydown", closeOnEscape); };
  }, [menuOpen]);

  const runAction = (action: string, source: HTMLElement) => {
    setMenuOpen(false);
    onAction?.(item, action, source);
  };
  const rowClass = `cash-row${selected ? " is-selected" : ""}${selectable ? " is-selectable" : ""}`;
  const rowProps = canOpenEvidence ? {
    tabIndex: 0,
    onClick: (event: React.MouseEvent<HTMLTableRowElement>) => open(event.currentTarget),
    onKeyDown: (event: React.KeyboardEvent<HTMLTableRowElement>) => {
      if (event.key === "Enter" || event.key === " ") { event.preventDefault(); open(event.currentTarget); }
    },
  } : {};
  const transaction = <td className="counterparty" data-label="交易信息" headers={`${columnIdPrefix}-column-transaction-info`}>
    <span className="counterparty-primary">{item.counterparty || "-"}</span>
    <span className="note" data-label="备注">{item.note || "-"}</span>
    {item.sourceIndicator}
  </td>;

  return <tr className={rowClass} data-transaction-id={item.id} data-projection-id={variant === "ledger" ? item.id : undefined} {...rowProps}>
    {selectable ? <td className="selection" data-label="选择" headers={`${columnIdPrefix}-column-selection`}><input type="checkbox" aria-label={`选择${item.counterparty || "该记录"}`} checked={selected} onClick={(event) => event.stopPropagation()} onKeyDown={(event) => event.stopPropagation()} onChange={() => onToggleSelection?.(item)} /></td> : null}
    <td className="occurred-at mono" data-label="发生时间" headers={`${columnIdPrefix}-column-occurred-at`}>{formatOccurredAt(item.occurredAt)}</td>
    <td className="account" data-label="账户" headers={`${columnIdPrefix}-column-account`}><MobileFieldMarker icon="account" /><span className="cash-mobile-field-value">{item.accountLabel || "-"}</span></td>
    {transaction}
    {showCategory ? <td className="category" data-label="分类" headers={`${columnIdPrefix}-column-category`}><MobileFieldMarker icon="tag" /><span className="cash-mobile-field-value">{item.categoryLabel || "未分类"}</span></td> : null}
    <td className="economic-type" data-label="流水类型" headers={`${columnIdPrefix}-column-economic-type`}><MobileFieldMarker icon="receipt" /><span className="cash-mobile-field-value">{item.flowLabel || "未提供"}</span></td>
    {showStatus ? <td className="status" data-label="状态" headers={`${columnIdPrefix}-column-status`}><span className={`import-status ${item.statusTone ?? "existing"}`}>{item.statusLabel || "—"}</span></td> : null}
    <td className={`amount mono ${item.direction === "transfer" ? "transfer" : item.direction === "expense" ? "outflow" : item.direction === "income" ? "inflow" : ""}`} data-direction={item.direction === "transfer" ? "转账" : item.direction === "expense" ? "支出" : item.direction === "income" ? "收入" : "未提供"} data-label="金额" headers={`${columnIdPrefix}-column-amount`}><span className="amount-value">{item.amountLabel}</span></td>
    {showActions ? <td className="action" headers={`${columnIdPrefix}-column-action`}><div className="cash-row-actions">
      {canOpenEvidence ? <button className="icon-button icon-only-button evidence-trigger" type="button" aria-label={`查看${item.counterparty || "该记录"}的收支详情`} title="查看详情" onClick={(event) => { event.stopPropagation(); open(event.currentTarget); }}><UiIcon name="eye" /></button> : null}
      <button ref={menuTrigger} className="icon-button icon-only-button cash-row-menu-trigger" type="button" aria-label={`打开${item.counterparty || "该记录"}的操作菜单`} aria-haspopup="menu" aria-expanded={menuOpen} title="更多操作" onClick={(event) => { event.stopPropagation(); setMenuOpen((value) => !value); }}><UiIcon name="more" /></button>
      {menuOpen ? <div ref={menu} className="cash-row-menu" role="menu" aria-label={`${item.counterparty || "该记录"}的操作`} onClick={(event) => event.stopPropagation()}>{actionItems.map((action) => <button key={action.id} type="button" role="menuitem" className={action.danger ? "is-danger" : undefined} onClick={() => runAction(action.id, menuTrigger.current ?? document.body)}>{action.label}</button>)}</div> : null}
    </div></td> : null}
  </tr>;
}

import type { Account, CashFilterOptions, CashFilters } from "../api/types";
import { UiIcon } from "./UiIcon";

type AmountFilterState = "error" | "success" | undefined;

type Props = {
  filters: CashFilters;
  accounts: Account[];
  filterOptions: CashFilterOptions;
  filterOptionsReady: boolean;
  filterOptionsLoading: boolean;
  amountFilterState?: AmountFilterState;
  onChange: (filters: CashFilters) => void;
  onAmountFilterChange: () => void;
};

type EconomicTypeSelection = {
  economic_type: string | null;
  transfer_subtype: string | null;
};

const economicTypeLabels: Record<string, string> = {
  expense: "消费",
  income: "收入",
  internal_transfer: "资金转移",
};

const transferSubtypeLabels: Record<string, string> = {
  bank_security_transfer: "银证转账",
  cross_currency_remittance: "跨币种汇款",
  ordinary_transfer: "普通转账",
  currency_exchange: "个人购汇",
};

function labelForEconomicType(value: string) {
  return economicTypeLabels[value] ?? value;
}

function labelForTransferSubtype(value: string) {
  return transferSubtypeLabels[value] ?? value;
}

function normalizedEconomicTypeSelection(filters: CashFilters): EconomicTypeSelection {
  if (filters.economic_type === "bank_security_transfer") {
    return { economic_type: "internal_transfer", transfer_subtype: "bank_security_transfer" };
  }
  return { economic_type: filters.economic_type ?? null, transfer_subtype: filters.transfer_subtype ?? null };
}

function selectionValue(selection: EconomicTypeSelection) {
  return selection.economic_type ? JSON.stringify(selection) : "";
}

function filterSummary(filters: CashFilters, accounts: Account[]) {
  const account = accounts.find((item) => String(item.id) === filters.account_id)?.name ?? "全部账户";
  const dateRange = [filters.date_from, filters.date_to].filter(Boolean).join(" 至 ");
  const selection = normalizedEconomicTypeSelection(filters);
  const economicType = selection.transfer_subtype ? labelForTransferSubtype(selection.transfer_subtype) : selection.economic_type ? labelForEconomicType(selection.economic_type) : "全部收支";
  const details = [dateRange, filters.category_id || filters.uncategorized ? "已筛选分类" : "", filters.counterparty ? `交易信息：${filters.counterparty}` : "", filters.currency ? filters.currency : "", filters.amount_min || filters.amount_max ? `金额：${filters.amount_min ?? "不限"} 至 ${filters.amount_max ?? "不限"}` : ""].filter(Boolean);
  return [account, ...details, economicType].join(" · ");
}

export function CashFiltersBar({ filters, accounts, filterOptions, filterOptionsReady, filterOptionsLoading, amountFilterState, onChange, onAmountFilterChange }: Props) {
  const update = (key: keyof CashFilters, value: string) => onChange({ ...filters, [key]: value });
  const updateAmount = (key: "amount_min" | "amount_max", value: string) => { onAmountFilterChange(); update(key, value); };
  const amountError = amountFilterState === "error" ? "筛选条件有误，请检查日期、金额和选项后重试。" : undefined;
  const amountSuccess = amountFilterState === "success" ? "金额筛选已应用。" : undefined;
  const amountDescription = amountError ? "amount-filter-error" : amountSuccess ? "amount-filter-success" : undefined;
  const amountState = amountFilterState ? `filter-control-${amountFilterState}` : undefined;
  const economicTypes = filterOptions.economic_types ?? [];
  const economicSelection = normalizedEconomicTypeSelection(filters);
  const economicTypeDescription = filterOptionsLoading
    ? "正在读取可用流水类型。"
    : !filterOptionsReady || economicTypes.length === 0
      ? "当前账本没有可筛选的流水类型。"
      : undefined;
  const onEconomicTypeChange = (value: string) => {
    if (!value) {
      onChange({ ...filters, economic_type: undefined, transfer_subtype: undefined });
      return;
    }
    const selection = JSON.parse(value) as EconomicTypeSelection;
    onChange({ ...filters, economic_type: selection.economic_type ?? undefined, transfer_subtype: selection.transfer_subtype ?? undefined });
  };
  return <details className="filters" aria-label="账本筛选工具" data-layout="filter-grid">
    <summary><span className="filter-mark" aria-hidden="true"><UiIcon name="sliders" /></span><span><strong>筛选</strong><small>{filterSummary(filters, accounts)}</small></span><span className="filter-toggle" aria-hidden="true"><UiIcon name="chevron-down" /></span></summary>
    <div className="filter-grid">
      <label>开始日期<input aria-label="开始日期" type="date" value={filters.date_from ?? ""} onChange={(e) => update("date_from", e.target.value)} /></label>
      <label>结束日期<input aria-label="结束日期" type="date" value={filters.date_to ?? ""} onChange={(e) => update("date_to", e.target.value)} /></label>
      <label>账户<select aria-label="账户" value={filters.account_id ?? ""} onChange={(e) => update("account_id", e.target.value)}><option value="">全部账户</option>{accounts.map((account) => <option value={account.id} key={account.id}>{account.name}</option>)}</select></label>
      <label>交易信息<input aria-label="交易信息" value={filters.counterparty ?? ""} onChange={(e) => update("counterparty", e.target.value)} /></label>
      <label>分类<select aria-label="分类" value={filters.uncategorized ? "__uncategorized__" : filters.category_id ?? ""} disabled={filterOptionsLoading || !filterOptionsReady} onChange={(e) => { const value = e.target.value; onChange({ ...filters, category_id: value && value !== "__uncategorized__" ? value : undefined, uncategorized: value === "__uncategorized__" ? "true" : undefined }); }}><option value="">全部分类</option><option value="__uncategorized__">无分类</option>{filterOptions.categories.map((category) => <option value={category.id} key={category.id}>{category.path.map((pathItem) => pathItem.name).join(" / ")}</option>)}</select></label>
      <label>币种<select aria-label="币种" value={filters.currency ?? ""} disabled={filterOptionsLoading || !filterOptionsReady} onChange={(e) => update("currency", e.target.value)}><option value="">全部币种</option>{[...new Set([filters.currency, ...filterOptions.currencies].filter(Boolean) as string[])].map((currency) => <option value={currency} key={currency}>{currency}</option>)}</select></label>
      <label>最低金额<input aria-label="最低金额" aria-invalid={amountFilterState === "error" || undefined} aria-describedby={amountDescription} className={amountState} inputMode="decimal" value={filters.amount_min ?? ""} onChange={(e) => updateAmount("amount_min", e.target.value)} /></label>
      <label>最高金额<input aria-label="最高金额" aria-invalid={amountFilterState === "error" || undefined} aria-describedby={amountDescription} className={amountState} inputMode="decimal" value={filters.amount_max ?? ""} onChange={(e) => updateAmount("amount_max", e.target.value)} /></label>
      {amountError ? <p className="filter-error" id="amount-filter-error" role="alert">{amountError}</p> : null}
      {amountSuccess ? <p className="filter-success" id="amount-filter-success" role="status">{amountSuccess}</p> : null}
      <label>流水类型<select className="economic-type-select" aria-label="流水类型" aria-describedby={economicTypeDescription ? "economic-type-description" : undefined} value={selectionValue(economicSelection)} disabled={filterOptionsLoading || !filterOptionsReady || economicTypes.length === 0} onChange={(e) => onEconomicTypeChange(e.target.value)}><option value="">全部收支</option>{economicTypes.map((item) => <optgroup label={labelForEconomicType(item.economic_type)} key={item.economic_type}><option value={selectionValue({ economic_type: item.economic_type, transfer_subtype: null })}>全部{labelForEconomicType(item.economic_type)}</option>{item.transfer_subtypes.map((subtype) => <option value={selectionValue({ economic_type: item.economic_type, transfer_subtype: subtype })} key={subtype}>{labelForTransferSubtype(subtype)}</option>)}</optgroup>)}</select>{economicTypeDescription ? <span className="sr-only" id="economic-type-description">{economicTypeDescription}</span> : null}</label>
      <label>合并状态<select aria-label="合并状态" value={filters.composition ?? ""} onChange={(e) => update("composition", e.target.value)}><option value="">全部</option><option value="single">未合并</option><option value="payment_mirror">同笔支付</option><option value="refund_offset">退款冲销</option><option value="combined">其他合并</option></select></label>
    </div>
  </details>;
}

import type { Account, CashFilterOptions, CashFilters } from "../api/types";

type AmountFilterState = "error" | "success" | undefined;

type Props = {
  filters: CashFilters;
  accounts: Account[];
  filterOptions: CashFilterOptions;
  filterOptionsReady: boolean;
  amountFilterState?: AmountFilterState;
  onChange: (filters: CashFilters) => void;
  onAmountFilterChange: () => void;
};

function filterSummary(filters: CashFilters, accounts: Account[]) {
  const account = accounts.find((item) => String(item.id) === filters.account_id)?.name ?? "全部账户";
  const dateRange = [filters.date_from, filters.date_to].filter(Boolean).join(" 至 ");
  const economicType = filters.economic_type === "expense" ? "消费" : filters.economic_type === "income" ? "收入" : filters.economic_type === "internal_transfer" ? "个人转账" : "全部收支";
  const details = [dateRange, filters.category ? `分类：${filters.category}` : "", filters.counterparty ? `交易信息：${filters.counterparty}` : "", filters.currency ? filters.currency : "", filters.amount_min || filters.amount_max ? `金额：${filters.amount_min ?? "不限"} 至 ${filters.amount_max ?? "不限"}` : ""].filter(Boolean);
  return [account, ...details, economicType].join(" · ");
}

export function CashFiltersBar({ filters, accounts, filterOptions, filterOptionsReady, amountFilterState, onChange, onAmountFilterChange }: Props) {
  const update = (key: keyof CashFilters, value: string) => onChange({ ...filters, [key]: value });
  const updateAmount = (key: "amount_min" | "amount_max", value: string) => { onAmountFilterChange(); update(key, value); };
  const amountError = amountFilterState === "error" ? "筛选条件有误，请检查日期、金额和选项后重试。" : undefined;
  const amountSuccess = amountFilterState === "success" ? "金额筛选已应用。" : undefined;
  const amountDescription = amountError ? "amount-filter-error" : amountSuccess ? "amount-filter-success" : undefined;
  const amountState = amountFilterState ? `filter-control-${amountFilterState}` : undefined;
  return <details className="filters" aria-label="账本筛选工具" data-layout="filter-grid">
    <summary><span className="filter-mark" aria-hidden="true">⌕</span><span><strong>筛选</strong><small>{filterSummary(filters, accounts)}</small></span><span className="filter-toggle">展开</span></summary>
    <div className="filter-grid">
      <label>开始日期<input aria-label="开始日期" type="date" value={filters.date_from ?? ""} onChange={(e) => update("date_from", e.target.value)} /></label>
      <label>结束日期<input aria-label="结束日期" type="date" value={filters.date_to ?? ""} onChange={(e) => update("date_to", e.target.value)} /></label>
      <label>账户<select aria-label="账户" value={filters.account_id ?? ""} onChange={(e) => update("account_id", e.target.value)}><option value="">全部账户</option>{accounts.map((account) => <option value={account.id} key={account.id}>{account.name}</option>)}</select></label>
      <label>交易信息<input aria-label="交易信息" value={filters.counterparty ?? ""} onChange={(e) => update("counterparty", e.target.value)} /></label>
      <label>分类<select aria-label="分类" value={filters.category ?? ""} disabled={!filterOptionsReady} onChange={(e) => update("category", e.target.value)}><option value="">全部分类</option>{[...new Set([filters.category, ...filterOptions.categories].filter(Boolean) as string[])].map((category) => <option value={category} key={category}>{category}</option>)}</select></label>
      <label>币种<select aria-label="币种" value={filters.currency ?? ""} disabled={!filterOptionsReady} onChange={(e) => update("currency", e.target.value)}><option value="">全部币种</option>{[...new Set([filters.currency, ...filterOptions.currencies].filter(Boolean) as string[])].map((currency) => <option value={currency} key={currency}>{currency}</option>)}</select></label>
      <label>最低金额<input aria-label="最低金额" aria-invalid={amountFilterState === "error" || undefined} aria-describedby={amountDescription} className={amountState} inputMode="decimal" value={filters.amount_min ?? ""} onChange={(e) => updateAmount("amount_min", e.target.value)} /></label>
      <label>最高金额<input aria-label="最高金额" aria-invalid={amountFilterState === "error" || undefined} aria-describedby={amountDescription} className={amountState} inputMode="decimal" value={filters.amount_max ?? ""} onChange={(e) => updateAmount("amount_max", e.target.value)} /></label>
      {amountError ? <p className="filter-error" id="amount-filter-error" role="alert">{amountError}</p> : null}
      {amountSuccess ? <p className="filter-success" id="amount-filter-success" role="status">{amountSuccess}</p> : null}
      <label>经济类型<select aria-label="经济类型" value={filters.economic_type ?? ""} onChange={(e) => update("economic_type", e.target.value)}><option value="">全部收支</option><option value="expense">消费</option><option value="income">收入</option><option value="internal_transfer">个人转账</option></select></label>
      <label>组成方式<select aria-label="组成方式" value={filters.composition ?? ""} onChange={(e) => update("composition", e.target.value)}><option value="">全部</option><option value="single">单成员</option><option value="payment_mirror">同笔支付</option><option value="refund_offset">退款冲销</option><option value="combined">组合关系</option></select></label>
    </div>
  </details>;
}

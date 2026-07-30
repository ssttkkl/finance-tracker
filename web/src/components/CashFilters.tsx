import type { Account, CashFilters } from "../api/types";

type Props = { filters: CashFilters; accounts: Account[]; onChange: (filters: CashFilters) => void };

export function CashFiltersBar({ filters, accounts, onChange }: Props) {
  const update = (key: keyof CashFilters, value: string) => onChange({ ...filters, [key]: value });
  return <details className="filters" open>
    <summary>筛选</summary>
    <div className="filter-grid">
      <label>开始日期<input aria-label="开始日期" type="date" value={filters.date_from ?? ""} onChange={(e) => update("date_from", e.target.value)} /></label>
      <label>结束日期<input aria-label="结束日期" type="date" value={filters.date_to ?? ""} onChange={(e) => update("date_to", e.target.value)} /></label>
      <label>账户<select aria-label="账户" value={filters.account_id ?? ""} onChange={(e) => update("account_id", e.target.value)}><option value="">全部账户</option>{accounts.map((account) => <option value={account.id} key={account.id}>{account.name}</option>)}</select></label>
      <label>交易对方<input aria-label="交易对方" value={filters.counterparty ?? ""} onChange={(e) => update("counterparty", e.target.value)} /></label>
      <label>分类<input aria-label="分类" value={filters.category ?? ""} onChange={(e) => update("category", e.target.value)} /></label>
      <label>币种<input aria-label="币种" maxLength={3} value={filters.currency ?? ""} onChange={(e) => update("currency", e.target.value.toUpperCase())} /></label>
      <label>最低金额<input aria-label="最低金额" inputMode="decimal" value={filters.amount_min ?? ""} onChange={(e) => update("amount_min", e.target.value)} /></label>
      <label>最高金额<input aria-label="最高金额" inputMode="decimal" value={filters.amount_max ?? ""} onChange={(e) => update("amount_max", e.target.value)} /></label>
      <label>经济类型<select aria-label="经济类型" value={filters.economic_type ?? ""} onChange={(e) => update("economic_type", e.target.value)}><option value="">全部收支</option><option value="expense">消费</option><option value="income">收入</option></select></label>
      <label>组成方式<select aria-label="组成方式" value={filters.composition ?? ""} onChange={(e) => update("composition", e.target.value)}><option value="">全部</option><option value="single">单成员</option><option value="payment_mirror">同笔支付</option><option value="refund_offset">退款冲销</option><option value="combined">组合关系</option></select></label>
    </div>
  </details>;
}

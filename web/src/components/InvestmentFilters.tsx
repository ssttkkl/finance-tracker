import type { Account, InvestmentFilters } from "../api/types";
import { recordTypeLabels } from "../investmentLabels";

type Props = {
  filters: InvestmentFilters;
  accounts: Account[];
  onChange: (filters: InvestmentFilters) => void;
};

function summary(filters: InvestmentFilters, accounts: Account[]) {
  const account = accounts.find((item) => String(item.id) === filters.account_id)?.name ?? "全部账户";
  const dates = [filters.date_from, filters.date_to].filter(Boolean).join(" 至 ");
  const type = filters.record_type ? recordTypeLabels[filters.record_type] ?? "投资记录" : "全部事件";
  return [account, dates, filters.ticker?.toUpperCase(), type].filter(Boolean).join(" · ");
}

export function InvestmentFiltersBar({ filters, accounts, onChange }: Props) {
  const update = (key: keyof InvestmentFilters, value: string) => onChange({ ...filters, [key]: value || undefined });
  return <details className="filters investment-filters" open>
    <summary aria-label="投资账本筛选工具"><span className="filter-mark" aria-hidden="true"><svg className="ui-icon" viewBox="0 0 24 24"><path d="M4 6h16M7 12h10M10 18h4" /></svg></span><span className="summary-copy"><strong>筛选</strong><small>{summary(filters, accounts)}</small></span><span className="filter-toggle" aria-hidden="true"><svg className="ui-icon" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6" /></svg></span></summary>
    <div className="filter-grid">
      <label>开始日期<input aria-label="开始日期" type="date" value={filters.date_from ?? ""} onChange={(event) => update("date_from", event.target.value)} /></label>
      <label>结束日期<input aria-label="结束日期" type="date" value={filters.date_to ?? ""} onChange={(event) => update("date_to", event.target.value)} /></label>
      <label>投资账户<select aria-label="投资账户" value={filters.account_id ?? ""} onChange={(event) => update("account_id", event.target.value)}><option value="">全部账户</option>{accounts.map((account) => <option key={account.id} value={account.id}>{account.name}</option>)}</select></label>
      <label>事件类型<select aria-label="事件类型" value={filters.record_type ?? ""} onChange={(event) => update("record_type", event.target.value)}><option value="">全部事件类型</option>{Object.entries(recordTypeLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>标的<input aria-label="标的" value={filters.ticker ?? ""} placeholder="如 AAPL.US" onChange={(event) => update("ticker", event.target.value)} /></label>
    </div>
  </details>;
}

export type Account = { id: number; name: string; type: string; active: boolean; currencies?: string[] };

export type RecordTypeOption = { value: string; label: string; subtypes: { value: string; label: string }[] };
export type RelationTypeOption = { value: string; label: string };
export type LedgerOptions = { record_types: RecordTypeOption[]; relation_types: RelationTypeOption[] };

export type AcceptedRelationSummary = { kind: string; subtype: string; count: number };

export type CashTransfer = {
  from_account: Account;
  from_amount: string;
  from_currency: string;
  to_account: Account;
  to_amount: string;
  to_currency: string;
};

export type CashProjection = {
  projection_id: string;
  occurred_at: string;
  account: Account;
  counterparty: string;
  category: string;
  note: string;
  amount: string;
  currency: string;
  economic_type: "expense" | "income" | "internal_transfer";
  transfer_subtype: string | null;
  composition: string[];
  member_count: number;
  accepted_relation_summary: AcceptedRelationSummary[];
  source_type: string | null;
  source_types: string[];
  record_id: string;
  visible: boolean;
  hidden_reason: string | null;
  transfer?: CashTransfer | null;
};

export type CashFilterOptions = {
  categories: string[];
  currencies: string[];
  economic_types: CashEconomicTypeFilterOption[];
};

export type CashEconomicTypeFilterOption = {
  economic_type: string;
  transfer_subtypes: string[];
};

export type CashMonthlyCurrencySummary = {
  currency: string;
  income: string;
  expense: string;
};

export type CashMonthlySummary = {
  month: string;
  currencies: CashMonthlyCurrencySummary[];
};

export type CashPage = {
  projection_version: number;
  items: CashProjection[];
  next_cursor: string | null;
  page_size: number;
  filters: Record<string, string | null>;
  filter_options: CashFilterOptions;
  monthly_summaries?: CashMonthlySummary[];
};

export type EvidenceRecord = {
  id: string;
  occurred_at: string;
  account: Account;
  account_name?: string;
  account_id?: number;
  account_type?: string;
  counterparty: string;
  counterparty_account?: string;
  category: string;
  note: string;
  amount: string;
  currency: string;
  source_type: string | null;
  record_id?: string;
  record_type?: string;
  record_subtype?: string;
};

export type EvidenceMember = EvidenceRecord & { roles: string[] };
export type EndpointRelation = {
  id: string;
  kind: string;
  subtype: string;
  primary_record: EvidenceRecord | null;
  secondary_record: EvidenceRecord | null;
};
export type AcceptedEvidenceRelation = EndpointRelation & {
  rule_id: string;
  confidence: string;
  evidence: Record<string, string | number | boolean | null | string[]>;
};
export type InactiveRelationHint = EndpointRelation & {
  status: "pending_review" | "rejected" | "superseded";
};
export type RefundTimelineItem = {
  record_id: string;
  occurred_at: string;
  amount: string;
  currency: string;
  source_type: string | null;
};
export type Evidence = {
  projection_version: number;
  projection: CashProjection;
  root_record: EvidenceRecord & { source_snapshot?: Record<string, string | number | boolean> | null };
  members: EvidenceMember[];
  accepted_relations: AcceptedEvidenceRelation[];
  inactive_relation_hints: InactiveRelationHint[];
  refund_timeline: RefundTimelineItem[];
};

export type CashRecord = {
  id: string;
  occurred_at: string;
  amount: string;
  currency: string;
  counterparty: string;
  counterparty_account: string;
  note: string;
  category: string;
  record_type: string;
  record_subtype: string;
  account_name: string;
  account_id: number;
  account_type: string;
  source_type: string | null;
};

export type CashRelation = {
  id: string;
  kind: string;
  label: string;
  subtype: string;
  status: "pending_review" | "accepted" | "rejected" | "superseded";
  primary_record: CashRecord | null;
  secondary_record: CashRecord | null;
};

export type CashRecordDetail = {
  record: CashRecord;
  relations: CashRelation[];
  options: LedgerOptions;
};

export type CashRecordPage = {
  items: CashRecord[];
  next_cursor: string | null;
};

export type ImportPreviewItem = {
  record_id: string;
  occurred_at: string;
  amount: string;
  currency: string;
  account_name: string;
  counterparty: string;
  counterparty_account: string;
  record_type: string;
  record_subtype: string;
  category: string;
  note: string;
  channel: string;
  status: "new" | "existing" | "unsupported";
  message: string;
};

export type ImportRelationRecord = ImportPreviewItem & {
  preview: boolean;
  fact_id?: number;
};

export type ImportRelation = {
  id: string;
  kind: string;
  label: string;
  subtype: string;
  status: "accepted" | "pending_review";
  automatic: boolean;
  rule_id: string;
  reason: string;
  primary: ImportRelationRecord;
  secondary: ImportRelationRecord | null;
  candidates: ImportRelationRecord[];
};

export type ImportPreview = {
  channel: string;
  channel_label: string;
  file: { name: string; digest: string };
  columns: string[];
  items: ImportPreviewItem[];
  summary: { total: number; new: number; existing: number; unsupported: number };
  relations: ImportRelation[];
};

export type ImportDetection = {
  channel: string;
  channel_label: string;
  file: { name: string; digest: string };
  digest: string;
  row_count: number;
};

export type ImportCommitResult = {
  message: string;
  new_rows: number;
  updated_rows: number;
  channel: string;
  digest: string;
  pending_relations?: number;
};

export type CashFilters = {
  date_from?: string;
  date_to?: string;
  account_id?: string;
  counterparty?: string;
  category?: string;
  currency?: string;
  amount_min?: string;
  amount_max?: string;
  economic_type?: string;
  transfer_subtype?: string;
  composition?: "single" | "payment_mirror" | "refund_offset" | "combined";
};

export type InvestmentAsset = { ticker: string | null; amount: string | null };
export type InvestmentCommission = { amount: string | null; asset: string | null };
export type InvestmentRelation = {
  kind: string;
  status: string;
  direction: string;
  rule_id: string;
  cash_account: Account;
  cash_amount: string;
  cash_currency: string;
  cash_occurred_at: string;
  cash_counterparty: string;
  cash_note: string;
  cash_source_type: string | null;
  cash_record_id: string;
  evidence: Record<string, unknown>;
};
export type InvestmentEvent = {
  event_id: string;
  occurred_at: string;
  account: Account;
  record_type: string;
  record_subtype: string;
  currency: string;
  note: string;
  from_asset: InvestmentAsset;
  to_asset: InvestmentAsset;
  commission: InvestmentCommission;
  source_type: string | null;
  record_id: string;
  relations: InvestmentRelation[];
};
export type InvestmentFilters = {
  date_from?: string;
  date_to?: string;
  account_id?: string;
  record_type?: string;
  ticker?: string;
};
export type InvestmentPage = {
  data_version: number;
  items: InvestmentEvent[];
  next_cursor: string | null;
  page_size: number;
  filters: Record<string, string | number | null>;
};
export type InvestmentEvidence = {
  data_version: number;
  event: InvestmentEvent;
  source_snapshot: Record<string, string | number | boolean | string[]> | null;
  relations: InvestmentRelation[];
};

export type PortfolioPosition = {
  ticker: string;
  display_name?: string | null;
  shares: string;
  total_cost: string;
  cost_currency: string;
  is_cash: boolean;
  current_price: string | null;
  market_value: string | null;
  profit: string | null;
  quote_status: "complete" | "stale" | "partial" | "unsupported" | null;
  quote_reason: string | null;
  quote_currency: string | null;
  quote_observed_at: string | null;
  quote_session: "pre_market" | "regular" | "post_market" | "overnight" | "unknown" | null;
  display_currency: string | null;
  display_market_value: string | null;
  usd_market_value?: string | null;
  fx_rate: string | null;
  fx_status: string | null;
  fx_reason: string | null;
  period_profit: string | null;
  period_profit_rate: string | null;
  period_baselines?: PortfolioPeriodBaseline[];
};
export type PortfolioPeriodBaseline = { account: string; ticker: string; occurred_at: string };
export type PortfolioAccount = { name: string; currency: string; positions: PortfolioPosition[] };
export type PortfolioPeriod = "24h" | "week_to_date" | "month_to_date" | "30d" | "90d" | "year_to_date" | "365d";
export type Portfolio = {
  accounts: PortfolioAccount[];
  total_market_value: string | null;
  total_profit: string | null;
  total_profit_rate: string | null;
  period_profit: string | null;
  period_profit_rate: string | null;
  period_baselines?: PortfolioPeriodBaseline[];
};

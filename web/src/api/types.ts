export type Account = { id: number; name: string; type: string; active: boolean; currencies?: string[] };

export type RecordTypeOption = { value: string; label: string; subtypes: { value: string; label: string }[] };
export type RelationTypeOption = { value: string; label: string };
export type LedgerOptions = { record_types: RecordTypeOption[]; relation_types: RelationTypeOption[] };

export type CashCategoryPathItem = { id: string; name: string };
export type CashCategory = {
  id: string;
  parent_id: string | null;
  name: string;
  description: string | null;
  path: CashCategoryPathItem[];
  depth: number;
  sort_order: number;
  revision: number;
};
export type CashCategoryDirectory = { revision: number; items: CashCategory[] };

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
  category: CashCategory | null;
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
  categories: CashCategory[];
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
  category: CashCategory | null;
  category_id?: string | null;
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
  category: CashCategory | null;
  category_id?: string | null;
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
  record_id?: string;
  occurred_at: string;
  counterparty: string;
  amount: string;
  currency: string;
  account_name: string;
  category: string;
  channel: string;
  status: "new" | "existing" | "unsupported" | "error";
  message: string;
};

export type ImportPreview = {
  channel: string;
  items: ImportPreviewItem[];
  summary: Record<string, number>;
};

export type CashFilters = {
  date_from?: string;
  date_to?: string;
  account_id?: string;
  counterparty?: string;
  category_id?: string;
  uncategorized?: "true";
  currency?: string;
  amount_min?: string;
  amount_max?: string;
  economic_type?: string;
  transfer_subtype?: string;
  composition?: "single" | "payment_mirror" | "refund_offset" | "combined";
};

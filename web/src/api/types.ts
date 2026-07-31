export type Account = { id: number; name: string; type: string; active: boolean };

export type AcceptedRelationSummary = { kind: string; subtype: string; count: number };

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
  record_id: string;
  visible: boolean;
  hidden_reason: string | null;
};

export type CashPage = {
  projection_version: number;
  items: CashProjection[];
  next_cursor: string | null;
  page_size: number;
  filters: Record<string, string | null>;
};

export type EvidenceRecord = {
  id: string;
  occurred_at: string;
  account: Account;
  counterparty: string;
  category: string;
  note: string;
  amount: string;
  currency: string;
  source_type: string | null;
  record_id: string;
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
  evidence: Record<string, string | number | boolean>;
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
  root_record: EvidenceRecord & { source_snapshot: Record<string, string | number | boolean> | null };
  members: EvidenceMember[];
  accepted_relations: AcceptedEvidenceRelation[];
  inactive_relation_hints: InactiveRelationHint[];
  refund_timeline: RefundTimelineItem[];
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
  economic_type?: "expense" | "income";
  composition?: "single" | "payment_mirror" | "refund_offset" | "combined";
};

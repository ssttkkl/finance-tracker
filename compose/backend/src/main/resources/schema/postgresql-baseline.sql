CREATE TABLE public.account_aliases (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    alias_type character varying(32) NOT NULL,
    alias_value character varying(255) NOT NULL,
    account_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE SEQUENCE public.account_aliases_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.account_aliases_id_seq OWNED BY public.account_aliases.id;

CREATE TABLE public.accounts (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    type character varying(32) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    metadata_json json NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    currencies json,
    revision bigint DEFAULT '1'::bigint NOT NULL,
    CONSTRAINT ck_accounts_revision CHECK ((revision >= 1))
);

CREATE SEQUENCE public.accounts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.accounts_id_seq OWNED BY public.accounts.id;

CREATE TABLE public.alembic_version (
    version_num character varying(32) NOT NULL
);

CREATE TABLE public.cash_categories (
    id character varying(64) NOT NULL,
    workspace_id character varying(64) NOT NULL,
    parent_id character varying(64),
    parent_scope_key character varying(64) NOT NULL,
    name character varying(40) NOT NULL,
    normalized_name character varying(40) NOT NULL,
    description character varying(500),
    category_path character varying(512) NOT NULL,
    depth integer NOT NULL,
    sort_order integer DEFAULT 0 NOT NULL,
    revision bigint DEFAULT '1'::bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_cash_categories_depth CHECK (((depth >= 1) AND (depth <= 5))),
    CONSTRAINT ck_cash_categories_name_length CHECK (((length((name)::text) >= 1) AND (length((name)::text) <= 40))),
    CONSTRAINT ck_cash_categories_path CHECK ((length((category_path)::text) > 2))
);

CREATE TABLE public.cash_category_states (
    workspace_id character varying(64) NOT NULL,
    revision bigint DEFAULT '0'::bigint NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE public.cash_entries (
    id character varying(64) NOT NULL,
    workspace_id character varying(64) NOT NULL,
    root_transaction_id bigint NOT NULL,
    revision bigint NOT NULL,
    category_id character varying(64),
    note text NOT NULL,
    counterparty character varying(512) NOT NULL,
    counterparty_account character varying(512) NOT NULL,
    counterparty_account_attrs json NOT NULL,
    manual_fields json NOT NULL,
    split_inherit boolean,
    identity_transaction_id bigint NOT NULL,
    CONSTRAINT ck_cash_entries_revision CHECK ((revision >= 1))
);

CREATE TABLE public.cash_entry_members (
    workspace_id character varying(64) NOT NULL,
    transaction_id bigint NOT NULL,
    entry_id character varying(64) NOT NULL,
    standalone_values json NOT NULL,
    standalone_manual_fields json NOT NULL
);

CREATE TABLE public.cash_investment_funding_relations (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    cash_transaction_component_id bigint NOT NULL,
    investment_event_id bigint NOT NULL,
    direction character varying(32) NOT NULL,
    status character varying(32) NOT NULL,
    rule_id character varying(128) NOT NULL,
    evidence json DEFAULT '{}'::json NOT NULL,
    active_slot character varying(36) DEFAULT 'active'::character varying NOT NULL,
    created_by character varying(128) DEFAULT 'system'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    decided_by character varying(128) DEFAULT ''::character varying NOT NULL,
    decided_at timestamp with time zone,
    decision_reason text DEFAULT ''::text NOT NULL,
    CONSTRAINT ck_cash_investment_funding_relations_direction CHECK (((direction)::text = ANY ((ARRAY['cash_to_investment'::character varying, 'investment_to_cash'::character varying])::text[]))),
    CONSTRAINT ck_cash_investment_funding_relations_status CHECK (((status)::text = ANY ((ARRAY['pending_review'::character varying, 'accepted'::character varying, 'rejected'::character varying])::text[])))
);

CREATE SEQUENCE public.cash_investment_funding_relations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_investment_funding_relations_id_seq OWNED BY public.cash_investment_funding_relations.id;

CREATE TABLE public.cash_issue_candidates (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    issue_id bigint NOT NULL,
    candidate_transaction_id bigint NOT NULL,
    expected_row_revision bigint NOT NULL,
    CONSTRAINT ck_cash_issue_candidate_revision CHECK ((expected_row_revision >= 1))
);

CREATE SEQUENCE public.cash_issue_candidates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_issue_candidates_id_seq OWNED BY public.cash_issue_candidates.id;

CREATE TABLE public.cash_mutation_receipts (
    id character varying(36) NOT NULL,
    workspace_id character varying(64) NOT NULL,
    user_id character varying(128) NOT NULL,
    idempotency_key character varying(255) NOT NULL,
    request_digest character varying(64) NOT NULL,
    operation_kind character varying(32) NOT NULL,
    plan_id character varying(128),
    session_digest character varying(64),
    edit_context_digest character varying(64),
    result_summary json NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_cash_mutation_receipt_edit_context CHECK (((edit_context_digest IS NULL) OR (length((edit_context_digest)::text) = 64))),
    CONSTRAINT ck_cash_mutation_receipt_one_context CHECK (((session_digest IS NULL) OR (edit_context_digest IS NULL))),
    CONSTRAINT ck_cash_mutation_receipt_request CHECK (((length((idempotency_key)::text) > 0) AND (length((request_digest)::text) = 64))),
    CONSTRAINT ck_cash_mutation_receipt_session CHECK (((session_digest IS NULL) OR (length((session_digest)::text) = 64)))
);

CREATE TABLE public.cash_mutation_results (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    receipt_id character varying(36) NOT NULL,
    result_kind character varying(16) NOT NULL,
    item_ref character varying(255) NOT NULL,
    outcome character varying(32) NOT NULL,
    code character varying(64),
    operation_ref character varying(128),
    transaction_id character varying(128),
    relation_id character varying(128),
    account_id character varying(128),
    CONSTRAINT ck_cash_mutation_result_kind CHECK (((result_kind)::text = ANY ((ARRAY['row'::character varying, 'relation'::character varying, 'file'::character varying, 'account'::character varying])::text[])))
);

CREATE SEQUENCE public.cash_mutation_results_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_mutation_results_id_seq OWNED BY public.cash_mutation_results.id;

CREATE TABLE public.cash_projection_datasets (
    id character varying(64) NOT NULL,
    workspace_id character varying(64) NOT NULL,
    state character varying(16) NOT NULL,
    source_revision bigint NOT NULL,
    source_digest character varying(64) NOT NULL,
    rules_version character varying(64) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    published_at timestamp with time zone,
    CONSTRAINT ck_cash_projection_datasets_state CHECK (((state)::text = ANY ((ARRAY['staging'::character varying, 'active'::character varying])::text[])))
);

CREATE TABLE public.cash_projection_members (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    dataset_id character varying(64) NOT NULL,
    projection_row_id bigint NOT NULL,
    cash_transaction_id bigint NOT NULL,
    roles_json json NOT NULL,
    ordinal integer NOT NULL
);

CREATE SEQUENCE public.cash_projection_members_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_projection_members_id_seq OWNED BY public.cash_projection_members.id;

CREATE TABLE public.cash_projection_relations (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    dataset_id character varying(64) NOT NULL,
    projection_row_id bigint NOT NULL,
    transaction_relation_id bigint NOT NULL,
    kind character varying(32) NOT NULL,
    ordinal integer NOT NULL
);

CREATE SEQUENCE public.cash_projection_relations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_projection_relations_id_seq OWNED BY public.cash_projection_relations.id;

CREATE TABLE public.cash_projection_states (
    workspace_id character varying(64) NOT NULL,
    active_dataset_id character varying(64),
    projection_version bigint DEFAULT '0'::bigint NOT NULL,
    source_revision bigint DEFAULT '0'::bigint NOT NULL,
    rules_version character varying(64) DEFAULT 'cash-projection-v2'::character varying NOT NULL,
    availability character varying(16) DEFAULT 'uninitialized'::character varying NOT NULL,
    last_build_status character varying(16) DEFAULT 'never'::character varying NOT NULL,
    last_build_id character varying(64),
    last_error_code character varying(64),
    last_error_summary text,
    projection_count bigint DEFAULT '0'::bigint NOT NULL,
    member_count bigint DEFAULT '0'::bigint NOT NULL,
    build_started_at timestamp with time zone,
    build_finished_at timestamp with time zone,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_cash_projection_states_availability CHECK (((availability)::text = ANY ((ARRAY['uninitialized'::character varying, 'ready'::character varying])::text[]))),
    CONSTRAINT ck_cash_projection_states_build_status CHECK (((last_build_status)::text = ANY ((ARRAY['never'::character varying, 'running'::character varying, 'succeeded'::character varying, 'failed'::character varying])::text[]))),
    CONSTRAINT ck_cash_projection_states_ready_dataset CHECK ((((availability)::text <> 'ready'::text) OR (active_dataset_id IS NOT NULL)))
);

CREATE TABLE public.cash_projections (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    dataset_id character varying(64) NOT NULL,
    projection_id character varying(96) NOT NULL,
    root_cash_transaction_id bigint NOT NULL,
    economic_type character varying(24),
    business_label character varying(32),
    net_amount numeric(38,18),
    currency character varying(3),
    occurred_at timestamp with time zone,
    account_id bigint,
    counterparty character varying(512) DEFAULT ''::character varying NOT NULL,
    note text DEFAULT ''::text NOT NULL,
    source_type character varying(64),
    record_id character varying(512) DEFAULT ''::character varying NOT NULL,
    visible boolean NOT NULL,
    hidden_reason character varying(32),
    has_payment_mirror boolean DEFAULT false NOT NULL,
    has_refund_offset boolean DEFAULT false NOT NULL,
    has_transfer_pair boolean DEFAULT false NOT NULL,
    member_count integer NOT NULL,
    accepted_relation_count integer NOT NULL,
    built_projection_version bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    funding_relation_id bigint,
    category_id character varying(64),
    category_path character varying(512),
    state character varying(16) DEFAULT 'posted'::character varying NOT NULL,
    statistics_eligible boolean DEFAULT true NOT NULL,
    attention_count integer DEFAULT 0 NOT NULL,
    sort_at timestamp with time zone NOT NULL,
    entry_id character varying(64),
    CONSTRAINT ck_cash_projections_attention CHECK (((attention_count >= 0) AND (attention_count <= member_count))),
    CONSTRAINT ck_cash_projections_counts CHECK (((member_count >= 1) AND (accepted_relation_count >= 0))),
    CONSTRAINT ck_cash_projections_draft_singleton CHECK ((((state)::text <> 'draft'::text) OR ((member_count = 1) AND (accepted_relation_count = 0) AND (attention_count = 1) AND (economic_type IS NULL) AND (funding_relation_id IS NULL) AND (NOT has_payment_mirror) AND (NOT has_refund_offset) AND (NOT has_transfer_pair)))),
    CONSTRAINT ck_cash_projections_economic_type CHECK (((economic_type)::text = ANY ((ARRAY['expense'::character varying, 'income'::character varying, 'internal_transfer'::character varying])::text[]))),
    CONSTRAINT ck_cash_projections_posted_complete CHECK ((((state)::text <> 'posted'::text) OR ((economic_type IS NOT NULL) AND (net_amount IS NOT NULL) AND (currency IS NOT NULL) AND (occurred_at IS NOT NULL)))),
    CONSTRAINT ck_cash_projections_state CHECK (((state)::text = ANY ((ARRAY['draft'::character varying, 'posted'::character varying])::text[]))),
    CONSTRAINT ck_cash_projections_statistics CHECK ((statistics_eligible = ((state)::text = 'posted'::text)))
);

CREATE SEQUENCE public.cash_projections_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_projections_id_seq OWNED BY public.cash_projections.id;

CREATE TABLE public.cash_relation_candidates (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    relation_id bigint NOT NULL,
    candidate_transaction_id bigint NOT NULL,
    candidate_component_id bigint,
    expected_row_revision bigint NOT NULL,
    evidence json NOT NULL,
    validity character varying(16) NOT NULL,
    CONSTRAINT ck_cash_relation_candidate_revision CHECK ((expected_row_revision >= 1)),
    CONSTRAINT ck_cash_relation_candidate_validity CHECK (((validity)::text = ANY ((ARRAY['valid'::character varying, 'incomplete'::character varying, 'stale'::character varying])::text[])))
);

CREATE SEQUENCE public.cash_relation_candidates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_relation_candidates_id_seq OWNED BY public.cash_relation_candidates.id;

CREATE TABLE public.cash_relation_migration_audit (
    relation_id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    legacy_subtype character varying(64) NOT NULL,
    migration_version character varying(32) NOT NULL
);

CREATE SEQUENCE public.cash_relation_migration_audit_relation_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_relation_migration_audit_relation_id_seq OWNED BY public.cash_relation_migration_audit.relation_id;

CREATE TABLE public.cash_source_bindings (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    transaction_id bigint NOT NULL,
    source_type character varying(64) NOT NULL,
    source_scope_kind character varying(16) NOT NULL,
    source_scope_key character varying(256) NOT NULL,
    identity_kind character varying(32) NOT NULL,
    identity_key character varying(64) NOT NULL,
    identity_strength character varying(16) NOT NULL,
    stable_evidence_key character varying(64),
    strategy_version character varying(64) NOT NULL,
    coverage json NOT NULL,
    revision bigint DEFAULT '1'::bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_cash_source_binding_identity CHECK (((identity_kind)::text = ANY ((ARRAY['provider_id'::character varying, 'file_occurrence'::character varying])::text[]))),
    CONSTRAINT ck_cash_source_binding_keys CHECK (((length((source_scope_key)::text) > 0) AND (length((identity_key)::text) > 0))),
    CONSTRAINT ck_cash_source_binding_revision CHECK ((revision >= 1)),
    CONSTRAINT ck_cash_source_binding_scope CHECK (((source_scope_kind)::text = ANY ((ARRAY['account'::character varying, 'file_local'::character varying])::text[]))),
    CONSTRAINT ck_cash_source_binding_strength CHECK (((identity_strength)::text = ANY ((ARRAY['provider'::character varying, 'validated'::character varying, 'manual'::character varying, 'file_local'::character varying])::text[])))
);

CREATE SEQUENCE public.cash_source_bindings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_source_bindings_id_seq OWNED BY public.cash_source_bindings.id;

CREATE TABLE public.cash_source_empty_pages (
    workspace_id character varying(64) NOT NULL,
    source_type character varying(64) NOT NULL,
    parser_version character varying(64) NOT NULL,
    document_key character varying(64) NOT NULL,
    page_number integer NOT NULL,
    coverage json NOT NULL,
    CONSTRAINT ck_cash_source_empty_page_number CHECK (((page_number >= 1) AND (page_number <= 2000)))
);

CREATE TABLE public.cash_transaction_components (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    cash_transaction_id bigint NOT NULL,
    account_id bigint,
    amount numeric(38,18),
    currency character varying(3),
    ordinal integer NOT NULL,
    label character varying(255) DEFAULT ''::character varying NOT NULL,
    source_key character varying(255) DEFAULT ''::character varying NOT NULL,
    metadata_json json DEFAULT '{}'::json NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_cash_transaction_components_ordinal CHECK ((ordinal >= 0))
);

CREATE SEQUENCE public.cash_transaction_components_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_transaction_components_id_seq OWNED BY public.cash_transaction_components.id;

CREATE TABLE public.cash_transaction_issues (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    transaction_id bigint NOT NULL,
    issue_key character varying(128) NOT NULL,
    code character varying(64) NOT NULL,
    field_path character varying(255),
    pending_source_payload json,
    pending_fields json,
    origin json NOT NULL,
    revision bigint DEFAULT '1'::bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_cash_issue_revision CHECK ((revision >= 1))
);

CREATE SEQUENCE public.cash_transaction_issues_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_transaction_issues_id_seq OWNED BY public.cash_transaction_issues.id;

CREATE TABLE public.cash_transactions (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    account_id bigint,
    source_type character varying(64),
    record_id character varying(512) DEFAULT ''::character varying NOT NULL,
    source_payload json,
    occurred_at timestamp with time zone,
    amount numeric(38,18),
    currency character varying(3),
    counterparty character varying(512) DEFAULT ''::character varying NOT NULL,
    note text DEFAULT ''::text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone,
    deleted_by character varying(128) DEFAULT ''::character varying NOT NULL,
    delete_reason text DEFAULT ''::text NOT NULL,
    record_type character varying(32) DEFAULT 'other'::character varying,
    counterparty_account character varying(512) DEFAULT ''::character varying NOT NULL,
    record_subtype character varying(32) DEFAULT 'not_applicable'::character varying,
    counterparty_account_attrs json DEFAULT '[]'::json NOT NULL,
    source_fingerprint character varying(64),
    manual_overrides json,
    category_id character varying(64),
    relation_metadata json,
    cash_granularity character varying(16) DEFAULT 'atomic'::character varying NOT NULL,
    state character varying(16) DEFAULT 'posted'::character varying NOT NULL,
    row_revision bigint DEFAULT '1'::bigint NOT NULL,
    record_origin character varying(24) DEFAULT 'legacy'::character varying NOT NULL,
    manual_transfer_id character varying(64),
    CONSTRAINT ck_cash_transactions_currency CHECK (((currency IS NULL) OR ((length((currency)::text) = 3) AND ((currency)::text = upper((currency)::text))))),
    CONSTRAINT ck_cash_transactions_posted_complete CHECK ((((state)::text <> 'posted'::text) OR ((amount IS NOT NULL) AND (occurred_at IS NOT NULL) AND (currency IS NOT NULL) AND (record_type IS NOT NULL) AND (record_subtype IS NOT NULL) AND ((((cash_granularity)::text = 'aggregate'::text) AND (account_id IS NULL)) OR (((cash_granularity)::text = 'atomic'::text) AND (account_id IS NOT NULL)))))),
    CONSTRAINT ck_cash_transactions_record_origin CHECK (((record_origin)::text = ANY ((ARRAY['import'::character varying, 'manual'::character varying, 'manual_transfer'::character varying, 'legacy'::character varying])::text[]))),
    CONSTRAINT ck_cash_transactions_record_type CHECK (((record_type)::text = ANY ((ARRAY['consumption'::character varying, 'refund'::character varying, 'reversal'::character varying, 'transfer_reversal'::character varying, 'withdrawal_in'::character varying, 'withdrawal_out'::character varying, 'transfer_in'::character varying, 'transfer_out'::character varying, 'repayment'::character varying, 'income'::character varying, 'investment_in'::character varying, 'investment_out'::character varying, 'interest'::character varying, 'fee'::character varying, 'fx_in'::character varying, 'fx_out'::character varying, 'other'::character varying])::text[]))),
    CONSTRAINT ck_cash_transactions_record_type_subtype CHECK (((((record_type)::text = ANY ((ARRAY['transfer_in'::character varying, 'transfer_out'::character varying])::text[])) AND ((record_subtype)::text = ANY ((ARRAY['ordinary_transfer'::character varying, 'cross_border_remittance'::character varying, 'internal_account_transfer'::character varying])::text[]))) OR (((record_type)::text = ANY ((ARRAY['fx_in'::character varying, 'fx_out'::character varying])::text[])) AND ((record_subtype)::text = 'currency_exchange'::text)) OR (((record_type)::text = 'repayment'::text) AND ((record_subtype)::text = 'credit_repayment'::text)) OR (((record_type)::text = ANY ((ARRAY['withdrawal_in'::character varying, 'withdrawal_out'::character varying])::text[])) AND ((record_subtype)::text = 'withdraw_to_bank'::text)) OR (((record_type)::text <> ALL ((ARRAY['transfer_in'::character varying, 'transfer_out'::character varying, 'fx_in'::character varying, 'fx_out'::character varying, 'repayment'::character varying, 'withdrawal_in'::character varying, 'withdrawal_out'::character varying])::text[])) AND ((record_subtype)::text = 'not_applicable'::text)))),
    CONSTRAINT ck_cash_transactions_row_revision CHECK ((row_revision >= 1)),
    CONSTRAINT ck_cash_transactions_state CHECK (((state)::text = ANY ((ARRAY['draft'::character varying, 'posted'::character varying])::text[])))
);

CREATE SEQUENCE public.cash_transactions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.cash_transactions_id_seq OWNED BY public.cash_transactions.id;

CREATE TABLE public.investment_events (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    account_id bigint NOT NULL,
    source_type character varying(64),
    record_id character varying(512) DEFAULT ''::character varying NOT NULL,
    source_payload json,
    occurred_at timestamp with time zone NOT NULL,
    record_type character varying(64) NOT NULL,
    currency character varying(3) DEFAULT ''::character varying NOT NULL,
    note text DEFAULT ''::text NOT NULL,
    from_ticker character varying(64) DEFAULT ''::character varying NOT NULL,
    from_amount numeric(38,18),
    to_ticker character varying(64) DEFAULT ''::character varying NOT NULL,
    to_amount numeric(38,18),
    commission numeric(38,18),
    commission_asset character varying(64) DEFAULT ''::character varying NOT NULL,
    payload json NOT NULL,
    created_at timestamp with time zone NOT NULL,
    record_subtype character varying(32) DEFAULT 'not_applicable'::character varying NOT NULL,
    CONSTRAINT ck_investment_events_record_type CHECK (((record_type)::text = ANY ((ARRAY['funding'::character varying, 'trade'::character varying, 'income'::character varying, 'expense'::character varying, 'reversal'::character varying, 'subscription'::character varying, 'adjustment'::character varying, 'snapshot'::character varying])::text[]))),
    CONSTRAINT ck_investment_events_record_type_subtype CHECK (((((record_type)::text = 'funding'::text) AND ((record_subtype)::text = ANY ((ARRAY['external'::character varying, 'subaccount'::character varying])::text[]))) OR (((record_type)::text = 'trade'::text) AND ((record_subtype)::text = ANY ((ARRAY['security'::character varying, 'fx'::character varying, 'repo'::character varying])::text[]))) OR (((record_type)::text = 'income'::text) AND ((record_subtype)::text = ANY ((ARRAY['dividend_cash'::character varying, 'dividend_stock'::character varying, 'interest'::character varying, 'reward'::character varying])::text[]))) OR (((record_type)::text = 'expense'::text) AND ((record_subtype)::text = ANY ((ARRAY['commission'::character varying, 'tax'::character varying, 'interest'::character varying, 'handling_fee'::character varying, 'penalty'::character varying])::text[]))) OR (((record_type)::text = 'reversal'::text) AND ((record_subtype)::text = ANY ((ARRAY['expense_tax'::character varying, 'expense_interest'::character varying, 'expense_commission'::character varying, 'expense_handling_fee'::character varying, 'expense_penalty'::character varying, 'funding_withdrawal'::character varying])::text[]))) OR (((record_type)::text = 'subscription'::text) AND ((record_subtype)::text = ANY ((ARRAY['ipo_debit'::character varying, 'ipo_refund'::character varying])::text[]))) OR (((record_type)::text = 'adjustment'::text) AND ((record_subtype)::text = ANY ((ARRAY['fx_net'::character varying, 'manual'::character varying, 'unclassified'::character varying])::text[]))) OR (((record_type)::text = 'snapshot'::text) AND ((record_subtype)::text = ANY ((ARRAY['cash'::character varying, 'position'::character varying])::text[])))))
);

CREATE SEQUENCE public.investment_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.investment_events_id_seq OWNED BY public.investment_events.id;

CREATE TABLE public.ledger_snapshots (
    workspace_id character varying(64) NOT NULL,
    payload json NOT NULL,
    version integer NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE TABLE public.statement_account_mappings (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    source_type character varying(64) NOT NULL,
    identity_kind character varying(64) NOT NULL,
    source_account_key character varying(255) NOT NULL,
    account_id bigint NOT NULL,
    confirmed_by character varying(128) DEFAULT ''::character varying NOT NULL,
    revision bigint DEFAULT '1'::bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE SEQUENCE public.statement_account_mappings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.statement_account_mappings_id_seq OWNED BY public.statement_account_mappings.id;

CREATE TABLE public.sync_cursors (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    account_id bigint NOT NULL,
    source_type character varying(64) NOT NULL,
    cursor_value character varying(256) NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE SEQUENCE public.sync_cursors_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.sync_cursors_id_seq OWNED BY public.sync_cursors.id;

CREATE TABLE public.transaction_relations (
    id bigint NOT NULL,
    workspace_id character varying(64) NOT NULL,
    kind character varying(32) NOT NULL,
    primary_component_id bigint,
    secondary_component_id bigint,
    applied_amount numeric(38,18) DEFAULT 0 NOT NULL,
    rule_id character varying(128) DEFAULT ''::character varying NOT NULL,
    created_by character varying(128) DEFAULT 'system'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    decided_by character varying(128) DEFAULT ''::character varying NOT NULL,
    decided_at timestamp with time zone,
    decision_reason text DEFAULT ''::text NOT NULL,
    anchor_component_id bigint,
    state character varying(16) DEFAULT 'draft'::character varying NOT NULL,
    active boolean DEFAULT true NOT NULL,
    primary_transaction_id bigint NOT NULL,
    secondary_transaction_id bigint,
    anchor_transaction_id bigint NOT NULL,
    primary_role character varying(32),
    secondary_role character varying(32),
    decision_group_id character varying(128),
    decision_source character varying(32) DEFAULT 'rule'::character varying NOT NULL,
    evidence json DEFAULT '{}'::json NOT NULL,
    row_revision bigint DEFAULT '1'::bigint NOT NULL,
    CONSTRAINT ck_cash_relation_anchor CHECK ((((anchor_transaction_id = primary_transaction_id) OR ((secondary_transaction_id IS NOT NULL) AND (anchor_transaction_id = secondary_transaction_id))) AND ((anchor_component_id IS NULL) OR ((primary_component_id IS NOT NULL) AND (anchor_component_id = primary_component_id)) OR ((secondary_component_id IS NOT NULL) AND (anchor_component_id = secondary_component_id))))),
    CONSTRAINT ck_cash_relation_confirmed_shape CHECK ((((state)::text <> 'confirmed'::text) OR ((secondary_transaction_id IS NOT NULL) AND (primary_component_id IS NOT NULL) AND (secondary_component_id IS NOT NULL) AND (anchor_component_id IS NOT NULL) AND (primary_role IS NOT NULL) AND (secondary_role IS NOT NULL)))),
    CONSTRAINT ck_cash_relation_distinct_parents CHECK (((secondary_transaction_id IS NULL) OR (primary_transaction_id <> secondary_transaction_id))),
    CONSTRAINT ck_cash_relation_primary_role CHECK (((primary_role IS NULL) OR ((primary_role)::text = ANY ((ARRAY['expense'::character varying, 'refund'::character varying, 'p2p_out'::character varying, 'p2p_in'::character varying, 'outgoing'::character varying, 'incoming'::character varying])::text[])))),
    CONSTRAINT ck_cash_relation_revision CHECK ((row_revision >= 1)),
    CONSTRAINT ck_cash_relation_secondary_role CHECK (((secondary_role IS NULL) OR ((secondary_role)::text = ANY ((ARRAY['expense'::character varying, 'refund'::character varying, 'p2p_out'::character varying, 'p2p_in'::character varying, 'outgoing'::character varying, 'incoming'::character varying])::text[])))),
    CONSTRAINT ck_cash_relation_secondary_shape CHECK (((secondary_transaction_id IS NOT NULL) OR ((secondary_component_id IS NULL) AND (secondary_role IS NULL)))),
    CONSTRAINT ck_cash_relation_state CHECK (((state)::text = ANY ((ARRAY['draft'::character varying, 'confirmed'::character varying, 'ignored'::character varying])::text[]))),
    CONSTRAINT ck_transaction_relations_kind CHECK (((kind)::text = ANY ((ARRAY['payment_mirror'::character varying, 'transfer_pair'::character varying, 'refund_offset'::character varying])::text[])))
);

CREATE SEQUENCE public.transaction_relations_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.transaction_relations_id_seq OWNED BY public.transaction_relations.id;

CREATE TABLE public.user_sessions (
    id character varying(36) NOT NULL,
    user_id character varying(36) NOT NULL,
    token_digest character varying(64) NOT NULL,
    active_workspace_id character varying(64),
    expires_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE public.users (
    id character varying(36) NOT NULL,
    email character varying(320) NOT NULL,
    password_hash character varying(512) NOT NULL,
    created_at timestamp with time zone NOT NULL
);

CREATE TABLE public.workspace_invitations (
    id character varying(36) NOT NULL,
    workspace_id character varying(64) NOT NULL,
    role character varying(16) NOT NULL,
    token_digest character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    accepted_at timestamp with time zone,
    created_by_user_id character varying(36) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_workspace_invitations_role CHECK (((role)::text = ANY ((ARRAY['editor'::character varying, 'viewer'::character varying])::text[])))
);

CREATE TABLE public.workspace_memberships (
    workspace_id character varying(64) NOT NULL,
    user_id character varying(36) NOT NULL,
    role character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT ck_workspace_memberships_role CHECK (((role)::text = ANY ((ARRAY['admin'::character varying, 'editor'::character varying, 'viewer'::character varying])::text[])))
);

CREATE TABLE public.workspaces (
    ledger_seq bigint DEFAULT 0 NOT NULL CONSTRAINT ck_workspaces_ledger_seq_nonnegative CHECK (ledger_seq >= 0),
    id character varying(64) NOT NULL,
    name character varying(255) NOT NULL,
    created_at timestamp with time zone NOT NULL
);

ALTER TABLE ONLY public.account_aliases ALTER COLUMN id SET DEFAULT nextval('public.account_aliases_id_seq'::regclass);

ALTER TABLE ONLY public.accounts ALTER COLUMN id SET DEFAULT nextval('public.accounts_id_seq'::regclass);

ALTER TABLE ONLY public.cash_investment_funding_relations ALTER COLUMN id SET DEFAULT nextval('public.cash_investment_funding_relations_id_seq'::regclass);

ALTER TABLE ONLY public.cash_issue_candidates ALTER COLUMN id SET DEFAULT nextval('public.cash_issue_candidates_id_seq'::regclass);

ALTER TABLE ONLY public.cash_mutation_results ALTER COLUMN id SET DEFAULT nextval('public.cash_mutation_results_id_seq'::regclass);

ALTER TABLE ONLY public.cash_projection_members ALTER COLUMN id SET DEFAULT nextval('public.cash_projection_members_id_seq'::regclass);

ALTER TABLE ONLY public.cash_projection_relations ALTER COLUMN id SET DEFAULT nextval('public.cash_projection_relations_id_seq'::regclass);

ALTER TABLE ONLY public.cash_projections ALTER COLUMN id SET DEFAULT nextval('public.cash_projections_id_seq'::regclass);

ALTER TABLE ONLY public.cash_relation_candidates ALTER COLUMN id SET DEFAULT nextval('public.cash_relation_candidates_id_seq'::regclass);

ALTER TABLE ONLY public.cash_relation_migration_audit ALTER COLUMN relation_id SET DEFAULT nextval('public.cash_relation_migration_audit_relation_id_seq'::regclass);

ALTER TABLE ONLY public.cash_source_bindings ALTER COLUMN id SET DEFAULT nextval('public.cash_source_bindings_id_seq'::regclass);

ALTER TABLE ONLY public.cash_transaction_components ALTER COLUMN id SET DEFAULT nextval('public.cash_transaction_components_id_seq'::regclass);

ALTER TABLE ONLY public.cash_transaction_issues ALTER COLUMN id SET DEFAULT nextval('public.cash_transaction_issues_id_seq'::regclass);

ALTER TABLE ONLY public.cash_transactions ALTER COLUMN id SET DEFAULT nextval('public.cash_transactions_id_seq'::regclass);

ALTER TABLE ONLY public.investment_events ALTER COLUMN id SET DEFAULT nextval('public.investment_events_id_seq'::regclass);

ALTER TABLE ONLY public.statement_account_mappings ALTER COLUMN id SET DEFAULT nextval('public.statement_account_mappings_id_seq'::regclass);

ALTER TABLE ONLY public.sync_cursors ALTER COLUMN id SET DEFAULT nextval('public.sync_cursors_id_seq'::regclass);

ALTER TABLE ONLY public.transaction_relations ALTER COLUMN id SET DEFAULT nextval('public.transaction_relations_id_seq'::regclass);

ALTER TABLE ONLY public.account_aliases
    ADD CONSTRAINT account_aliases__i_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.account_aliases
    ADD CONSTRAINT account_aliases__i_workspace_id_alias_type_alias_value_acco_key UNIQUE (workspace_id, alias_type, alias_value, account_id);

ALTER TABLE ONLY public.account_aliases
    ADD CONSTRAINT account_aliases__i_workspace_id_id_key UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts__i_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts__i_workspace_id_id_key UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts__i_workspace_id_name_key UNIQUE (workspace_id, name);

ALTER TABLE ONLY public.alembic_version
    ADD CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num);

ALTER TABLE ONLY public.cash_categories
    ADD CONSTRAINT cash_categories_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_category_states
    ADD CONSTRAINT cash_category_states_pkey PRIMARY KEY (workspace_id);

ALTER TABLE ONLY public.cash_entries
    ADD CONSTRAINT cash_entries_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_entry_members
    ADD CONSTRAINT cash_entry_members_pkey PRIMARY KEY (workspace_id, transaction_id);

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT cash_investment_funding_relations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_issue_candidates
    ADD CONSTRAINT cash_issue_candidates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_mutation_receipts
    ADD CONSTRAINT cash_mutation_receipts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_mutation_results
    ADD CONSTRAINT cash_mutation_results_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_projection_datasets
    ADD CONSTRAINT cash_projection_datasets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_projection_members
    ADD CONSTRAINT cash_projection_members_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_projection_relations
    ADD CONSTRAINT cash_projection_relations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_projection_states
    ADD CONSTRAINT cash_projection_states_pkey PRIMARY KEY (workspace_id);

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT cash_projections_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_relation_candidates
    ADD CONSTRAINT cash_relation_candidates_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_relation_migration_audit
    ADD CONSTRAINT cash_relation_migration_audit_pkey PRIMARY KEY (relation_id);

ALTER TABLE ONLY public.cash_source_bindings
    ADD CONSTRAINT cash_source_bindings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_source_empty_pages
    ADD CONSTRAINT cash_source_empty_pages_pkey PRIMARY KEY (workspace_id, source_type, parser_version, document_key, page_number);

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT cash_transaction_components_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_transaction_issues
    ADD CONSTRAINT cash_transaction_issues_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_transactions
    ADD CONSTRAINT cash_transactions__i_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_transactions
    ADD CONSTRAINT cash_transactions__i_workspace_id_id_key UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.investment_events
    ADD CONSTRAINT investment_events__i_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.investment_events
    ADD CONSTRAINT investment_events__i_workspace_id_id_key UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.ledger_snapshots
    ADD CONSTRAINT ledger_snapshots_pkey PRIMARY KEY (workspace_id);

ALTER TABLE ONLY public.statement_account_mappings
    ADD CONSTRAINT statement_account_mappings_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.sync_cursors
    ADD CONSTRAINT sync_cursors_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT transaction_relations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.cash_categories
    ADD CONSTRAINT uq_cash_categories_sibling_name UNIQUE (workspace_id, parent_scope_key, normalized_name);

ALTER TABLE ONLY public.cash_categories
    ADD CONSTRAINT uq_cash_categories_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT uq_cash_components_workspace_parent_id UNIQUE (workspace_id, cash_transaction_id, id);

ALTER TABLE ONLY public.cash_entries
    ADD CONSTRAINT uq_cash_entries_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT uq_cash_investment_funding_relations_active_pair UNIQUE (workspace_id, cash_transaction_component_id, investment_event_id, active_slot);

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT uq_cash_investment_funding_relations_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_issue_candidates
    ADD CONSTRAINT uq_cash_issue_candidate UNIQUE (workspace_id, issue_id, candidate_transaction_id);

ALTER TABLE ONLY public.cash_transaction_issues
    ADD CONSTRAINT uq_cash_issue_transaction_key UNIQUE (workspace_id, transaction_id, issue_key);

ALTER TABLE ONLY public.cash_transaction_issues
    ADD CONSTRAINT uq_cash_issue_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_mutation_receipts
    ADD CONSTRAINT uq_cash_mutation_receipt_user_key UNIQUE (workspace_id, user_id, idempotency_key);

ALTER TABLE ONLY public.cash_mutation_receipts
    ADD CONSTRAINT uq_cash_mutation_receipt_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_mutation_results
    ADD CONSTRAINT uq_cash_mutation_result_item UNIQUE (workspace_id, receipt_id, result_kind, item_ref);

ALTER TABLE ONLY public.cash_projection_datasets
    ADD CONSTRAINT uq_cash_projection_datasets_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_projection_members
    ADD CONSTRAINT uq_cash_projection_members_dataset_cash UNIQUE (workspace_id, dataset_id, cash_transaction_id);

ALTER TABLE ONLY public.cash_projection_members
    ADD CONSTRAINT uq_cash_projection_members_ordinal UNIQUE (projection_row_id, ordinal);

ALTER TABLE ONLY public.cash_projection_relations
    ADD CONSTRAINT uq_cash_projection_relations_dataset_relation UNIQUE (workspace_id, dataset_id, transaction_relation_id);

ALTER TABLE ONLY public.cash_projection_relations
    ADD CONSTRAINT uq_cash_projection_relations_ordinal UNIQUE (projection_row_id, ordinal);

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT uq_cash_projections_dataset_projection UNIQUE (workspace_id, dataset_id, projection_id);

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT uq_cash_projections_workspace_dataset_id UNIQUE (workspace_id, dataset_id, id);

ALTER TABLE ONLY public.cash_source_bindings
    ADD CONSTRAINT uq_cash_source_binding_identity UNIQUE (workspace_id, source_type, source_scope_kind, source_scope_key, identity_kind, identity_key);

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT uq_cash_transaction_components_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT uq_cash_transaction_components_workspace_transaction_ordinal UNIQUE (workspace_id, cash_transaction_id, ordinal);

ALTER TABLE ONLY public.statement_account_mappings
    ADD CONSTRAINT uq_statement_account_mappings_source_identity UNIQUE (workspace_id, source_type, identity_kind, source_account_key);

ALTER TABLE ONLY public.statement_account_mappings
    ADD CONSTRAINT uq_statement_account_mappings_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.sync_cursors
    ADD CONSTRAINT uq_sync_cursors_workspace_account_source UNIQUE (workspace_id, account_id, source_type);

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT uq_transaction_relations_workspace_id UNIQUE (workspace_id, id);

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT user_sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT user_sessions_token_digest_key UNIQUE (token_digest);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_token_digest_key UNIQUE (token_digest);

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_pkey PRIMARY KEY (workspace_id, user_id);

ALTER TABLE ONLY public.workspaces
    ADD CONSTRAINT workspaces_pkey PRIMARY KEY (id);

CREATE INDEX ix_account_aliases_workspace_value ON public.account_aliases USING btree (workspace_id, alias_type, alias_value);

CREATE INDEX ix_accounts_workspace ON public.accounts USING btree (workspace_id);

CREATE INDEX ix_cash_categories_workspace_parent_order ON public.cash_categories USING btree (workspace_id, parent_scope_key, sort_order, id);

CREATE INDEX ix_cash_categories_workspace_path ON public.cash_categories USING btree (workspace_id, category_path);

CREATE INDEX ix_cash_entries_workspace_category ON public.cash_entries USING btree (workspace_id, category_id);

CREATE INDEX ix_cash_entry_members_entry ON public.cash_entry_members USING btree (workspace_id, entry_id);

CREATE INDEX ix_cash_investment_funding_relations_workspace_status ON public.cash_investment_funding_relations USING btree (workspace_id, status);

CREATE INDEX ix_cash_issue_candidate_transaction ON public.cash_issue_candidates USING btree (workspace_id, candidate_transaction_id);

CREATE INDEX ix_cash_issue_transaction ON public.cash_transaction_issues USING btree (workspace_id, transaction_id);

CREATE INDEX ix_cash_mutation_result_all_page ON public.cash_mutation_results USING btree (workspace_id, receipt_id, id);

CREATE INDEX ix_cash_mutation_result_page ON public.cash_mutation_results USING btree (workspace_id, receipt_id, result_kind, id);

CREATE INDEX ix_cash_projection_datasets_workspace_state ON public.cash_projection_datasets USING btree (workspace_id, state);

CREATE INDEX ix_cash_projection_members_dataset ON public.cash_projection_members USING btree (dataset_id);

CREATE INDEX ix_cash_projection_members_page_lookup ON public.cash_projection_members USING btree (workspace_id, dataset_id, projection_row_id, ordinal);

CREATE INDEX ix_cash_projection_relations_dataset ON public.cash_projection_relations USING btree (dataset_id);

CREATE INDEX ix_cash_projection_relations_page_lookup ON public.cash_projection_relations USING btree (workspace_id, dataset_id, projection_row_id, kind, ordinal);

CREATE INDEX ix_cash_projections_account ON public.cash_projections USING btree (workspace_id, dataset_id, account_id);

CREATE INDEX ix_cash_projections_attention_sort ON public.cash_projections USING btree (workspace_id, dataset_id, attention_count, sort_at, projection_id);

CREATE INDEX ix_cash_projections_category_id ON public.cash_projections USING btree (workspace_id, dataset_id, category_id);

CREATE INDEX ix_cash_projections_category_path ON public.cash_projections USING btree (workspace_id, dataset_id, category_path);

CREATE INDEX ix_cash_projections_currency ON public.cash_projections USING btree (workspace_id, dataset_id, currency);

CREATE INDEX ix_cash_projections_current_sort ON public.cash_projections USING btree (workspace_id, dataset_id, sort_at, projection_id);

CREATE INDEX ix_cash_projections_economic_type ON public.cash_projections USING btree (workspace_id, dataset_id, economic_type);

CREATE INDEX ix_cash_projections_root ON public.cash_projections USING btree (workspace_id, dataset_id, root_cash_transaction_id);

CREATE INDEX ix_cash_projections_state_sort ON public.cash_projections USING btree (workspace_id, dataset_id, state, sort_at, projection_id);

CREATE INDEX ix_cash_projections_visible_list ON public.cash_projections USING btree (workspace_id, dataset_id, visible, occurred_at, projection_id);

CREATE INDEX ix_cash_relation_anchor ON public.transaction_relations USING btree (workspace_id, anchor_transaction_id, active);

CREATE INDEX ix_cash_relation_anchor_component ON public.transaction_relations USING btree (workspace_id, anchor_component_id, active);

CREATE INDEX ix_cash_relation_candidate_component ON public.cash_relation_candidates USING btree (workspace_id, candidate_component_id, relation_id);

CREATE INDEX ix_cash_relation_candidate_parent ON public.cash_relation_candidates USING btree (workspace_id, candidate_transaction_id, relation_id);

CREATE INDEX ix_cash_relation_group ON public.transaction_relations USING btree (workspace_id, decision_group_id, active);

CREATE INDEX ix_cash_relation_primary ON public.transaction_relations USING btree (workspace_id, primary_transaction_id, active, state);

CREATE INDEX ix_cash_relation_primary_component ON public.transaction_relations USING btree (workspace_id, primary_component_id, active, state);

CREATE INDEX ix_cash_relation_secondary ON public.transaction_relations USING btree (workspace_id, secondary_transaction_id, active, state);

CREATE INDEX ix_cash_relation_secondary_component ON public.transaction_relations USING btree (workspace_id, secondary_component_id, active, state);

CREATE INDEX ix_cash_relation_state ON public.transaction_relations USING btree (workspace_id, active, state, kind);

CREATE INDEX ix_cash_source_binding_evidence ON public.cash_source_bindings USING btree (workspace_id, stable_evidence_key);

CREATE INDEX ix_cash_source_binding_transaction ON public.cash_source_bindings USING btree (workspace_id, transaction_id);

CREATE INDEX ix_cash_transaction_components_workspace_account ON public.cash_transaction_components USING btree (workspace_id, account_id);

CREATE INDEX ix_cash_transaction_components_workspace_transaction ON public.cash_transaction_components USING btree (workspace_id, cash_transaction_id);

CREATE INDEX ix_cash_transactions_workspace_account ON public.cash_transactions USING btree (workspace_id, account_id);

CREATE INDEX ix_cash_transactions_workspace_category ON public.cash_transactions USING btree (workspace_id, category_id);

CREATE INDEX ix_cash_transactions_workspace_date ON public.cash_transactions USING btree (workspace_id, occurred_at);

CREATE INDEX ix_cash_transactions_workspace_source_record ON public.cash_transactions USING btree (workspace_id, source_type, record_id);

CREATE INDEX ix_cash_transactions_workspace_state ON public.cash_transactions USING btree (workspace_id, state, occurred_at, id);

CREATE INDEX ix_investment_events_workspace_account ON public.investment_events USING btree (workspace_id, account_id);

CREATE INDEX ix_investment_events_workspace_date ON public.investment_events USING btree (workspace_id, occurred_at);

CREATE INDEX ix_investment_events_workspace_source_record ON public.investment_events USING btree (workspace_id, source_type, record_id);

CREATE INDEX ix_statement_account_mappings_workspace ON public.statement_account_mappings USING btree (workspace_id);

CREATE INDEX ix_sync_cursors_workspace ON public.sync_cursors USING btree (workspace_id);

CREATE INDEX ix_user_sessions_active_workspace ON public.user_sessions USING btree (active_workspace_id);

CREATE INDEX ix_user_sessions_user ON public.user_sessions USING btree (user_id);

CREATE INDEX ix_workspace_invitations_workspace ON public.workspace_invitations USING btree (workspace_id);

CREATE INDEX ix_workspace_memberships_user ON public.workspace_memberships USING btree (user_id);

CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_cash ON public.cash_investment_funding_relations USING btree (workspace_id, cash_transaction_component_id) WHERE (((status)::text = 'accepted'::text) AND ((active_slot)::text = 'active'::text));

CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_investment ON public.cash_investment_funding_relations USING btree (workspace_id, investment_event_id) WHERE (((status)::text = 'accepted'::text) AND ((active_slot)::text = 'active'::text));

CREATE UNIQUE INDEX uq_cash_mutation_receipt_edit_context ON public.cash_mutation_receipts USING btree (workspace_id, user_id, edit_context_digest) WHERE (edit_context_digest IS NOT NULL);

CREATE UNIQUE INDEX uq_cash_mutation_receipt_session ON public.cash_mutation_receipts USING btree (workspace_id, user_id, session_digest) WHERE (session_digest IS NOT NULL);

CREATE UNIQUE INDEX uq_cash_projection_datasets_active_workspace ON public.cash_projection_datasets USING btree (workspace_id) WHERE ((state)::text = 'active'::text);

CREATE UNIQUE INDEX uq_cash_relation_candidate ON public.cash_relation_candidates USING btree (workspace_id, relation_id, candidate_transaction_id, COALESCE(candidate_component_id, (0)::bigint));

CREATE UNIQUE INDEX uq_cash_relation_open_anchor ON public.transaction_relations USING btree (workspace_id, kind, anchor_transaction_id, COALESCE(anchor_component_id, (0)::bigint)) WHERE (active AND (secondary_transaction_id IS NULL));

CREATE UNIQUE INDEX uq_cash_relation_pair ON public.transaction_relations USING btree (workspace_id, kind, (
CASE
    WHEN (((kind)::text = 'payment_mirror'::text) AND (((primary_component_id IS NOT NULL) AND (secondary_component_id IS NOT NULL) AND (primary_component_id > secondary_component_id)) OR (((primary_component_id IS NULL) OR (secondary_component_id IS NULL)) AND (primary_transaction_id > secondary_transaction_id)))) THEN secondary_transaction_id
    ELSE primary_transaction_id
END), (
CASE
    WHEN (((kind)::text = 'payment_mirror'::text) AND (((primary_component_id IS NOT NULL) AND (secondary_component_id IS NOT NULL) AND (primary_component_id > secondary_component_id)) OR (((primary_component_id IS NULL) OR (secondary_component_id IS NULL)) AND (primary_transaction_id > secondary_transaction_id)))) THEN primary_transaction_id
    ELSE secondary_transaction_id
END), COALESCE(
CASE
    WHEN (((kind)::text = 'payment_mirror'::text) AND (((primary_component_id IS NOT NULL) AND (secondary_component_id IS NOT NULL) AND (primary_component_id > secondary_component_id)) OR (((primary_component_id IS NULL) OR (secondary_component_id IS NULL)) AND (primary_transaction_id > secondary_transaction_id)))) THEN secondary_component_id
    ELSE primary_component_id
END, (0)::bigint), COALESCE(
CASE
    WHEN (((kind)::text = 'payment_mirror'::text) AND (((primary_component_id IS NOT NULL) AND (secondary_component_id IS NOT NULL) AND (primary_component_id > secondary_component_id)) OR (((primary_component_id IS NULL) OR (secondary_component_id IS NULL)) AND (primary_transaction_id > secondary_transaction_id)))) THEN primary_component_id
    ELSE secondary_component_id
END, (0)::bigint)) WHERE (active AND (secondary_transaction_id IS NOT NULL));

CREATE UNIQUE INDEX uq_cash_transactions_active_source_record ON public.cash_transactions USING btree (workspace_id, source_type, record_id) WHERE ((source_type IS NOT NULL) AND ((source_type)::text <> ''::text) AND (record_id IS NOT NULL) AND ((record_id)::text <> ''::text) AND (deleted_at IS NULL));

CREATE UNIQUE INDEX uq_investment_events_source_record ON public.investment_events USING btree (workspace_id, source_type, record_id) WHERE ((source_type IS NOT NULL) AND ((source_type)::text <> ''::text) AND (record_id IS NOT NULL) AND ((record_id)::text <> ''::text));

ALTER TABLE ONLY public.account_aliases
    ADD CONSTRAINT account_aliases__i_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.accounts
    ADD CONSTRAINT accounts__i_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_categories
    ADD CONSTRAINT cash_categories_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_category_states
    ADD CONSTRAINT cash_category_states_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_entries
    ADD CONSTRAINT cash_entries_workspace_id_category_id_fkey FOREIGN KEY (workspace_id, category_id) REFERENCES public.cash_categories(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_entries
    ADD CONSTRAINT cash_entries_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_entry_members
    ADD CONSTRAINT cash_entry_members_workspace_id_entry_id_fkey FOREIGN KEY (workspace_id, entry_id) REFERENCES public.cash_entries(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_entry_members
    ADD CONSTRAINT cash_entry_members_workspace_id_transaction_id_fkey FOREIGN KEY (workspace_id, transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_mutation_receipts
    ADD CONSTRAINT cash_mutation_receipts_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projection_datasets
    ADD CONSTRAINT cash_projection_datasets_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projection_states
    ADD CONSTRAINT cash_projection_states_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_relation_candidates
    ADD CONSTRAINT cash_relation_candidates_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_relation_migration_audit
    ADD CONSTRAINT cash_relation_migration_audit_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_source_empty_pages
    ADD CONSTRAINT cash_source_empty_pages_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT cash_transaction_components_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_transactions
    ADD CONSTRAINT cash_transactions__i_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_categories
    ADD CONSTRAINT fk_cash_categories_workspace_parent FOREIGN KEY (workspace_id, parent_id) REFERENCES public.cash_categories(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT fk_cash_investment_funding_relations_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT fk_cash_investment_funding_relations_workspace_component FOREIGN KEY (workspace_id, cash_transaction_component_id) REFERENCES public.cash_transaction_components(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_investment_funding_relations
    ADD CONSTRAINT fk_cash_investment_funding_relations_workspace_investment FOREIGN KEY (workspace_id, investment_event_id) REFERENCES public.investment_events(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_issue_candidates
    ADD CONSTRAINT fk_cash_issue_candidate_issue FOREIGN KEY (workspace_id, issue_id) REFERENCES public.cash_transaction_issues(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_issue_candidates
    ADD CONSTRAINT fk_cash_issue_candidate_transaction FOREIGN KEY (workspace_id, candidate_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_transaction_issues
    ADD CONSTRAINT fk_cash_issue_transaction FOREIGN KEY (workspace_id, transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_mutation_results
    ADD CONSTRAINT fk_cash_mutation_result_receipt FOREIGN KEY (workspace_id, receipt_id) REFERENCES public.cash_mutation_receipts(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projection_members
    ADD CONSTRAINT fk_cash_projection_members_cash FOREIGN KEY (workspace_id, cash_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_projection_members
    ADD CONSTRAINT fk_cash_projection_members_projection FOREIGN KEY (workspace_id, dataset_id, projection_row_id) REFERENCES public.cash_projections(workspace_id, dataset_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projection_relations
    ADD CONSTRAINT fk_cash_projection_relations_projection FOREIGN KEY (workspace_id, dataset_id, projection_row_id) REFERENCES public.cash_projections(workspace_id, dataset_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projection_relations
    ADD CONSTRAINT fk_cash_projection_relations_relation FOREIGN KEY (workspace_id, transaction_relation_id) REFERENCES public.transaction_relations(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_projection_states
    ADD CONSTRAINT fk_cash_projection_states_workspace_dataset FOREIGN KEY (workspace_id, active_dataset_id) REFERENCES public.cash_projection_datasets(workspace_id, id);

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT fk_cash_projections_dataset FOREIGN KEY (workspace_id, dataset_id) REFERENCES public.cash_projection_datasets(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT fk_cash_projections_workspace_account FOREIGN KEY (workspace_id, account_id) REFERENCES public.accounts(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT fk_cash_projections_workspace_category FOREIGN KEY (workspace_id, category_id) REFERENCES public.cash_categories(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT fk_cash_projections_workspace_funding_relation FOREIGN KEY (workspace_id, funding_relation_id) REFERENCES public.cash_investment_funding_relations(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_projections
    ADD CONSTRAINT fk_cash_projections_workspace_root FOREIGN KEY (workspace_id, root_cash_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_anchor_component FOREIGN KEY (workspace_id, anchor_transaction_id, anchor_component_id) REFERENCES public.cash_transaction_components(workspace_id, cash_transaction_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_anchor_parent FOREIGN KEY (workspace_id, anchor_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_relation_candidates
    ADD CONSTRAINT fk_cash_relation_candidate_component FOREIGN KEY (workspace_id, candidate_transaction_id, candidate_component_id) REFERENCES public.cash_transaction_components(workspace_id, cash_transaction_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_relation_candidates
    ADD CONSTRAINT fk_cash_relation_candidate_parent FOREIGN KEY (workspace_id, candidate_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_relation_candidates
    ADD CONSTRAINT fk_cash_relation_candidate_relation FOREIGN KEY (workspace_id, relation_id) REFERENCES public.transaction_relations(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_primary_component FOREIGN KEY (workspace_id, primary_transaction_id, primary_component_id) REFERENCES public.cash_transaction_components(workspace_id, cash_transaction_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_primary_parent FOREIGN KEY (workspace_id, primary_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_secondary_component FOREIGN KEY (workspace_id, secondary_transaction_id, secondary_component_id) REFERENCES public.cash_transaction_components(workspace_id, cash_transaction_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_cash_relation_secondary_parent FOREIGN KEY (workspace_id, secondary_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_source_bindings
    ADD CONSTRAINT fk_cash_source_binding_transaction FOREIGN KEY (workspace_id, transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT fk_cash_transaction_components_workspace_account FOREIGN KEY (workspace_id, account_id) REFERENCES public.accounts(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_transaction_components
    ADD CONSTRAINT fk_cash_transaction_components_workspace_transaction FOREIGN KEY (workspace_id, cash_transaction_id) REFERENCES public.cash_transactions(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.cash_transactions
    ADD CONSTRAINT fk_cash_transactions_workspace_account FOREIGN KEY (workspace_id, account_id) REFERENCES public.accounts(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.cash_transactions
    ADD CONSTRAINT fk_cash_transactions_workspace_category FOREIGN KEY (workspace_id, category_id) REFERENCES public.cash_categories(workspace_id, id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.statement_account_mappings
    ADD CONSTRAINT fk_statement_account_mappings_workspace_account FOREIGN KEY (workspace_id, account_id) REFERENCES public.accounts(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.sync_cursors
    ADD CONSTRAINT fk_sync_cursors_workspace_account FOREIGN KEY (workspace_id, account_id) REFERENCES public.accounts(workspace_id, id) ON DELETE CASCADE;

ALTER TABLE ONLY public.transaction_relations
    ADD CONSTRAINT fk_transaction_relations_workspace FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.investment_events
    ADD CONSTRAINT investment_events__i_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.ledger_snapshots
    ADD CONSTRAINT ledger_snapshots_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.statement_account_mappings
    ADD CONSTRAINT statement_account_mappings_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.sync_cursors
    ADD CONSTRAINT sync_cursors_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.user_sessions
    ADD CONSTRAINT user_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_created_by_user_id_fkey FOREIGN KEY (created_by_user_id) REFERENCES public.users(id) ON DELETE RESTRICT;

ALTER TABLE ONLY public.workspace_invitations
    ADD CONSTRAINT workspace_invitations_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.workspace_memberships
    ADD CONSTRAINT workspace_memberships_workspace_id_fkey FOREIGN KEY (workspace_id) REFERENCES public.workspaces(id) ON DELETE CASCADE;

INSERT INTO public.alembic_version (version_num) VALUES ('20261104_54');

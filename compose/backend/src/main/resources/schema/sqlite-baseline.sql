CREATE TABLE alembic_version (
	version_num VARCHAR(32) NOT NULL,
	CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
);

CREATE TABLE workspaces (
	ledger_seq BIGINT DEFAULT 0 NOT NULL CHECK (ledger_seq >= 0),
	id VARCHAR(64) NOT NULL,
	name VARCHAR(255) NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (id)
);

CREATE TABLE ledger_snapshots (
	workspace_id VARCHAR(64) NOT NULL,
	payload JSON NOT NULL,
	version INTEGER NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (workspace_id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "account_aliases" (
            id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
            workspace_id VARCHAR(64) NOT NULL,
            alias_type VARCHAR(32) NOT NULL,
            alias_value VARCHAR(255) NOT NULL,
            account_id INTEGER NOT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
            UNIQUE (workspace_id, id),
            UNIQUE (workspace_id, alias_type, alias_value, account_id)
        );

CREATE INDEX ix_account_aliases_workspace_value ON account_aliases (workspace_id, alias_type, alias_value);

CREATE TABLE sync_cursors (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	account_id INTEGER NOT NULL,
	source_type VARCHAR(64) NOT NULL,
	cursor_value VARCHAR(256) NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_sync_cursors_workspace_account_source UNIQUE (workspace_id, account_id, source_type),
	CONSTRAINT fk_sync_cursors_workspace_account FOREIGN KEY(workspace_id, account_id) REFERENCES accounts (workspace_id, id) ON DELETE CASCADE,
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_sync_cursors_workspace ON sync_cursors (workspace_id);

CREATE TABLE IF NOT EXISTS "investment_events" (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	account_id INTEGER NOT NULL,
	source_type VARCHAR(64),
	record_id VARCHAR(512) DEFAULT ('') NOT NULL,
	source_payload JSON,
	occurred_at DATETIME NOT NULL,
	record_type VARCHAR(64) NOT NULL,
	currency VARCHAR(3) DEFAULT ('') NOT NULL,
	note TEXT DEFAULT ('') NOT NULL,
	from_ticker VARCHAR(64) DEFAULT ('') NOT NULL,
	from_amount VARCHAR(96),
	to_ticker VARCHAR(64) DEFAULT ('') NOT NULL,
	to_amount VARCHAR(96),
	commission VARCHAR(96),
	commission_asset VARCHAR(64) DEFAULT ('') NOT NULL,
	payload JSON NOT NULL,
	created_at DATETIME NOT NULL,
	record_subtype VARCHAR(32) DEFAULT 'not_applicable' NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT ck_investment_events_record_type CHECK (record_type IN ('funding', 'trade', 'income', 'expense', 'reversal', 'subscription', 'adjustment', 'snapshot')),
	CONSTRAINT ck_investment_events_record_type_subtype CHECK ((record_type = 'funding' AND record_subtype IN ('external', 'subaccount')) OR (record_type = 'trade' AND record_subtype IN ('security', 'fx', 'repo')) OR (record_type = 'income' AND record_subtype IN ('dividend_cash', 'dividend_stock', 'interest', 'reward')) OR (record_type = 'expense' AND record_subtype IN ('commission', 'tax', 'interest', 'handling_fee', 'penalty')) OR (record_type = 'reversal' AND record_subtype IN ('expense_tax', 'expense_interest', 'expense_commission', 'expense_handling_fee', 'expense_penalty', 'funding_withdrawal')) OR (record_type = 'subscription' AND record_subtype IN ('ipo_debit', 'ipo_refund')) OR (record_type = 'adjustment' AND record_subtype IN ('fx_net', 'manual', 'unclassified')) OR (record_type = 'snapshot' AND record_subtype IN ('cash', 'position'))),
	UNIQUE (workspace_id, id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_investment_events_workspace_date ON investment_events (workspace_id, occurred_at);

CREATE UNIQUE INDEX uq_investment_events_source_record ON investment_events (workspace_id, source_type, record_id) WHERE source_type IS NOT NULL AND source_type <> '';

CREATE INDEX ix_investment_events_workspace_source_record ON investment_events (workspace_id, source_type, record_id);

CREATE INDEX ix_investment_events_workspace_account ON investment_events (workspace_id, account_id);

CREATE TABLE cash_categories (
	id VARCHAR(64) NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	parent_id VARCHAR(64),
	parent_scope_key VARCHAR(64) NOT NULL,
	name VARCHAR(40) NOT NULL,
	normalized_name VARCHAR(40) NOT NULL,
	description VARCHAR(500),
	category_path VARCHAR(512) NOT NULL,
	depth INTEGER NOT NULL,
	sort_order INTEGER DEFAULT '0' NOT NULL,
	revision BIGINT DEFAULT '1' NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	CONSTRAINT fk_cash_categories_workspace_parent FOREIGN KEY(workspace_id, parent_id) REFERENCES cash_categories (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT uq_cash_categories_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT uq_cash_categories_sibling_name UNIQUE (workspace_id, parent_scope_key, normalized_name),
	CONSTRAINT ck_cash_categories_depth CHECK (depth BETWEEN 1 AND 5),
	CONSTRAINT ck_cash_categories_name_length CHECK (length(name) BETWEEN 1 AND 40),
	CONSTRAINT ck_cash_categories_path CHECK (length(category_path) > 2)
);

CREATE INDEX ix_cash_categories_workspace_parent_order ON cash_categories (workspace_id, parent_scope_key, sort_order, id);

CREATE INDEX ix_cash_categories_workspace_path ON cash_categories (workspace_id, category_path);

CREATE TABLE cash_category_states (
	workspace_id VARCHAR(64) NOT NULL,
	revision BIGINT DEFAULT '0' NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (workspace_id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE TABLE users (
	id VARCHAR(36) NOT NULL,
	email VARCHAR(320) NOT NULL,
	password_hash VARCHAR(512) NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	UNIQUE (email)
);

CREATE TABLE workspace_memberships (
	workspace_id VARCHAR(64) NOT NULL,
	user_id VARCHAR(36) NOT NULL,
	role VARCHAR(16) NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (workspace_id, user_id),
	CONSTRAINT ck_workspace_memberships_role CHECK (role IN ('admin', 'editor', 'viewer')),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX ix_workspace_memberships_user ON workspace_memberships (user_id);

CREATE TABLE user_sessions (
	id VARCHAR(36) NOT NULL,
	user_id VARCHAR(36) NOT NULL,
	token_digest VARCHAR(64) NOT NULL,
	active_workspace_id VARCHAR(64),
	expires_at DATETIME NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	FOREIGN KEY(user_id) REFERENCES users (id) ON DELETE CASCADE,
	UNIQUE (token_digest)
);

CREATE INDEX ix_user_sessions_user ON user_sessions (user_id);

CREATE TABLE workspace_invitations (
	id VARCHAR(36) NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	role VARCHAR(16) NOT NULL,
	token_digest VARCHAR(64) NOT NULL,
	expires_at DATETIME NOT NULL,
	accepted_at DATETIME,
	created_by_user_id VARCHAR(36) NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT ck_workspace_invitations_role CHECK (role IN ('editor', 'viewer')),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	UNIQUE (token_digest),
	FOREIGN KEY(created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE INDEX ix_workspace_invitations_workspace ON workspace_invitations (workspace_id);

CREATE INDEX ix_user_sessions_active_workspace ON user_sessions (active_workspace_id);

CREATE TABLE statement_account_mappings (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	source_type VARCHAR(64) NOT NULL,
	identity_kind VARCHAR(64) NOT NULL,
	source_account_key VARCHAR(255) NOT NULL,
	account_id INTEGER NOT NULL,
	confirmed_by VARCHAR(128) DEFAULT '' NOT NULL,
	revision BIGINT DEFAULT '1' NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_statement_account_mappings_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT uq_statement_account_mappings_source_identity UNIQUE (workspace_id, source_type, identity_kind, source_account_key),
	CONSTRAINT fk_statement_account_mappings_workspace_account FOREIGN KEY(workspace_id, account_id) REFERENCES accounts (workspace_id, id) ON DELETE CASCADE,
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_statement_account_mappings_workspace ON statement_account_mappings (workspace_id);

CREATE TABLE cash_investment_funding_relations (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                workspace_id VARCHAR(64) NOT NULL,
                cash_transaction_component_id INTEGER NOT NULL,
                investment_event_id INTEGER NOT NULL,
                direction VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                rule_id VARCHAR(128) NOT NULL,
                evidence JSON NOT NULL DEFAULT '{}',
                active_slot VARCHAR(36) NOT NULL DEFAULT 'active',
                created_by VARCHAR(128) NOT NULL DEFAULT 'system',
                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                decided_by VARCHAR(128) NOT NULL DEFAULT '',
                decided_at DATETIME,
                decision_reason TEXT NOT NULL DEFAULT '',
                CONSTRAINT uq_cash_investment_funding_relations_workspace_id UNIQUE (workspace_id, id),
                CONSTRAINT uq_cash_investment_funding_relations_active_pair UNIQUE (workspace_id, cash_transaction_component_id, investment_event_id, active_slot),
                CONSTRAINT ck_cash_investment_funding_relations_direction CHECK (direction IN ('cash_to_investment', 'investment_to_cash')),
                CONSTRAINT ck_cash_investment_funding_relations_status CHECK (status IN ('pending_review', 'accepted', 'rejected')),
                FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
                FOREIGN KEY(workspace_id, cash_transaction_component_id) REFERENCES cash_transaction_components(workspace_id, id) ON DELETE RESTRICT,
                FOREIGN KEY(workspace_id, investment_event_id) REFERENCES investment_events(workspace_id, id) ON DELETE RESTRICT
            );

CREATE INDEX ix_cash_investment_funding_relations_workspace_status ON cash_investment_funding_relations (workspace_id, status);

CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_cash ON cash_investment_funding_relations (workspace_id, cash_transaction_component_id) WHERE status = 'accepted' AND active_slot = 'active';

CREATE UNIQUE INDEX uq_cash_investment_funding_relations_accepted_investment ON cash_investment_funding_relations (workspace_id, investment_event_id) WHERE status = 'accepted' AND active_slot = 'active';

CREATE TABLE cash_source_bindings (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	transaction_id INTEGER NOT NULL,
	source_type VARCHAR(64) NOT NULL,
	source_scope_kind VARCHAR(16) NOT NULL,
	source_scope_key VARCHAR(256) NOT NULL,
	identity_kind VARCHAR(32) NOT NULL,
	identity_key VARCHAR(64) NOT NULL,
	identity_strength VARCHAR(16) NOT NULL,
	stable_evidence_key VARCHAR(64),
	strategy_version VARCHAR(64) NOT NULL,
	coverage JSON NOT NULL,
	revision BIGINT DEFAULT '1' NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_source_binding_identity UNIQUE (workspace_id, source_type, source_scope_kind, source_scope_key, identity_kind, identity_key),
	CONSTRAINT fk_cash_source_binding_transaction FOREIGN KEY(workspace_id, transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT ck_cash_source_binding_scope CHECK (source_scope_kind IN ('account', 'file_local')),
	CONSTRAINT ck_cash_source_binding_keys CHECK (length(source_scope_key) > 0 AND length(identity_key) > 0),
	CONSTRAINT ck_cash_source_binding_identity CHECK (identity_kind IN ('provider_id', 'file_occurrence')),
	CONSTRAINT ck_cash_source_binding_strength CHECK (identity_strength IN ('provider', 'validated', 'manual', 'file_local')),
	CONSTRAINT ck_cash_source_binding_revision CHECK (revision >= 1)
);

CREATE INDEX ix_cash_source_binding_transaction ON cash_source_bindings (workspace_id, transaction_id);

CREATE INDEX ix_cash_source_binding_evidence ON cash_source_bindings (workspace_id, stable_evidence_key);

CREATE TABLE cash_issue_candidates (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	issue_id INTEGER NOT NULL,
	candidate_transaction_id INTEGER NOT NULL,
	expected_row_revision BIGINT NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_issue_candidate UNIQUE (workspace_id, issue_id, candidate_transaction_id),
	CONSTRAINT fk_cash_issue_candidate_issue FOREIGN KEY(workspace_id, issue_id) REFERENCES cash_transaction_issues (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT fk_cash_issue_candidate_transaction FOREIGN KEY(workspace_id, candidate_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT ck_cash_issue_candidate_revision CHECK (expected_row_revision >= 1)
);

CREATE INDEX ix_cash_issue_candidate_transaction ON cash_issue_candidates (workspace_id, candidate_transaction_id);

CREATE TABLE cash_mutation_receipts (
	id VARCHAR(36) NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	user_id VARCHAR(128) NOT NULL,
	idempotency_key VARCHAR(255) NOT NULL,
	request_digest VARCHAR(64) NOT NULL,
	operation_kind VARCHAR(32) NOT NULL,
	plan_id VARCHAR(128),
	session_digest VARCHAR(64),
	edit_context_digest VARCHAR(64),
	result_summary JSON NOT NULL,
	created_at DATETIME NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_mutation_receipt_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT uq_cash_mutation_receipt_user_key UNIQUE (workspace_id, user_id, idempotency_key),
	CONSTRAINT ck_cash_mutation_receipt_request CHECK (length(idempotency_key) > 0 AND length(request_digest) = 64),
	CONSTRAINT ck_cash_mutation_receipt_session CHECK (session_digest IS NULL OR length(session_digest) = 64),
	CONSTRAINT ck_cash_mutation_receipt_edit_context CHECK (edit_context_digest IS NULL OR length(edit_context_digest) = 64),
	CONSTRAINT ck_cash_mutation_receipt_one_context CHECK (session_digest IS NULL OR edit_context_digest IS NULL),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_cash_mutation_receipt_session ON cash_mutation_receipts (workspace_id, user_id, session_digest) WHERE session_digest IS NOT NULL;

CREATE UNIQUE INDEX uq_cash_mutation_receipt_edit_context ON cash_mutation_receipts (workspace_id, user_id, edit_context_digest) WHERE edit_context_digest IS NOT NULL;

CREATE TABLE cash_mutation_results (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	receipt_id VARCHAR(36) NOT NULL,
	result_kind VARCHAR(16) NOT NULL,
	item_ref VARCHAR(255) NOT NULL,
	outcome VARCHAR(32) NOT NULL,
	code VARCHAR(64),
	operation_ref VARCHAR(128),
	transaction_id VARCHAR(128),
	relation_id VARCHAR(128),
	account_id VARCHAR(128),
	PRIMARY KEY (id),
	CONSTRAINT fk_cash_mutation_result_receipt FOREIGN KEY(workspace_id, receipt_id) REFERENCES cash_mutation_receipts (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT uq_cash_mutation_result_item UNIQUE (workspace_id, receipt_id, result_kind, item_ref),
	CONSTRAINT ck_cash_mutation_result_kind CHECK (result_kind IN ('row', 'relation', 'file', 'account'))
);

CREATE INDEX ix_cash_mutation_result_page ON cash_mutation_results (workspace_id, receipt_id, result_kind, id);

CREATE INDEX ix_cash_mutation_result_all_page ON cash_mutation_results (workspace_id, receipt_id, id);

CREATE TABLE IF NOT EXISTS "accounts" (
	id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	workspace_id VARCHAR(64) NOT NULL,
	name VARCHAR(255) NOT NULL,
	type VARCHAR(32) NOT NULL,
	active BOOLEAN DEFAULT 1 NOT NULL,
	metadata_json JSON NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	currencies JSON,
	revision BIGINT DEFAULT '1' NOT NULL,
	CONSTRAINT ck_accounts_revision CHECK (revision >= 1),
	UNIQUE (workspace_id, id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	UNIQUE (workspace_id, name)
);

CREATE INDEX ix_accounts_workspace ON accounts (workspace_id);

CREATE TABLE cash_relation_candidates (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	relation_id INTEGER NOT NULL,
	candidate_transaction_id INTEGER NOT NULL,
	candidate_component_id INTEGER,
	expected_row_revision BIGINT NOT NULL,
	evidence JSON NOT NULL,
	validity VARCHAR(16) NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT fk_cash_relation_candidate_relation FOREIGN KEY(workspace_id, relation_id) REFERENCES transaction_relations (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT fk_cash_relation_candidate_parent FOREIGN KEY(workspace_id, candidate_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT fk_cash_relation_candidate_component FOREIGN KEY(workspace_id, candidate_transaction_id, candidate_component_id) REFERENCES cash_transaction_components (workspace_id, cash_transaction_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_relation_candidate_revision CHECK (expected_row_revision >= 1),
	CONSTRAINT ck_cash_relation_candidate_validity CHECK (validity IN ('valid','incomplete','stale')),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX uq_cash_relation_candidate ON cash_relation_candidates (workspace_id, relation_id, candidate_transaction_id, coalesce(candidate_component_id, 0));

CREATE INDEX ix_cash_relation_candidate_parent ON cash_relation_candidates (workspace_id, candidate_transaction_id, relation_id);

CREATE INDEX ix_cash_relation_candidate_component ON cash_relation_candidates (workspace_id, candidate_component_id, relation_id);

CREATE TABLE IF NOT EXISTS "cash_transaction_issues" (
	id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	workspace_id VARCHAR(64) NOT NULL,
	transaction_id INTEGER NOT NULL,
	issue_key VARCHAR(128) NOT NULL,
	code VARCHAR(64) NOT NULL,
	field_path VARCHAR(255),
	pending_source_payload JSON,
	pending_fields JSON,
	origin JSON NOT NULL,
	revision BIGINT DEFAULT '1' NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	CONSTRAINT uq_cash_issue_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT fk_cash_issue_transaction FOREIGN KEY(workspace_id, transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT ck_cash_issue_revision CHECK (revision >= 1),
	CONSTRAINT uq_cash_issue_transaction_key UNIQUE (workspace_id, transaction_id, issue_key)
);

CREATE INDEX ix_cash_issue_transaction ON cash_transaction_issues (workspace_id, transaction_id);

CREATE TABLE IF NOT EXISTS "cash_projection_datasets" (
	id VARCHAR(64) NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	state VARCHAR(16) NOT NULL,
	source_revision BIGINT NOT NULL,
	source_digest VARCHAR(64) NOT NULL,
	rules_version VARCHAR(64) NOT NULL,
	created_at DATETIME NOT NULL,
	published_at DATETIME,
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_projection_datasets_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT ck_cash_projection_datasets_state CHECK (state IN ('staging', 'active')),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_projection_datasets_workspace_state ON cash_projection_datasets (workspace_id, state);

CREATE UNIQUE INDEX uq_cash_projection_datasets_active_workspace ON cash_projection_datasets (workspace_id) WHERE state = 'active';

CREATE TABLE IF NOT EXISTS "cash_projection_members" (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	dataset_id VARCHAR(64) NOT NULL,
	projection_row_id INTEGER NOT NULL,
	cash_transaction_id INTEGER NOT NULL,
	roles_json JSON NOT NULL,
	ordinal INTEGER NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_projection_members_ordinal UNIQUE (projection_row_id, ordinal),
	CONSTRAINT fk_cash_projection_members_cash FOREIGN KEY(workspace_id, cash_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT uq_cash_projection_members_dataset_cash UNIQUE (workspace_id, dataset_id, cash_transaction_id),
	CONSTRAINT fk_cash_projection_members_projection FOREIGN KEY(workspace_id, dataset_id, projection_row_id) REFERENCES cash_projections (workspace_id, dataset_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_projection_members_dataset ON cash_projection_members (dataset_id);

CREATE INDEX ix_cash_projection_members_page_lookup ON cash_projection_members (workspace_id, dataset_id, projection_row_id, ordinal);

CREATE TABLE IF NOT EXISTS "cash_transactions" (
	id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	workspace_id VARCHAR(64) NOT NULL,
	account_id INTEGER,
	source_type VARCHAR(64),
	record_id VARCHAR(512) DEFAULT ('') NOT NULL,
	source_payload JSON,
	occurred_at DATETIME,
	amount VARCHAR(96),
	currency VARCHAR(3),
	counterparty VARCHAR(512) DEFAULT ('') NOT NULL,
	note TEXT DEFAULT ('') NOT NULL,
	created_at DATETIME NOT NULL,
	deleted_at DATETIME,
	deleted_by VARCHAR(128) DEFAULT ('') NOT NULL,
	delete_reason TEXT DEFAULT ('') NOT NULL,
	record_type VARCHAR(32) DEFAULT 'other',
	counterparty_account VARCHAR(512) DEFAULT ('') NOT NULL,
	record_subtype VARCHAR(32) DEFAULT 'not_applicable',
	counterparty_account_attrs JSON DEFAULT '[]' NOT NULL,
	source_fingerprint VARCHAR(64),
	manual_overrides JSON,
	category_id VARCHAR(64),
	relation_metadata JSON,
	cash_granularity VARCHAR(16) DEFAULT 'atomic' NOT NULL,
	state VARCHAR(16) DEFAULT 'posted' NOT NULL,
	row_revision BIGINT DEFAULT '1' NOT NULL, record_origin VARCHAR(24) DEFAULT 'legacy' NOT NULL CONSTRAINT ck_cash_transactions_record_origin CHECK (record_origin IN ('import', 'manual', 'manual_transfer', 'legacy')), manual_transfer_id VARCHAR(64),
	CONSTRAINT ck_cash_transactions_record_type CHECK (record_type IN ('consumption', 'refund', 'reversal', 'transfer_reversal', 'withdrawal_in', 'withdrawal_out', 'transfer_in', 'transfer_out', 'repayment', 'income', 'investment_in', 'investment_out', 'interest', 'fee', 'fx_in', 'fx_out', 'other')),
	CONSTRAINT ck_cash_transactions_state CHECK (state IN ('draft', 'posted')),
	CONSTRAINT ck_cash_transactions_posted_complete CHECK (state <> 'posted' OR (amount IS NOT NULL AND occurred_at IS NOT NULL AND currency IS NOT NULL AND record_type IS NOT NULL AND record_subtype IS NOT NULL AND ((cash_granularity = 'aggregate' AND account_id IS NULL) OR (cash_granularity = 'atomic' AND account_id IS NOT NULL)))),
	CONSTRAINT ck_cash_transactions_record_type_subtype CHECK ((record_type IN ('transfer_in', 'transfer_out') AND record_subtype IN ('ordinary_transfer', 'cross_border_remittance', 'internal_account_transfer')) OR (record_type IN ('fx_in', 'fx_out') AND record_subtype = 'currency_exchange') OR (record_type = 'repayment' AND record_subtype = 'credit_repayment') OR (record_type IN ('withdrawal_in', 'withdrawal_out') AND record_subtype = 'withdraw_to_bank') OR (record_type NOT IN ('transfer_in', 'transfer_out', 'fx_in', 'fx_out', 'repayment', 'withdrawal_in', 'withdrawal_out') AND record_subtype = 'not_applicable')),
	CONSTRAINT fk_cash_transactions_workspace_category FOREIGN KEY(workspace_id, category_id) REFERENCES cash_categories (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_transactions_currency CHECK (currency IS NULL OR (length(currency) = 3 AND currency = upper(currency))),
	CONSTRAINT fk_cash_transactions_workspace_account FOREIGN KEY(workspace_id, account_id) REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_transactions_row_revision CHECK (row_revision >= 1),
	UNIQUE (workspace_id, id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_transactions_workspace_account ON cash_transactions (workspace_id, account_id);

CREATE INDEX ix_cash_transactions_workspace_source_record ON cash_transactions (workspace_id, source_type, record_id);

CREATE INDEX ix_cash_transactions_workspace_category ON cash_transactions (workspace_id, category_id);

CREATE INDEX ix_cash_transactions_workspace_date ON cash_transactions (workspace_id, occurred_at);

CREATE INDEX ix_cash_transactions_workspace_state ON cash_transactions (workspace_id, state, occurred_at, id);

CREATE UNIQUE INDEX uq_cash_transactions_active_source_record ON cash_transactions (workspace_id, source_type, record_id) WHERE source_type IS NOT NULL AND source_type <> '' AND record_id IS NOT NULL AND record_id <> '' AND deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS "cash_transaction_components" (
	id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	workspace_id VARCHAR(64) NOT NULL,
	cash_transaction_id INTEGER NOT NULL,
	account_id INTEGER,
	amount VARCHAR(96),
	currency VARCHAR(3),
	ordinal INTEGER NOT NULL,
	label VARCHAR(255) DEFAULT ('') NOT NULL,
	source_key VARCHAR(255) DEFAULT ('') NOT NULL,
	metadata_json JSON DEFAULT '{}' NOT NULL,
	created_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL,
	CONSTRAINT uq_cash_transaction_components_workspace_transaction_ordinal UNIQUE (workspace_id, cash_transaction_id, ordinal),
	CONSTRAINT fk_cash_transaction_components_workspace_transaction FOREIGN KEY(workspace_id, cash_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT uq_cash_components_workspace_parent_id UNIQUE (workspace_id, cash_transaction_id, id),
	CONSTRAINT uq_cash_transaction_components_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT ck_cash_transaction_components_ordinal CHECK (ordinal >= 0),
	CONSTRAINT fk_cash_transaction_components_workspace_account FOREIGN KEY(workspace_id, account_id) REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT,
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_transaction_components_workspace_account ON cash_transaction_components (workspace_id, account_id);

CREATE INDEX ix_cash_transaction_components_workspace_transaction ON cash_transaction_components (workspace_id, cash_transaction_id);

CREATE TABLE cash_source_empty_pages (
	workspace_id VARCHAR(64) NOT NULL,
	source_type VARCHAR(64) NOT NULL,
	parser_version VARCHAR(64) NOT NULL,
	document_key VARCHAR(64) NOT NULL,
	page_number INTEGER NOT NULL,
	coverage JSON NOT NULL,
	PRIMARY KEY (workspace_id, source_type, parser_version, document_key, page_number),
	CONSTRAINT ck_cash_source_empty_page_number CHECK (page_number BETWEEN 1 AND 2000),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "cash_projection_states" (
	workspace_id VARCHAR(64) NOT NULL,
	active_dataset_id VARCHAR(64),
	projection_version BIGINT DEFAULT '0' NOT NULL,
	source_revision BIGINT DEFAULT '0' NOT NULL,
	rules_version VARCHAR(64) DEFAULT 'cash-projection-v2' NOT NULL,
	availability VARCHAR(16) DEFAULT 'uninitialized' NOT NULL,
	last_build_status VARCHAR(16) DEFAULT 'never' NOT NULL,
	last_build_id VARCHAR(64),
	last_error_code VARCHAR(64),
	last_error_summary TEXT,
	projection_count BIGINT DEFAULT '0' NOT NULL,
	member_count BIGINT DEFAULT '0' NOT NULL,
	build_started_at DATETIME,
	build_finished_at DATETIME,
	updated_at DATETIME NOT NULL,
	PRIMARY KEY (workspace_id),
	CONSTRAINT ck_cash_projection_states_ready_dataset CHECK (availability <> 'ready' OR active_dataset_id IS NOT NULL),
	CONSTRAINT fk_cash_projection_states_workspace_dataset FOREIGN KEY(workspace_id, active_dataset_id) REFERENCES cash_projection_datasets (workspace_id, id),
	CONSTRAINT ck_cash_projection_states_availability CHECK (availability IN ('uninitialized', 'ready')),
	CONSTRAINT ck_cash_projection_states_build_status CHECK (last_build_status IN ('never', 'running', 'succeeded', 'failed')),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE TABLE cash_entry_members (
	workspace_id VARCHAR(64) NOT NULL,
	transaction_id INTEGER NOT NULL,
	entry_id VARCHAR(64) NOT NULL,
	standalone_values JSON NOT NULL,
	standalone_manual_fields JSON NOT NULL,
	PRIMARY KEY (workspace_id, transaction_id),
	FOREIGN KEY(workspace_id, entry_id) REFERENCES cash_entries (workspace_id, id) ON DELETE CASCADE,
	FOREIGN KEY(workspace_id, transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_entry_members_entry ON cash_entry_members (workspace_id, entry_id);

CREATE TABLE IF NOT EXISTS "cash_entries" (
	id VARCHAR(64) NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	root_transaction_id INTEGER NOT NULL,
	revision BIGINT NOT NULL,
	category_id VARCHAR(64),
	note TEXT NOT NULL,
	counterparty VARCHAR(512) NOT NULL,
	counterparty_account VARCHAR(512) NOT NULL,
	counterparty_account_attrs JSON NOT NULL,
	manual_fields JSON NOT NULL,
	split_inherit BOOLEAN,
	identity_transaction_id INTEGER NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT ck_cash_entries_revision CHECK (revision >= 1),
	CONSTRAINT uq_cash_entries_workspace_id UNIQUE (workspace_id, id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	FOREIGN KEY(workspace_id, category_id) REFERENCES cash_categories (workspace_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_cash_entries_workspace_category ON cash_entries (workspace_id, category_id);

CREATE TABLE cash_relation_migration_audit (
	relation_id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	legacy_subtype VARCHAR(64) NOT NULL,
	migration_version VARCHAR(32) NOT NULL,
	PRIMARY KEY (relation_id),
	FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "transaction_relations" (
	id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
	workspace_id VARCHAR(64) NOT NULL,
	kind VARCHAR(32) NOT NULL,
	primary_component_id INTEGER,
	secondary_component_id INTEGER,
	applied_amount VARCHAR(96) DEFAULT '0' NOT NULL,
	rule_id VARCHAR(128) DEFAULT ('') NOT NULL,
	created_by VARCHAR(128) DEFAULT 'system' NOT NULL,
	created_at DATETIME DEFAULT (CURRENT_TIMESTAMP) NOT NULL,
	decided_by VARCHAR(128) DEFAULT ('') NOT NULL,
	decided_at DATETIME,
	decision_reason TEXT DEFAULT ('') NOT NULL,
	anchor_component_id INTEGER,
	state VARCHAR(16) DEFAULT 'draft' NOT NULL,
	active BOOLEAN DEFAULT 1 NOT NULL,
	primary_transaction_id INTEGER NOT NULL,
	secondary_transaction_id INTEGER,
	anchor_transaction_id INTEGER NOT NULL,
	primary_role VARCHAR(32),
	secondary_role VARCHAR(32),
	decision_group_id VARCHAR(128),
	decision_source VARCHAR(32) DEFAULT 'rule' NOT NULL,
	evidence JSON DEFAULT '{}' NOT NULL,
	row_revision BIGINT DEFAULT '1' NOT NULL,
	CONSTRAINT fk_cash_relation_anchor_parent FOREIGN KEY(workspace_id, anchor_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_relation_state CHECK (state IN ('draft','confirmed','ignored')),
	CONSTRAINT ck_transaction_relations_kind CHECK (kind IN ('payment_mirror','transfer_pair','refund_offset')),
	CONSTRAINT fk_cash_relation_primary_component FOREIGN KEY(workspace_id, primary_transaction_id, primary_component_id) REFERENCES cash_transaction_components (workspace_id, cash_transaction_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_relation_anchor CHECK ((anchor_transaction_id = primary_transaction_id OR (secondary_transaction_id IS NOT NULL AND anchor_transaction_id = secondary_transaction_id)) AND (anchor_component_id IS NULL OR (primary_component_id IS NOT NULL AND anchor_component_id = primary_component_id) OR (secondary_component_id IS NOT NULL AND anchor_component_id = secondary_component_id))),
	CONSTRAINT ck_cash_relation_confirmed_shape CHECK (state <> 'confirmed' OR (secondary_transaction_id IS NOT NULL AND primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND anchor_component_id IS NOT NULL AND primary_role IS NOT NULL AND secondary_role IS NOT NULL)),
	CONSTRAINT ck_cash_relation_distinct_parents CHECK (secondary_transaction_id IS NULL OR primary_transaction_id <> secondary_transaction_id),
	CONSTRAINT fk_cash_relation_secondary_component FOREIGN KEY(workspace_id, secondary_transaction_id, secondary_component_id) REFERENCES cash_transaction_components (workspace_id, cash_transaction_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_relation_secondary_shape CHECK (secondary_transaction_id IS NOT NULL OR (secondary_component_id IS NULL AND secondary_role IS NULL)),
	CONSTRAINT fk_cash_relation_secondary_parent FOREIGN KEY(workspace_id, secondary_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_relation_primary_role CHECK (primary_role IS NULL OR primary_role IN ('expense','refund','p2p_out','p2p_in','outgoing','incoming')),
	CONSTRAINT ck_cash_relation_revision CHECK (row_revision >= 1),
	CONSTRAINT fk_transaction_relations_workspace_id_workspaces FOREIGN KEY(workspace_id) REFERENCES workspaces (id) ON DELETE CASCADE,
	CONSTRAINT uq_transaction_relations_workspace_id UNIQUE (workspace_id, id),
	CONSTRAINT ck_cash_relation_secondary_role CHECK (secondary_role IS NULL OR secondary_role IN ('expense','refund','p2p_out','p2p_in','outgoing','incoming')),
	CONSTRAINT fk_cash_relation_anchor_component FOREIGN KEY(workspace_id, anchor_transaction_id, anchor_component_id) REFERENCES cash_transaction_components (workspace_id, cash_transaction_id, id) ON DELETE RESTRICT,
	CONSTRAINT fk_cash_relation_primary_parent FOREIGN KEY(workspace_id, primary_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_cash_relation_secondary ON transaction_relations (workspace_id, secondary_transaction_id, active, state);

CREATE INDEX ix_cash_relation_state ON transaction_relations (workspace_id, active, state, kind);

CREATE INDEX ix_cash_relation_primary_component ON transaction_relations (workspace_id, primary_component_id, active, state);

CREATE INDEX ix_cash_relation_secondary_component ON transaction_relations (workspace_id, secondary_component_id, active, state);

CREATE INDEX ix_cash_relation_anchor_component ON transaction_relations (workspace_id, anchor_component_id, active);

CREATE INDEX ix_cash_relation_anchor ON transaction_relations (workspace_id, anchor_transaction_id, active);

CREATE INDEX ix_cash_relation_primary ON transaction_relations (workspace_id, primary_transaction_id, active, state);

CREATE INDEX ix_cash_relation_group ON transaction_relations (workspace_id, decision_group_id, active);

CREATE UNIQUE INDEX uq_cash_relation_pair ON transaction_relations (workspace_id, kind, (CASE WHEN kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id)) THEN secondary_transaction_id ELSE primary_transaction_id END), (CASE WHEN kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id)) THEN primary_transaction_id ELSE secondary_transaction_id END), coalesce((CASE WHEN kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id)) THEN secondary_component_id ELSE primary_component_id END),0), coalesce((CASE WHEN kind = 'payment_mirror' AND ((primary_component_id IS NOT NULL AND secondary_component_id IS NOT NULL AND primary_component_id > secondary_component_id) OR ((primary_component_id IS NULL OR secondary_component_id IS NULL) AND primary_transaction_id > secondary_transaction_id)) THEN primary_component_id ELSE secondary_component_id END),0)) WHERE active AND secondary_transaction_id IS NOT NULL;

CREATE UNIQUE INDEX uq_cash_relation_open_anchor ON transaction_relations (workspace_id, kind, anchor_transaction_id, coalesce(anchor_component_id,0)) WHERE active AND secondary_transaction_id IS NULL;

CREATE TABLE IF NOT EXISTS "cash_projection_relations" (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	dataset_id VARCHAR(64) NOT NULL,
	projection_row_id INTEGER NOT NULL,
	transaction_relation_id INTEGER NOT NULL,
	kind VARCHAR(32) NOT NULL,
	ordinal INTEGER NOT NULL,
	PRIMARY KEY (id),
	CONSTRAINT fk_cash_projection_relations_relation FOREIGN KEY(workspace_id, transaction_relation_id) REFERENCES transaction_relations (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT uq_cash_projection_relations_ordinal UNIQUE (projection_row_id, ordinal),
	CONSTRAINT uq_cash_projection_relations_dataset_relation UNIQUE (workspace_id, dataset_id, transaction_relation_id),
	CONSTRAINT fk_cash_projection_relations_projection FOREIGN KEY(workspace_id, dataset_id, projection_row_id) REFERENCES cash_projections (workspace_id, dataset_id, id) ON DELETE CASCADE
);

CREATE INDEX ix_cash_projection_relations_dataset ON cash_projection_relations (dataset_id);

CREATE INDEX ix_cash_projection_relations_page_lookup ON cash_projection_relations (workspace_id, dataset_id, projection_row_id, kind, ordinal);

CREATE TABLE IF NOT EXISTS "cash_projections" (
	id INTEGER NOT NULL,
	workspace_id VARCHAR(64) NOT NULL,
	dataset_id VARCHAR(64) NOT NULL,
	projection_id VARCHAR(96) NOT NULL,
	root_cash_transaction_id INTEGER NOT NULL,
	economic_type VARCHAR(24),
	business_label VARCHAR(32),
	net_amount VARCHAR(64),
	currency VARCHAR(3),
	occurred_at DATETIME,
	account_id INTEGER,
	counterparty VARCHAR(512) DEFAULT ('') NOT NULL,
	note TEXT DEFAULT ('') NOT NULL,
	source_type VARCHAR(64),
	record_id VARCHAR(512) DEFAULT ('') NOT NULL,
	visible BOOLEAN NOT NULL,
	hidden_reason VARCHAR(32),
	has_payment_mirror BOOLEAN DEFAULT 0 NOT NULL,
	has_refund_offset BOOLEAN DEFAULT 0 NOT NULL,
	has_transfer_pair BOOLEAN DEFAULT 0 NOT NULL,
	member_count INTEGER NOT NULL,
	accepted_relation_count INTEGER NOT NULL,
	built_projection_version BIGINT NOT NULL,
	created_at DATETIME NOT NULL,
	updated_at DATETIME NOT NULL,
	funding_relation_id INTEGER,
	category_id VARCHAR(64),
	category_path VARCHAR(512),
	state VARCHAR(16) DEFAULT 'posted' NOT NULL,
	statistics_eligible BOOLEAN DEFAULT 1 NOT NULL,
	attention_count INTEGER DEFAULT '0' NOT NULL,
	sort_at DATETIME NOT NULL,
	entry_id VARCHAR(64),
	PRIMARY KEY (id),
	CONSTRAINT uq_cash_projections_dataset_projection UNIQUE (workspace_id, dataset_id, projection_id),
	CONSTRAINT ck_cash_projections_attention CHECK (attention_count >= 0 AND attention_count <= member_count),
	CONSTRAINT ck_cash_projections_statistics CHECK (statistics_eligible = (state = 'posted')),
	CONSTRAINT fk_cash_projections_workspace_category FOREIGN KEY(workspace_id, category_id) REFERENCES cash_categories (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_projections_counts CHECK (member_count >= 1 AND accepted_relation_count >= 0),
	CONSTRAINT ck_cash_projections_economic_type CHECK (economic_type IN ('expense', 'income', 'internal_transfer')),
	CONSTRAINT ck_cash_projections_posted_complete CHECK (state <> 'posted' OR (economic_type IS NOT NULL AND net_amount IS NOT NULL AND currency IS NOT NULL AND occurred_at IS NOT NULL)),
	CONSTRAINT fk_cash_projections_dataset FOREIGN KEY(workspace_id, dataset_id) REFERENCES cash_projection_datasets (workspace_id, id) ON DELETE CASCADE,
	CONSTRAINT ck_cash_projections_state CHECK (state IN ('draft', 'posted')),
	CONSTRAINT uq_cash_projections_workspace_dataset_id UNIQUE (workspace_id, dataset_id, id),
	CONSTRAINT fk_cash_projections_workspace_funding_relation FOREIGN KEY(workspace_id, funding_relation_id) REFERENCES cash_investment_funding_relations (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT ck_cash_projections_draft_singleton CHECK (state <> 'draft' OR (member_count = 1 AND accepted_relation_count = 0 AND attention_count = 1 AND economic_type IS NULL AND funding_relation_id IS NULL AND NOT has_payment_mirror AND NOT has_refund_offset AND NOT has_transfer_pair)),
	CONSTRAINT fk_cash_projections_workspace_root FOREIGN KEY(workspace_id, root_cash_transaction_id) REFERENCES cash_transactions (workspace_id, id) ON DELETE RESTRICT,
	CONSTRAINT fk_cash_projections_workspace_account FOREIGN KEY(workspace_id, account_id) REFERENCES accounts (workspace_id, id) ON DELETE RESTRICT
);

CREATE INDEX ix_cash_projections_category_path ON cash_projections (workspace_id, dataset_id, category_path);

CREATE INDEX ix_cash_projections_root ON cash_projections (workspace_id, dataset_id, root_cash_transaction_id);

CREATE INDEX ix_cash_projections_currency ON cash_projections (workspace_id, dataset_id, currency);

CREATE INDEX ix_cash_projections_economic_type ON cash_projections (workspace_id, dataset_id, economic_type);

CREATE INDEX ix_cash_projections_category_id ON cash_projections (workspace_id, dataset_id, category_id);

CREATE INDEX ix_cash_projections_account ON cash_projections (workspace_id, dataset_id, account_id);

CREATE INDEX ix_cash_projections_visible_list ON cash_projections (workspace_id, dataset_id, visible, occurred_at, projection_id);

CREATE INDEX ix_cash_projections_state_sort ON cash_projections (workspace_id, dataset_id, state, sort_at, projection_id);

CREATE INDEX ix_cash_projections_attention_sort ON cash_projections (workspace_id, dataset_id, attention_count, sort_at, projection_id);

CREATE INDEX ix_cash_projections_current_sort ON cash_projections (workspace_id, dataset_id, sort_at, projection_id);

INSERT INTO alembic_version (version_num) VALUES ('20261104_54');

CREATE TABLE cash_import_confirmations (
    workspace_id VARCHAR(64) NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    operation_key VARCHAR(255) NOT NULL,
    authorization_session_id VARCHAR(36) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    base_ledger_seq VARCHAR(24) NOT NULL,
    rules_version VARCHAR(128) NOT NULL,
    state VARCHAR(24) NOT NULL,
    payload TEXT,
    generation BIGINT NOT NULL DEFAULT 0,
    lease_until_ms BIGINT,
    accepted_at_ms BIGINT NOT NULL,
    failure_code VARCHAR(96),
    failure_status INTEGER,
    PRIMARY KEY (workspace_id, user_id, operation_key),
    CHECK (state IN ('accepted', 'running', 'cancel_requested', 'cancelled', 'rejected', 'committed')),
    CHECK ((payload IS NOT NULL) = (state IN ('accepted', 'running', 'cancel_requested'))),
    CHECK ((failure_code IS NOT NULL AND failure_status IS NOT NULL) = (state = 'rejected'))
);

CREATE INDEX ix_cash_import_confirmation_ready
    ON cash_import_confirmations (state, lease_until_ms, accepted_at_ms);

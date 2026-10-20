"""Persist current source identities and deferred source issues."""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20261021_39"
down_revision = "20261021_38"
branch_labels = None
depends_on = None
PK = sa.BigInteger().with_variant(sa.Integer(), "sqlite")


def _timestamps():
    return [sa.Column("created_at", sa.DateTime(timezone=True), nullable=False), sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False)]


def upgrade():
    op.create_table("cash_source_bindings",
        sa.Column("id", PK, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("transaction_id", PK, nullable=False),
        sa.Column("source_type", sa.String(64), nullable=False),
        sa.Column("source_scope_kind", sa.String(16), nullable=False),
        sa.Column("source_scope_key", sa.String(256), nullable=False),
        sa.Column("identity_kind", sa.String(32), nullable=False),
        sa.Column("identity_key", sa.String(64), nullable=False),
        sa.Column("identity_strength", sa.String(16), nullable=False),
        sa.Column("stable_evidence_key", sa.String(64), nullable=True),
        sa.Column("strategy_version", sa.String(64), nullable=False),
        sa.Column("coverage", sa.JSON(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False, server_default="1"),
        *_timestamps(),
        sa.UniqueConstraint("workspace_id", "source_type", "source_scope_kind", "source_scope_key", "identity_kind", "identity_key", name="uq_cash_source_binding_identity"),
        sa.ForeignKeyConstraint(["workspace_id", "transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="CASCADE", name="fk_cash_source_binding_transaction"),
        sa.CheckConstraint("source_scope_kind IN ('account', 'file_local')", name="ck_cash_source_binding_scope"),
        sa.CheckConstraint("length(source_scope_key) > 0 AND length(identity_key) > 0", name="ck_cash_source_binding_keys"),
        sa.CheckConstraint("identity_kind IN ('provider_id', 'file_occurrence')", name="ck_cash_source_binding_identity"),
        sa.CheckConstraint("identity_strength IN ('provider', 'validated', 'manual', 'file_local')", name="ck_cash_source_binding_strength"),
        sa.CheckConstraint("revision >= 1", name="ck_cash_source_binding_revision"),
    )
    op.create_index("ix_cash_source_binding_transaction", "cash_source_bindings", ["workspace_id", "transaction_id"])
    op.create_index("ix_cash_source_binding_evidence", "cash_source_bindings", ["workspace_id", "stable_evidence_key"])
    op.create_table("cash_transaction_issues",
        sa.Column("id", PK, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("transaction_id", PK, nullable=False),
        sa.Column("issue_key", sa.String(128), nullable=False),
        sa.Column("code", sa.String(64), nullable=False),
        sa.Column("field_path", sa.String(255), nullable=True),
        sa.Column("pending_source_payload", sa.JSON(), nullable=True),
        sa.Column("pending_fields", sa.JSON(), nullable=True),
        sa.Column("origin", sa.JSON(), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False, server_default="1"),
        *_timestamps(),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_issue_workspace_id"),
        sa.UniqueConstraint("workspace_id", "transaction_id", "issue_key", name="uq_cash_issue_transaction_key"),
        sa.ForeignKeyConstraint(["workspace_id", "transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="CASCADE", name="fk_cash_issue_transaction"),
        sa.CheckConstraint("revision >= 1", name="ck_cash_issue_revision"),
    )
    op.create_index("ix_cash_issue_transaction", "cash_transaction_issues", ["workspace_id", "transaction_id"])
    op.create_table("cash_issue_candidates",
        sa.Column("id", PK, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("issue_id", PK, nullable=False),
        sa.Column("candidate_transaction_id", PK, nullable=False),
        sa.Column("expected_row_revision", sa.BigInteger(), nullable=False),
        sa.UniqueConstraint("workspace_id", "issue_id", "candidate_transaction_id", name="uq_cash_issue_candidate"),
        sa.ForeignKeyConstraint(["workspace_id", "issue_id"], ["cash_transaction_issues.workspace_id", "cash_transaction_issues.id"], ondelete="CASCADE", name="fk_cash_issue_candidate_issue"),
        sa.ForeignKeyConstraint(["workspace_id", "candidate_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="CASCADE", name="fk_cash_issue_candidate_transaction"),
        sa.CheckConstraint("expected_row_revision >= 1", name="ck_cash_issue_candidate_revision"),
    )
    op.create_index("ix_cash_issue_candidate_transaction", "cash_issue_candidates", ["workspace_id", "candidate_transaction_id"])


def downgrade():
    bind = op.get_bind()
    if any(bind.execute(sa.text(f"SELECT 1 FROM {table} LIMIT 1")).first() for table in ("cash_source_bindings", "cash_transaction_issues", "cash_issue_candidates")):
        raise ValueError("current source identities exist; refusing a lossy schema downgrade")
    for table in ("cash_issue_candidates", "cash_transaction_issues", "cash_source_bindings"):
        op.drop_table(table)

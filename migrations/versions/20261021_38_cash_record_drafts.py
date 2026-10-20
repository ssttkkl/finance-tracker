"""Add same-identity cash drafts without changing existing posted values."""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20261021_38"
down_revision = "20261018_37"
branch_labels = None
depends_on = None


def _create_source_identity_index() -> None:
    # Rebuild explicitly: SQLite reflection can truncate a multi-line partial
    # predicate while batch-copying the parent table.
    condition = sa.text(
        "source_type IS NOT NULL AND source_type <> '' "
        "AND record_id IS NOT NULL AND record_id <> '' AND deleted_at IS NULL"
    )
    op.create_index(
        "uq_cash_transactions_active_source_record", "cash_transactions",
        ["workspace_id", "source_type", "record_id"], unique=True,
        sqlite_where=condition, postgresql_where=condition,
    )


def upgrade() -> None:
    bind = op.get_bind()
    amount_type = sa.String(96) if bind.dialect.name == "sqlite" else sa.Numeric(38, 18)
    account_fk_present = any(
        fk["constrained_columns"] == ["workspace_id", "account_id"]
        for fk in sa.inspect(bind).get_foreign_keys("cash_transactions")
    )
    op.drop_index("uq_cash_transactions_active_source_record", table_name="cash_transactions")
    with op.batch_alter_table("cash_transactions") as batch:
        batch.add_column(sa.Column("state", sa.String(16), nullable=False, server_default="posted"))
        batch.add_column(sa.Column("row_revision", sa.BigInteger(), nullable=False, server_default="1"))
        for field, column_type in (
            ("occurred_at", sa.DateTime(timezone=True)), ("amount", amount_type),
            ("currency", sa.String(3)), ("record_type", sa.String(32)), ("record_subtype", sa.String(32)),
        ):
            batch.alter_column(field, existing_type=column_type, nullable=True)
        batch.create_check_constraint("ck_cash_transactions_state", "state IN ('draft', 'posted')")
        batch.create_check_constraint("ck_cash_transactions_row_revision", "row_revision >= 1")
        batch.create_check_constraint(
            "ck_cash_transactions_posted_complete",
            "state <> 'posted' OR (amount IS NOT NULL AND occurred_at IS NOT NULL "
            "AND currency IS NOT NULL AND record_type IS NOT NULL AND record_subtype IS NOT NULL "
            "AND ((cash_granularity = 'aggregate' AND account_id IS NULL) "
            "OR (cash_granularity = 'atomic' AND account_id IS NOT NULL)))",
        )
        batch.create_check_constraint(
            "ck_cash_transactions_currency",
            "currency IS NULL OR (length(currency) = 3 AND currency = upper(currency))",
        )
        batch.create_index("ix_cash_transactions_workspace_state", ["workspace_id", "state", "occurred_at", "id"])
        if not account_fk_present:
            # Some historical rebuilds omitted this FK. Restore the same
            # workspace boundary declared by the current ORM before drafts
            # can carry an optional account reference.
            batch.create_foreign_key(
                "fk_cash_transactions_workspace_account", "accounts",
                ["workspace_id", "account_id"], ["workspace_id", "id"], ondelete="RESTRICT",
            )
    _create_source_identity_index()
    with op.batch_alter_table("cash_transaction_components") as batch:
        for field, column_type in (("account_id", sa.BigInteger()), ("amount", amount_type), ("currency", sa.String(3))):
            batch.alter_column(field, existing_type=column_type, nullable=True)
        batch.create_unique_constraint("uq_cash_components_workspace_parent_id", ["workspace_id", "cash_transaction_id", "id"])


def downgrade() -> None:
    bind = op.get_bind()
    if bind.execute(sa.text("SELECT 1 FROM cash_transactions WHERE state = 'draft' LIMIT 1")).first():
        raise ValueError("cash drafts exist; refusing a lossy schema downgrade")
    amount_type = sa.String(96) if bind.dialect.name == "sqlite" else sa.Numeric(38, 18)
    with op.batch_alter_table("cash_transaction_components") as batch:
        batch.drop_constraint("uq_cash_components_workspace_parent_id", type_="unique")
        for field, column_type in (("account_id", sa.BigInteger()), ("amount", amount_type), ("currency", sa.String(3))):
            batch.alter_column(field, existing_type=column_type, nullable=False)
    op.drop_index("uq_cash_transactions_active_source_record", table_name="cash_transactions")
    with op.batch_alter_table("cash_transactions") as batch:
        batch.drop_index("ix_cash_transactions_workspace_state")
        for constraint in ("ck_cash_transactions_state", "ck_cash_transactions_row_revision", "ck_cash_transactions_posted_complete", "ck_cash_transactions_currency"):
            batch.drop_constraint(constraint, type_="check")
        batch.drop_column("row_revision")
        batch.drop_column("state")
        for field, column_type in (
            ("occurred_at", sa.DateTime(timezone=True)), ("amount", amount_type),
            ("currency", sa.String(3)), ("record_type", sa.String(32)), ("record_subtype", sa.String(32)),
        ):
            batch.alter_column(field, existing_type=column_type, nullable=False)
    _create_source_identity_index()

"""Add source-normalized record_type to cash transactions.

Revision ID: 20260801_13
Revises: 20260731_12
Create Date: 2026-08-01
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260801_13"
down_revision = "20260731_12"
branch_labels = None
depends_on = None


RECORD_TYPES = (
    "'consumption'", "'refund'", "'transfer_in'", "'transfer_out'",
    "'repayment'", "'income'", "'investment_in'", "'investment_out'",
    "'interest'", "'fee'", "'fx_in'", "'fx_out'", "'other'",
)


def _create_active_identity_index() -> None:
    bind = op.get_bind()
    bind.exec_driver_sql(
        "DROP INDEX IF EXISTS uq_cash_transactions_active_source_record"
    )
    bind.exec_driver_sql(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          AND deleted_at IS NULL
        """
    )


def upgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.add_column(
                sa.Column("record_type", sa.String(32), nullable=False, server_default="other")
            )
            batch_op.create_check_constraint(
                "ck_cash_transactions_record_type",
                f"record_type IN ({', '.join(RECORD_TYPES)})",
            )
        # SQLite batch reflection truncates a multi-clause partial-index
        # predicate; restore the exact active-fact identity predicate.
        _create_active_identity_index()
        return

    op.add_column(
        "cash_transactions",
        sa.Column("record_type", sa.String(32), nullable=False, server_default="other"),
    )
    op.create_check_constraint(
        "ck_cash_transactions_record_type",
        "cash_transactions",
        f"record_type IN ({', '.join(RECORD_TYPES)})",
    )


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.drop_constraint("ck_cash_transactions_record_type", type_="check")
            batch_op.drop_column("record_type")
        _create_active_identity_index()
        return

    op.drop_constraint("ck_cash_transactions_record_type", "cash_transactions", type_="check")
    op.drop_column("cash_transactions", "record_type")

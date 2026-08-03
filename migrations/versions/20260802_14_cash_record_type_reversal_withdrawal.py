"""Split reversal and withdrawal from the normalized cash record types.

Revision ID: 20260802_14
Revises: 20260801_13
Create Date: 2026-08-02
"""
from __future__ import annotations

from alembic import op


revision = "20260802_14"
down_revision = "20260801_13"
branch_labels = None
depends_on = None


PREVIOUS_RECORD_TYPES = (
    "'consumption'", "'refund'", "'transfer_in'", "'transfer_out'",
    "'repayment'", "'income'", "'investment_in'", "'investment_out'",
    "'interest'", "'fee'", "'fx_in'", "'fx_out'", "'other'",
)
RECORD_TYPES = (
    "'consumption'", "'refund'", "'reversal'", "'withdrawal'",
    "'transfer_in'", "'transfer_out'", "'repayment'", "'income'",
    "'investment_in'", "'investment_out'", "'interest'", "'fee'",
    "'fx_in'", "'fx_out'", "'other'",
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


def _replace_check_constraint(record_types: tuple[str, ...]) -> None:
    expression = f"record_type IN ({', '.join(record_types)})"
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.drop_constraint("ck_cash_transactions_record_type", type_="check")
            batch_op.create_check_constraint(
                "ck_cash_transactions_record_type",
                expression,
            )
        _create_active_identity_index()
        return

    op.drop_constraint("ck_cash_transactions_record_type", "cash_transactions", type_="check")
    op.create_check_constraint(
        "ck_cash_transactions_record_type",
        "cash_transactions",
        expression,
    )


def upgrade() -> None:
    _replace_check_constraint(RECORD_TYPES)


def downgrade() -> None:
    _replace_check_constraint(PREVIOUS_RECORD_TYPES)

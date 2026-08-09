"""Persist normalized cash record subtypes.

Revision ID: 20260804_19
Revises: 20260803_18
Create Date: 2026-08-04
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260804_19"
down_revision = "20260803_18"
branch_labels = None
depends_on = None


_SUBTYPE_CONSTRAINT = (
    "(record_type IN ('transfer_in', 'transfer_out') AND record_subtype IN "
    "('ordinary_transfer', 'cross_border_remittance', 'internal_account_transfer')) OR "
    "(record_type IN ('fx_in', 'fx_out') AND record_subtype = 'currency_exchange') OR "
    "(record_type = 'repayment' AND record_subtype = 'credit_repayment') OR "
    "(record_type IN ('withdrawal_in', 'withdrawal_out') AND record_subtype = 'withdraw_to_bank') OR "
    "(record_type NOT IN ('transfer_in', 'transfer_out', 'fx_in', 'fx_out', 'repayment', "
    "'withdrawal_in', 'withdrawal_out') AND record_subtype = 'not_applicable')"
)


def _create_active_identity_index() -> None:
    """恢复 SQLite 批量重建表时丢失的完整 partial-index 谓词。"""
    op.get_bind().exec_driver_sql(
        "DROP INDEX IF EXISTS uq_cash_transactions_active_source_record"
    )
    op.get_bind().exec_driver_sql(
        """
        CREATE UNIQUE INDEX uq_cash_transactions_active_source_record
        ON cash_transactions (workspace_id, source_type, record_id)
        WHERE source_type IS NOT NULL AND source_type <> ''
          AND record_id IS NOT NULL AND record_id <> ''
          AND deleted_at IS NULL
        """
    )


def upgrade() -> None:
    recreate = "always" if op.get_bind().dialect.name == "sqlite" else "auto"
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        bind.exec_driver_sql("PRAGMA foreign_keys=OFF")
    try:
        with op.batch_alter_table("cash_transactions", recreate=recreate) as batch:
            batch.add_column(sa.Column(
                "record_subtype", sa.String(length=32), nullable=False,
                server_default=sa.text("'not_applicable'"),
            ))
    finally:
        if bind.dialect.name == "sqlite":
            bind.exec_driver_sql("PRAGMA foreign_keys=ON")
    op.execute(
        "UPDATE cash_transactions SET record_subtype = CASE "
        "WHEN record_type IN ('transfer_in', 'transfer_out') THEN 'ordinary_transfer' "
        "WHEN record_type IN ('fx_in', 'fx_out') THEN 'currency_exchange' "
        "WHEN record_type = 'repayment' THEN 'credit_repayment' "
        "WHEN record_type IN ('withdrawal_in', 'withdrawal_out') THEN 'withdraw_to_bank' "
        "ELSE 'not_applicable' END"
    )
    with op.batch_alter_table("cash_transactions") as batch:
        batch.create_check_constraint(
            "ck_cash_transactions_record_type_subtype", _SUBTYPE_CONSTRAINT,
        )
    if op.get_bind().dialect.name == "sqlite":
        _create_active_identity_index()


def downgrade() -> None:
    recreate = "always" if op.get_bind().dialect.name == "sqlite" else "auto"
    with op.batch_alter_table("cash_transactions", recreate=recreate) as batch:
        batch.drop_constraint("ck_cash_transactions_record_type_subtype", type_="check")
        batch.drop_column("record_subtype")
    if op.get_bind().dialect.name == "sqlite":
        _create_active_identity_index()

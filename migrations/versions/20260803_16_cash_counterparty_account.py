"""Add the formal cash counterparty account field.

Revision ID: 20260803_16
Revises: 20260802_15
Create Date: 2026-08-03
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260803_16"
down_revision = "20260802_15"
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.add_column(
        "cash_transactions",
        sa.Column(
            "counterparty_account",
            sa.String(length=512),
            nullable=False,
            server_default="",
        ),
    )
def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.drop_column("counterparty_account")
        return
    op.drop_column("cash_transactions", "counterparty_account")

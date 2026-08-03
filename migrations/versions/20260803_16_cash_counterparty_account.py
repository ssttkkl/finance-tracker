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


def _legacy_counterparty_account(source_type: str, payload: object) -> str:
    if not isinstance(payload, dict):
        return ""
    direct = str(payload.get("对方账号") or "").strip()
    if direct:
        return direct
    if source_type == "ccb_debit":
        raw = str(payload.get("acct_name_raw") or "")
        if "/" in raw:
            return raw.split("/", 1)[0].strip()
    return ""


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
    bind = op.get_bind()
    cash = sa.table(
        "cash_transactions",
        sa.column("id", sa.BigInteger()),
        sa.column("source_type", sa.String()),
        sa.column("source_payload", sa.JSON()),
        sa.column("counterparty_account", sa.String()),
    )
    rows = bind.execute(sa.select(cash.c.id, cash.c.source_type, cash.c.source_payload)).mappings()
    for row in rows:
        value = _legacy_counterparty_account(str(row["source_type"] or ""), row["source_payload"])
        if value:
            bind.execute(
                sa.update(cash).where(cash.c.id == row["id"]).values(counterparty_account=value)
            )


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("cash_transactions", recreate="always") as batch_op:
            batch_op.drop_column("counterparty_account")
        return
    op.drop_column("cash_transactions", "counterparty_account")

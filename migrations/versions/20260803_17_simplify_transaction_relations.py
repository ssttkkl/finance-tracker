"""Remove recomputable transaction-relation process evidence.

Revision ID: 20260803_17
Revises: 20260803_16
Create Date: 2026-08-03
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260803_17"
down_revision = "20260803_16"
branch_labels = None
depends_on = None


_REMOVED_COLUMNS = ("evidence_json", "confidence", "later_marker")


def upgrade() -> None:
    for column in _REMOVED_COLUMNS:
        op.drop_column("transaction_relations", column)


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        with op.batch_alter_table("transaction_relations", recreate="always") as batch_op:
            batch_op.add_column(sa.Column("confidence", sa.String(length=32), nullable=False, server_default=""))
            batch_op.add_column(sa.Column("evidence_json", sa.JSON(), nullable=False, server_default=sa.text("'{}'")))
            batch_op.add_column(sa.Column("later_marker", sa.String(length=64), nullable=False, server_default=""))
        return
    op.add_column("transaction_relations", sa.Column("confidence", sa.String(length=32), nullable=False, server_default=""))
    op.add_column("transaction_relations", sa.Column("evidence_json", sa.JSON(), nullable=False, server_default=sa.text("'{}'")))
    op.add_column("transaction_relations", sa.Column("later_marker", sa.String(length=64), nullable=False, server_default=""))

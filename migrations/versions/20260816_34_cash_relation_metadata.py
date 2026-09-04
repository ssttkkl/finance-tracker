"""Persist relation-derived cash import metadata separately from source snapshots."""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260816_34"
down_revision = "20260814_33"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "cash_transactions",
        sa.Column("relation_metadata", sa.JSON(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("cash_transactions", "relation_metadata")

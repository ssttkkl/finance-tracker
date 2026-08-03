"""Persist selectable candidates for unpaired transaction relations.

Revision ID: 20260803_18
Revises: 20260803_17
Create Date: 2026-08-03
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260803_18"
down_revision = "20260803_17"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "transaction_relations",
        sa.Column(
            "candidate_fact_ids",
            sa.JSON(),
            nullable=False,
            server_default=sa.text("'[]'"),
        ),
    )


def downgrade() -> None:
    op.drop_column("transaction_relations", "candidate_fact_ids")

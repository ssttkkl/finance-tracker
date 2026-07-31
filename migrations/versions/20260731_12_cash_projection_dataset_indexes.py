"""为收支投影子表添加数据集索引。

Revision ID: 20260731_12
Revises: 20260729_11
Create Date: 2026-07-31
"""
from __future__ import annotations

from alembic import op


revision = "20260731_12"
down_revision = "20260729_11"
branch_labels = None
depends_on = None


INDEXES = (
    ("ix_cash_projection_members_dataset", "cash_projection_members"),
    ("ix_cash_projection_relations_dataset", "cash_projection_relations"),
)


def upgrade() -> None:
    for name, table in INDEXES:
        op.create_index(name, table, ["dataset_id"])


def downgrade() -> None:
    for name, table in reversed(INDEXES):
        op.drop_index(name, table_name=table)

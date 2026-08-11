"""为收支账单分页和关联组扫描添加复合索引。"""
from __future__ import annotations

from alembic import op


revision = "20260811_26"
down_revision = "20260807_25"
branch_labels = None
depends_on = None


INDEXES = (
    (
        "ix_cash_projection_members_page_lookup",
        "cash_projection_members",
        ["workspace_id", "dataset_id", "projection_row_id", "ordinal"],
    ),
    (
        "ix_cash_projection_relations_page_lookup",
        "cash_projection_relations",
        ["workspace_id", "dataset_id", "projection_row_id", "kind", "ordinal"],
    ),
    (
        "ix_transaction_relations_component_primary",
        "transaction_relations",
        ["workspace_id", "status", "primary_fact_id"],
    ),
    (
        "ix_transaction_relations_component_secondary",
        "transaction_relations",
        ["workspace_id", "status", "secondary_fact_id"],
    ),
)


def upgrade() -> None:
    for name, table, columns in INDEXES:
        op.create_index(name, table, columns)


def downgrade() -> None:
    for name, table, _columns in reversed(INDEXES):
        op.drop_index(name, table_name=table)

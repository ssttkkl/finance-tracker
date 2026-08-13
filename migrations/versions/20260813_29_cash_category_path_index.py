"""为收支分类树筛选添加投影路径索引。"""
from __future__ import annotations

from alembic import op


revision = "20260813_29"
down_revision = "20260813_28"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index(
        "ix_cash_projections_category_path",
        "cash_projections",
        ["workspace_id", "dataset_id", "category_path"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_cash_projections_category_path",
        table_name="cash_projections",
    )

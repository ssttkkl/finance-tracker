"""为收支分类影响统计添加流水分类索引。"""
from __future__ import annotations

from alembic import op


revision = "20260813_28"
down_revision = "20260812_27"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index(
        "ix_cash_transactions_workspace_category",
        "cash_transactions",
        ["workspace_id", "category_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_cash_transactions_workspace_category",
        table_name="cash_transactions",
    )

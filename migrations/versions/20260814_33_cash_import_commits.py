"""Persist cash-import confirmation idempotency results."""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260814_33"
down_revision = "20260814_32"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "cash_import_commits",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column(
            "workspace_id",
            sa.String(length=64),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("user_id", sa.String(length=36), nullable=False),
        sa.Column("idempotency_key", sa.String(length=255), nullable=False),
        sa.Column("session_digest", sa.String(length=64), nullable=False),
        sa.Column("result_json", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "workspace_id", "idempotency_key",
            name="uq_cash_import_commits_workspace_key",
        ),
    )
    op.create_index(
        "ix_cash_import_commits_workspace",
        "cash_import_commits",
        ["workspace_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_cash_import_commits_workspace", table_name="cash_import_commits")
    op.drop_table("cash_import_commits")

"""Persist confirmed cash statement source-account mappings."""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260814_31"
down_revision = "20260813_30"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "statement_account_mappings",
        sa.Column("id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(length=64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("source_type", sa.String(length=64), nullable=False),
        sa.Column("identity_kind", sa.String(length=64), nullable=False),
        sa.Column("source_account_key", sa.String(length=255), nullable=False),
        sa.Column("account_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=False),
        sa.Column("confirmed_by", sa.String(length=128), server_default="", nullable=False),
        sa.Column("revision", sa.BigInteger(), server_default="1", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_statement_account_mappings_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "source_type", "identity_kind", "source_account_key",
            name="uq_statement_account_mappings_source_identity",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_statement_account_mappings_workspace_account",
        ),
    )
    op.create_index(
        "ix_statement_account_mappings_workspace",
        "statement_account_mappings",
        ["workspace_id"],
    )


def downgrade() -> None:
    op.drop_index("ix_statement_account_mappings_workspace", table_name="statement_account_mappings")
    op.drop_table("statement_account_mappings")

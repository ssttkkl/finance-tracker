"""Add sync_cursors table for incremental connector sync.

Revision ID: 20260726_10
Revises: 20260724_09
Create Date: 2026-07-26

Adds sync_cursors table with (workspace_id, account_id, source_type) unique
constraint for persisting incremental sync cursor positions.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260726_10"
down_revision = "20260724_09"
branch_labels = None
depends_on = None


def _dialect() -> str:
    return op.get_bind().dialect.name


def upgrade() -> None:
    if _dialect() == "sqlite":
        _upgrade_sqlite()
    else:
        _upgrade_postgresql()


def downgrade() -> None:
    op.drop_table("sync_cursors")


def _upgrade_sqlite() -> None:
    op.create_table(
        "sync_cursors",
        sa.Column("id", sa.Integer, primary_key=True, autoincrement=True),
        sa.Column(
            "workspace_id",
            sa.String(64),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("account_id", sa.Integer, nullable=False),
        sa.Column("source_type", sa.String(64), nullable=False),
        sa.Column("cursor_value", sa.String(256), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "workspace_id", "account_id", "source_type",
            name="uq_sync_cursors_workspace_account_source",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_sync_cursors_workspace_account",
        ),
        sa.Index("ix_sync_cursors_workspace", "workspace_id"),
    )


def _upgrade_postgresql() -> None:
    op.create_table(
        "sync_cursors",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column(
            "workspace_id",
            sa.String(64),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("account_id", sa.BigInteger, nullable=False),
        sa.Column("source_type", sa.String(64), nullable=False),
        sa.Column("cursor_value", sa.String(256), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint(
            "workspace_id", "account_id", "source_type",
            name="uq_sync_cursors_workspace_account_source",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_sync_cursors_workspace_account",
        ),
        sa.Index("ix_sync_cursors_workspace", "workspace_id"),
    )

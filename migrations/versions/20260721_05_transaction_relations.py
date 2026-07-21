"""Transaction relations, check runs, aliases, logical delete.

Revision ID: 20260721_05
Revises: 20260720_04
Create Date: 2026-07-21
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260721_05"
down_revision = "20260720_04"
branch_labels = None
depends_on = None


def _dialect() -> str:
    return op.get_bind().dialect.name


def upgrade() -> None:
    # Logical delete marker on cash formal facts.
    with op.batch_alter_table("cash_transactions") as batch:
        batch.add_column(sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True))
        batch.add_column(sa.Column("deleted_by", sa.String(length=128), server_default="", nullable=False))
        batch.add_column(sa.Column("delete_reason", sa.Text(), server_default="", nullable=False))

    op.create_table(
        "transaction_relations",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("subtype", sa.String(length=64), server_default="", nullable=False),
        sa.Column("primary_fact_id", sa.String(length=36), nullable=False),
        sa.Column("secondary_fact_id", sa.String(length=36), nullable=False),
        sa.Column("primary_fact_type", sa.String(length=32), server_default="cash", nullable=False),
        sa.Column("secondary_fact_type", sa.String(length=32), server_default="cash", nullable=False),
        sa.Column("ordered_fact_a", sa.String(length=36), nullable=False),
        sa.Column("ordered_fact_b", sa.String(length=36), nullable=False),
        sa.Column("active_slot", sa.String(length=36), server_default="active", nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("rule_id", sa.String(length=128), server_default="", nullable=False),
        sa.Column("confidence", sa.String(length=32), server_default="", nullable=False),
        sa.Column("evidence_json", sa.JSON(), nullable=False),
        sa.Column("created_by", sa.String(length=128), server_default="system", nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("decided_by", sa.String(length=128), server_default="", nullable=False),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("decision_reason", sa.Text(), server_default="", nullable=False),
        sa.Column("later_marker", sa.String(length=64), server_default="", nullable=False),
        sa.Column("superseded_by_id", sa.String(length=36), nullable=True),
        sa.Column("revision", sa.Integer(), server_default="1", nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_transaction_relations_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "kind", "ordered_fact_a", "ordered_fact_b", "subtype", "active_slot",
            name="uq_transaction_relations_active_business_key",
        ),
        sa.CheckConstraint(
            "kind IN ('payment_mirror','transfer_pair','refund_offset')",
            name="ck_transaction_relations_kind",
        ),
        sa.CheckConstraint(
            "status IN ('pending_review','accepted','rejected','superseded')",
            name="ck_transaction_relations_status",
        ),
    )
    op.create_index(
        "ix_transaction_relations_workspace_status",
        "transaction_relations",
        ["workspace_id", "status"],
    )
    op.create_index(
        "ix_transaction_relations_workspace_kind",
        "transaction_relations",
        ["workspace_id", "kind"],
    )
    op.create_index(
        "ix_transaction_relations_primary",
        "transaction_relations",
        ["workspace_id", "primary_fact_id"],
    )
    op.create_index(
        "ix_transaction_relations_secondary",
        "transaction_relations",
        ["workspace_id", "secondary_fact_id"],
    )

    op.create_table(
        "relation_check_runs",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("trigger", sa.String(length=64), nullable=False),
        sa.Column("seed_ref", sa.Text(), server_default="", nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("error", sa.Text(), server_default="", nullable=False),
        sa.Column("stats_json", sa.JSON(), nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_relation_check_runs_workspace_id"),
        sa.CheckConstraint(
            "status IN ('pending','running','completed','failed')",
            name="ck_relation_check_runs_status",
        ),
    )
    op.create_index(
        "ix_relation_check_runs_workspace_status",
        "relation_check_runs",
        ["workspace_id", "status"],
    )

    op.create_table(
        "account_aliases",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("alias_type", sa.String(length=32), nullable=False),
        sa.Column("alias_value", sa.String(length=255), nullable=False),
        sa.Column("account_id", sa.String(length=36), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_account_aliases_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "alias_type", "alias_value", "account_id",
            name="uq_account_aliases_value_account",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_account_aliases_workspace_account",
        ),
    )
    op.create_index(
        "ix_account_aliases_workspace_value",
        "account_aliases",
        ["workspace_id", "alias_type", "alias_value"],
    )

    op.create_table(
        "fact_deletion_events",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("fact_id", sa.String(length=36), nullable=False),
        sa.Column("fact_type", sa.String(length=32), server_default="cash", nullable=False),
        sa.Column("actor", sa.String(length=128), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_fact_deletion_events_workspace_id"),
    )
    op.create_index(
        "ix_fact_deletion_events_fact",
        "fact_deletion_events",
        ["workspace_id", "fact_type", "fact_id"],
    )

    # Active-only occupancy helpers: index on non-deleted cash rows (dialect-aware partial when possible).
    if _dialect() == "postgresql":
        op.execute(
            "CREATE UNIQUE INDEX uq_cash_active_raw_record "
            "ON cash_transactions (workspace_id, raw_record_id) "
            "WHERE deleted_at IS NULL AND raw_record_id IS NOT NULL"
        )
    else:
        # SQLite: keep original unique; active-only enforced in application for re-import path.
        # Drop/recreate would break existing FK uniqueness contract; app layer + tests cover active occupancy.
        pass


def downgrade() -> None:
    if _dialect() == "postgresql":
        op.execute("DROP INDEX IF EXISTS uq_cash_active_raw_record")
    op.drop_table("fact_deletion_events")
    op.drop_table("account_aliases")
    op.drop_table("relation_check_runs")
    op.drop_table("transaction_relations")
    with op.batch_alter_table("cash_transactions") as batch:
        batch.drop_column("delete_reason")
        batch.drop_column("deleted_by")
        batch.drop_column("deleted_at")

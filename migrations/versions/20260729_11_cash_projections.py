"""Add derived cash projection read-model tables.

Revision ID: 20260729_11
Revises: 20260726_10
Create Date: 2026-07-29
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260729_11"
down_revision = "20260726_10"
branch_labels = None
depends_on = None


def _id_type():
    return sa.Integer if op.get_bind().dialect.name == "sqlite" else sa.BigInteger


def upgrade() -> None:
    identifier = _id_type()
    op.create_table(
        "cash_projection_states",
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True),
        sa.Column("active_dataset_id", sa.String(64), nullable=True),
        sa.Column("projection_version", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("source_revision", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("rules_version", sa.String(64), nullable=False, server_default="cash-projection-v1"),
        sa.Column("availability", sa.String(16), nullable=False, server_default="uninitialized"),
        sa.Column("last_build_status", sa.String(16), nullable=False, server_default="never"),
        sa.Column("last_build_id", sa.String(64), nullable=True),
        sa.Column("last_error_code", sa.String(64), nullable=True),
        sa.Column("last_error_summary", sa.Text, nullable=True),
        sa.Column("projection_count", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("member_count", sa.BigInteger, nullable=False, server_default="0"),
        sa.Column("build_started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("build_finished_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint("availability IN ('uninitialized', 'ready')", name="ck_cash_projection_states_availability"),
        sa.CheckConstraint("last_build_status IN ('never', 'running', 'succeeded', 'failed')", name="ck_cash_projection_states_build_status"),
        sa.CheckConstraint("availability <> 'ready' OR active_dataset_id IS NOT NULL", name="ck_cash_projection_states_ready_dataset"),
    )
    op.create_table(
        "cash_projection_datasets",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("state", sa.String(16), nullable=False),
        sa.Column("source_revision", sa.BigInteger, nullable=False),
        sa.Column("source_digest", sa.String(64), nullable=False),
        sa.Column("rules_version", sa.String(64), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("published_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint("state IN ('staging', 'active', 'retired')", name="ck_cash_projection_datasets_state"),
    )
    op.create_index("ix_cash_projection_datasets_workspace_state", "cash_projection_datasets", ["workspace_id", "state"])
    op.create_table(
        "cash_projections",
        sa.Column("id", identifier, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("dataset_id", sa.String(64), nullable=False),
        sa.Column("projection_id", sa.String(96), nullable=False),
        sa.Column("root_cash_transaction_id", identifier, nullable=False),
        sa.Column("economic_type", sa.String(24), nullable=False),
        sa.Column("transfer_subtype", sa.String(32), nullable=True),
        sa.Column("net_amount", sa.Numeric(38, 18) if op.get_bind().dialect.name != "sqlite" else sa.String(64), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("account_id", identifier, nullable=False),
        sa.Column("counterparty", sa.String(512), nullable=False, server_default=""),
        sa.Column("category", sa.String(64), nullable=False, server_default=""),
        sa.Column("note", sa.Text, nullable=False, server_default=""),
        sa.Column("source_type", sa.String(64), nullable=True),
        sa.Column("record_id", sa.String(512), nullable=False, server_default=""),
        sa.Column("visible", sa.Boolean, nullable=False),
        sa.Column("hidden_reason", sa.String(32), nullable=True),
        sa.Column("has_payment_mirror", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("has_refund_offset", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("has_transfer_pair", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("member_count", sa.Integer, nullable=False),
        sa.Column("accepted_relation_count", sa.Integer, nullable=False),
        sa.Column("built_projection_version", sa.BigInteger, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["dataset_id"], ["cash_projection_datasets.id"], ondelete="CASCADE", name="fk_cash_projections_dataset"),
        sa.ForeignKeyConstraint(["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_cash_projections_workspace_account"),
        sa.ForeignKeyConstraint(["workspace_id", "root_cash_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="RESTRICT", name="fk_cash_projections_workspace_root"),
        sa.UniqueConstraint("workspace_id", "dataset_id", "projection_id", name="uq_cash_projections_dataset_projection"),
        sa.CheckConstraint("economic_type IN ('expense', 'income', 'internal_transfer')", name="ck_cash_projections_economic_type"),
    )
    for name, columns in {
        "ix_cash_projections_visible_list": ["workspace_id", "dataset_id", "visible", "occurred_at", "projection_id"],
        "ix_cash_projections_account": ["workspace_id", "dataset_id", "account_id"],
        "ix_cash_projections_currency": ["workspace_id", "dataset_id", "currency"],
        "ix_cash_projections_economic_type": ["workspace_id", "dataset_id", "economic_type"],
        "ix_cash_projections_category": ["workspace_id", "dataset_id", "category"],
        "ix_cash_projections_root": ["workspace_id", "dataset_id", "root_cash_transaction_id"],
    }.items():
        op.create_index(name, "cash_projections", columns)
    op.create_table(
        "cash_projection_members",
        sa.Column("id", identifier, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("dataset_id", sa.String(64), nullable=False),
        sa.Column("projection_row_id", identifier, nullable=False),
        sa.Column("cash_transaction_id", identifier, nullable=False),
        sa.Column("roles_json", sa.JSON, nullable=False),
        sa.Column("ordinal", sa.Integer, nullable=False),
        sa.ForeignKeyConstraint(["projection_row_id"], ["cash_projections.id"], ondelete="CASCADE", name="fk_cash_projection_members_projection"),
        sa.ForeignKeyConstraint(["workspace_id", "cash_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="RESTRICT", name="fk_cash_projection_members_cash"),
        sa.UniqueConstraint("workspace_id", "dataset_id", "cash_transaction_id", name="uq_cash_projection_members_dataset_cash"),
        sa.UniqueConstraint("projection_row_id", "ordinal", name="uq_cash_projection_members_ordinal"),
    )
    op.create_table(
        "cash_projection_relations",
        sa.Column("id", identifier, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("dataset_id", sa.String(64), nullable=False),
        sa.Column("projection_row_id", identifier, nullable=False),
        sa.Column("transaction_relation_id", identifier, nullable=False),
        sa.Column("kind", sa.String(32), nullable=False),
        sa.Column("subtype", sa.String(32), nullable=False, server_default=""),
        sa.Column("ordinal", sa.Integer, nullable=False),
        sa.ForeignKeyConstraint(["projection_row_id"], ["cash_projections.id"], ondelete="CASCADE", name="fk_cash_projection_relations_projection"),
        sa.ForeignKeyConstraint(["workspace_id", "transaction_relation_id"], ["transaction_relations.workspace_id", "transaction_relations.id"], ondelete="RESTRICT", name="fk_cash_projection_relations_relation"),
        sa.UniqueConstraint("workspace_id", "dataset_id", "transaction_relation_id", name="uq_cash_projection_relations_dataset_relation"),
        sa.UniqueConstraint("projection_row_id", "ordinal", name="uq_cash_projection_relations_ordinal"),
    )


def downgrade() -> None:
    op.drop_table("cash_projection_relations")
    op.drop_table("cash_projection_members")
    op.drop_table("cash_projections")
    op.drop_table("cash_projection_datasets")
    op.drop_table("cash_projection_states")

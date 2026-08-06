"""新增收支与投资账户资金调拨关系。

Revision ID: 20260804_23
Revises: 20260804_22
Create Date: 2026-08-04
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op


revision = "20260804_23"
down_revision = "20260804_22"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "cash_investment_funding_relations",
        sa.Column("id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(length=64), nullable=False),
        sa.Column("cash_transaction_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=False),
        sa.Column("investment_event_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=False),
        sa.Column("direction", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=32), nullable=False),
        sa.Column("rule_id", sa.String(length=128), nullable=False),
        sa.Column("evidence", sa.JSON(), nullable=False),
        sa.Column("active_slot", sa.String(length=36), nullable=False, server_default="active"),
        sa.Column("created_by", sa.String(length=128), nullable=False, server_default="system"),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
        sa.Column("decided_by", sa.String(length=128), nullable=False, server_default=""),
        sa.Column("decided_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("decision_reason", sa.Text(), nullable=False, server_default=""),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "cash_transaction_id"],
            ["cash_transactions.workspace_id", "cash_transactions.id"],
            ondelete="RESTRICT",
            name="fk_cash_investment_funding_relations_workspace_cash",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "investment_event_id"],
            ["investment_events.workspace_id", "investment_events.id"],
            ondelete="RESTRICT",
            name="fk_cash_investment_funding_relations_workspace_investment",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_investment_funding_relations_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "cash_transaction_id", "investment_event_id", "active_slot",
            name="uq_cash_investment_funding_relations_active_pair",
        ),
        sa.CheckConstraint(
            "direction IN ('cash_to_investment', 'investment_to_cash')",
            name="ck_cash_investment_funding_relations_direction",
        ),
        sa.CheckConstraint(
            "status IN ('pending_review', 'accepted', 'rejected')",
            name="ck_cash_investment_funding_relations_status",
        ),
    )
    op.create_index(
        "ix_cash_investment_funding_relations_workspace_status",
        "cash_investment_funding_relations",
        ["workspace_id", "status"],
    )
    op.create_index(
        "uq_cash_investment_funding_relations_accepted_cash",
        "cash_investment_funding_relations",
        ["workspace_id", "cash_transaction_id"],
        unique=True,
        sqlite_where=sa.text("status = 'accepted' AND active_slot = 'active'"),
        postgresql_where=sa.text("status = 'accepted' AND active_slot = 'active'"),
    )
    op.create_index(
        "uq_cash_investment_funding_relations_accepted_investment",
        "cash_investment_funding_relations",
        ["workspace_id", "investment_event_id"],
        unique=True,
        sqlite_where=sa.text("status = 'accepted' AND active_slot = 'active'"),
        postgresql_where=sa.text("status = 'accepted' AND active_slot = 'active'"),
    )
    with op.batch_alter_table("cash_projections") as batch:
        batch.add_column(sa.Column("funding_relation_id", sa.BigInteger().with_variant(sa.Integer, "sqlite"), nullable=True))
        batch.create_foreign_key(
            "fk_cash_projections_workspace_funding_relation",
            "cash_investment_funding_relations",
            ["workspace_id", "funding_relation_id"],
            ["workspace_id", "id"],
            ondelete="RESTRICT",
        )


def downgrade() -> None:
    with op.batch_alter_table("cash_projections") as batch:
        batch.drop_constraint("fk_cash_projections_workspace_funding_relation", type_="foreignkey")
        batch.drop_column("funding_relation_id")
    op.drop_index("uq_cash_investment_funding_relations_accepted_investment", table_name="cash_investment_funding_relations")
    op.drop_index("uq_cash_investment_funding_relations_accepted_cash", table_name="cash_investment_funding_relations")
    op.drop_index("ix_cash_investment_funding_relations_workspace_status", table_name="cash_investment_funding_relations")
    op.drop_table("cash_investment_funding_relations")

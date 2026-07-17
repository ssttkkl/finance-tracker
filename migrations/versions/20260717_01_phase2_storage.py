"""Create the workspace-scoped Phase 2 storage tables."""
from alembic import op
import sqlalchemy as sa


revision = "20260717_01"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "workspaces",
        sa.Column("id", sa.String(length=64), primary_key=True),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )
    op.create_table(
        "accounts",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), nullable=False),
        sa.Column("name", sa.String(length=255), nullable=False),
        sa.Column("type", sa.String(length=32), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("metadata_json", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("workspace_id", "name", "currency", name="uq_accounts_workspace_name_currency"),
    )
    op.create_index("ix_accounts_workspace", "accounts", ["workspace_id"])
    op.create_table(
        "cash_transactions",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), nullable=False),
        sa.Column("record_id", sa.String(length=255), nullable=False),
        sa.Column("occurred_at", sa.String(length=32), nullable=False),
        sa.Column("amount", sa.Numeric(38, 18), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False),
        sa.Column("counterparty", sa.String(length=512), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("category", sa.String(length=64), nullable=False),
        sa.Column("account_name", sa.String(length=255), nullable=False),
        sa.Column("account_type", sa.String(length=32), nullable=False),
        sa.Column("source", sa.String(length=255), nullable=False),
        sa.Column("bill_source", sa.String(length=255), nullable=False),
        sa.Column("transfer_account", sa.String(length=255), nullable=False),
        sa.Column("locked", sa.String(length=32), nullable=False),
        sa.Column("offset_group", sa.String(length=255), nullable=False),
        sa.Column("offset_role", sa.String(length=64), nullable=False),
        sa.Column("offset_strength", sa.String(length=64), nullable=False),
        sa.Column("offset_source", sa.String(length=255), nullable=False),
        sa.Column("offset_rule_hint", sa.Text(), nullable=False),
        sa.Column("offset_match_type", sa.String(length=64), nullable=False),
        sa.Column("proposed_action", sa.String(length=64), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
    )
    op.create_index(
        "ix_cash_transactions_workspace_date", "cash_transactions", ["workspace_id", "occurred_at"]
    )
    op.create_index(
        "ix_cash_transactions_workspace_account", "cash_transactions", ["workspace_id", "account_name"]
    )
    op.create_table(
        "investment_events",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column("workspace_id", sa.String(length=64), nullable=False),
        sa.Column("occurred_at", sa.String(length=32), nullable=False),
        sa.Column("kind", sa.String(length=64), nullable=False),
        sa.Column("account_name", sa.String(length=255), nullable=False),
        sa.Column("account_type", sa.String(length=32), nullable=False),
        sa.Column("currency", sa.String(length=3), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
    )
    op.create_index(
        "ix_investment_events_workspace_date", "investment_events", ["workspace_id", "occurred_at"]
    )
    op.create_index(
        "ix_investment_events_workspace_account", "investment_events", ["workspace_id", "account_name"]
    )
    op.create_table(
        "ledger_snapshots",
        sa.Column("workspace_id", sa.String(length=64), primary_key=True),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
    )


def downgrade() -> None:
    op.drop_table("ledger_snapshots")
    op.drop_index("ix_investment_events_workspace_account", table_name="investment_events")
    op.drop_index("ix_investment_events_workspace_date", table_name="investment_events")
    op.drop_table("investment_events")
    op.drop_index("ix_cash_transactions_workspace_account", table_name="cash_transactions")
    op.drop_index("ix_cash_transactions_workspace_date", table_name="cash_transactions")
    op.drop_table("cash_transactions")
    op.drop_index("ix_accounts_workspace", table_name="accounts")
    op.drop_table("accounts")
    op.drop_table("workspaces")

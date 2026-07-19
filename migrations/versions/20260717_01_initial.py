"""Create the shared PostgreSQL and SQLite initial schema."""
from alembic import op
import sqlalchemy as sa
from ft.adapters.relational.models import ExactDecimal


revision = "20260717_01"
down_revision = None
branch_labels = None
depends_on = None


def _timestamps():
    return (
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
    )


def upgrade() -> None:
    op.create_table(
        "workspaces",
        sa.Column("id", sa.String(64), primary_key=True),
        sa.Column("name", sa.String(255), nullable=False),
        *_timestamps(),
    )
    op.create_table(
        "accounts",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("type", sa.String(32), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("metadata_json", sa.JSON(), nullable=False),
        *_timestamps(),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.UniqueConstraint("workspace_id", "id", name="uq_accounts_workspace_id"),
        sa.UniqueConstraint("workspace_id", "name", "currency", name="uq_accounts_workspace_name_currency"),
    )
    op.create_index("ix_accounts_workspace", "accounts", ["workspace_id"])

    op.create_table(
        "import_batches",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("target_account_id", sa.String(36), nullable=False),
        sa.Column("source_kind", sa.String(64), nullable=False),
        sa.Column("source_digest", sa.String(128), nullable=False),
        sa.Column("source_ref", sa.Text(), nullable=False),
        sa.Column("status", sa.String(32), nullable=False),
        *_timestamps(),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "target_account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT", name="fk_import_batches_workspace_target_account",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_import_batches_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "source_kind", "source_digest",
            name="uq_import_batches_workspace_kind_digest",
        ),
    )
    op.create_index("ix_import_batches_workspace", "import_batches", ["workspace_id"])
    op.create_index(
        "ix_import_batches_workspace_target", "import_batches",
        ["workspace_id", "target_account_id"],
    )

    op.create_table(
        "raw_files",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("source_path", sa.Text(), nullable=False),
        sa.Column("content_digest", sa.String(128), nullable=False),
        sa.Column("size_bytes", sa.Integer(), nullable=False),
        sa.Column("media_type", sa.String(128), nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "batch_id"],
            ["import_batches.workspace_id", "import_batches.id"],
            ondelete="CASCADE", name="fk_raw_files_workspace_batch",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_raw_files_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "batch_id", "id", name="uq_raw_files_workspace_batch_id",
        ),
        sa.UniqueConstraint(
            "workspace_id", "batch_id", "content_digest",
            name="uq_raw_files_workspace_batch_digest",
        ),
    )
    op.create_index("ix_raw_files_workspace_batch", "raw_files", ["workspace_id", "batch_id"])

    op.create_table(
        "raw_records",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("batch_id", sa.String(36), nullable=False),
        sa.Column("raw_file_id", sa.String(36), nullable=True),
        sa.Column("source_type", sa.String(64), nullable=False),
        sa.Column("source_identity", sa.String(512), nullable=False),
        sa.Column("source_line", sa.Integer(), nullable=True),
        sa.Column("payload", sa.JSON(), nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "batch_id"],
            ["import_batches.workspace_id", "import_batches.id"],
            ondelete="CASCADE", name="fk_raw_records_workspace_batch",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "batch_id", "raw_file_id"],
            ["raw_files.workspace_id", "raw_files.batch_id", "raw_files.id"],
            ondelete="RESTRICT", name="fk_raw_records_workspace_batch_file",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_raw_records_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "source_type", "source_identity",
            name="uq_raw_records_workspace_source_identity",
        ),
    )
    op.create_index("ix_raw_records_workspace_batch", "raw_records", ["workspace_id", "batch_id"])

    op.create_table(
        "cash_transactions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("account_id", sa.String(36), nullable=False),
        sa.Column("raw_record_id", sa.String(36), nullable=True),
        sa.Column("record_id", sa.String(255), nullable=False),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("amount", ExactDecimal(), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("counterparty", sa.String(512), nullable=False),
        sa.Column("description", sa.Text(), nullable=False),
        sa.Column("category", sa.String(64), nullable=False),
        sa.Column("source", sa.String(255), nullable=False),
        sa.Column("bill_source", sa.String(255), nullable=False),
        sa.Column("transfer_account", sa.String(255), nullable=False),
        sa.Column("locked", sa.String(32), nullable=False),
        sa.Column("offset_group", sa.String(255), nullable=False),
        sa.Column("offset_role", sa.String(64), nullable=False),
        sa.Column("offset_strength", sa.String(64), nullable=False),
        sa.Column("offset_source", sa.String(255), nullable=False),
        sa.Column("offset_rule_hint", sa.Text(), nullable=False),
        sa.Column("offset_match_type", sa.String(64), nullable=False),
        sa.Column("proposed_action", sa.String(64), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT", name="fk_cash_transactions_workspace_account",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "raw_record_id"], ["raw_records.workspace_id", "raw_records.id"],
            ondelete="RESTRICT", name="fk_cash_transactions_workspace_raw_record",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_transactions_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "raw_record_id", name="uq_cash_transactions_workspace_raw_record",
        ),
    )
    op.create_index("ix_cash_transactions_workspace_date", "cash_transactions", ["workspace_id", "occurred_at"])
    op.create_index("ix_cash_transactions_workspace_account", "cash_transactions", ["workspace_id", "account_id"])

    op.create_table(
        "investment_events",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("account_id", sa.String(36), nullable=False),
        sa.Column("raw_record_id", sa.String(36), nullable=True),
        sa.Column("occurred_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("kind", sa.String(64), nullable=False),
        sa.Column("currency", sa.String(3), nullable=False),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("revision", sa.Integer(), nullable=False),
        *_timestamps(),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT", name="fk_investment_events_workspace_account",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "raw_record_id"], ["raw_records.workspace_id", "raw_records.id"],
            ondelete="RESTRICT", name="fk_investment_events_workspace_raw_record",
        ),
        sa.UniqueConstraint("workspace_id", "id", name="uq_investment_events_workspace_id"),
        sa.UniqueConstraint(
            "workspace_id", "raw_record_id", name="uq_investment_events_workspace_raw_record",
        ),
    )
    op.create_index("ix_investment_events_workspace_date", "investment_events", ["workspace_id", "occurred_at"])
    op.create_index("ix_investment_events_workspace_account", "investment_events", ["workspace_id", "account_id"])

    op.create_table(
        "ledger_snapshots",
        sa.Column("workspace_id", sa.String(64), primary_key=True),
        sa.Column("payload", sa.JSON(), nullable=False),
        sa.Column("version", sa.Integer(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
    )

    op.create_table(
        "record_revisions",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("cash_transaction_id", sa.String(36), nullable=True),
        sa.Column("investment_event_id", sa.String(36), nullable=True),
        sa.Column("before", sa.JSON(), nullable=False),
        sa.Column("after", sa.JSON(), nullable=False),
        sa.Column("actor_type", sa.String(64), nullable=False),
        sa.Column("reason", sa.Text(), nullable=False),
        *_timestamps(),
        sa.CheckConstraint(
            "(cash_transaction_id IS NOT NULL AND investment_event_id IS NULL) OR "
            "(cash_transaction_id IS NULL AND investment_event_id IS NOT NULL)",
            name="ck_record_revisions_exactly_one_target",
        ),
        sa.ForeignKeyConstraint(["workspace_id"], ["workspaces.id"], ondelete="CASCADE"),
        sa.ForeignKeyConstraint(
            ["workspace_id", "cash_transaction_id"],
            ["cash_transactions.workspace_id", "cash_transactions.id"],
            ondelete="CASCADE", name="fk_record_revisions_workspace_cash",
        ),
        sa.ForeignKeyConstraint(
            ["workspace_id", "investment_event_id"],
            ["investment_events.workspace_id", "investment_events.id"],
            ondelete="CASCADE", name="fk_record_revisions_workspace_investment",
        ),
    )
    op.create_index("ix_record_revisions_workspace_cash", "record_revisions", ["workspace_id", "cash_transaction_id", "created_at"])
    op.create_index("ix_record_revisions_workspace_investment", "record_revisions", ["workspace_id", "investment_event_id", "created_at"])


def downgrade() -> None:
    op.drop_table("record_revisions")
    op.drop_table("ledger_snapshots")
    op.drop_table("investment_events")
    op.drop_table("cash_transactions")
    op.drop_table("raw_records")
    op.drop_table("raw_files")
    op.drop_table("import_batches")
    op.drop_table("accounts")
    op.drop_table("workspaces")

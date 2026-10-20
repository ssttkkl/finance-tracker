"""Add user-scoped mutation receipts without changing legacy import results."""
from alembic import op
import sqlalchemy as sa


revision = "20261021_40"
down_revision = "20261021_39"
branch_labels = None
depends_on = None
PK = sa.BigInteger().with_variant(sa.Integer(), "sqlite")


def upgrade():
    op.create_table("cash_mutation_receipts",
        sa.Column("id", sa.String(36), primary_key=True),
        sa.Column("workspace_id", sa.String(64), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
        sa.Column("user_id", sa.String(128), nullable=False),
        sa.Column("idempotency_key", sa.String(255), nullable=False),
        sa.Column("request_digest", sa.String(64), nullable=False),
        sa.Column("operation_kind", sa.String(32), nullable=False),
        sa.Column("plan_id", sa.String(128)),
        sa.Column("session_digest", sa.String(64)),
        sa.Column("edit_context_digest", sa.String(64)),
        sa.Column("result_summary", sa.JSON(), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("workspace_id", "id", name="uq_cash_mutation_receipt_workspace_id"),
        sa.UniqueConstraint("workspace_id", "user_id", "idempotency_key", name="uq_cash_mutation_receipt_user_key"),
        sa.CheckConstraint("length(idempotency_key) > 0 AND length(request_digest) = 64", name="ck_cash_mutation_receipt_request"),
        sa.CheckConstraint("session_digest IS NULL OR length(session_digest) = 64", name="ck_cash_mutation_receipt_session"),
        sa.CheckConstraint("edit_context_digest IS NULL OR length(edit_context_digest) = 64", name="ck_cash_mutation_receipt_edit_context"),
        sa.CheckConstraint("session_digest IS NULL OR edit_context_digest IS NULL", name="ck_cash_mutation_receipt_one_context"),
    )
    for column, name in (("session_digest", "session"), ("edit_context_digest", "edit_context")):
        op.create_index(f"uq_cash_mutation_receipt_{name}", "cash_mutation_receipts", ["workspace_id", "user_id", column],
            unique=True, sqlite_where=sa.text(f"{column} IS NOT NULL"), postgresql_where=sa.text(f"{column} IS NOT NULL"))
    op.create_table("cash_mutation_results",
        sa.Column("id", PK, primary_key=True, autoincrement=True),
        sa.Column("workspace_id", sa.String(64), nullable=False),
        sa.Column("receipt_id", sa.String(36), nullable=False),
        sa.Column("result_kind", sa.String(16), nullable=False),
        sa.Column("item_ref", sa.String(255), nullable=False),
        sa.Column("outcome", sa.String(32), nullable=False),
        sa.Column("code", sa.String(64)),
        sa.Column("operation_ref", sa.String(128)),
        sa.Column("transaction_id", sa.String(128)),
        sa.Column("relation_id", sa.String(128)),
        sa.Column("account_id", sa.String(128)),
        sa.ForeignKeyConstraint(["workspace_id", "receipt_id"], ["cash_mutation_receipts.workspace_id", "cash_mutation_receipts.id"],
            ondelete="CASCADE", name="fk_cash_mutation_result_receipt"),
        sa.UniqueConstraint("workspace_id", "receipt_id", "result_kind", "item_ref", name="uq_cash_mutation_result_item"),
        sa.CheckConstraint("result_kind IN ('row', 'relation', 'file', 'account')", name="ck_cash_mutation_result_kind"),
    )
    op.create_index("ix_cash_mutation_result_page", "cash_mutation_results", ["workspace_id", "receipt_id", "result_kind", "id"])
    op.create_index("ix_cash_mutation_result_all_page", "cash_mutation_results", ["workspace_id", "receipt_id", "id"])


def downgrade():
    bind = op.get_bind()
    if bind.execute(sa.text("SELECT 1 FROM cash_mutation_receipts LIMIT 1")).first():
        raise ValueError("mutation receipts exist; refusing a lossy schema downgrade")
    op.drop_table("cash_mutation_results")
    op.drop_table("cash_mutation_receipts")

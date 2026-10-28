"""Allow unknown draft projection values without weakening posted finances."""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "20261021_43"
down_revision = "20261021_42"
branch_labels = None
depends_on = None

CHECKS = {
    "ck_cash_projections_state": "state IN ('draft', 'posted')",
    "ck_cash_projections_statistics": "statistics_eligible = (state = 'posted')",
    "ck_cash_projections_attention": "attention_count >= 0 AND attention_count <= member_count",
    "ck_cash_projections_posted_complete": "state <> 'posted' OR (economic_type IS NOT NULL AND net_amount IS NOT NULL AND currency IS NOT NULL AND occurred_at IS NOT NULL)",
    "ck_cash_projections_draft_singleton": "state <> 'draft' OR (member_count = 1 AND accepted_relation_count = 0 AND attention_count = 1 AND economic_type IS NULL AND funding_relation_id IS NULL AND NOT has_payment_mirror AND NOT has_refund_offset AND NOT has_transfer_pair)",
    "ck_cash_projections_counts": "member_count >= 1 AND accepted_relation_count >= 0",
}
INDEXES = {
    "ix_cash_projections_current_sort": ["workspace_id", "dataset_id", "sort_at", "projection_id"],
    "ix_cash_projections_state_sort": ["workspace_id", "dataset_id", "state", "sort_at", "projection_id"],
    "ix_cash_projections_attention_sort": ["workspace_id", "dataset_id", "attention_count", "sort_at", "projection_id"],
}


def _financial_columns():
    amount = sa.String(96) if op.get_bind().dialect.name == "sqlite" else sa.Numeric(38, 18)
    return (("economic_type", sa.String(24)), ("net_amount", amount), ("currency", sa.String(3)), ("occurred_at", sa.DateTime(timezone=True)))


def _invalidate_published_shape():
    # Existing rows and references remain intact until an explicit rebuild.
    # Their old shape has no derived attention, so do not advertise it as ready.
    op.execute(sa.text("UPDATE cash_projection_states SET availability = 'uninitialized'"))


def upgrade():
    with op.batch_alter_table("cash_projections") as batch:
        batch.add_column(sa.Column("state", sa.String(16), nullable=False, server_default="posted"))
        batch.add_column(sa.Column("statistics_eligible", sa.Boolean(), nullable=False, server_default=sa.true()))
        batch.add_column(sa.Column("attention_count", sa.Integer(), nullable=False, server_default="0"))
        batch.add_column(sa.Column("sort_at", sa.DateTime(timezone=True), nullable=True))
        for field, column_type in _financial_columns():
            batch.alter_column(field, existing_type=column_type, nullable=True)
    op.execute(sa.text("UPDATE cash_projections SET sort_at = occurred_at"))
    with op.batch_alter_table("cash_projections") as batch:
        batch.alter_column("sort_at", existing_type=sa.DateTime(timezone=True), nullable=False)
        for name, expression in CHECKS.items():
            batch.create_check_constraint(name, expression)
        for name, columns in INDEXES.items():
            batch.create_index(name, columns)
    _invalidate_published_shape()


def downgrade():
    bind = op.get_bind()
    if (bind.execute(sa.text("SELECT 1 FROM cash_projections WHERE state <> 'posted' OR attention_count <> 0 OR NOT statistics_eligible LIMIT 1")).first()
        or bind.execute(sa.text("SELECT 1 FROM cash_transactions WHERE state = 'draft' AND deleted_at IS NULL LIMIT 1")).first()):
        raise ValueError("draft or pending cash projections exist; refusing a lossy schema downgrade")
    with op.batch_alter_table("cash_projections") as batch:
        for name in INDEXES:
            batch.drop_index(name)
        for name in CHECKS:
            batch.drop_constraint(name, type_="check")
        for field, column_type in _financial_columns():
            batch.alter_column(field, existing_type=column_type, nullable=False)
        for field in ("sort_at", "attention_count", "statistics_eligible", "state"):
            batch.drop_column(field)
    _invalidate_published_shape()

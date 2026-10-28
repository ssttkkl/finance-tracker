"""Index current public category usage without changing ledger values."""
from alembic import op

revision = "20261028_51"
down_revision = "20261023_50"
branch_labels = None
depends_on = None


def upgrade():
    op.create_index("ix_cash_entries_workspace_category", "cash_entries", ["workspace_id", "category_id"])


def downgrade():
    op.drop_index("ix_cash_entries_workspace_category", table_name="cash_entries")

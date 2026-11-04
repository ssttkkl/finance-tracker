"""Add a workspace version for authoritative cash fact synchronization."""
from alembic import op
import sqlalchemy as sa

revision = "20261104_54"
down_revision = "20261030_53"
branch_labels = None
depends_on = None


def upgrade():
    # Inline CHECK works with SQLite ADD COLUMN; never rebuild a referenced workspace table.
    op.add_column("workspaces", sa.Column("ledger_seq", sa.BigInteger(),
        sa.CheckConstraint("ledger_seq >= 0", name="ck_workspaces_ledger_seq_nonnegative"),
        nullable=False, server_default=sa.text("0")))


def downgrade():
    op.drop_column("workspaces", "ledger_seq")

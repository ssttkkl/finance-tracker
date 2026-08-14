"""Index session pointers used by workspace deletion."""
from alembic import op


revision = "20260814_31"
down_revision = "20260813_30"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index(
        "ix_user_sessions_active_workspace",
        "user_sessions",
        ["active_workspace_id"],
    )


def downgrade() -> None:
    op.drop_index(
        "ix_user_sessions_active_workspace",
        table_name="user_sessions",
    )

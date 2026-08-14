"""Merge user workspace access and cash category migration branches."""

from alembic import op

revision = "20260813_30"
down_revision = ("20260813_27", "20260813_29")
branch_labels = None
depends_on = None


def upgrade() -> None:
    pass


def downgrade() -> None:
    pass

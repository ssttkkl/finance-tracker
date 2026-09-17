"""Maintain a cheap workspace revision token for wealth source fencing."""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

from ft.adapters.relational.wealth_source_revision import install_source_revision_triggers


revision = "20260917_35"
down_revision = "20260816_34"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "wealth_source_revisions",
        sa.Column(
            "workspace_id",
            sa.String(length=64),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            primary_key=True,
        ),
        sa.Column("revision", sa.BigInteger(), server_default="0", nullable=False),
    )
    bind = op.get_bind()
    bind.execute(sa.text(
        "INSERT INTO wealth_source_revisions (workspace_id, revision) "
        "SELECT id, 0 FROM workspaces"
    ))
    install_source_revision_triggers(bind)


def downgrade() -> None:
    bind = op.get_bind()
    from ft.adapters.relational.wealth_source_revision import drop_source_revision_triggers

    drop_source_revision_triggers(bind)
    op.drop_table("wealth_source_revisions")

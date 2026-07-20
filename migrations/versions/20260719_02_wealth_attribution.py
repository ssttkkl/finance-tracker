"""Add formal wealth-attribution source and read-model tables.

This revision is deliberately additive: existing cash/investment facts remain untouched.
"""
import hashlib

import sqlalchemy as sa
from alembic import op
from ft.adapters.relational.models import Base


revision = "20260719_02"
down_revision = "20260717_01"
branch_labels = None
depends_on = None


_TABLES = (
    "valuation_observations", "account_lifecycle_events", "wealth_source_manifests",
    "wealth_source_manifest_items", "wealth_generations", "wealth_daily_results",
    "wealth_generation_days", "wealth_evidence_manifests", "wealth_components",
    "wealth_active_manifests", "wealth_evidence_items", "wealth_evidence_manifest_items",
    "wealth_coverage_dispositions",
)


def upgrade() -> None:
    bind = op.get_bind()
    for name in _TABLES:
        Base.metadata.tables[name].create(bind, checkfirst=False)
    accounts = sa.table(
        "accounts", sa.column("id"), sa.column("workspace_id"), sa.column("created_at"),
    )
    lifecycle = sa.table(
        "account_lifecycle_events", sa.column("event_id"), sa.column("workspace_id"),
        sa.column("account_id"), sa.column("event_kind"), sa.column("effective_at"),
        sa.column("source_identity"), sa.column("source_revision"), sa.column("reason"),
        sa.column("created_at"),
    )
    rows = bind.execute(sa.select(accounts.c.id, accounts.c.workspace_id, accounts.c.created_at)).all()
    # Deliberately no inferred close: mutable active/updated_at has no historical meaning.
    for account_id, workspace_id, created_at in rows:
        # SQLite's pre-existing timestamp is read as text while PostgreSQL yields
        # a datetime; its persisted canonical text is the cross-dialect identity.
        seed = f"opened:{workspace_id}:{account_id}:{created_at}"
        event_id = hashlib.sha256(seed.encode("utf-8")).hexdigest()
        bind.execute(lifecycle.insert().values(
            event_id=event_id, workspace_id=workspace_id, account_id=account_id,
            event_kind="opened", effective_at=created_at, source_identity=f"migration:{account_id}",
            source_revision=event_id, reason="migration backfill", created_at=created_at,
        ))


def downgrade() -> None:
    bind = op.get_bind()
    for name in reversed(_TABLES):
        Base.metadata.tables[name].drop(bind, checkfirst=False)

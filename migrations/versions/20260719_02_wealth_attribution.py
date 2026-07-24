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


def _historical_tables() -> dict[str, sa.Table]:
    """Return the 002 table shape, not the current runtime-account FK shape.

    Revision 002 predates the 016 bigint cutover.  Its owner/account columns
    must therefore be VARCHAR(36), even though the current ORM models expose
    them as bigint.  Clone the stable wealth tables into private metadata so
    the historical type override cannot mutate runtime metadata.
    """
    metadata = sa.MetaData()
    # The cloned account table resolves composite account FK targets; it is not
    # created here because 001 already created the historical UUID table.
    Base.metadata.tables["workspaces"].to_metadata(metadata)
    Base.metadata.tables["accounts"].to_metadata(metadata)
    for name in _TABLES:
        Base.metadata.tables[name].to_metadata(metadata)
    for table_name, column_name in (
        ("valuation_observations", "owner_account_id"),
        ("account_lifecycle_events", "account_id"),
        ("wealth_coverage_dispositions", "owner_account_id"),
    ):
        metadata.tables[table_name].c[column_name].type = sa.String(36)
    return {name: metadata.tables[name] for name in _TABLES}


def upgrade() -> None:
    bind = op.get_bind()
    tables = _historical_tables()
    for name in _TABLES:
        tables[name].create(bind, checkfirst=False)
    # This historical revision predates multi-currency cash pockets.  Keep its
    # schema faithful so 20260720_04 can migrate real legacy observations.
    with op.batch_alter_table("valuation_observations") as batch:
        batch.drop_constraint("ck_valuation_cash_owner_identity", type_="check")
        batch.create_check_constraint(
            "ck_valuation_cash_owner_identity",
            "identity_kind != 'cash_account' OR identity = owner_account_id",
        )
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
    tables = _historical_tables()
    for name in reversed(_TABLES):
        tables[name].drop(bind, checkfirst=False)

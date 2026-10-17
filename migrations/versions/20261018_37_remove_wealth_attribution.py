"""Remove wealth-attribution-only source snapshots, results, and evidence."""
from alembic import op

from migrations.wealth_source_revision import drop_source_revision_triggers


revision = "20261018_37"
down_revision = "20260920_36"
branch_labels = None
depends_on = None


_TABLES_TO_DROP = (
    "wealth_generation_days",
    "wealth_active_manifests",
    "wealth_components",
    "wealth_evidence_manifest_items",
    "wealth_coverage_dispositions",
    "wealth_evidence_manifests",
    "wealth_evidence_items",
    "wealth_generations",
    "wealth_daily_results",
    "wealth_source_manifest_items",
    "wealth_source_manifests",
    "account_lifecycle_events",
    "valuation_observations",
    "wealth_source_revisions",
)


def upgrade() -> None:
    bind = op.get_bind()
    drop_source_revision_triggers(bind)
    for table_name in _TABLES_TO_DROP:
        op.drop_table(table_name)


def downgrade() -> None:
    raise NotImplementedError(
        "wealth-attribution retirement deletes source snapshots and results; restore from backup"
    )

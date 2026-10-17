"""Frozen ORM table definitions needed only to replay legacy migrations 002–016."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from sqlalchemy import (
    CheckConstraint, Column, DateTime, ForeignKey,
    ForeignKeyConstraint, Index, Integer, Numeric, String, Table,
    Text, UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


# Frozen revision-002 database types.  Keep these independent of the current
# application ORM so replaying old revisions does not import runtime models.
def ExactDecimal():
    return Numeric(38, 18).with_variant(String(96), "sqlite")


def UTCDateTime():
    return DateTime(timezone=True)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class MigrationBase(DeclarativeBase):
    pass


Table(
    "workspaces",
    MigrationBase.metadata,
    Column("id", String(64), primary_key=True),
    Column("name", String(255), nullable=False),
    Column("created_at", UTCDateTime(), nullable=False),
)
Table(
    "accounts",
    MigrationBase.metadata,
    Column("id", String(36), primary_key=True),
    Column("workspace_id", String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False),
    UniqueConstraint("workspace_id", "id", name="uq_accounts_workspace_id"),
)


class ValuationObservationModel(MigrationBase):
    __tablename__ = "valuation_observations"
    __table_args__ = (
        UniqueConstraint("workspace_id", "observation_id", "source_revision", name="uq_valuation_revision"),
        ForeignKeyConstraint(
            ["workspace_id", "owner_account_id"], ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT", name="fk_valuation_workspace_owner_account",
        ),
        CheckConstraint(
            "(identity_kind IN ('cash_account', 'position') AND owner_account_id IS NOT NULL) OR "
            "(identity_kind IN ('instrument_quote', 'currency_pair', 'fx') AND owner_account_id IS NULL)",
            name="ck_valuation_owner_kind",
        ),
        CheckConstraint(
            # Multi-currency cash identity is "{account_id}:{currency}".
            "identity_kind != 'cash_account' OR ("
            "owner_account_id IS NOT NULL AND "
            "identity LIKE owner_account_id || ':%'"
            ")",
            name="ck_valuation_cash_owner_identity",
        ),
        Index("ix_valuation_workspace_identity_asof", "workspace_id", "identity", "as_of"),
    )
    observation_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    identity_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    identity: Mapped[str] = mapped_column(String(255), nullable=False)
    owner_account_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    observation_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    value: Mapped[Decimal] = mapped_column(ExactDecimal(), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    unit: Mapped[str] = mapped_column(String(32), nullable=False)
    as_of: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    observed_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    source_identity: Mapped[str] = mapped_column(String(255), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    trust: Mapped[str] = mapped_column(String(32), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class AccountLifecycleEventModel(MigrationBase):
    __tablename__ = "account_lifecycle_events"
    __table_args__ = (
        UniqueConstraint("workspace_id", "event_id", name="uq_lifecycle_workspace_event"),
        ForeignKeyConstraint(["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_lifecycle_workspace_account"),
        Index("ix_lifecycle_workspace_account_effective", "workspace_id", "account_id", "effective_at"),
    )
    event_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    account_id: Mapped[str] = mapped_column(String(36), nullable=False)
    event_kind: Mapped[str] = mapped_column(String(16), nullable=False)
    effective_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    source_identity: Mapped[str] = mapped_column(String(255), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    reason: Mapped[str] = mapped_column(Text, default="", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WealthSourceManifestModel(MigrationBase):
    __tablename__ = "wealth_source_manifests"
    __table_args__ = (UniqueConstraint("workspace_id", "manifest_id", name="uq_source_manifest_workspace_id"),)
    manifest_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    source_watermark: Mapped[str] = mapped_column(String(128), nullable=False)
    canonical_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WealthSourceManifestItemModel(MigrationBase):
    __tablename__ = "wealth_source_manifest_items"
    __table_args__ = (
        UniqueConstraint("workspace_id", "manifest_id", "item_identity", "revision", name="uq_manifest_item"),
        ForeignKeyConstraint(["workspace_id", "manifest_id"], ["wealth_source_manifests.workspace_id", "wealth_source_manifests.manifest_id"], ondelete="CASCADE", name="fk_manifest_item_workspace_manifest"),
    )
    id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    manifest_id: Mapped[str] = mapped_column(String(128), ForeignKey("wealth_source_manifests.manifest_id", ondelete="CASCADE"), nullable=False)
    item_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    item_identity: Mapped[str] = mapped_column(String(255), nullable=False)
    revision: Mapped[str] = mapped_column(String(128), nullable=False)
    content_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    evidence_occurred_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    evidence_kind: Mapped[str | None] = mapped_column(String(64), nullable=True)
    evidence_contribution: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    evidence_scope_fold_identity: Mapped[str | None] = mapped_column(String(255), nullable=True)
    evidence_safe_metadata: Mapped[str] = mapped_column(Text, default="{}", nullable=False)


class WealthGenerationModel(MigrationBase):
    __tablename__ = "wealth_generations"
    __table_args__ = (
        UniqueConstraint("workspace_id", "build_revision", name="uq_generation_workspace_build"),
        ForeignKeyConstraint(["workspace_id", "source_manifest_id"], ["wealth_source_manifests.workspace_id", "wealth_source_manifests.manifest_id"], ondelete="RESTRICT", name="fk_generation_workspace_source_manifest"),
    )
    build_revision: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    source_watermark: Mapped[str] = mapped_column(String(128), nullable=False)
    source_manifest_id: Mapped[str] = mapped_column(String(128), nullable=False)
    calculation_version: Mapped[str] = mapped_column(String(64), nullable=False)
    valuation_policy_version: Mapped[str] = mapped_column(String(64), nullable=False)
    date_from: Mapped[str] = mapped_column(String(10), nullable=False)
    date_to: Mapped[str] = mapped_column(String(10), nullable=False)
    expected_active_revision: Mapped[str | None] = mapped_column(String(128), nullable=True)
    state: Mapped[str] = mapped_column(String(16), nullable=False)
    canonical_manifest_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)


class WealthDailyResultModel(MigrationBase):
    __tablename__ = "wealth_daily_results"
    __table_args__ = (UniqueConstraint("workspace_id", "result_digest", name="uq_daily_result_workspace_digest"),)
    result_digest: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    local_date: Mapped[str] = mapped_column(String(10), nullable=False)
    calculation_version: Mapped[str] = mapped_column(String(64), nullable=False)
    valuation_policy_version: Mapped[str] = mapped_column(String(64), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    result_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    canonical_payload: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WealthGenerationDayModel(MigrationBase):
    __tablename__ = "wealth_generation_days"
    __table_args__ = (
        UniqueConstraint("workspace_id", "build_revision", "local_date", name="uq_generation_day"),
        ForeignKeyConstraint(["workspace_id", "build_revision"], ["wealth_generations.workspace_id", "wealth_generations.build_revision"], ondelete="CASCADE", name="fk_generation_day_workspace_generation"),
        ForeignKeyConstraint(["workspace_id", "result_digest"], ["wealth_daily_results.workspace_id", "wealth_daily_results.result_digest"], ondelete="RESTRICT", name="fk_generation_day_workspace_result"),
    )
    id: Mapped[str] = mapped_column(String(160), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    build_revision: Mapped[str] = mapped_column(String(128), ForeignKey("wealth_generations.build_revision", ondelete="CASCADE"), nullable=False)
    local_date: Mapped[str] = mapped_column(String(10), nullable=False)
    result_digest: Mapped[str | None] = mapped_column(String(128), ForeignKey("wealth_daily_results.result_digest"), nullable=True)
    missing_reason: Mapped[str | None] = mapped_column(String(64), nullable=True)


class WealthActiveManifestModel(MigrationBase):
    __tablename__ = "wealth_active_manifests"
    __table_args__ = (
        ForeignKeyConstraint(
            ["workspace_id", "build_revision"],
            ["wealth_generations.workspace_id", "wealth_generations.build_revision"],
            ondelete="RESTRICT",
            name="fk_active_manifest_workspace_generation",
        ),
    )
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True)
    build_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    manifest_revision: Mapped[int] = mapped_column(Integer, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WealthComponentModel(MigrationBase):
    __tablename__ = "wealth_components"
    __table_args__ = (
        UniqueConstraint("workspace_id", "component_id", name="uq_component_workspace_id"),
        ForeignKeyConstraint(
            ["workspace_id", "evidence_manifest_id"],
            ["wealth_evidence_manifests.workspace_id", "wealth_evidence_manifests.manifest_id"],
            ondelete="RESTRICT",
            name="fk_component_workspace_evidence_manifest",
        ),
    )
    component_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    component_key: Mapped[str] = mapped_column(String(128), nullable=False)
    result_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    kind: Mapped[str] = mapped_column(String(64), nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False)
    amount: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    evidence_manifest_id: Mapped[str] = mapped_column(String(128), nullable=False)
    canonical_payload: Mapped[str] = mapped_column(Text, nullable=False)


class WealthEvidenceManifestModel(MigrationBase):
    __tablename__ = "wealth_evidence_manifests"
    __table_args__ = (
        UniqueConstraint("workspace_id", "manifest_id", name="uq_evidence_manifest_workspace_id"),
        ForeignKeyConstraint(["workspace_id", "source_manifest_id"], ["wealth_source_manifests.workspace_id", "wealth_source_manifests.manifest_id"], ondelete="RESTRICT", name="fk_evidence_manifest_workspace_source_manifest"),
    )
    manifest_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    result_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    ordering_version: Mapped[str] = mapped_column(String(32), nullable=False)
    canonical_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    source_manifest_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    selection_payload: Mapped[str] = mapped_column(Text, default="{}", nullable=False)


class WealthEvidenceItemModel(MigrationBase):
    __tablename__ = "wealth_evidence_items"
    __table_args__ = (UniqueConstraint("workspace_id", "evidence_identity", name="uq_evidence_item_workspace_id"),)
    evidence_identity: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    source_identity: Mapped[str] = mapped_column(String(255), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    evidence_kind: Mapped[str] = mapped_column(String(64), nullable=False)
    contribution: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    safe_metadata: Mapped[str] = mapped_column(Text, default="{}", nullable=False)


class WealthEvidenceManifestItemModel(MigrationBase):
    __tablename__ = "wealth_evidence_manifest_items"
    __table_args__ = (
        UniqueConstraint("workspace_id", "manifest_id", "scope_fold_identity", name="uq_evidence_fold"),
        ForeignKeyConstraint(["workspace_id", "manifest_id"], ["wealth_evidence_manifests.workspace_id", "wealth_evidence_manifests.manifest_id"], ondelete="CASCADE", name="fk_evidence_link_workspace_manifest"),
        ForeignKeyConstraint(["workspace_id", "evidence_identity"], ["wealth_evidence_items.workspace_id", "wealth_evidence_items.evidence_identity"], ondelete="RESTRICT", name="fk_evidence_link_workspace_item"),
    )
    id: Mapped[str] = mapped_column(String(160), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    manifest_id: Mapped[str] = mapped_column(String(128), ForeignKey("wealth_evidence_manifests.manifest_id", ondelete="CASCADE"), nullable=False)
    evidence_identity: Mapped[str] = mapped_column(String(128), ForeignKey("wealth_evidence_items.evidence_identity"), nullable=False)
    scope_fold_identity: Mapped[str] = mapped_column(String(255), nullable=False)
    contribution: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)


class WealthCoverageDispositionModel(MigrationBase):
    __tablename__ = "wealth_coverage_dispositions"
    __table_args__ = (
        UniqueConstraint("workspace_id", "result_digest", "owner_account_id", "identity_kind", "identity", name="uq_coverage_result_owned_identity"),
        ForeignKeyConstraint(["workspace_id", "result_digest"], ["wealth_daily_results.workspace_id", "wealth_daily_results.result_digest"], ondelete="CASCADE", name="fk_coverage_workspace_result"),
        ForeignKeyConstraint(["workspace_id", "owner_account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_coverage_workspace_owner"),
    )
    id: Mapped[str] = mapped_column(String(160), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    result_digest: Mapped[str] = mapped_column(String(128), ForeignKey("wealth_daily_results.result_digest", ondelete="CASCADE"), nullable=False)
    local_date: Mapped[str] = mapped_column(String(10), nullable=False)
    source_revision: Mapped[str] = mapped_column(String(128), nullable=False)
    owner_account_id: Mapped[str] = mapped_column(String(36), nullable=False)
    identity_kind: Mapped[str] = mapped_column(String(32), nullable=False)
    identity: Mapped[str] = mapped_column(String(255), nullable=False)
    disposition: Mapped[str] = mapped_column(String(32), nullable=False)


_TABLES = (
    "valuation_observations", "account_lifecycle_events", "wealth_source_manifests",
    "wealth_source_manifest_items", "wealth_generations", "wealth_daily_results",
    "wealth_generation_days", "wealth_evidence_manifests", "wealth_components",
    "wealth_active_manifests", "wealth_evidence_items", "wealth_evidence_manifest_items",
    "wealth_coverage_dispositions",
)


def historical_wealth_tables():
    """Return revision 002 tables with their original UUID account keys."""
    return {name: MigrationBase.metadata.tables[name] for name in _TABLES}

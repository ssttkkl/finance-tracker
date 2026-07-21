"""Shared PostgreSQL and SQLite SQLAlchemy persistence models."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator
from ft.domain.decimal import exact_decimal as _domain_exact_decimal


def _uuid() -> str:
    return str(uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


def exact_decimal(value) -> Decimal:
    """Return a finite Decimal that PostgreSQL NUMERIC(38,18) will not round."""
    return _domain_exact_decimal(value)


class Base(DeclarativeBase):
    pass


class ExactDecimal(TypeDecorator):
    """Use NUMERIC on PostgreSQL and lossless text in SQLite contract tests."""

    impl = String
    cache_ok = True

    def load_dialect_impl(self, dialect):
        if dialect.name == "postgresql":
            return dialect.type_descriptor(Numeric(38, 18))
        return dialect.type_descriptor(String(96))

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        decimal = exact_decimal(value)
        return decimal if dialect.name == "postgresql" else format(decimal, "f")

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        decimal = Decimal(str(value))
        if dialect.name != "postgresql":
            return decimal
        # PostgreSQL NUMERIC(38,18) pads trailing zeros on read.  Strip only that
        # representation padding so domain multiplications keep the same exact
        # bounds as SQLite text storage, which never invents scale-18 zeros.
        return Decimal("0") if decimal == 0 else decimal.normalize()


class UTCDateTime(TypeDecorator):
    """Store UTC timestamps and restore tzinfo in SQLite contract tests."""

    impl = DateTime
    cache_ok = True
    timezone = True

    def load_dialect_impl(self, dialect):
        return dialect.type_descriptor(DateTime(timezone=True))

    def process_bind_param(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            raise ValueError("timestamp must be timezone-aware")
        return value.astimezone(timezone.utc)

    def process_result_value(self, value, dialect):
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=timezone.utc)
        return value.astimezone(timezone.utc)


class WorkspaceModel(Base):
    __tablename__ = "workspaces"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class AccountModel(Base):
    __tablename__ = "accounts"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_accounts_workspace_id"),
        UniqueConstraint("workspace_id", "name", name="uq_accounts_workspace_name"),
        Index("ix_accounts_workspace", "workspace_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    type: Mapped[str] = mapped_column(String(32), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class ImportBatchModel(Base):
    __tablename__ = "import_batches"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_import_batches_workspace_id"),
        ForeignKeyConstraint(
            ["workspace_id", "target_account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT",
            name="fk_import_batches_workspace_target_account",
        ),
        UniqueConstraint(
            "workspace_id", "source_kind", "source_digest",
            name="uq_import_batches_workspace_kind_digest",
        ),
        Index("ix_import_batches_workspace", "workspace_id"),
        Index("ix_import_batches_workspace_target", "workspace_id", "target_account_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    target_account_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    source_kind: Mapped[str] = mapped_column(String(64), nullable=False)
    source_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    source_ref: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(32), default="pending", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    completed_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)


class RawFileModel(Base):
    __tablename__ = "raw_files"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_raw_files_workspace_id"),
        UniqueConstraint(
            "workspace_id", "batch_id", "id", name="uq_raw_files_workspace_batch_id",
        ),
        UniqueConstraint(
            "workspace_id", "batch_id", "content_digest",
            name="uq_raw_files_workspace_batch_digest",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "batch_id"],
            ["import_batches.workspace_id", "import_batches.id"],
            ondelete="CASCADE",
            name="fk_raw_files_workspace_batch",
        ),
        Index("ix_raw_files_workspace_batch", "workspace_id", "batch_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    batch_id: Mapped[str] = mapped_column(String(36), nullable=False)
    source_path: Mapped[str] = mapped_column(Text, nullable=False)
    content_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    size_bytes: Mapped[int] = mapped_column(Integer, nullable=False)
    media_type: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class RawRecordModel(Base):
    __tablename__ = "raw_records"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_raw_records_workspace_id"),
        UniqueConstraint(
            "workspace_id", "source_type", "source_identity",
            name="uq_raw_records_workspace_source_identity",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "batch_id"],
            ["import_batches.workspace_id", "import_batches.id"],
            ondelete="CASCADE",
            name="fk_raw_records_workspace_batch",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "batch_id", "raw_file_id"],
            ["raw_files.workspace_id", "raw_files.batch_id", "raw_files.id"],
            ondelete="RESTRICT",
            name="fk_raw_records_workspace_batch_file",
        ),
        Index("ix_raw_records_workspace_batch", "workspace_id", "batch_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    batch_id: Mapped[str] = mapped_column(String(36), nullable=False)
    raw_file_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    source_type: Mapped[str] = mapped_column(String(64), nullable=False)
    source_identity: Mapped[str] = mapped_column(String(512), nullable=False)
    source_line: Mapped[int | None] = mapped_column(Integer, nullable=True)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class CashTransactionModel(Base):
    __tablename__ = "cash_transactions"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_cash_transactions_workspace_id"),
        UniqueConstraint(
            "workspace_id", "raw_record_id", name="uq_cash_transactions_workspace_raw_record",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT",
            name="fk_cash_transactions_workspace_account",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "raw_record_id"],
            ["raw_records.workspace_id", "raw_records.id"],
            ondelete="RESTRICT",
            name="fk_cash_transactions_workspace_raw_record",
        ),
        Index("ix_cash_transactions_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_cash_transactions_workspace_account", "workspace_id", "account_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    account_id: Mapped[str] = mapped_column(String(36), nullable=False)
    raw_record_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    record_id: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    amount: Mapped[Decimal] = mapped_column(ExactDecimal(), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    counterparty: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    description: Mapped[str] = mapped_column(Text, default="", nullable=False)
    category: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    source: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    bill_source: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    transfer_account: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    locked: Mapped[str] = mapped_column(String(32), default="", nullable=False)
    offset_group: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    offset_role: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    offset_strength: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    offset_source: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    offset_rule_hint: Mapped[str] = mapped_column(Text, default="", nullable=False)
    offset_match_type: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    proposed_action: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    revision: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    deleted_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    delete_reason: Mapped[str] = mapped_column(Text, default="", nullable=False)


class InvestmentEventModel(Base):
    __tablename__ = "investment_events"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_investment_events_workspace_id"),
        UniqueConstraint(
            "workspace_id", "raw_record_id", name="uq_investment_events_workspace_raw_record",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT",
            name="fk_investment_events_workspace_account",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "raw_record_id"],
            ["raw_records.workspace_id", "raw_records.id"],
            ondelete="RESTRICT",
            name="fk_investment_events_workspace_raw_record",
        ),
        Index("ix_investment_events_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_investment_events_workspace_account", "workspace_id", "account_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    account_id: Mapped[str] = mapped_column(String(36), nullable=False)
    raw_record_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    kind: Mapped[str] = mapped_column(String(64), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="", nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    revision: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class LedgerSnapshotModel(Base):
    __tablename__ = "ledger_snapshots"

    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True
    )
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class RecordRevisionModel(Base):
    __tablename__ = "record_revisions"
    __table_args__ = (
        CheckConstraint(
            "(cash_transaction_id IS NOT NULL AND investment_event_id IS NULL) OR "
            "(cash_transaction_id IS NULL AND investment_event_id IS NOT NULL)",
            name="ck_record_revisions_exactly_one_target",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "cash_transaction_id"],
            ["cash_transactions.workspace_id", "cash_transactions.id"],
            ondelete="CASCADE",
            name="fk_record_revisions_workspace_cash",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "investment_event_id"],
            ["investment_events.workspace_id", "investment_events.id"],
            ondelete="CASCADE",
            name="fk_record_revisions_workspace_investment",
        ),
        Index("ix_record_revisions_workspace_cash", "workspace_id", "cash_transaction_id", "created_at"),
        Index("ix_record_revisions_workspace_investment", "workspace_id", "investment_event_id", "created_at"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    cash_transaction_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    investment_event_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    before: Mapped[dict] = mapped_column(JSON, nullable=False)
    after: Mapped[dict] = mapped_column(JSON, nullable=False)
    actor_type: Mapped[str] = mapped_column(String(64), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


# Wealth read-model rows deliberately use deterministic, caller-supplied identities.  The
# payload values are canonical text so neither dialect owns financial serialization.
class ValuationObservationModel(Base):
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
    raw_record_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    trust: Mapped[str] = mapped_column(String(32), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class AccountLifecycleEventModel(Base):
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


class WealthSourceManifestModel(Base):
    __tablename__ = "wealth_source_manifests"
    __table_args__ = (UniqueConstraint("workspace_id", "manifest_id", name="uq_source_manifest_workspace_id"),)
    manifest_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    source_watermark: Mapped[str] = mapped_column(String(128), nullable=False)
    canonical_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WealthSourceManifestItemModel(Base):
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


class WealthGenerationModel(Base):
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


class WealthDailyResultModel(Base):
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


class WealthGenerationDayModel(Base):
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


class WealthActiveManifestModel(Base):
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


class WealthComponentModel(Base):
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


class WealthEvidenceManifestModel(Base):
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


class WealthEvidenceItemModel(Base):
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


class WealthEvidenceManifestItemModel(Base):
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


class WealthCoverageDispositionModel(Base):
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



class TransactionRelationModel(Base):
    __tablename__ = "transaction_relations"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_transaction_relations_workspace_id"),
        UniqueConstraint(
            "workspace_id",
            "kind",
            "ordered_fact_a",
            "ordered_fact_b",
            "subtype",
            "active_slot",
            name="uq_transaction_relations_active_business_key",
        ),
        Index("ix_transaction_relations_workspace_status", "workspace_id", "status"),
        Index("ix_transaction_relations_workspace_kind", "workspace_id", "kind"),
        Index("ix_transaction_relations_primary", "workspace_id", "primary_fact_id"),
        Index("ix_transaction_relations_secondary", "workspace_id", "secondary_fact_id"),
        CheckConstraint(
            "kind IN ('payment_mirror','transfer_pair','refund_offset')",
            name="ck_transaction_relations_kind",
        ),
        CheckConstraint(
            "status IN ('pending_review','accepted','rejected','superseded')",
            name="ck_transaction_relations_status",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    subtype: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    primary_fact_id: Mapped[str] = mapped_column(String(36), nullable=False)
    secondary_fact_id: Mapped[str] = mapped_column(String(36), nullable=False)
    primary_fact_type: Mapped[str] = mapped_column(String(32), default="cash", nullable=False)
    secondary_fact_type: Mapped[str] = mapped_column(String(32), default="cash", nullable=False)
    ordered_fact_a: Mapped[str] = mapped_column(String(36), nullable=False)
    ordered_fact_b: Mapped[str] = mapped_column(String(36), nullable=False)
    # active_slot is 1 for non-superseded rows; superseded rows use id hash slot to free the key.
    active_slot: Mapped[str] = mapped_column(String(36), default="active", nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    rule_id: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    confidence: Mapped[str] = mapped_column(String(32), default="", nullable=False)
    evidence_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_by: Mapped[str] = mapped_column(String(128), default="system", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    decided_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    decided_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    decision_reason: Mapped[str] = mapped_column(Text, default="", nullable=False)
    later_marker: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    superseded_by_id: Mapped[str | None] = mapped_column(String(36), nullable=True)
    revision: Mapped[int] = mapped_column(Integer, default=1, nullable=False)


class RelationCheckRunModel(Base):
    __tablename__ = "relation_check_runs"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_relation_check_runs_workspace_id"),
        Index("ix_relation_check_runs_workspace_status", "workspace_id", "status"),
        CheckConstraint(
            "status IN ('pending','running','completed','failed')",
            name="ck_relation_check_runs_status",
        ),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    trigger: Mapped[str] = mapped_column(String(64), nullable=False)
    seed_ref: Mapped[str] = mapped_column(Text, default="", nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    started_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    error: Mapped[str] = mapped_column(Text, default="", nullable=False)
    stats_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)


class AccountAliasModel(Base):
    __tablename__ = "account_aliases"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_account_aliases_workspace_id"),
        UniqueConstraint(
            "workspace_id", "alias_type", "alias_value", "account_id",
            name="uq_account_aliases_value_account",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_account_aliases_workspace_account",
        ),
        Index("ix_account_aliases_workspace_value", "workspace_id", "alias_type", "alias_value"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    alias_type: Mapped[str] = mapped_column(String(32), nullable=False)
    alias_value: Mapped[str] = mapped_column(String(255), nullable=False)
    account_id: Mapped[str] = mapped_column(String(36), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class FactDeletionEventModel(Base):
    __tablename__ = "fact_deletion_events"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_fact_deletion_events_workspace_id"),
        Index("ix_fact_deletion_events_fact", "workspace_id", "fact_type", "fact_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    fact_id: Mapped[str] = mapped_column(String(36), nullable=False)
    fact_type: Mapped[str] = mapped_column(String(32), default="cash", nullable=False)
    actor: Mapped[str] = mapped_column(String(128), nullable=False)
    reason: Mapped[str] = mapped_column(Text, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)

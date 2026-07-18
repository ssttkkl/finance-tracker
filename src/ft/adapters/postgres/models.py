"""PostgreSQL-only SQLAlchemy persistence models."""
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
        return None if value is None else Decimal(str(value))


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
        UniqueConstraint("workspace_id", "name", "currency", name="uq_accounts_workspace_name_currency"),
        Index("ix_accounts_workspace", "workspace_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    type: Mapped[str] = mapped_column(String(32), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
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
    target_account_id: Mapped[str] = mapped_column(String(36), nullable=False)
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

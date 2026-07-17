"""SQLAlchemy persistence model for the PostgreSQL storage adapter."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import (
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    Numeric,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.types import TypeDecorator
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def _uuid() -> str:
    return str(uuid4())


def _now() -> datetime:
    return datetime.now(timezone.utc)


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
        decimal = Decimal(str(value))
        return decimal if dialect.name == "postgresql" else format(decimal, "f")

    def process_result_value(self, value, dialect):
        return None if value is None else Decimal(str(value))


class WorkspaceModel(Base):
    __tablename__ = "workspaces"

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, nullable=False)


class AccountModel(Base):
    __tablename__ = "accounts"
    __table_args__ = (
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
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, onupdate=_now, nullable=False)


class CashTransactionModel(Base):
    __tablename__ = "cash_transactions"
    __table_args__ = (
        Index("ix_cash_transactions_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_cash_transactions_workspace_account", "workspace_id", "account_name"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    record_id: Mapped[str] = mapped_column(String(255), default="", nullable=False)
    occurred_at: Mapped[str] = mapped_column(String(32), nullable=False)
    amount: Mapped[Decimal] = mapped_column(ExactDecimal(), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    counterparty: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    description: Mapped[str] = mapped_column(Text, default="", nullable=False)
    category: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    account_name: Mapped[str] = mapped_column(String(255), nullable=False)
    account_type: Mapped[str] = mapped_column(String(32), nullable=False)
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
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, nullable=False)


class InvestmentEventModel(Base):
    __tablename__ = "investment_events"
    __table_args__ = (
        Index("ix_investment_events_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_investment_events_workspace_account", "workspace_id", "account_name"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    occurred_at: Mapped[str] = mapped_column(String(32), nullable=False)
    kind: Mapped[str] = mapped_column(String(64), nullable=False)
    account_name: Mapped[str] = mapped_column(String(255), nullable=False)
    account_type: Mapped[str] = mapped_column(String(32), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="", nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    revision: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, nullable=False)


class LedgerSnapshotModel(Base):
    __tablename__ = "ledger_snapshots"

    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True
    )
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=_now, onupdate=_now, nullable=False)

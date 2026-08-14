"""Shared PostgreSQL and SQLite SQLAlchemy persistence models."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

from sqlalchemy import (
    BigInteger,
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
    text,
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


# PG BIGINT + SQLite INTEGER AUTOINCREMENT affinity for surrogate PKs/FKs (016).
SurrogatePK = BigInteger().with_variant(Integer, "sqlite")




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


class UserModel(Base):
    __tablename__ = "users"

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    email: Mapped[str] = mapped_column(String(320), unique=True, nullable=False)
    password_hash: Mapped[str] = mapped_column(String(512), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WorkspaceMembershipModel(Base):
    __tablename__ = "workspace_memberships"
    __table_args__ = (
        CheckConstraint("role IN ('admin', 'editor', 'viewer')", name="ck_workspace_memberships_role"),
        Index("ix_workspace_memberships_user", "user_id"),
    )

    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True
    )
    user_id: Mapped[str] = mapped_column(
        String(36), ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class UserSessionModel(Base):
    __tablename__ = "user_sessions"
    __table_args__ = (
        Index("ix_user_sessions_user", "user_id"),
        Index("ix_user_sessions_active_workspace", "active_workspace_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    token_digest: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    active_workspace_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class WorkspaceInvitationModel(Base):
    __tablename__ = "workspace_invitations"
    __table_args__ = (
        CheckConstraint("role IN ('editor', 'viewer')", name="ck_workspace_invitations_role"),
        Index("ix_workspace_invitations_workspace", "workspace_id"),
    )

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=_uuid)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    role: Mapped[str] = mapped_column(String(16), nullable=False)
    token_digest: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    accepted_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    created_by_user_id: Mapped[str] = mapped_column(String(36), ForeignKey("users.id", ondelete="RESTRICT"), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class AccountModel(Base):
    __tablename__ = "accounts"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_accounts_workspace_id"),
        UniqueConstraint("workspace_id", "name", name="uq_accounts_workspace_name"),
        Index("ix_accounts_workspace", "workspace_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    type: Mapped[str] = mapped_column(String(32), nullable=False)
    active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    # Account-supported currencies are database configuration, not a user-facing
    # setting in the cash ledger.  Keep the collection normalized at write
    # boundaries and expose it as read-only data to the transaction form.
    currencies: Mapped[list[str]] = mapped_column(JSON, default=list, nullable=False)
    metadata_json: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)





class StatementAccountMappingModel(Base):
    __tablename__ = "statement_account_mappings"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_statement_account_mappings_workspace_id"),
        UniqueConstraint(
            "workspace_id", "source_type", "identity_kind", "source_account_key",
            name="uq_statement_account_mappings_source_identity",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_statement_account_mappings_workspace_account",
        ),
        Index("ix_statement_account_mappings_workspace", "workspace_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False,
    )
    source_type: Mapped[str] = mapped_column(String(64), nullable=False)
    identity_kind: Mapped[str] = mapped_column(String(64), nullable=False)
    source_account_key: Mapped[str] = mapped_column(String(255), nullable=False)
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    confirmed_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    revision: Mapped[int] = mapped_column(BigInteger, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class CashCategoryModel(Base):
    __tablename__ = "cash_categories"
    __table_args__ = (
        ForeignKeyConstraint(
            ["workspace_id", "parent_id"],
            ["cash_categories.workspace_id", "cash_categories.id"],
            ondelete="RESTRICT", name="fk_cash_categories_workspace_parent",
        ),
        UniqueConstraint("workspace_id", "id", name="uq_cash_categories_workspace_id"),
        UniqueConstraint("workspace_id", "parent_scope_key", "normalized_name", name="uq_cash_categories_sibling_name"),
        CheckConstraint("depth BETWEEN 1 AND 5", name="ck_cash_categories_depth"),
        CheckConstraint("length(name) BETWEEN 1 AND 40", name="ck_cash_categories_name_length"),
        CheckConstraint("length(category_path) > 2", name="ck_cash_categories_path"),
        Index("ix_cash_categories_workspace_parent_order", "workspace_id", "parent_scope_key", "sort_order", "id"),
        Index("ix_cash_categories_workspace_path", "workspace_id", "category_path"),
    )

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    parent_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    parent_scope_key: Mapped[str] = mapped_column(String(64), nullable=False)
    name: Mapped[str] = mapped_column(String(40), nullable=False)
    normalized_name: Mapped[str] = mapped_column(String(40), nullable=False)
    description: Mapped[str | None] = mapped_column(String(500), nullable=True)
    category_path: Mapped[str] = mapped_column(String(512), nullable=False)
    depth: Mapped[int] = mapped_column(Integer, nullable=False)
    sort_order: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    revision: Mapped[int] = mapped_column(BigInteger, default=1, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class CashCategoryStateModel(Base):
    __tablename__ = "cash_category_states"
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True)
    revision: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class CashTransactionModel(Base):
    __tablename__ = "cash_transactions"
    __table_args__ = (
        CheckConstraint(
            "record_type IN ('consumption', 'refund', 'reversal', 'transfer_reversal', 'withdrawal_in', 'withdrawal_out', 'transfer_in', 'transfer_out', 'repayment', 'income', 'investment_in', 'investment_out', 'interest', 'fee', 'fx_in', 'fx_out', 'other')",
            name="ck_cash_transactions_record_type",
        ),
        CheckConstraint(
            "(record_type IN ('transfer_in', 'transfer_out') AND record_subtype IN ('ordinary_transfer', 'cross_border_remittance', 'internal_account_transfer')) OR "
            "(record_type IN ('fx_in', 'fx_out') AND record_subtype = 'currency_exchange') OR "
            "(record_type = 'repayment' AND record_subtype = 'credit_repayment') OR "
            "(record_type IN ('withdrawal_in', 'withdrawal_out') AND record_subtype = 'withdraw_to_bank') OR "
            "(record_type NOT IN ('transfer_in', 'transfer_out', 'fx_in', 'fx_out', 'repayment', 'withdrawal_in', 'withdrawal_out') AND record_subtype = 'not_applicable')",
            name="ck_cash_transactions_record_type_subtype",
        ),
        UniqueConstraint("workspace_id", "id", name="uq_cash_transactions_workspace_id"),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT",
            name="fk_cash_transactions_workspace_account",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "category_id"],
            ["cash_categories.workspace_id", "cash_categories.id"],
            ondelete="RESTRICT", name="fk_cash_transactions_workspace_category",
        ),
        Index("ix_cash_transactions_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_cash_transactions_workspace_account", "workspace_id", "account_id"),
        Index("ix_cash_transactions_workspace_category", "workspace_id", "category_id"),
        Index("ix_cash_transactions_workspace_source_record", "workspace_id", "source_type", "record_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    source_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    record_id: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    source_payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    source_fingerprint: Mapped[str | None] = mapped_column(String(64), nullable=True)
    manual_overrides: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    amount: Mapped[Decimal] = mapped_column(ExactDecimal(), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    counterparty: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    counterparty_account: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    counterparty_account_attrs: Mapped[list[str]] = mapped_column(
        JSON, default=list, nullable=False,
    )
    note: Mapped[str] = mapped_column(Text, default="", nullable=False)
    category_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    record_type: Mapped[str] = mapped_column(String(32), default="other", nullable=False)
    record_subtype: Mapped[str] = mapped_column(String(32), default="not_applicable", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    deleted_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    deleted_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    delete_reason: Mapped[str] = mapped_column(Text, default="", nullable=False)


class CashProjectionStateModel(Base):
    """每个工作区的收支投影活动指针和构建诊断。"""

    __tablename__ = "cash_projection_states"
    __table_args__ = (
        CheckConstraint("availability IN ('uninitialized', 'ready')", name="ck_cash_projection_states_availability"),
        CheckConstraint("last_build_status IN ('never', 'running', 'succeeded', 'failed')", name="ck_cash_projection_states_build_status"),
        CheckConstraint("availability <> 'ready' OR active_dataset_id IS NOT NULL", name="ck_cash_projection_states_ready_dataset"),
    )

    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True)
    active_dataset_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    projection_version: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    source_revision: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    rules_version: Mapped[str] = mapped_column(String(64), default="cash-projection-v1", nullable=False)
    availability: Mapped[str] = mapped_column(String(16), default="uninitialized", nullable=False)
    last_build_status: Mapped[str] = mapped_column(String(16), default="never", nullable=False)
    last_build_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    last_error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    last_error_summary: Mapped[str | None] = mapped_column(Text, nullable=True)
    projection_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    member_count: Mapped[int] = mapped_column(BigInteger, default=0, nullable=False)
    build_started_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    build_finished_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class CashProjectionDatasetModel(Base):
    """收支投影的暂存、活动或已退休数据集。"""

    __tablename__ = "cash_projection_datasets"
    __table_args__ = (
        CheckConstraint("state IN ('staging', 'active', 'retired')", name="ck_cash_projection_datasets_state"),
        Index("ix_cash_projection_datasets_workspace_state", "workspace_id", "state"),
    )

    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    state: Mapped[str] = mapped_column(String(16), nullable=False)
    source_revision: Mapped[int] = mapped_column(BigInteger, nullable=False)
    source_digest: Mapped[str] = mapped_column(String(64), nullable=False)
    rules_version: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    published_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)


class CashProjectionModel(Base):
    """活动数据集内的一条收支投影。"""

    __tablename__ = "cash_projections"
    __table_args__ = (
        ForeignKeyConstraint(["dataset_id"], ["cash_projection_datasets.id"], ondelete="CASCADE", name="fk_cash_projections_dataset"),
        ForeignKeyConstraint(["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_cash_projections_workspace_account"),
        ForeignKeyConstraint(["workspace_id", "root_cash_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="RESTRICT", name="fk_cash_projections_workspace_root"),
        ForeignKeyConstraint(["workspace_id", "funding_relation_id"], ["cash_investment_funding_relations.workspace_id", "cash_investment_funding_relations.id"], ondelete="RESTRICT", name="fk_cash_projections_workspace_funding_relation"),
        UniqueConstraint("workspace_id", "dataset_id", "projection_id", name="uq_cash_projections_dataset_projection"),
        CheckConstraint("economic_type IN ('expense', 'income', 'internal_transfer')", name="ck_cash_projections_economic_type"),
        Index("ix_cash_projections_visible_list", "workspace_id", "dataset_id", "visible", "occurred_at", "projection_id"),
        Index("ix_cash_projections_account", "workspace_id", "dataset_id", "account_id"),
        Index("ix_cash_projections_currency", "workspace_id", "dataset_id", "currency"),
        Index("ix_cash_projections_economic_type", "workspace_id", "dataset_id", "economic_type"),
        Index("ix_cash_projections_category_id", "workspace_id", "dataset_id", "category_id"),
        Index("ix_cash_projections_category_path", "workspace_id", "dataset_id", "category_path"),
        Index("ix_cash_projections_root", "workspace_id", "dataset_id", "root_cash_transaction_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    dataset_id: Mapped[str] = mapped_column(String(64), nullable=False)
    projection_id: Mapped[str] = mapped_column(String(96), nullable=False)
    root_cash_transaction_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    funding_relation_id: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
    economic_type: Mapped[str] = mapped_column(String(24), nullable=False)
    transfer_subtype: Mapped[str | None] = mapped_column(String(32), nullable=True)
    net_amount: Mapped[Decimal] = mapped_column(ExactDecimal(), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), nullable=False)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    counterparty: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    category_id: Mapped[str | None] = mapped_column(String(64), nullable=True)
    category_path: Mapped[str | None] = mapped_column(String(512), nullable=True)
    note: Mapped[str] = mapped_column(Text, default="", nullable=False)
    source_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    record_id: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    visible: Mapped[bool] = mapped_column(Boolean, nullable=False)
    hidden_reason: Mapped[str | None] = mapped_column(String(32), nullable=True)
    has_payment_mirror: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    has_refund_offset: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    has_transfer_pair: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    member_count: Mapped[int] = mapped_column(Integer, nullable=False)
    accepted_relation_count: Mapped[int] = mapped_column(Integer, nullable=False)
    built_projection_version: Mapped[int] = mapped_column(BigInteger, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)


class CashProjectionMemberModel(Base):
    __tablename__ = "cash_projection_members"
    __table_args__ = (
        ForeignKeyConstraint(["projection_row_id"], ["cash_projections.id"], ondelete="CASCADE", name="fk_cash_projection_members_projection"),
        ForeignKeyConstraint(["workspace_id", "cash_transaction_id"], ["cash_transactions.workspace_id", "cash_transactions.id"], ondelete="RESTRICT", name="fk_cash_projection_members_cash"),
        UniqueConstraint("workspace_id", "dataset_id", "cash_transaction_id", name="uq_cash_projection_members_dataset_cash"),
        UniqueConstraint("projection_row_id", "ordinal", name="uq_cash_projection_members_ordinal"),
        Index("ix_cash_projection_members_dataset", "dataset_id"),
        Index("ix_cash_projection_members_page_lookup", "workspace_id", "dataset_id", "projection_row_id", "ordinal"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    dataset_id: Mapped[str] = mapped_column(String(64), nullable=False)
    projection_row_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    cash_transaction_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    roles_json: Mapped[list[str]] = mapped_column(JSON, nullable=False)
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False)


class CashProjectionRelationModel(Base):
    __tablename__ = "cash_projection_relations"
    __table_args__ = (
        ForeignKeyConstraint(["projection_row_id"], ["cash_projections.id"], ondelete="CASCADE", name="fk_cash_projection_relations_projection"),
        ForeignKeyConstraint(["workspace_id", "transaction_relation_id"], ["transaction_relations.workspace_id", "transaction_relations.id"], ondelete="RESTRICT", name="fk_cash_projection_relations_relation"),
        UniqueConstraint("workspace_id", "dataset_id", "transaction_relation_id", name="uq_cash_projection_relations_dataset_relation"),
        UniqueConstraint("projection_row_id", "ordinal", name="uq_cash_projection_relations_ordinal"),
        Index("ix_cash_projection_relations_dataset", "dataset_id"),
        Index("ix_cash_projection_relations_page_lookup", "workspace_id", "dataset_id", "projection_row_id", "kind", "ordinal"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(String(64), nullable=False)
    dataset_id: Mapped[str] = mapped_column(String(64), nullable=False)
    projection_row_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    transaction_relation_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    subtype: Mapped[str] = mapped_column(String(32), default="", nullable=False)
    ordinal: Mapped[int] = mapped_column(Integer, nullable=False)


class InvestmentEventModel(Base):
    __tablename__ = "investment_events"
    __table_args__ = (
        CheckConstraint(
            "record_type IN ('funding', 'trade', 'income', 'expense', 'reversal', 'subscription', 'adjustment', 'snapshot')",
            name="ck_investment_events_record_type",
        ),
        CheckConstraint(
            "(record_type = 'funding' AND record_subtype IN ('external', 'subaccount')) OR "
            "(record_type = 'trade' AND record_subtype IN ('security', 'fx', 'repo')) OR "
            "(record_type = 'income' AND record_subtype IN ('dividend_cash', 'dividend_stock', 'interest', 'reward')) OR "
            "(record_type = 'expense' AND record_subtype IN ('commission', 'tax', 'interest', 'handling_fee', 'penalty')) OR "
            "(record_type = 'reversal' AND record_subtype IN ('expense_tax', 'expense_interest', 'expense_commission', 'expense_handling_fee', 'expense_penalty', 'funding_withdrawal')) OR "
            "(record_type = 'subscription' AND record_subtype IN ('ipo_debit', 'ipo_refund')) OR "
            "(record_type = 'adjustment' AND record_subtype IN ('fx_net', 'manual', 'unclassified')) OR "
            "(record_type = 'snapshot' AND record_subtype IN ('cash', 'position'))",
            name="ck_investment_events_record_type_subtype",
        ),
        UniqueConstraint("workspace_id", "id", name="uq_investment_events_workspace_id"),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="RESTRICT",
            name="fk_investment_events_workspace_account",
        ),
        Index("ix_investment_events_workspace_date", "workspace_id", "occurred_at"),
        Index("ix_investment_events_workspace_account", "workspace_id", "account_id"),
        Index("ix_investment_events_workspace_source_record", "workspace_id", "source_type", "record_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    source_type: Mapped[str | None] = mapped_column(String(64), nullable=True)
    record_id: Mapped[str] = mapped_column(String(512), default="", nullable=False)
    source_payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    occurred_at: Mapped[datetime] = mapped_column(UTCDateTime(), nullable=False)
    record_type: Mapped[str] = mapped_column(String(64), nullable=False)
    record_subtype: Mapped[str] = mapped_column(String(32), nullable=False)
    currency: Mapped[str] = mapped_column(String(3), default="", nullable=False)
    note: Mapped[str] = mapped_column(Text, default="", nullable=False)
    from_ticker: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    from_amount: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    to_ticker: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    to_amount: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    commission: Mapped[Decimal | None] = mapped_column(ExactDecimal(), nullable=True)
    commission_asset: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class CashInvestmentFundingRelationModel(Base):
    __tablename__ = "cash_investment_funding_relations"
    __table_args__ = (
        UniqueConstraint("workspace_id", "id", name="uq_cash_investment_funding_relations_workspace_id"),
        UniqueConstraint(
            "workspace_id", "cash_transaction_id", "investment_event_id", "active_slot",
            name="uq_cash_investment_funding_relations_active_pair",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "cash_transaction_id"],
            ["cash_transactions.workspace_id", "cash_transactions.id"],
            ondelete="RESTRICT",
            name="fk_cash_investment_funding_relations_workspace_cash",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "investment_event_id"],
            ["investment_events.workspace_id", "investment_events.id"],
            ondelete="RESTRICT",
            name="fk_cash_investment_funding_relations_workspace_investment",
        ),
        CheckConstraint(
            "direction IN ('cash_to_investment', 'investment_to_cash')",
            name="ck_cash_investment_funding_relations_direction",
        ),
        CheckConstraint(
            "status IN ('pending_review', 'accepted', 'rejected')",
            name="ck_cash_investment_funding_relations_status",
        ),
        Index("ix_cash_investment_funding_relations_workspace_status", "workspace_id", "status"),
        Index(
            "uq_cash_investment_funding_relations_accepted_cash",
            "workspace_id", "cash_transaction_id",
            unique=True,
            sqlite_where=text("status = 'accepted' AND active_slot = 'active'"),
            postgresql_where=text("status = 'accepted' AND active_slot = 'active'"),
        ),
        Index(
            "uq_cash_investment_funding_relations_accepted_investment",
            "workspace_id", "investment_event_id",
            unique=True,
            sqlite_where=text("status = 'accepted' AND active_slot = 'active'"),
            postgresql_where=text("status = 'accepted' AND active_slot = 'active'"),
        ),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    cash_transaction_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    investment_event_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    direction: Mapped[str] = mapped_column(String(32), nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    rule_id: Mapped[str] = mapped_column(String(128), nullable=False)
    evidence: Mapped[dict] = mapped_column(JSON, default=dict, nullable=False)
    active_slot: Mapped[str] = mapped_column(String(36), default="active", nullable=False)
    created_by: Mapped[str] = mapped_column(String(128), default="system", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    decided_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    decided_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    decision_reason: Mapped[str] = mapped_column(Text, default="", nullable=False)


class LedgerSnapshotModel(Base):
    __tablename__ = "ledger_snapshots"

    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True
    )
    payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    version: Mapped[int] = mapped_column(Integer, default=1, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)



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
    owner_account_id: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
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


class AccountLifecycleEventModel(Base):
    __tablename__ = "account_lifecycle_events"
    __table_args__ = (
        UniqueConstraint("workspace_id", "event_id", name="uq_lifecycle_workspace_event"),
        ForeignKeyConstraint(["workspace_id", "account_id"], ["accounts.workspace_id", "accounts.id"], ondelete="RESTRICT", name="fk_lifecycle_workspace_account"),
        Index("ix_lifecycle_workspace_account_effective", "workspace_id", "account_id", "effective_at"),
    )
    event_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    workspace_id: Mapped[str] = mapped_column(String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False)
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
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
    owner_account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
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
        Index("ix_transaction_relations_component_primary", "workspace_id", "status", "primary_fact_id"),
        Index("ix_transaction_relations_component_secondary", "workspace_id", "status", "secondary_fact_id"),
        Index("ix_transaction_relations_anchor", "workspace_id", "anchor_fact_id"),
        # Partial unique for unpaired relation active occupancy (PG + SQLite 3.8+).
        Index(
            "uq_transaction_relations_open_leg_active",
            "workspace_id",
            "kind",
            "subtype",
            "anchor_fact_id",
            unique=True,
            sqlite_where=text("secondary_fact_id IS NULL AND active_slot = 'active'"),
            postgresql_where=text("secondary_fact_id IS NULL AND active_slot = 'active'"),
        ),
        CheckConstraint(
            "kind IN ('payment_mirror','transfer_pair','refund_offset')",
            name="ck_transaction_relations_kind",
        ),
        CheckConstraint(
            "status IN ('pending_review','accepted','rejected','superseded')",
            name="ck_transaction_relations_status",
        ),
        CheckConstraint(
            "status != 'accepted' OR secondary_fact_id IS NOT NULL",
            name="ck_transaction_relations_accepted_bilateral",
        ),
        CheckConstraint(
            "kind != 'payment_mirror' OR secondary_fact_id IS NOT NULL",
            name="ck_transaction_relations_mirror_bilateral",
        ),
        CheckConstraint(
            "(secondary_fact_id IS NOT NULL) OR ("
            "status IN ('pending_review','rejected','superseded') "
            "AND kind IN ('refund_offset','transfer_pair')"
            ")",
            name="ck_transaction_relations_open_leg_shape",
        ),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    kind: Mapped[str] = mapped_column(String(32), nullable=False)
    subtype: Mapped[str] = mapped_column(String(64), default="", nullable=False)
    primary_fact_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    # Null only for unpaired relation refund_offset / transfer_pair pending/reject occupancy.
    secondary_fact_id: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
    primary_fact_type: Mapped[str] = mapped_column(String(32), default="cash", nullable=False)
    secondary_fact_type: Mapped[str | None] = mapped_column(String(32), nullable=True)
    # `open_leg` relations retain a nullable ordered endpoint after the 016 cutover.
    ordered_fact_a: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
    ordered_fact_b: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
    # active_slot is 'active' for non-superseded rows; superseded rows use id slot to free the key.
    active_slot: Mapped[str] = mapped_column(String(36), default="active", nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    rule_id: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    candidate_fact_ids: Mapped[list[int]] = mapped_column(JSON, default=list, nullable=False)
    created_by: Mapped[str] = mapped_column(String(128), default="system", nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    decided_by: Mapped[str] = mapped_column(String(128), default="", nullable=False)
    decided_at: Mapped[datetime | None] = mapped_column(UTCDateTime(), nullable=True)
    decision_reason: Mapped[str] = mapped_column(Text, default="", nullable=False)
    superseded_by_id: Mapped[int | None] = mapped_column(SurrogatePK, nullable=True)
    # Durable unpaired relation / role anchor (refund row, transfer out, etc.).
    anchor_fact_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)



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

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    alias_type: Mapped[str] = mapped_column(String(32), nullable=False)
    alias_value: Mapped[str] = mapped_column(String(255), nullable=False)
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    created_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, nullable=False)


class SyncCursorModel(Base):
    __tablename__ = "sync_cursors"
    __table_args__ = (
        UniqueConstraint(
            "workspace_id", "account_id", "source_type",
            name="uq_sync_cursors_workspace_account_source",
        ),
        ForeignKeyConstraint(
            ["workspace_id", "account_id"],
            ["accounts.workspace_id", "accounts.id"],
            ondelete="CASCADE",
            name="fk_sync_cursors_workspace_account",
        ),
        Index("ix_sync_cursors_workspace", "workspace_id"),
    )

    id: Mapped[int] = mapped_column(SurrogatePK, primary_key=True, autoincrement=True)
    workspace_id: Mapped[str] = mapped_column(
        String(64), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    account_id: Mapped[int] = mapped_column(SurrogatePK, nullable=False)
    source_type: Mapped[str] = mapped_column(String(64), nullable=False)
    cursor_value: Mapped[str] = mapped_column(String(256), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(UTCDateTime(), default=_now, onupdate=_now, nullable=False)

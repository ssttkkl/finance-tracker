"""Typed, read-only boundaries for wealth facts and immutable results."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Mapping, Protocol, Sequence, runtime_checkable


@dataclass(frozen=True)
class AccountFact:
    workspace_id: str
    account_id: str
    account_type: str
    metadata: Mapping[str, str]


@dataclass(frozen=True)
class ValuationFact:
    workspace_id: str
    observation_id: str
    identity_kind: str
    identity: str
    observation_kind: str
    value: Decimal
    currency: str
    unit: str
    as_of: datetime
    observed_at: datetime
    source_identity: str
    source_revision: str
    trust: str
    raw_record_id: str | None = None
    owner_account_id: str | None = None


@dataclass(frozen=True)
class LifecycleFact:
    workspace_id: str
    event_id: str
    account_id: str
    event_kind: str
    effective_at: datetime
    source_identity: str
    source_revision: str
    reason: str = ""


@dataclass(frozen=True)
class CashflowFact:
    workspace_id: str
    fact_id: str
    account_id: str
    occurred_at: datetime
    amount: Decimal
    currency: str
    revision: int
    raw_record_id: str | None = None
    category: str = ""
    transfer_account: str = ""
    offset_group: str = ""
    offset_role: str = ""


@dataclass(frozen=True)
class InvestmentFact:
    workspace_id: str
    fact_id: str
    account_id: str
    occurred_at: datetime
    kind: str
    currency: str
    payload: Mapping[str, str]
    revision: int
    raw_record_id: str | None = None


@dataclass(frozen=True)
class WealthSourceItem:
    item_kind: str
    identity: str
    revision: str
    content_digest: str
    occurred_at: datetime | None = None
    evidence_kind: str | None = None
    contribution: Decimal | None = None
    scope_fold_identity: str | None = None
    safe_metadata: Mapping[str, str] | None = None


@runtime_checkable
class WealthFactRepository(Protocol):
    def accounts(self) -> Sequence[AccountFact]: ...
    def valuations(self, *, starts_at: datetime, ends_at: datetime) -> Sequence[ValuationFact]: ...
    def lifecycle_events(self) -> Sequence[LifecycleFact]: ...
    def cashflows(self) -> Sequence[CashflowFact]: ...
    def investments(self) -> Sequence[InvestmentFact]: ...
    def capture_source_manifest(self) -> tuple[str, Sequence[WealthSourceItem]]: ...


@runtime_checkable
class WealthReadModelRepository(Protocol):
    def active_generation(self) -> str | None: ...
    def component_evidence(self, component_id: str, result_revision: str) -> Sequence[object]: ...

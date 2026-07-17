"""Focused persistence ports for Phase 1 application use cases."""
from __future__ import annotations

from typing import Protocol, runtime_checkable


@runtime_checkable
class AccountQueryRepository(Protocol):
    def list_accounts(self) -> list[object]:
        ...


@runtime_checkable
class TransactionQueryRepository(Protocol):
    def list_transactions(
        self,
        *,
        month: str | None = None,
        account: str | None = None,
        category: str | None = None,
    ) -> list[dict]:
        ...


@runtime_checkable
class SnapshotQueryRepository(Protocol):
    def load_snapshot(self) -> dict:
        ...


@runtime_checkable
class CashflowImportRepository(Protocol):
    def append_cashflows(self, rows: list[dict]) -> int:
        ...


@runtime_checkable
class InvestmentCommandRepository(Protocol):
    def execute(self, command: object) -> object:
        ...

    def append_investments(self, rows: list[dict]) -> int:
        ...


@runtime_checkable
class InvestmentEventRepository(Protocol):
    def existing_external_ids(self, provider: str, account: str) -> set[str]:
        ...

    def append_events(self, rows: list[dict]) -> int:
        ...


@runtime_checkable
class PortfolioRepository(Protocol):
    def load_portfolio(self) -> dict:
        ...


@runtime_checkable
class VerificationRepository(Protocol):
    def verify_cashflows(self) -> tuple[int, tuple[object, ...]]:
        ...

    def verify_investments(self) -> tuple[object, ...]:
        ...

    def rebuild(self) -> None:
        ...


@runtime_checkable
class ReconciliationRepository(Protocol):
    def state(self) -> str:
        ...

    def start(self, *, month=None, date_from=None, date_to=None) -> dict:
        ...

    def continue_with_decisions(self) -> dict:
        ...

    def abort(self) -> dict:
        ...


@runtime_checkable
class ChangeSetRepository(Protocol):
    def stage(self) -> None:
        ...

    def status(self) -> tuple[str, ...]:
        ...

    def commit(self, message: str | None = None) -> bool:
        ...

    def reset(self) -> tuple[str, ...]:
        ...

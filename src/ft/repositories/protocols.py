"""Runtime-checkable repository protocols."""
from __future__ import annotations

from typing import Protocol, runtime_checkable

from ft.domain.accounts import AccountDTO


@runtime_checkable
class AccountRepository(Protocol):
    def list(self) -> list[AccountDTO]:
        ...

    def find(self, name: str, currency: str | None = None) -> AccountDTO | None:
        ...

    def add(self, account: AccountDTO) -> None:
        ...

    def replace_all(self, accounts: list[AccountDTO]) -> None:
        ...


@runtime_checkable
class CashflowRepository(Protocol):
    def list(self, account_type: str | None = None) -> list[dict]:
        ...

    def add(self, account_type: str, row: dict) -> None:
        ...


@runtime_checkable
class SnapshotRepository(Protocol):
    def load(self) -> dict:
        ...

    def save(self, data: dict) -> None:
        ...

    def set_balance(self, snap: dict, account_name: str, account_type: str, currency: str, balance) -> None:
        ...

    def update_balance(self, snap: dict, account_name: str, account_type: str, currency: str, delta) -> None:
        ...


@runtime_checkable
class InvestmentRepository(Protocol):
    def list(self) -> list[dict]:
        ...

    def add(self, account_type: str, row: dict) -> None:
        ...


@runtime_checkable
class ReviewRepository(Protocol):
    def list(self) -> list[dict]:
        ...

    def add(self, item: dict) -> None:
        ...


@runtime_checkable
class UnitOfWork(Protocol):
    ledger_root: object
    accounts: AccountRepository
    cashflows: CashflowRepository
    investments: InvestmentRepository
    snapshot: SnapshotRepository

    def __enter__(self) -> "UnitOfWork":
        ...

    def __exit__(self, exc_type, exc, tb) -> None:
        ...

    def commit(self) -> None:
        ...

    def rollback(self) -> None:
        ...

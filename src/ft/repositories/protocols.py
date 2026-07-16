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


@runtime_checkable
class CashflowRepository(Protocol):
    def list(self) -> list[dict]:
        ...

    def add(self, row: dict) -> None:
        ...


@runtime_checkable
class InvestmentRepository(Protocol):
    def list(self) -> list[dict]:
        ...

    def add(self, row: dict) -> None:
        ...


@runtime_checkable
class ReviewRepository(Protocol):
    def list(self) -> list[dict]:
        ...

    def add(self, item: dict) -> None:
        ...


@runtime_checkable
class UnitOfWork(Protocol):
    accounts: AccountRepository

    def __enter__(self) -> "UnitOfWork":
        ...

    def __exit__(self, exc_type, exc, tb) -> None:
        ...

    def commit(self) -> None:
        ...

    def rollback(self) -> None:
        ...

"""Runtime-checkable repository protocols."""
from __future__ import annotations

from typing import Protocol, runtime_checkable

from ft.domain.accounts import AccountDTO


@runtime_checkable
class AccountRepository(Protocol):
    def list(self) -> list[AccountDTO]:
        ...

    def find(self, name: str) -> AccountDTO | None:
        ...

    def add(self, account: AccountDTO) -> None:
        ...

    def rename(self, name: str, new_name: str) -> AccountDTO:
        ...

    def set_active(self, name: str, active: bool) -> AccountDTO:
        ...

    def has_facts(self, name: str) -> bool:
        ...

    def delete(self, name: str) -> AccountDTO:
        ...


@runtime_checkable
class CashflowRepository(Protocol):
    def list(self, account_type: str | None = None) -> list[dict]:
        ...

    def add(self, account_type: str, row: dict) -> str:
        ...


@runtime_checkable
class SnapshotRepository(Protocol):
    def load(self, *, lock: bool = False) -> dict:
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

    def add(self, account_type: str, row: dict) -> str:
        ...


@runtime_checkable
class ImportRepository(Protocol):
    def start_batch(
        self, *, source_kind: str, source_digest: str, source_ref: str,
        target_account_name: str | None = None,
        target_account_currency: str | None = None,  # ignored; name-only resolve
    ) -> str:
        ...

    def get_batch(self, batch_id: str) -> dict | None:
        ...

    def add_raw_file(
        self, *, batch_id: str, source_path: str, content_digest: str,
        size_bytes: int, media_type: str,
    ) -> str:
        ...

    def add_raw_records(
        self, *, batch_id: str, raw_file_id: str | None,
        source_type: str, records: list[dict],
    ) -> list[str]:
        ...

    def formal_fact_targets(
        self, raw_record_ids: list[str],
    ) -> dict[str, tuple[str, str]]:
        ...

    def batch_target_accounts(self, batch_id: str) -> set[tuple[str, str]]:
        ...

    def append_revision(self, **kwargs) -> str:
        ...

    def complete_batch(self, batch_id: str) -> None:
        ...


@runtime_checkable
class UnitOfWork(Protocol):
    accounts: AccountRepository
    cashflows: CashflowRepository
    investments: InvestmentRepository
    snapshot: SnapshotRepository
    imports: ImportRepository

    def __enter__(self) -> "UnitOfWork":
        ...

    def __exit__(self, exc_type, exc, tb) -> None:
        ...

    def commit(self) -> None:
        ...

    def rollback(self) -> None:
        ...

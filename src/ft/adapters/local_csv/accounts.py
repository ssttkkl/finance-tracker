"""Local accounts.yaml repository and unit of work."""
from __future__ import annotations

from copy import deepcopy
from pathlib import Path

import yaml

from ft.accounts import DEFAULT_ACCOUNTS_YAML
from ft.domain.accounts import AccountDTO


class LocalCsvAccountRepository:
    def __init__(self, ledger_root: Path):
        self.ledger_root = Path(ledger_root)
        self.accounts_path = self.ledger_root / "accounts.yaml"

    def list(self) -> list[AccountDTO]:
        return [_to_dto(item) for item in self._read_raw()]

    def find(self, name: str, currency: str | None = None) -> AccountDTO | None:
        matches = [
            account
            for account in self.list()
            if account.name == name and (currency is None or account.currency == currency)
        ]
        active = [account for account in matches if account.active]
        return (active or matches or [None])[0]

    def add(self, account: AccountDTO) -> None:
        accounts = self._read_raw()
        accounts.append(_to_raw(account))
        self._write_raw(accounts)

    def _read_raw(self) -> list[dict]:
        if not self.accounts_path.exists():
            self.accounts_path.parent.mkdir(parents=True, exist_ok=True)
            self.accounts_path.write_text(DEFAULT_ACCOUNTS_YAML, encoding="utf-8")
        data = yaml.safe_load(self.accounts_path.read_text(encoding="utf-8"))
        if data is None:
            return []
        return list(data.get("accounts", []))

    def _write_raw(self, accounts: list[dict]) -> None:
        self.accounts_path.parent.mkdir(parents=True, exist_ok=True)
        self.accounts_path.write_text(
            yaml.dump(
                {"accounts": accounts},
                allow_unicode=True,
                default_flow_style=False,
            ),
            encoding="utf-8",
        )


class _BufferedAccountRepository:
    def __init__(self, loader):
        self._loader = loader
        self.dirty = False

    @property
    def _accounts(self) -> list[dict]:
        return self._loader()

    def list(self) -> list[AccountDTO]:
        return [_to_dto(item) for item in self._accounts]

    def find(self, name: str, currency: str | None = None) -> AccountDTO | None:
        matches = [
            account
            for account in self.list()
            if account.name == name and (currency is None or account.currency == currency)
        ]
        active = [account for account in matches if account.active]
        return (active or matches or [None])[0]

    def add(self, account: AccountDTO) -> None:
        self._accounts.append(_to_raw(account))
        self.dirty = True


class LocalCsvUnitOfWork:
    def __init__(self, ledger_root: Path):
        self.ledger_root = Path(ledger_root)
        self._repository = LocalCsvAccountRepository(self.ledger_root)
        self._working_accounts: list[dict] | None = None
        self.accounts = _BufferedAccountRepository(self._load_working_accounts)
        self._committed = False

    def __enter__(self) -> "LocalCsvUnitOfWork":
        self._working_accounts = None
        self.accounts = _BufferedAccountRepository(self._load_working_accounts)
        self._committed = False
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if exc_type is not None or not self._committed:
            self.rollback()

    def commit(self) -> None:
        if self._working_accounts is not None and self.accounts.dirty:
            self._repository._write_raw(self._working_accounts)
        self._committed = True

    def rollback(self) -> None:
        self._committed = False

    def _load_working_accounts(self) -> list[dict]:
        if self._working_accounts is None:
            self._working_accounts = deepcopy(self._repository._read_raw())
        return self._working_accounts


def _to_dto(item: dict) -> AccountDTO:
    return AccountDTO(
        name=item.get("name", ""),
        type=item.get("type", ""),
        currency=item.get("currency", ""),
        active=item.get("active", True),
    )


def _to_raw(account: AccountDTO) -> dict:
    return {
        "name": account.name,
        "type": account.type,
        "currency": account.currency,
        "active": account.active,
    }

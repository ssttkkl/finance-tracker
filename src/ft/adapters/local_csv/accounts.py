"""Local CSV/YAML repositories and unit of work."""
from __future__ import annotations

import csv
from copy import deepcopy
from decimal import Decimal
from pathlib import Path
import tempfile

import yaml

from ft.domain.accounts import AccountDTO
from ft.ledger_layout import ensure_monthly_cash_ledger
from ft.schema import CASH_CSV_FIELDS, DEFAULT_ACCOUNTS_YAML, DEFAULT_SNAPSHOT


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

    def replace_all(self, accounts: list[AccountDTO]) -> None:
        self._write_raw([_to_raw(account) for account in accounts])

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

    def replace_all(self, accounts: list[AccountDTO]) -> None:
        self._accounts[:] = [_to_raw(account) for account in accounts]
        self.dirty = True


class _BufferedCashflowRepository:
    def __init__(self, ledger_root: Path):
        self.ledger_root = Path(ledger_root)
        self.records_dir = self.ledger_root / "records"
        self._cache: dict[tuple[str, str], list[dict]] = {}
        self.dirty: set[tuple[str, str]] = set()

    def list(self, account_type: str | None = None) -> list[dict]:
        rows = []
        if account_type in {"security", "crypto"}:
            raise ValueError(
                "cashflow repository only supports cash, loan, and lend records; "
                "use the investment repository"
            )
        self.ensure_no_legacy_crypto_records()
        ensure_monthly_cash_ledger(self.records_dir)
        types = [account_type] if account_type else ["cash", "loan", "lend"]
        for typ in types:
            type_dir = self.records_dir / typ
            if not type_dir.exists():
                continue
            for path in sorted(type_dir.glob("*.csv")):
                for row in self._read_file(typ, path.stem):
                    copied = dict(row)
                    copied["_record_type"] = typ
                    copied["_record_file"] = str(path)
                    rows.append(copied)
        return rows

    def add(self, account_type: str, row: dict) -> None:
        if account_type not in {"cash", "loan", "lend"}:
            raise ValueError(
                "cashflow repository only supports cash, loan, and lend records; "
                "use the investment repository"
            )
        ensure_monthly_cash_ledger(self.records_dir)
        month = row["date"][:7]
        key = (account_type, month)
        rows = self._rows_for(account_type, month)
        rows.append(dict(row) if account_type == "security" else _normal_cash_row(row))
        rows.sort(key=lambda item: item.get("date", ""))
        self.dirty.add(key)

    def commit(self) -> None:
        for account_type, month in sorted(self.dirty):
            path = self.records_dir / account_type / f"{month}.csv"
            rows = self._cache.get((account_type, month), [])
            if not rows:
                path.unlink(missing_ok=True)
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            if account_type == "security":
                from ft.stock import _write_security_csv
                _write_security_csv(path, rows)
            else:
                with path.open("w", newline="", encoding="utf-8") as f:
                    writer = csv.DictWriter(f, fieldnames=CASH_CSV_FIELDS)
                    writer.writeheader()
                    writer.writerows(rows)
        self.dirty.clear()

    def _rows_for(self, account_type: str, day: str) -> list[dict]:
        key = (account_type, day)
        if key not in self._cache:
            self._cache[key] = self._read_file(account_type, day)
        return self._cache[key]

    def _read_file(self, account_type: str, day: str) -> list[dict]:
        path = self.records_dir / account_type / f"{day}.csv"
        if not path.exists():
            return []
        with path.open(encoding="utf-8") as f:
            if account_type == "security":
                from ft.stock import _validate_security_csv_header

                reader = csv.DictReader(f)
                _validate_security_csv_header(reader.fieldnames, path)
                return [dict(row) for row in reader]
            return [_normal_cash_row(row) for row in csv.DictReader(f)]

    def ensure_no_legacy_crypto_records(self) -> None:
        legacy_files = sorted((self.records_dir / "crypto").glob("*.csv"))
        if legacy_files:
            raise ValueError(
                "legacy crypto ledger is unsupported; move investment events to records/security: "
                + ", ".join(str(path) for path in legacy_files)
            )

    def ensure_no_legacy_daily_cash_records(self) -> None:
        ensure_monthly_cash_ledger(self.records_dir)


class _BufferedInvestmentRepository:
    """Investment events share the UnitOfWork's buffered record transaction."""

    def __init__(self, records: _BufferedCashflowRepository):
        self._records = records

    def list(self) -> list[dict]:
        self._records.ensure_no_legacy_crypto_records()
        self._records.ensure_no_legacy_daily_cash_records()
        return self._read_security_events()

    def add(self, account_type: str, row: dict) -> None:
        if account_type not in {"security", "crypto"}:
            raise ValueError(f"investment events require security or crypto account: {account_type}")
        self._records.ensure_no_legacy_crypto_records()
        self._records.ensure_no_legacy_daily_cash_records()
        day = row["date"][:10]
        rows = self._records._rows_for("security", day)
        rows.append(dict(row))
        rows.sort(key=lambda item: item.get("date", ""))
        self._records.dirty.add(("security", day))

    def _read_security_events(self) -> list[dict]:
        rows = []
        type_dir = self._records.records_dir / "security"
        if not type_dir.exists():
            return rows
        for path in sorted(type_dir.glob("*.csv")):
            for row in self._records._read_file("security", path.stem):
                copied = dict(row)
                copied["_record_type"] = "security"
                copied["_record_file"] = str(path)
                rows.append(copied)
        return rows


class _BufferedSnapshotRepository:
    def __init__(self, ledger_root: Path):
        self.ledger_root = Path(ledger_root)
        self.snapshot_path = self.ledger_root / "snapshot.yaml"
        self._snapshot: dict | None = None
        self.dirty = False

    def load(self) -> dict:
        if self._snapshot is None:
            if not self.snapshot_path.exists():
                self._snapshot = deepcopy(DEFAULT_SNAPSHOT)
            else:
                data = yaml.safe_load(self.snapshot_path.read_text(encoding="utf-8"))
                self._snapshot = data if data is not None else deepcopy(DEFAULT_SNAPSHOT)
        return self._snapshot

    def save(self, data: dict) -> None:
        self._snapshot = data
        self.dirty = True

    def set_balance(self, snap: dict, account_name: str, account_type: str, currency: str, balance) -> None:
        accounts = snap.setdefault("accounts", {})
        bucket = accounts.setdefault(account_type, {})
        acct_bucket = bucket.setdefault(account_name, {})
        if not isinstance(acct_bucket, dict):
            raise ValueError(
                f"invalid snapshot schema for {account_type} account {account_name!r}: "
                "expected account -> currency -> numeric balance"
            )
        acct_bucket[currency] = _number_for_yaml(balance)
        self.dirty = True

    def update_balance(self, snap: dict, account_name: str, account_type: str, currency: str, delta) -> None:
        accounts = snap.setdefault("accounts", {})
        accts = accounts.setdefault(account_type, {})
        if account_name in accts:
            bucket = accts[account_name]
            if isinstance(bucket, dict):
                current = Decimal(str(bucket.get(currency, 0)))
                bucket[currency] = _number_for_yaml(current + Decimal(str(delta)))
            else:
                raise ValueError(
                    f"invalid snapshot schema for {account_type} account {account_name!r}: "
                    "expected account -> currency -> numeric balance"
                )
            self.dirty = True
            return
        account_bucket = accts.setdefault(account_name, {})
        account_bucket[currency] = _number_for_yaml(Decimal(str(delta)))
        self.dirty = True

    def commit(self) -> None:
        if not self.dirty or self._snapshot is None:
            return
        self.snapshot_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = None
        try:
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.snapshot_path.parent, delete=False) as f:
                tmp_path = Path(f.name)
                yaml.dump(self._snapshot, f, allow_unicode=True, default_flow_style=False)
            tmp_path.replace(self.snapshot_path)
        finally:
            if tmp_path is not None and tmp_path.exists():
                tmp_path.unlink(missing_ok=True)
        self.dirty = False


class LocalCsvUnitOfWork:
    def __init__(self, ledger_root: Path):
        self.ledger_root = Path(ledger_root)
        self._repository = LocalCsvAccountRepository(self.ledger_root)
        self._working_accounts: list[dict] | None = None
        self.accounts = _BufferedAccountRepository(self._load_working_accounts)
        self.cashflows = _BufferedCashflowRepository(self.ledger_root)
        self.investments = _BufferedInvestmentRepository(self.cashflows)
        self.snapshot = _BufferedSnapshotRepository(self.ledger_root)
        self._committed = False

    def __enter__(self) -> "LocalCsvUnitOfWork":
        self._working_accounts = None
        self.accounts = _BufferedAccountRepository(self._load_working_accounts)
        self.cashflows = _BufferedCashflowRepository(self.ledger_root)
        self.investments = _BufferedInvestmentRepository(self.cashflows)
        self.snapshot = _BufferedSnapshotRepository(self.ledger_root)
        self._committed = False
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if exc_type is not None or not self._committed:
            self.rollback()

    def commit(self) -> None:
        self.cashflows.ensure_no_legacy_crypto_records()
        self.cashflows.ensure_no_legacy_daily_cash_records()
        affected_paths = self._affected_paths()
        backups = {
            path: path.read_bytes() if path.exists() else None
            for path in affected_paths
        }
        dirty_state = (
            self.accounts.dirty,
            set(self.cashflows.dirty),
            self.snapshot.dirty,
        )
        try:
            if self._working_accounts is not None and self.accounts.dirty:
                self._repository._write_raw(self._working_accounts)
            self.cashflows.commit()
            self.snapshot.commit()
        except Exception as original_error:
            restore_errors = []
            for path, content in backups.items():
                try:
                    if content is None:
                        path.unlink(missing_ok=True)
                    else:
                        path.parent.mkdir(parents=True, exist_ok=True)
                        path.write_bytes(content)
                except Exception as restore_error:
                    restore_errors.append(f"{path}: {restore_error}")
            self.accounts.dirty, self.cashflows.dirty, self.snapshot.dirty = dirty_state
            self.rollback()
            if restore_errors and hasattr(original_error, "add_note"):
                original_error.add_note(
                    "rollback restoration failures: " + "; ".join(restore_errors)
                )
            raise
        self._committed = True

    def rollback(self) -> None:
        self._committed = False

    def _load_working_accounts(self) -> list[dict]:
        if self._working_accounts is None:
            self._working_accounts = deepcopy(self._repository._read_raw())
        return self._working_accounts

    def _affected_paths(self) -> set[Path]:
        paths = {
            self.cashflows.records_dir / account_type / f"{day}.csv"
            for account_type, day in self.cashflows.dirty
        }
        if self._working_accounts is not None and self.accounts.dirty:
            paths.add(self._repository.accounts_path)
        if self.snapshot.dirty:
            paths.add(self.snapshot.snapshot_path)
        return paths


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


def _normal_cash_row(row: dict) -> dict:
    normalized = {field: row.get(field, "") for field in CASH_CSV_FIELDS}
    amount = normalized.get("amount")
    if isinstance(amount, Decimal):
        normalized["amount"] = format(amount, "f")
    else:
        normalized["amount"] = str(amount)
    return normalized


def _number_for_yaml(value):
    """Return a plain numeric YAML value for snapshot balances."""
    dec = Decimal(str(value))
    if dec == dec.to_integral_value():
        return int(dec)
    return float(dec)

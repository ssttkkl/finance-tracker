"""Focused local read adapters for application query services."""
from copy import deepcopy
import csv
from pathlib import Path

import yaml

from ft.adapters.local_csv.accounts import LocalCsvAccountRepository
from ft.ledger_layout import ensure_monthly_cash_ledger
from ft.schema import DEFAULT_SNAPSHOT


class LocalAccountQueryRepository:
    def __init__(self, ledger_root):
        self._repository = LocalCsvAccountRepository(Path(ledger_root))

    def list_accounts(self):
        return self._repository.list()


class LocalTransactionQueryRepository:
    def __init__(self, ledger_root):
        self._records_dir = Path(ledger_root) / "records"

    def list_transactions(self, *, month=None, account=None, category=None):
        ensure_monthly_cash_ledger(self._records_dir)
        rows = []
        if self._records_dir.exists():
            for type_dir in sorted(self._records_dir.iterdir()):
                if not type_dir.is_dir():
                    continue
                for path in sorted(type_dir.glob("*.csv")):
                    if month and not path.stem.startswith(month):
                        continue
                    with path.open(encoding="utf-8") as handle:
                        rows.extend(dict(row) for row in csv.DictReader(handle))
        if account:
            rows = [row for row in rows if row.get("account_name", "").strip() == account]
        if category:
            rows = [row for row in rows if row.get("category", "") == category]
        return rows


class LocalSnapshotQueryRepository:
    def __init__(self, ledger_root):
        self._path = Path(ledger_root) / "snapshot.yaml"

    def load_snapshot(self):
        if not self._path.exists():
            return deepcopy(DEFAULT_SNAPSHOT)
        data = yaml.safe_load(self._path.read_text(encoding="utf-8"))
        return data if data is not None else deepcopy(DEFAULT_SNAPSHOT)

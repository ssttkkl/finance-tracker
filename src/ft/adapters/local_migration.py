"""Read and write deterministic local-ledger migration fixtures."""
from __future__ import annotations

import csv
from hashlib import sha256
from pathlib import Path

import yaml

from ft.domain.migration import LedgerData, RawFileData
from ft.ledger_layout import ensure_monthly_cash_ledger
from ft.schema import DEFAULT_SNAPSHOT


def _digest(data: bytes) -> str:
    return f"sha256:{sha256(data).hexdigest()}"


class LocalMigrationSource:
    def __init__(self, ledger_root):
        self.ledger_root = Path(ledger_root)

    def load(self) -> LedgerData:
        if not self.ledger_root.exists():
            raise FileNotFoundError(f"ledger root does not exist: {self.ledger_root}")
        accounts_path = self.ledger_root / "accounts.yaml"
        snapshot_path = self.ledger_root / "snapshot.yaml"
        accounts = self._accounts(accounts_path)
        account_types = {row.get("name", ""): row.get("type", "security") for row in accounts}
        cashflows = []
        investments = []
        raw_files = []
        source_parts = []

        for path, source_type, records in self._structured_files(accounts_path, snapshot_path, accounts):
            raw_files.append(self._raw_file(path, source_type, records))
            source_parts.append((path.relative_to(self.ledger_root).as_posix(), path.read_bytes()))

        records_dir = self.ledger_root / "records"
        ensure_monthly_cash_ledger(records_dir)
        for account_type in ("cash", "loan", "lend"):
            directory = records_dir / account_type
            for path in sorted(directory.glob("*.csv")) if directory.exists() else ():
                rows = self._csv_rows(path)
                cashflows.extend({**row, "_record_type": account_type} for row in rows)
                raw_files.append(self._raw_file(path, account_type, rows))
                source_parts.append((path.relative_to(self.ledger_root).as_posix(), path.read_bytes()))

        security_dir = records_dir / "security"
        for path in sorted(security_dir.glob("*.csv")) if security_dir.exists() else ():
            rows = self._csv_rows(path)
            typed_rows = []
            for row in rows:
                account_type = account_types.get(row.get("account_name", ""), "security")
                typed_rows.append({**row, "_record_type": account_type})
            investments.extend(typed_rows)
            raw_files.append(self._raw_file(path, "investment", rows))
            source_parts.append((path.relative_to(self.ledger_root).as_posix(), path.read_bytes()))

        combined = sha256()
        for relative_path, content in sorted(source_parts):
            combined.update(relative_path.encode("utf-8"))
            combined.update(b"\0")
            combined.update(content)
            combined.update(b"\0")
        snapshot = self._snapshot(snapshot_path)
        return LedgerData(
            accounts=tuple(accounts),
            cashflows=tuple(cashflows),
            investments=tuple(investments),
            snapshot=snapshot,
            raw_files=tuple(sorted(raw_files, key=lambda item: item.source_path)),
            source_digest=f"sha256:{combined.hexdigest()}",
        )

    def _structured_files(self, accounts_path, snapshot_path, accounts):
        if accounts_path.exists():
            yield accounts_path, "accounts", tuple(accounts)
        if snapshot_path.exists():
            yield snapshot_path, "snapshot", (self._snapshot(snapshot_path),)

    def _raw_file(self, path: Path, source_type: str, rows) -> RawFileData:
        content = path.read_bytes()
        relative = path.relative_to(self.ledger_root).as_posix()
        records = tuple({
            "source_identity": f"{source_type}:{relative}:{index}",
            "source_line": index,
            "payload": dict(row),
        } for index, row in enumerate(rows, start=1))
        media_type = "text/csv" if path.suffix == ".csv" else "application/yaml"
        return RawFileData(relative, _digest(content), len(content), media_type, source_type, records)

    @staticmethod
    def _accounts(path: Path) -> list[dict]:
        if not path.exists():
            return []
        return list((yaml.safe_load(path.read_text(encoding="utf-8")) or {}).get("accounts", []))

    @staticmethod
    def _snapshot(path: Path) -> dict:
        if not path.exists():
            from copy import deepcopy
            return deepcopy(DEFAULT_SNAPSHOT)
        return yaml.safe_load(path.read_text(encoding="utf-8")) or {}

    @staticmethod
    def _csv_rows(path: Path) -> list[dict]:
        with path.open(encoding="utf-8") as handle:
            return [dict(row) for row in csv.DictReader(handle)]

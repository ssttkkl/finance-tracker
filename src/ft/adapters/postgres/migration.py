"""Database target for local-ledger migration and deterministic export."""
from __future__ import annotations

import csv
from pathlib import Path

import yaml
from sqlalchemy import func, select

from ft.domain.migration import LedgerData, MigrationImportResult
from ft.schema import CASH_CSV_FIELDS, CSV_FIELDS

from .models import RawRecordModel
from .uow import PostgresUnitOfWork


class PostgresMigrationTarget:
    def __init__(self, session_factory, workspace_id: str):
        self._session_factory = session_factory
        self.workspace_id = workspace_id

    def import_ledger(self, data: LedgerData) -> MigrationImportResult:
        with PostgresUnitOfWork(self._session_factory, self.workspace_id) as uow:
            batch_id = uow.imports.start_batch(
                source_kind="local_migration",
                source_digest=data.source_digest,
                source_ref="local-ledger",
            )
            batch = uow.imports.get_batch(batch_id)
            counts = {
                "accounts": len(data.accounts),
                "cash_transactions": len(data.cashflows),
                "investment_events": len(data.investments),
            }
            if batch["status"] == "completed":
                uow.commit()
                return MigrationImportResult(batch_id, False, counts)

            for account in data.accounts:
                uow.accounts.add_raw(dict(account))
            for row in data.cashflows:
                uow.cashflows.add(row.get("_record_type", "cash"), row)
            for row in data.investments:
                uow.investments.add(row.get("_record_type", "security"), row)
            uow.snapshot.save(data.snapshot)
            for raw_file in data.raw_files:
                raw_file_id = uow.imports.add_raw_file(
                    batch_id=batch_id,
                    source_path=raw_file.source_path,
                    content_digest=raw_file.content_digest,
                    size_bytes=raw_file.size_bytes,
                    media_type=raw_file.media_type,
                )
                uow.imports.add_raw_records(
                    batch_id=batch_id,
                    raw_file_id=raw_file_id,
                    source_type=raw_file.source_type,
                    records=list(raw_file.records),
                )
            uow.imports.complete_batch(batch_id)
            uow.commit()
            return MigrationImportResult(batch_id, True, counts)

    def load(self) -> LedgerData:
        with PostgresUnitOfWork(self._session_factory, self.workspace_id) as uow:
            data = LedgerData(
                accounts=tuple(uow.accounts.list_raw()),
                cashflows=tuple(uow.cashflows.list()),
                investments=tuple(uow.investments.list()),
                snapshot=uow.snapshot.load(),
            )
            uow.commit()
            return data

    def raw_record_count(self, batch_id: str) -> int:
        with self._session_factory() as session:
            return session.scalar(select(func.count()).select_from(RawRecordModel).where(
                RawRecordModel.workspace_id == self.workspace_id,
                RawRecordModel.batch_id == batch_id,
            ))

    def export(self, data: LedgerData, destination) -> None:
        root = Path(destination)
        root.mkdir(parents=True, exist_ok=True)
        (root / "accounts.yaml").write_text(
            yaml.safe_dump({"accounts": list(data.accounts)}, allow_unicode=True, sort_keys=False),
            encoding="utf-8",
        )
        (root / "snapshot.yaml").write_text(
            yaml.safe_dump(data.snapshot, allow_unicode=True, sort_keys=False), encoding="utf-8"
        )
        cash_groups = {}
        for row in data.cashflows:
            key = (row.get("_record_type", "cash"), row.get("date", "")[:7])
            cash_groups.setdefault(key, []).append(row)
        for (account_type, month), rows in sorted(cash_groups.items()):
            self._write_csv(
                root / "records" / account_type / f"{month}.csv",
                CASH_CSV_FIELDS, rows,
            )
        investment_groups = {}
        for row in data.investments:
            day = row.get("date", "")[:10]
            investment_groups.setdefault(day, []).append(row)
        for day, rows in sorted(investment_groups.items()):
            self._write_csv(root / "records" / "security" / f"{day}.csv", CSV_FIELDS, rows)

    @staticmethod
    def _write_csv(path: Path, fieldnames, rows) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames, extrasaction="ignore")
            writer.writeheader()
            writer.writerows(rows)

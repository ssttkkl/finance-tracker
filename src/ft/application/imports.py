"""Cashflow conversion and atomic import orchestration."""
from collections import Counter

from ft.domain.application import ExportPayload, OperationResult
from ft.domain.imports import CASHFLOW_EXPORT_FIELDS


class CashflowImportService:
    def __init__(self, *, importer, repository, mappings, change_sets):
        self._importer = importer
        self._repository = repository
        self._mappings = mappings
        self._change_sets = change_sets

    def convert(self, command) -> OperationResult:
        mapping = self._mappings.get_mapping("cashflow")
        rows = self._importer.convert(command, mapping=mapping)
        payload = ExportPayload(
            rows=tuple(rows),
            fieldnames=CASHFLOW_EXPORT_FIELDS,
        )
        return OperationResult(
            ok=bool(rows),
            count=len(rows),
            export=payload,
            message="converted" if rows else "no data",
        )

    def append(self, sources) -> OperationResult:
        rows = self._importer.read_converted(tuple(sources))
        if not rows:
            return OperationResult(ok=True, message="no data")
        count = self._repository.append_cashflows(rows)
        self._change_sets.stage()
        by_date = Counter(row.get("date", "")[:10] for row in rows)
        return OperationResult(
            ok=True,
            count=count,
            message="imported",
            details={"by_date": dict(sorted(by_date.items()))},
        )

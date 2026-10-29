"""Export source matching/resolution oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.domain.cash_source_matching import (
    SourceCoverage, SourceOccurrence, SourceRecord, allocate_source_record_id,
    resolve_source_file, _resolve_source_rows as resolve_source_rows,
)

FIELDS = {"amount": "-20.00", "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00",
    "record_type": "consumption", "record_subtype": "not_applicable", "counterparty": "merchant",
    "note": "n", "source_payload": {"a": "b"}}


def coverage(complete=True, key=None, pages=None, total=None):
    return SourceCoverage(start="2026-07-31T16:00:00+00:00", end="2026-08-31T16:00:00+00:00",
        complete=complete, evidence="e" if complete else "", filters={}, start_inclusive=True,
        end_inclusive=False, strategy_version="v", document_key=key, page_total=total,
        page_fingerprints=pages or {})


def occ(position=1, *, account="6222", provider=None, fields=None, file_digest="f" * 64,
        document_position=None, cov=None, source_type="ccb_debit"):
    return SourceOccurrence(source_type, account, file_digest, "bank-table-rows-v1", "sheet:0", position,
        dict(fields or FIELDS), cov or coverage(), provider, None, document_position)


def record(tid, *, fields=None, account="6222", **kwargs):
    kwargs.setdefault("coverages", [coverage()])
    return SourceRecord(tid, "ccb_debit", account, dict(fields or FIELDS), **kwargs)


def resolution(value):
    return {"occurrence_key": value.occurrence_key, "action": value.action,
        "transaction_id": value.transaction_id, "candidate_ids": list(value.candidate_ids),
        "reason": value.reason, "bind": value.bind, "duplicate_of": value.duplicate_of}


def cases():
    row = occ()
    key = row.occurrence_key
    doc_row = occ(document_position="p1:t0:r1", cov=coverage(key="a" * 64, pages={"1": "b" * 64}, total=1))
    doc_conflict = occ(document_position="p1:t0:r1", cov=coverage(key="a" * 64, pages={"1": "c" * 64}, total=1))
    provider_a = occ(1, provider="p1")
    provider_b = occ(2, provider="p1")
    distinct_row = occ(2)
    return [
        ("new", [row], [], {}, None),
        ("duplicate", [row], [record("t1")], {}, None),
        ("match", [row], [record("t1", fields={**FIELDS, "counterparty": "other"})], {}, None),
        ("account-conflict", [row], [record("t1", account="9999", binding_keys={row.file_binding_key})], {}, None),
        ("pending", [row], [record("t1"), record("t2", coverages=[SourceCoverage(start="2026-07-31T16:00:00+00:00",
            end="2026-08-31T16:00:00+00:00", complete=True, evidence="e", filters={"x": "y"},
            start_inclusive=True, end_inclusive=False, strategy_version="v")])], {}, None),
        ("deleted", [row], [record("t1", deleted=True)], {}, None),
        ("targets", [row], [record("t1")], {key: {"t1"}}, None),
        ("batch-duplicate", [provider_a, provider_b], [], {}, None),
        ("distinct-position", [distinct_row], [record("t1", binding_keys={distinct_row.file_binding_key},
            file_occurrences={distinct_row.file_scope_key: set()})], {}, None),
        ("doc-conflict", [doc_row, doc_conflict], [], {}, None),
        ("unchanged-multi", [occ(1), occ(2)], [record("t1"), record("t2")], {}, None),
        ("uniform-extra", [occ(1), occ(2), occ(3)], [record("t1"), record("t2")], {}, None),
    ]


def export(output: Path) -> None:
    result = []
    for name, rows, records, targets, _ in cases():
        try:
            if targets or name == "doc-conflict" or len(rows) != 1:
                values = resolve_source_rows(rows, records, targets_by_occurrence=targets)
            else:
                values = resolve_source_file(rows, records)
            result.append({"name": name, "ok": True,
                "rows": [row_export(row) for row in rows],
                "records": [record_export(record) for record in records],
                "targets": {key: list(value) for key, value in targets.items()},
                "resolutions": [resolution(value) for value in values]})
        except ValueError as failure:
            result.append({"name": name, "ok": False, "error": str(failure)})
    ids = {"provider": allocate_source_record_id(occ(1, provider="p1")),
        "no_provider_prefix": "import-v1:"}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"cases": result, "allocated_ids": ids},
        ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def cov_export(value):
    return {"start": value.start, "end": value.end, "complete": value.complete, "evidence": value.evidence,
        "filters": value.filters, "start_inclusive": value.start_inclusive, "end_inclusive": value.end_inclusive,
        "time_precision": value.time_precision, "strategy_version": value.strategy_version,
        "key": value.document_key, "total": value.page_total, "pages": value.page_fingerprints}


def row_export(value):
    return {"source_type": value.source_type, "account": value.source_account_key, "digest": value.file_digest,
        "parser_version": value.parser_version, "table_key": value.table_key, "position": value.position,
        "fields": value.fields, "coverage": cov_export(value.coverage),
        "provider_id": value.provider_id, "document_position": value.document_position}


def record_export(value):
    return {"transaction_id": value.transaction_id, "source_type": value.source_type,
        "account": value.source_account_key, "fields": value.fields,
        "coverages": [cov_export(cov) for cov in value.coverages],
        "binding_keys": sorted(value.binding_keys), "protected_fields": sorted(value.protected_fields),
        "stable_evidence": value.stable_evidence, "provider_id": value.provider_id,
        "provider_binding_key": value.provider_binding_key,
        "stable_evidence_binding_key": value.stable_evidence_binding_key,
        "deleted": value.deleted,
        "file_occurrences": {key: sorted(items) for key, items in value.file_occurrences.items()}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)

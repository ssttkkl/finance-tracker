"""Export parsed-file source row composition oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.application.cash_import_source_rows import (
    file_document_evidence,
    source_batch_rows,
    source_rows,
)
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_matching import SourceCoverage, SourceDocumentEvidence


def coverage(*, key=None, pages=None, page_total=None, complete=True, start="2026-07-31T16:00:00+00:00",
             end="2026-08-31T16:00:00+00:00"):
    return SourceCoverage(start=start, end=end, complete=complete,
        evidence="icbc-pdf-range-pages-count-v1" if complete else "", filters={"export_type": "all"},
        start_inclusive=True, end_inclusive=False, strategy_version="icbc-pdf-coverage-v1",
        document_key=key, page_total=page_total, page_fingerprints=pages or {})


def document(source="alipay", *, rows=None, cov=None, account=None, currency=None):
    return ParsedCashDocument(source, "csv:0", "platform-document-v1+native-v1",
        tuple(rows or []), account, account, cov or coverage(), len(rows or []), len(rows or []))


def row(position, fields=None, *, skipped=False, provider=None, document_position=None, account=None):
    if skipped:
        return ParsedCashRow(position, None, error_code="row_shape_invalid", disposition="skipped")
    return ParsedCashRow(position, fields, provider_id=provider, source_account_identifier=account,
        document_position=document_position)


def file(ref, doc, *, status="parsed", subset=False, label=None, modified=None):
    return ParsedImportFile(ref, "c", "f", "f" * 64, 10, doc.source_type if doc else None, status,
        document=doc, source_scope_label=label, is_subset=subset, last_modified_at_ms=modified)


def fields(amount="-20.00"):
    return {"amount": amount, "currency": "CNY", "occurred_at": "2026-08-15T02:00:00+00:00",
        "record_type": "consumption", "counterparty": "merchant", "source_payload": {"a": "b"}}


def cases():
    alipay = document(rows=[row(1, fields(), provider="p1"), row(2, skipped=True), row(3, fields("30.00"))],
        account="alipay-owner")
    wechat = document("wechat", rows=[row(1, fields(), provider="wx-1")])
    page1 = document("icbc_debit", rows=[row(1, fields(), account=None)], cov=coverage(
        key="a" * 64, page_total=2, pages={"1": "b" * 64}))
    page2 = document("icbc_debit", rows=[row(1, fields(), account=None)], cov=coverage(
        key="a" * 64, page_total=2, pages={"2": "c" * 64}))
    conflict = document("icbc_debit", rows=[row(1, fields())], cov=coverage(
        key="a" * 64, page_total=1, pages={"1": "c" * 64}))
    return [
        ("single", [file("f1", alipay, modified=100)], {}, []),
        ("resolved", [file("f1", alipay)], {"f1": {3: {"counterparty": "patched", "account_id": 7}}}, []),
        ("subset", [file("f1", alipay, subset=True)], {}, []),
        ("subset-document", [file("f1", page1, subset=True)], {}, []),
        ("wechat-named", [file("f1", wechat, label="  命名 账户 ")], {}, []),
        ("unsupported-label", [file("f1", alipay, label="label")], {}, []),
        ("duplicate-ref", [file("f1", alipay), file("f1", alipay)], {}, []),
        ("two-pages", [file("f1", page1, modified=1), file("f2", page2, modified=2)], {}, []),
        ("page-conflict", [file("f1", page1), file("f2", conflict)], {}, []),
        ("not-parsed", [file("f1", alipay, status="needs_password")], {}, []),
        ("empty-row-account", [file("f1", document(rows=[row(1, fields(), account="")], account="document-owner"))], {}, []),
        ("known-same-namespace", [file("f1", page1)], {}, [SourceDocumentEvidence("icbc_debit", "v", coverage(key="a" * 64, page_total=2, pages={"2": "c" * 64}))]),
        ("known-evidence", [file("f1", alipay)], {}, [SourceDocumentEvidence("icbc_debit", "v",
            coverage(key="c" * 64, page_total=1, pages={"1": "d" * 64}))]),
    ]


def serialize_coverage(value):
    return {"start": value.start, "end": value.end, "complete": value.complete, "evidence": value.evidence,
        "filters": value.filters, "start_inclusive": value.start_inclusive, "end_inclusive": value.end_inclusive,
        "time_precision": value.time_precision, "strategy_version": value.strategy_version,
        "document_key": value.document_key, "page_total": value.page_total,
        "page_fingerprints": value.page_fingerprints}


def serialize_input(files, resolved, known):
    return {"files": [{"file_ref": f.file_ref, "digest": f.digest, "status": f.status,
        "source_scope_label": f.source_scope_label, "is_subset": f.is_subset,
        "last_modified_at_ms": f.last_modified_at_ms,
        "document": None if f.document is None else {
            "source_type": f.document.source_type, "table_key": f.document.table_key,
            "parser_version": f.document.parser_version, "account": f.document.source_account_identifier,
            "label": f.document.source_account_label, "declared": f.document.declared_count,
            "observed": f.document.observed_count, "coverage": serialize_coverage(f.document.coverage),
            "rows": [{"position": r.position, "fields": r.fields, "provider_id": r.provider_id,
                "issue_codes": list(r.issue_codes), "error_code": r.error_code, "disposition": r.disposition,
                "source_account_identifier": r.source_account_identifier,
                "document_position": r.document_position, "source_explanation": r.source_explanation}
                for r in f.document.rows]}}
        for f in files],
        "resolved": resolved,
        "known": [{"source_type": e.source_type, "parser_version": e.parser_version,
            "coverage": serialize_coverage(e.coverage)} for e in known]}


def export(output: Path) -> None:
    result = []
    for name, files, resolved, known in cases():
        direct = name in {"single", "resolved", "subset", "subset-document", "wechat-named", "empty-row-account"}
        entry = {"name": name, "input": serialize_input(files, resolved, known), "direct": direct}
        try:
            if direct:
                rows = {}
                for item in files:
                    for key, value in source_rows(item, resolved_fields=resolved.get(item.file_ref)).items():
                        rows[key] = value
                entry.update(ok=True, direct=True, rows=[row_export(key, value) for key, value in rows.items()])
            else:
                batch = source_batch_rows(files, resolved_fields=resolved, known_document_evidence=known)
                entry.update(ok=True, direct=False, rows=[row_export(key, value) for key, value in batch.items()],
                    evidence=[{"source_type": e.source_type, "parser_version": e.parser_version,
                        "key": e.coverage.document_key, "complete": e.coverage.complete}
                        for e in batch.document_evidence],
                    file_metadata={key: list(value) for key, value in batch.file_metadata.items()})
        except ValueError as failure:
            entry.update(ok=False, error=str(failure))
        result.append(entry)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def row_export(key, value):
    return {"key": key, "position": value.position, "fields": value.fields,
        "source_type": value.source_type, "account_key": value.source_account_key,
        "provider_id": value.provider_id, "document_position": value.document_position,
        "occurrence_key": value.occurrence_key, "file_scope_key": value.file_scope_key,
        "observation_scope_key": value.observation_scope_key, "binding_keys": list(value.binding_keys),
        "coverage": {"complete": value.coverage.complete, "evidence": value.coverage.evidence,
            "document_key": value.coverage.document_key}}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)

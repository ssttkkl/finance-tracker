"""Export source identity, scope and document completeness oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.domain.cash_source_documents import current_document_coverage, document_completeness
from ft.domain.cash_source_matching import (
    SourceBindingKey,
    SourceCoverage,
    SourceDocumentEvidence,
    SourceOccurrence,
    provider_identity_key,
)
from ft.domain.cash_source_scope import source_account_key


def coverage(*, key="a" * 64, pages=None, page_total=1, start="2026-07-31T16:00:00+00:00",
             end="2026-08-31T16:00:00+00:00", complete=True, evidence="e", filters=None,
             start_inclusive=True, end_inclusive=False, time_precision="second",
             strategy_version="source-coverage-v1"):
    return SourceCoverage(start=start, end=end, complete=complete, evidence=evidence,
        filters=filters if filters is not None else {"export_type": "all"}, start_inclusive=start_inclusive,
        end_inclusive=end_inclusive, time_precision=time_precision,
        strategy_version=strategy_version, document_key=key, page_total=page_total,
        page_fingerprints=pages if pages is not None else {"1": "b" * 64})


def serialize_coverage(value):
    return {"start": value.start, "end": value.end, "complete": value.complete, "evidence": value.evidence,
        "filters": value.filters, "start_inclusive": value.start_inclusive, "end_inclusive": value.end_inclusive,
        "time_precision": value.time_precision, "strategy_version": value.strategy_version,
        "document_key": value.document_key, "page_total": value.page_total,
        "page_fingerprints": value.page_fingerprints}


def occurrence_cases():
    cases = []
    base = dict(source_type="ccb_debit", source_account_key="6222", file_digest="f" * 64,
        parser_version="bank-table-rows-v1", table_key="sheet:0", position=1, fields={"amount": "-20.00"})
    return [
        ("base", base),
        ("wechat-provider", {**base, "source_type": "wechat", "source_account_key": "wechat:default:v1", "provider_id": "wx-1"}),
        ("provider-evidence", {**base, "provider_id": "0000123", "stable_evidence": "evidence-1"}),
        ("document", {**base, "coverage": coverage(), "document_position": "p1:t0:r1"}),
        ("document-page-missing", {**base, "coverage": coverage(pages={"2": "b" * 64}, page_total=2), "document_position": "p1:t0:r1"}),
        ("document-no-position", {**base, "coverage": coverage(), "document_position": None}),
        ("document-no-account", {**base, "source_account_key": None, "coverage": coverage(), "document_position": "p1:t0:r1"}),
        ("credit-pages", {**base, "source_type": "icbc_credit", "source_account_key": None, "provider_id": None,
            "coverage": coverage(page_total=2, pages={"1": "b" * 64, "2": "c" * 64}), "document_position": "p2:t1:r2"}),
        ("provider-no-account", {**base, "source_account_key": None, "provider_id": "wx-1"}),
        ("bad-file", {**base, "file_digest": "f" * 65}),
        ("bad-parser", {**base, "parser_version": ""}),
        ("bad-table", {**base, "table_key": "t" * 256}),
        ("bad-account", {**base, "source_account_key": "6" * 257}),
        ("bad-provider", {**base, "provider_id": "p" * 513}),
        ("bad-stable", {**base, "stable_evidence": "e" * 513}),
        ("bad-position", {**base, "position": -1}),
        ("bad-document-position", {**base, "document_position": "p0:t0:r0"}),
    ]


def export(output: Path) -> None:
    occurrences = []
    for name, case in occurrence_cases():
        exported = {key: (serialize_coverage(value) if key == "coverage" else value) for key, value in case.items()}
        try:
            row = SourceOccurrence(**case)
        except ValueError as failure:
            occurrences.append({"name": name, "input": exported, "error": str(failure)})
            continue
        occurrences.append({"name": name, "input": exported, "ok": True, "occurrence_key": row.occurrence_key,
            "file_scope_key": row.file_scope_key, "document_scope_key": row.document_scope_key,
            "document_occurrence_key": row.document_occurrence_key,
            "observation_scope_key": row.observation_scope_key, "document_binding_key": row.document_binding_key,
            "document_page": list(row.document_page) if row.document_page else None,
            "document_page_scope_key": row.document_page_scope_key,
            "file_binding_key": row.file_binding_key, "provider_identity": row.provider_identity,
            "provider_binding_key": row.provider_binding_key,
            "stable_evidence_binding_key": row.stable_evidence_binding_key,
            "binding_keys": list(row.binding_keys),
            "binding_specs": [{"source_type": s.source_type, "scope_kind": s.scope_kind,
                "scope_key": s.scope_key, "identity_kind": s.identity_kind,
                "identity_key": s.identity_key, "key": s.key} for s in row.binding_specs]})

    scopes = []
    for source_type, owner, label in [
        ("wechat", None, None), ("wechat", "ignored", " 命名 账户 "), ("wechat", None, ""),
        ("ccb_debit", "6222", None), ("ccb_debit", None, None), ("ccb_debit", "", None),
        ("ccb_debit", "6" * 256, None), ("ccb_debit", "6" * 257, None),
        ("wechat", None, "bad\x00label"), ("wechat", None, "a" * 65), ("ccb_debit", "6222", "label"),
        ("wechat", None, "   "), ("wechat", None, "e\u0301"), ("wechat", None, "😀" * 64), ("wechat", None, "😀" * 65),
    ]:
        try:
            value = source_account_key(source_type, owner, scope_label=label)
            scopes.append({"source_type": source_type, "owner": owner, "label": label, "value": value})
        except ValueError as failure:
            scopes.append({"source_type": source_type, "owner": owner, "label": label,
                "error": getattr(failure, "code", str(failure))})

    evidence_cases = []
    for name, evidence in [
        ("consistent", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64, "2": "c" * 64}, page_total=2)),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64, "2": "c" * 64}, page_total=2))]),
        ("missing-page", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, page_total=2)),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, page_total=2))]),
        ("fingerprint-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, page_total=1)),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "c" * 64}, page_total=1))]),
        ("declaration-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64})),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, end="2026-09-30T00:00:00+00:00"))]),
        ("unbounded", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64},
            start="2026-08-31T16:00:00+00:00", end="2026-07-31T16:00:00+00:00"))]),
        ("no-key", [SourceDocumentEvidence("ccb_debit", "v", coverage(key=None, pages={}, page_total=None))]),
        ("two-documents", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64})),
            SourceDocumentEvidence("icbc_debit", "v", coverage(key="c" * 64, pages={"1": "d" * 64}))]),
        ("filters-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64})),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, filters={"export_type": "income"}))]),
        ("precision-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64})),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, time_precision="day"))]),
        ("strategy-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64})),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, strategy_version="other"))]),
        ("page-total-conflict", [SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, page_total=1)),
            SourceDocumentEvidence("icbc_debit", "v", coverage(pages={"1": "b" * 64}, page_total=2))]),
    ]:
        completeness = document_completeness(evidence)
        current = [{"namespace": list(item.namespace) if item.namespace[2] else None,
            "complete": current_document_coverage(item, completeness).complete,
            "evidence": current_document_coverage(item, completeness).evidence} for item in evidence]
        evidence_cases.append({"name": name,
            "evidence": [{"source_type": item.source_type, "parser_version": item.parser_version,
                "coverage": serialize_coverage(item.coverage)} for item in evidence],
            "completeness": [[list(k), v] for k, v in completeness.items()], "current": current})

    output.parent.mkdir(parents=True, exist_ok=True)
    comparables = []
    base = coverage()
    for name, left, right, when in [
        ("inside", base, base, "2026-08-15T02:00:00+00:00"),
        ("start-inclusive", base, base, "2026-07-31T16:00:00+00:00"),
        ("end-exclusive", base, base, "2026-08-31T16:00:00+00:00"),
        ("filters", base, coverage(filters={"export_type": "income"}), "2026-08-15T02:00:00+00:00"),
        ("precision", base, coverage(time_precision="day"), "2026-08-15T02:00:00+00:00"),
        ("strategy", base, coverage(strategy_version="other"), "2026-08-15T02:00:00+00:00"),
        ("incomplete", coverage(complete=False), base, "2026-08-15T02:00:00+00:00"),
        ("space-separator", coverage(start="2026-07-31 16:00:00+00:00", end="2026-08-31 16:00:00+00:00"),
            coverage(start="2026-07-31 16:00:00+00:00", end="2026-08-31 16:00:00+00:00"), "2026-08-15 02:00:00+00:00"),
        ("hour-offset", coverage(start="2026-07-31T16:00:00+00", end="2026-08-31T16:00:00+00"),
            coverage(start="2026-07-31T16:00:00+00", end="2026-08-31T16:00:00+00"), "2026-08-15T02:00:00+00"),
        ("offset-seconds", coverage(start="2026-07-31T16:00:00+00:00:30", end="2026-08-31T16:00:00+00:00:30"),
            coverage(start="2026-07-31T16:00:00+00:00:30", end="2026-08-31T16:00:00+00:00:30"), "2026-08-15T02:00:00+00:00:30"),
        ("date-only", coverage(start="2026-07-31", end="2026-08-31"), coverage(start="2026-07-31", end="2026-08-31"),
            "2026-08-15"),
    ]:
        comparables.append({"name": name, "left": serialize_coverage(left), "right": serialize_coverage(right),
            "when": when, "contains": left.contains(when), "comparable": left.comparable(right, when)})

    output.write_text(json.dumps({"occurrences": occurrences, "scopes": scopes, "documents": evidence_cases,
        "comparables": comparables},
        ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)

"""来源页认证按完整声明和命名空间组合，不写回暂存内容。"""
from copy import deepcopy
from dataclasses import replace

import pytest

from ft.adapters.cash_statement_rows import ParsedCashDocument, ParsedCashRow
from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_batch import resolve_source_batch
from ft.domain.cash_source_matching import SourceCoverage


def document_coverage(pages, **changes):
    return SourceCoverage(start="2026-07-01T00:00:00Z", end="2026-08-01T00:00:00Z",
        document_key="d" * 64, page_total=3, page_fingerprints=pages, **changes)


def document_file(coverage, *, rows=(), letter="a", parser="fixture-v1", source="fixture"):
    rows = tuple(replace(row, provider_id=f"alipay-{letter}-{index}") if source == "alipay" and row.provider_id is None else row
        for index, row in enumerate(rows, 1))
    document = ParsedCashDocument(source, "table", parser, rows, "full-owner-account", None,
        coverage, len(rows), len(rows))
    return ParsedImportFile(f"file_{letter}", f"client_{letter}", "synthetic.pdf", letter * 64, 100,
        source, "parsed", document)


def document_row(page=2, *, fields=None, disposition="candidate"):
    return ParsedCashRow(page, fields if fields is not None else {
        "amount": "-12.5", "currency": "CNY", "occurred_at": "2026-07-05T00:00:00Z",
        "counterparty": "合成商户", "note": "", "record_type": "consumption",
        "record_subtype": "not_applicable", "source_payload": {}},
        disposition=disposition, document_position=f"p{page}:t0:r1")


def test_different_parser_namespaces_cannot_create_false_page_conflicts():
    first = document_file(replace(document_coverage({"1": "a" * 64}), page_total=1), rows=(document_row(1),))
    second = document_file(replace(document_coverage({"1": "b" * 64}), page_total=1), rows=(document_row(1),),
        letter="b", parser="another-parser")
    results = resolve_source_batch(source_batch_rows((first, second)), []).rows
    assert not any(result.action == "conflict" for result in results)


def test_known_empty_page_can_complete_current_file_without_mutating_inputs():
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    file = document_file(document_coverage({"2": "b" * 64, "3": "c" * 64}), rows=(document_row(), document_row(3)))
    known = SourceDocumentEvidence("fixture", "fixture-v1", document_coverage({"1": "a" * 64}))
    before = deepcopy((file, known))
    rows = source_batch_rows((file,), known_document_evidence=(known,))
    assert all(row.coverage.complete and row.coverage.contains(row.fields["occurred_at"]) for row in rows.values())
    assert (file, known) == before
    assert len(rows.document_evidence) == 2


@pytest.mark.parametrize("change", [
    {"start": "2026-07-02T00:00:00Z"}, {"end": "2026-08-02T00:00:00Z"},
    {"start_inclusive": False}, {"end_inclusive": True}, {"filters": {"currency": "CNY"}},
    {"time_precision": "day"}, {"strategy_version": "other"}, {"page_total": 4},
])
def test_mismatched_boundary_filter_precision_or_declaration_never_certifies(change):
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    file = document_file(document_coverage({"2": "b" * 64, "3": "c" * 64}), rows=(document_row(),))
    known = SourceDocumentEvidence("fixture", "fixture-v1", replace(document_coverage({"1": "a" * 64}), **change))
    assert not next(iter(source_batch_rows((file,), known_document_evidence=(known,)).values())).coverage.complete


@pytest.mark.parametrize("source,parser", [("other", "fixture-v1"), ("fixture", "other")])
def test_known_pages_from_other_namespaces_cannot_complete_a_file(source, parser):
    from ft.domain.cash_source_matching import SourceDocumentEvidence
    file = document_file(document_coverage({"2": "b" * 64, "3": "c" * 64}), rows=(document_row(),))
    known = SourceDocumentEvidence(source, parser, document_coverage({"1": "a" * 64}))
    assert not next(iter(source_batch_rows((file,), known_document_evidence=(known,)).values())).coverage.complete

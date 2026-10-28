from dataclasses import replace

import pytest

from ft.application.cash_import_source_rows import source_batch_rows
from ft.application.parsed_import_staging import ParsedImportFile
from ft.domain.cash_source_batch import resolve_source_batch
from ft.domain.cash_source_matching import SourceRecord
from test_icbc_pdf_statement import Page, PDF, OWNER, _credit, _parse


def _file(monkeypatch, letter, pages, *, total=3, stamp="2026-09-01 12:00:00", changes=None):
    result = []
    for number in pages:
        page = Page(_credit(**(changes or {}).get(number, {})), number=number, total=total)
        if stamp:
            page.text += "\n下单时间：" + stamp
        result.append(page)
    document = _parse(monkeypatch, result)
    return ParsedImportFile(letter, letter, "fixture.pdf", letter * 64, len(PDF), document.source_type, "parsed", document)


def _plan(*files, records=()):
    rows = source_batch_rows(files)
    return rows, {item.row_ref: item for item in resolve_source_batch(rows, records).rows}


def test_partial_file_has_certified_export_and_page_locations_but_no_complete_range(monkeypatch):
    file = _file(monkeypatch, "a", [2, 3])
    coverage = file.document.coverage
    assert not coverage.complete and coverage.document_key
    assert coverage.page_total == 3 and set(coverage.page_fingerprints) == {"2", "3"}
    assert [row.document_position for row in file.document.rows] == ["p2:t0:r1", "p3:t0:r1"]


def test_disjoint_parts_sum_true_occurrences_and_duplicate_pages_are_not_added(monkeypatch):
    first, last = _file(monkeypatch, "a", [1, 2]), _file(monkeypatch, "b", [3])
    repeated = replace(last, file_ref="retry", client_file_ref="retry", digest="c" * 64)
    rows, plan = _plan(first, last, repeated)
    assert all(row.coverage.complete for row in rows.values())
    assert sum(item.action == "new" for item in plan.values()) == 3
    assert sum(item.action == "batch_duplicate" for item in plan.values()) == 1
    assert not first.document.coverage.complete and not last.document.coverage.complete


def test_whole_export_and_parts_are_the_same_three_occurrences(monkeypatch):
    whole = _file(monkeypatch, "a", [1, 2, 3])
    first, last = _file(monkeypatch, "b", [1, 2]), _file(monkeypatch, "c", [3])
    rows, plan = _plan(first, whole, last)
    assert sum(item.action == "new" for item in plan.values()) == 3
    assert sum(item.action == "batch_duplicate" for item in plan.values()) == 3
    assert len({row.document_occurrence_key for row in rows.values()}) == 3


def test_different_exports_still_use_maximum_multiplicity(monkeypatch):
    first, last = _file(monkeypatch, "a", [1, 2]), _file(monkeypatch, "b", [3])
    newer = _file(monkeypatch, "c", [1, 2, 3], stamp="2026-09-02 12:00:00")
    rows, plan = _plan(first, last, newer)
    assert len({row.observation_scope_key for row in rows.values()}) == 2
    assert sum(item.action == "new" for item in plan.values()) == 3
    assert sum(item.action == "batch_duplicate" for item in plan.values()) == 3


def test_missing_pages_remain_unknown_but_do_not_discard_real_occurrences(monkeypatch):
    first, last = _file(monkeypatch, "a", [1]), _file(monkeypatch, "b", [3])
    rows, plan = _plan(first, last)
    assert all(not row.coverage.complete for row in rows.values())
    assert all(item.action == "new" for item in plan.values())


def test_conflicting_page_isolated_without_two_formal_versions(monkeypatch):
    first = _file(monkeypatch, "a", [1, 2])
    corrupted = _file(monkeypatch, "b", [2, 3], changes={2: {"交易金额": "99.00"}})
    rows, plan = _plan(first, corrupted)
    assert all(not row.coverage.complete for row in rows.values())
    assert [item.action for item in plan.values()] == ["new", "conflict", "conflict", "new"]
    assert {item.reason for item in plan.values() if item.action == "conflict"} == {"batch_identity_conflict"}


def test_conflicting_page_and_a_different_export_do_not_crash_the_batch(monkeypatch):
    first = _file(monkeypatch, "a", [1, 2])
    corrupted = _file(monkeypatch, "b", [2, 3], changes={2: {"交易金额": "99.00"}})
    newer = _file(monkeypatch, "c", [1, 2, 3], stamp="2026-09-02 12:00:00")
    _, plan = _plan(first, corrupted, newer)
    assert all(item.action == "conflict" for ref, item in plan.items() if ref in {"a:r4", "b:r2"})
    assert all(item.action == "pending" for ref, item in plan.items() if ref.startswith("c:"))


def test_zero_row_page_also_participates_in_conflict_detection(monkeypatch):
    nonempty = _file(monkeypatch, "a", [1], total=1)
    page = Page(number=1, total=1)
    page.text += "\n下单时间：2026-09-01 12:00:00"
    document = _parse(monkeypatch, [page])
    empty = replace(nonempty, file_ref="b", client_file_ref="b", digest="b" * 64, document=document)
    assert not document.rows and document.coverage.document_key
    rows, plan = _plan(nonempty, empty)
    assert not rows["a:r2"].coverage.complete
    assert plan["a:r2"].action == "conflict"


@pytest.mark.parametrize("changes", [
    {"document_key": "bad"}, {"page_total": True}, {"page_total": 2001},
    {"page_fingerprints": {"0": "e" * 64}}, {"page_fingerprints": {"4": "e" * 64}},
    {"page_fingerprints": {"2": "not-a-digest"}}, {"page_fingerprints": {2: "e" * 64}},
])
def test_document_evidence_is_bounded_and_has_canonical_page_keys(changes):
    from ft.domain.cash_source_matching import SourceCoverage
    with pytest.raises(ValueError, match="invalid_source_document_evidence"):
        SourceCoverage(**{"document_key": "f" * 64, "page_total": 3,
            "page_fingerprints": {"2": "e" * 64}, **changes})


def test_page_alias_recovers_existing_record_after_repartitioning(monkeypatch):
    first = _file(monkeypatch, "a", [2])
    original = next(iter(source_batch_rows([first]).values()))
    old = SourceRecord("existing", original.source_type, original.source_account_key, original.fields,
                       coverages=(original.coverage,), binding_keys=frozenset(original.binding_keys),
                       file_occurrences={original.document_scope_key: frozenset({original.document_occurrence_key})})
    changed_partition = _file(monkeypatch, "b", [2, 3])
    _, plan = _plan(changed_partition, records=[old])
    assert plan["b:r2"].action == "duplicate" and plan["b:r2"].transaction_id == "existing"
    # 同一导出内的另一个页行位置已能排除同笔，不要求完整时间范围。
    assert plan["b:r4"].action == "new"


@pytest.mark.parametrize("stamp", [None, "invalid", "2026-99-01 12:00:00"])
def test_missing_or_invalid_export_stamp_cannot_join_fragments(monkeypatch, stamp):
    file = _file(monkeypatch, "a", [1], stamp=stamp)
    assert file.document.coverage.document_key is None


@pytest.mark.parametrize("changes", [{"交易金额": "bad"}, {"入账日期": "2026-07-01\n10:00:00"}])
def test_invalid_or_out_of_range_row_cannot_certify_a_fragment(monkeypatch, changes):
    file = _file(monkeypatch, "a", [1], changes={1: changes})
    assert not file.document.coverage.complete and file.document.coverage.document_key is None


@pytest.mark.parametrize("pages", [[2, 1], [1, 3], [1, 1], [4]])
def test_noncontiguous_or_invalid_declared_pages_cannot_certify_fragments(monkeypatch, pages):
    assert _file(monkeypatch, "a", pages).document.coverage.document_key is None


def test_file_header_owner_is_required_and_does_not_replace_row_funding_owner(monkeypatch):
    page = Page(_credit(), number=1, total=2)
    page.text = page.text.replace(OWNER, "6212****1234") + "\n下单时间：2026-09-01 12:00:00"
    document = _parse(monkeypatch, [page])
    assert document.coverage.document_key is None
    assert document.rows[0].source_account_identifier == "6222000000001234"


def test_same_page_in_other_export_is_not_a_global_transaction_identity(monkeypatch):
    older = _file(monkeypatch, "a", [1], total=1)
    newer = _file(monkeypatch, "b", [1], total=1, stamp="2026-09-02 12:00:00")
    left, right = [next(iter(source_batch_rows([file]).values())) for file in (older, newer)]
    assert left.document_occurrence_key != right.document_occurrence_key
    assert left.source_account_key == right.source_account_key


@pytest.mark.parametrize("changes", [{"对方户名": "新的对方名称"}, {"交易金额": "21.00"}])
def test_saved_page_fingerprint_conflict_is_not_treated_as_update_or_extra_row(monkeypatch, changes):
    from ft.domain.cash_source_matching import resolve_source_file
    first = _file(monkeypatch, "a", [1], total=1)
    original = next(iter(source_batch_rows([first]).values()))
    old = SourceRecord("existing", original.source_type, original.source_account_key, original.fields,
        coverages=(original.coverage,), binding_keys=frozenset(original.binding_keys))
    changed = _file(monkeypatch, "b", [1], total=1, changes={1: changes})
    incoming = next(iter(source_batch_rows([changed]).values()))
    assert resolve_source_file([incoming], [old])[0].action == "conflict"
    _, plan = _plan(changed, records=[old])
    assert plan["b:r2"].action == "conflict"

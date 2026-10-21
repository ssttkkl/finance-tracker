"""同一原文件的不同位置不能在失败子集中被弱对应吞掉。"""
from dataclasses import replace

from ft.domain.cash_source_matching import SourceCoverage, resolve_source_file
from test_cash_source_matching import _row, _old


def test_other_position_of_same_original_file_proves_a_distinct_unnumbered_occurrence():
    first = _row(1)
    existing = _old(binding_keys=frozenset(first.binding_keys),
        file_occurrences={first.file_scope_key: frozenset((first.occurrence_key,))})
    remaining = replace(_row(2), coverage=SourceCoverage())
    result, = resolve_source_file((remaining,), (existing,))
    assert result.action == "new" and result.bind


def test_same_provider_identity_still_matches_when_export_repeats_it_at_another_position():
    first = _row(1, provider_id="synthetic-transaction")
    existing = _old(binding_keys=frozenset(first.binding_keys), provider_binding_key=first.provider_binding_key,
        file_occurrences={first.file_scope_key: frozenset((first.occurrence_key,))})
    remaining = replace(_row(2, provider_id="synthetic-transaction"), coverage=SourceCoverage())
    result, = resolve_source_file((remaining,), (existing,))
    assert result.action == "duplicate" and result.transaction_id == existing.transaction_id


def test_other_file_or_parser_position_cannot_prove_distinctness():
    first = _row(1)
    existing = _old(binding_keys=frozenset(first.binding_keys),
        file_occurrences={first.file_scope_key: frozenset((first.occurrence_key,))})
    for remaining in (replace(_row(2), file_digest="b" * 64, coverage=SourceCoverage()),
        replace(_row(2), parser_version="other-parser", coverage=SourceCoverage())):
        result, = resolve_source_file((remaining,), (existing,))
        assert result.action == "pending" and not result.bind


def test_new_provider_number_cannot_override_distinct_position_without_existing_same_number():
    first = _row(1)
    existing = _old(binding_keys=frozenset(first.binding_keys),
        file_occurrences={first.file_scope_key: frozenset((first.occurrence_key,))})
    remaining = replace(_row(2, provider_id="synthetic-new-number"), coverage=SourceCoverage())
    result, = resolve_source_file((remaining,), (existing,))
    assert result.action == "new" and result.bind

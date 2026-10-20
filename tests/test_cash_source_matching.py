from dataclasses import replace
from datetime import datetime, timezone

import pytest


def _coverage(**changes):
    from ft.domain.cash_source_matching import SourceCoverage
    return SourceCoverage(
        start="2026-08-01T00:00:00Z", end="2026-09-01T00:00:00Z",
        complete=True, evidence="statement-declared-count-and-range", **changes,
    )


def _row(position=1, name="甲", **changes):
    from ft.domain.cash_source_matching import SourceOccurrence
    fields = {
        "amount": "-20.00", "currency": "CNY", "account_id": "101",
        "occurred_at": "2026-08-15T00:00:00Z", "counterparty": name,
        "note": "", "category_id": None,
        "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": {"名称": name},
    }
    fields.update(changes.pop("fields", {}))
    return SourceOccurrence(
        source_type="fixture-bank", source_account_key="owner-full-account",
        file_digest="a" * 64, parser_version="fixture-v1", table_key="sheet-1",
        position=position, fields=fields, coverage=_coverage(), **changes,
    )


def _old(transaction_id="1", name="甲", **changes):
    from ft.domain.cash_source_matching import SourceRecord
    row = _row(name=name)
    values = {"state": "posted", **row.fields}
    values.update(changes.pop("fields", {}))
    return SourceRecord(
        transaction_id=transaction_id, source_type=row.source_type,
        source_account_key=row.source_account_key, fields=values,
        coverages=(row.coverage,), **changes,
    )


def _resolve(rows, old):
    from ft.domain.cash_source_matching import resolve_source_file
    return resolve_source_file(rows, old)


def test_first_import_preserves_identical_real_occurrences():
    results = _resolve([_row(1), _row(2)], [])
    assert [r.action for r in results] == ["new", "new"]
    assert results[0].occurrence_key != results[1].occurrence_key


def test_unchanged_complete_multiset_is_duplicate_without_arbitrary_bindings():
    rows = [_row(1, "乙"), _row(2, "甲")]
    old = [_old("1", "甲"), _old("2", "乙")]
    results = _resolve(rows, old)
    assert [r.action for r in results] == ["duplicate", "duplicate"]
    assert all(r.transaction_id is None and not r.bind for r in results)


def test_unique_stable_group_updates_name_without_identity_question():
    result, = _resolve([_row(name="新名称")], [_old()])
    assert result.action == "match" and result.transaction_id == "1" and result.bind


def test_mutable_crossed_names_cannot_manufacture_a_unique_remainder():
    results = _resolve([_row(1, "乙"), _row(2, "丙")], [_old("1", "甲"), _old("2", "乙")])
    assert all(r.action == "pending" and r.candidate_ids == ("1", "2") for r in results)


def test_uniform_true_multiplicity_increase_adds_only_the_excess():
    results = _resolve([_row(1), _row(2), _row(3)], [_old("1"), _old("2")])
    assert [r.action for r in results] == ["duplicate", "duplicate", "new"]
    assert all(not r.bind for r in results[:2])


def test_nonuniform_multiplicity_increase_does_not_guess_which_is_new():
    results = _resolve([_row(1, "甲"), _row(2, "乙"), _row(3, "丙")], [_old("1", "甲"), _old("2", "乙")])
    assert all(r.action == "pending" for r in results)


@pytest.mark.parametrize("difference", ["unknown", "filter", "bounds", "precision"])
def test_unique_candidate_is_insufficient_without_comparable_coverage(difference):
    from ft.domain.cash_source_matching import SourceCoverage
    coverage = {
        "unknown": SourceCoverage(),
        "filter": replace(_coverage(), filters={"merchant": "乙"}),
        "bounds": replace(_coverage(), start_inclusive=False, start="2026-08-15T00:00:00Z"),
        "precision": replace(_coverage(), time_precision="day"),
    }[difference]
    row = replace(_row(name="乙"), coverage=coverage)
    result, = _resolve([row], [_old()])
    assert result.action == "pending" and not result.bind


def test_unknown_range_without_old_collision_is_normally_new():
    from ft.domain.cash_source_matching import SourceCoverage
    assert _resolve([replace(_row(), coverage=SourceCoverage())], [])[0].action == "new"


def test_overlapping_declared_ranges_can_match_in_their_shared_interval():
    old = replace(_old(), coverages=(replace(_coverage(), start="2026-08-10T00:00:00Z", end="2026-10-01T00:00:00Z"),))
    assert _resolve([_row()], [old])[0].action == "duplicate"


def test_full_account_identity_isolation_ignores_same_tail_and_internal_mapping():
    old = replace(_old(), source_account_key="different-owner-same-tail")
    assert _resolve([_row()], [old])[0].action == "new"


def test_unknown_source_owner_never_claims_cross_file_identity():
    row = replace(_row(), source_account_key=None)
    old = replace(_old(), source_account_key=None)
    result, = _resolve([row], [old])
    assert result.action == "pending" and not result.bind


def test_unknown_source_owner_colliding_with_known_owner_is_pending_not_another_formal_record():
    row = replace(_row(), source_account_key=None)
    result, = _resolve([row], [_old()])
    assert result.action == "pending" and result.candidate_ids == ("1",) and not result.bind


def test_same_file_binding_reuses_unknown_owner_draft():
    row = replace(_row(), source_account_key=None)
    old = replace(_old(), source_account_key=None, fields={"state": "draft", **row.fields}, binding_keys=frozenset(row.binding_keys))
    result, = _resolve([row], [old])
    assert result.action == "duplicate" and result.transaction_id == "1"


def test_parser_namespace_change_does_not_reuse_old_file_position():
    from ft.domain.cash_source_matching import SourceCoverage
    row = replace(_row(), source_account_key=None, coverage=SourceCoverage())
    old = replace(_old(), source_account_key=None, binding_keys=frozenset(row.binding_keys))
    changed = replace(row, parser_version="fixture-v2")
    result, = _resolve([changed], [old])
    assert result.action == "pending" and result.transaction_id is None and not result.bind


def test_verified_provider_id_matches_even_without_coverage_and_rejects_amount_change():
    from ft.domain.cash_source_matching import SourceCoverage
    row = replace(_row(provider_id="provider-123"), coverage=SourceCoverage())
    old = replace(_old(), binding_keys=frozenset(row.binding_keys))
    assert _resolve([row], [old])[0].transaction_id == "1"
    changed = replace(row, fields={**row.fields, "amount": "-21", "counterparty": "新"})
    result, = _resolve([changed], [old])
    assert result.action == "conflict" and result.reason == "fixed_field_conflict"
    assert not result.bind


def test_same_source_fingerprint_does_not_ignore_manual_mutable_changes():
    old = _old(fields={"counterparty": "用户曾修改"})
    assert _resolve([_row()], [old])[0].action == "match"


def test_manual_note_and_category_protection_in_identity_comparison():
    old = _old(fields={"note": "", "category_id": "user-cat"}, protected_fields=frozenset({"note", "category_id"}))
    row = replace(_row(), fields={**_row().fields, "note": "来源备注"})
    assert _resolve([row], [old])[0].action == "duplicate"


def test_entire_set_is_not_duplicate_when_manual_mutable_fields_need_updates():
    results = _resolve([_row(1, "甲"), _row(2, "乙")], [
        _old("1", "甲", fields={"counterparty": "用户修改"}), _old("2", "乙"),
    ])
    assert all(r.action == "pending" for r in results)


def test_deleted_target_is_ignored_and_live_replacement_wins():
    row = _row(provider_id="123")
    deleted = _old("1", deleted=True, binding_keys=frozenset(row.binding_keys))
    assert _resolve([row], [deleted])[0].action == "new"
    replacement = _old("2", binding_keys=frozenset(row.binding_keys))
    assert _resolve([row], [deleted, replacement])[0].transaction_id == "2"


def test_conflicting_provider_occurrences_all_fail_instead_of_first_wins():
    rows = [_row(1, "甲", provider_id="123"), _row(2, "乙", provider_id="123")]
    results = _resolve(rows, [])
    assert all(r.action == "conflict" and r.reason == "batch_identity_conflict" for r in results)


def test_identical_provider_occurrences_are_batch_duplicates():
    rows = [_row(1, provider_id="123"), _row(2, provider_id="123")]
    results = _resolve(rows, [])
    assert [r.action for r in results] == ["new", "batch_duplicate"]
    assert results[1].duplicate_of == rows[0].occurrence_key


def test_extra_occurrence_cannot_consume_an_already_file_bound_transaction():
    first = _row(1)
    old = _old(binding_keys=frozenset(first.binding_keys))
    results = _resolve([first, _row(2)], [old])
    assert [r.action for r in results] == ["duplicate", "new"]


def test_unknown_owner_extra_occurrence_stays_pending_after_known_match_consumed_candidate():
    first = _row(1)
    second = replace(_row(2), source_account_key=None)
    old = _old(binding_keys=frozenset(first.binding_keys))
    results = _resolve([first, second], [old])
    assert [r.action for r in results] == ["duplicate", "pending"]
    assert not results[1].bind


def test_verified_row_evidence_allows_unknown_range_but_names_never_do():
    from ft.domain.cash_source_matching import SourceCoverage
    row = replace(_row(name="新名称", stable_evidence="certified-bank-sequence:123"), coverage=SourceCoverage())
    old = _old(stable_evidence="certified-bank-sequence:123")
    assert _resolve([row], [old])[0].action == "match"


def test_verified_evidence_disambiguates_colliding_financial_indexes_as_a_group():
    from ft.domain.cash_source_matching import SourceCoverage
    rows = [replace(_row(i, name, stable_evidence=f"verified:{i}"), coverage=SourceCoverage()) for i, name in ((1, "新甲"), (2, "新乙"))]
    old = [_old(str(i), name, stable_evidence=f"verified:{i}") for i, name in ((1, "甲"), (2, "乙"))]
    results = _resolve(rows, old)
    assert [r.transaction_id for r in results] == ["1", "2"]
    assert all(r.action == "match" for r in results)


def test_verified_row_evidence_detects_a_fixed_field_conflict():
    row = _row(stable_evidence="verified:1", fields={"amount": "-21"})
    result, = _resolve([row], [_old(stable_evidence="verified:1")])
    assert result.action == "conflict"


def test_distinct_reliable_provider_ids_are_distinct_despite_financial_collision():
    row = _row(provider_id="new-id")
    old = _old(provider_id="old-id")
    assert _resolve([row], [old])[0].action == "new"


def test_known_provider_mismatch_cannot_be_hidden_by_anonymous_candidates():
    row = _row(provider_id="B")
    old = [_old("1", "甲", provider_id="A"), _old("2", "乙")]
    result, = _resolve([row], old)
    assert result.action == "match" and result.transaction_id == "2"


def test_different_verified_row_evidence_excludes_a_weak_candidate():
    row = _row(stable_evidence="verified:B")
    assert _resolve([row], [_old(stable_evidence="verified:A")])[0].action == "new"


def test_file_alias_does_not_allow_changing_a_known_source_account():
    row = _row()
    old = _old(binding_keys=frozenset(row.binding_keys))
    changed = replace(row, source_account_key="another-known-owner")
    result, = _resolve([changed], [old])
    assert result.action == "conflict" and result.reason == "source_identity_conflict"


def test_one_file_is_required_so_occurrences_are_consumed_per_export():
    with pytest.raises(ValueError, match="one_source_file_required"):
        _resolve([_row(1), replace(_row(2), file_digest="b" * 64)], [])


def test_scope_and_identity_keys_are_canonical_and_do_not_expose_accounts():
    row = _row(provider_id="provider-secret-id")
    for key in row.binding_keys:
        assert "owner-full-account" not in key and "provider-secret-id" not in key
    changed = replace(row, source_account_key="other-owner")
    assert row.provider_binding_key != changed.provider_binding_key
    assert row.file_binding_key == changed.file_binding_key


def test_missing_financial_index_uses_binding_only_not_empty_candidate_collision():
    row = replace(_row(), fields={**_row().fields, "amount": None})
    old = _old(fields={"amount": None, "state": "draft"})
    assert _resolve([row], [old])[0].action == "new"


def test_invalid_business_field_isolated_from_other_occurrences():
    bad = _row(1, fields={"currency": []})
    good = _row(2)
    results = _resolve([bad, good], [])
    assert [r.action for r in results] == ["conflict", "new"]
    assert results[0].reason == "invalid_field" and not results[0].bind


def test_legacy_posted_record_without_provable_owner_blocks_automatic_duplicate_creation():
    old = replace(_old(), source_account_key=None)
    result, = _resolve([_row()], [old])
    assert result.action == "pending" and result.candidate_ids == ("1",)


def test_conflicting_verified_evidence_occurrences_do_not_partially_update_a_target():
    rows = [_row(1, "甲", stable_evidence="verified:1"), _row(2, "乙", stable_evidence="verified:1")]
    assert all(r.action == "conflict" for r in _resolve(rows, [_old(stable_evidence="verified:1")]))


@pytest.mark.parametrize("count", [2, 3])
def test_result_equivalent_uniform_updates_are_automatic(count):
    rows = [_row(index, "共同新名称") for index in range(count)]
    old = [_old("1", "甲"), _old("2", "乙")]
    results = _resolve(rows, old)
    assert [r.action for r in results] == ["match", "match"] + (["new"] if count == 3 else [])
    assert {r.transaction_id for r in results if r.action == "match"} == {"1", "2"}


def test_synthetic_identity_is_allocated_once_and_never_a_mutable_content_hash():
    from ft.domain.cash_source_matching import allocate_source_record_id
    row = _row()
    assert allocate_source_record_id(row) != allocate_source_record_id(row)
    provider = replace(row, provider_id="provider-1")
    assert allocate_source_record_id(provider) == allocate_source_record_id(replace(provider, file_digest="b" * 64, fields={**provider.fields, "counterparty": "新名称"}))
    assert allocate_source_record_id(provider) != allocate_source_record_id(replace(provider, source_account_key="other-owner"))


@pytest.mark.parametrize("second_provider", [None, "provider-B"])
def test_all_certified_identities_participate_in_batch_conflict_group(second_provider):
    rows = [_row(1, "甲", provider_id="provider-A", stable_evidence="verified:S"),
            _row(2, "乙", provider_id=second_provider, stable_evidence="verified:S")]
    assert all(r.action == "conflict" for r in _resolve(rows, [_old(stable_evidence="verified:S")]))


def test_batch_identity_conflict_propagates_through_shared_certified_keys():
    rows = [_row(1, "甲", provider_id="provider-A"),
            _row(2, "甲", provider_id="provider-A", stable_evidence="verified:S"),
            _row(3, "乙", provider_id="provider-B", stable_evidence="verified:S")]
    assert all(r.action == "conflict" for r in _resolve(rows, []))


def test_known_provider_and_evidence_aliases_join_batch_conflict_scope():
    provider = _row(1, "甲", provider_id="provider-A")
    evidence = _row(2, "乙", stable_evidence="verified:S")
    old = _old(provider_id="provider-A", stable_evidence="verified:S")
    assert all(r.action == "conflict" for r in _resolve([provider, evidence], [old]))


def test_unchanged_assignment_respects_different_manual_protection_without_greedy_binding():
    rows = [_row(1, fields={"note": "甲"}), _row(2, fields={"note": "乙"})]
    old = [_old("1", protected_fields=frozenset({"note"})), _old("2", fields={"note": "甲"})]
    results = _resolve(rows, old)
    assert all(r.action == "duplicate" and not r.bind for r in results)


def test_failed_direct_update_does_not_consume_a_live_candidate_for_other_rows():
    first = _row(1, provider_id="provider-1", fields={"amount": "-21"})
    second = _row(2)
    results = _resolve([first, second], [_old(provider_id="provider-1")])
    assert [r.action for r in results] == ["conflict", "duplicate"]
    assert results[1].transaction_id == "1"


@pytest.mark.performance
def test_large_unchanged_collision_set_is_processed_within_preview_budget():
    from time import perf_counter
    rows = [_row(index, f"name-{index}") for index in range(1000)]
    old = [_old(str(index), f"name-{index}") for index in range(1000)]
    started = perf_counter()
    results = _resolve(rows, old)
    elapsed = perf_counter() - started
    assert all(result.action == "duplicate" and not result.bind for result in results)
    assert elapsed < 2.0

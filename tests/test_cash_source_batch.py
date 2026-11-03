from copy import deepcopy
from dataclasses import asdict, replace
from itertools import permutations
import json
from time import perf_counter

import pytest

from test_cash_source_matching import _coverage, _old, _row


def _file(letter, rows):
    return {f"{letter}:r{row.position}": replace(row, file_digest=letter * 64) for row in rows}


class _Results(dict):
    def __init__(self, plan):
        super().__init__((row.row_ref, row) for row in plan.rows)
        self.plan = plan
        self.groups = {group.group_ref: group for group in plan.groups}


def _batch(rows, records=(), file_modified_at_ms=None):
    from ft.domain.cash_source_batch import SourceBatchInput, resolve_source_batch
    metadata = {}
    for ref in rows:
        file_ref = ref.split(":", 1)[0]
        metadata.setdefault(file_ref, (len(metadata), (file_modified_at_ms or {}).get(file_ref)))
    batch = SourceBatchInput(rows)
    batch.file_metadata = metadata
    plan = resolve_source_batch(batch, records)
    assert [row.row_ref for row in plan.rows] == list(rows)
    return _Results(plan)


def _equivalent_rows(result, results):
    return results.groups[result.group_ref].row_refs if result.group_ref else ()


def _writes(results):
    return [row for row in results.values() if row.action in {"new", "match"}]


def test_reuploading_the_same_file_in_one_batch_preserves_two_occurrences_not_four():
    first = _file("a", [_row(1), _row(2)])
    rows = {**first, **{key.replace("a:", "retry:"): row for key, row in first.items()}}
    results = _batch(rows)
    assert len(_writes(results)) == 2
    for position in (1, 2):
        duplicate = results[f"retry:r{position}"]
        assert duplicate.action == "batch_duplicate"
        assert duplicate.duplicate_of == f"a:r{position}" and duplicate.bind


def test_overlapping_uniform_exports_use_maximum_multiplicity():
    rows = {**_file("a", [_row(1), _row(2)]), **_file("b", [_row(1), _row(2), _row(3)])}
    results = _batch(rows)
    assert len(_writes(results)) == 3
    assert sum(row.action == "batch_duplicate" for row in results.values()) == 2
    assert all(not row.bind and len(_equivalent_rows(row, results)) == 3 for row in results.values() if row.action == "batch_duplicate")


def test_overlapping_exports_add_only_the_common_excess_over_existing_records():
    rows = {**_file("a", [_row(1), _row(2)]), **_file("b", [_row(1), _row(2), _row(3)])}
    results = _batch(rows, [_old("1"), _old("2")])
    assert [row.action for row in _writes(results)] == ["new"]
    assert all(not row.bind for row in results.values() if row.action in {"duplicate", "batch_duplicate"})


def test_reordered_nonuniform_equal_sets_are_automatic_without_individual_old_bindings():
    rows = {**_file("a", [_row(1, "甲"), _row(2, "乙")]), **_file("b", [_row(1, "乙"), _row(2, "甲")])}
    for old in ([], [_old("1", "乙"), _old("2", "甲")]):
        results = _batch(rows, old)
        assert len(_writes(results)) == (0 if old else 2)
        assert not any(row.action in {"pending", "conflict"} for row in results.values())
        if old:
            assert all(not row.bind and row.transaction_id is None for row in results.values())


@pytest.mark.parametrize("old", [[], [_old("1", "甲"), _old("2", "乙")]])
def test_crossed_names_defer_only_the_ambiguous_set_without_greedy_remainders(old):
    rows = {**_file("a", [_row(1, "甲"), _row(2, "乙")]), **_file("b", [_row(1, "乙"), _row(2, "丙")]),
            **_file("c", [_row(1, fields={"amount": "-99"})])}
    results = _batch(rows, old)
    assert all(results[key].action == "pending" for key in rows if not key.startswith("c:"))
    assert results["c:r1"].action == "new"


def test_uniform_updates_run_once_per_existing_target_across_files():
    rows = {**_file("a", [_row(1, "新"), _row(2, "新")]), **_file("b", [_row(1, "新"), _row(2, "新")])}
    results = _batch(rows, [_old("1", "甲"), _old("2", "乙")])
    assert {row.transaction_id for row in _writes(results)} == {"1", "2"}
    assert len(_writes(results)) == 2


def test_one_weak_unique_row_in_each_export_can_share_a_proven_correspondence():
    rows = {**_file("a", [_row()]), **_file("b", [_row()])}
    results = _batch(rows, [_old()])
    assert results["a:r1"].action == "duplicate" and results["a:r1"].transaction_id == "1"
    assert results["b:r1"].action == "batch_duplicate" and results["b:r1"].duplicate_of == "a:r1"
    assert results["b:r1"].bind


@pytest.mark.parametrize("unknown", ["owner", "coverage", "filters", "precision"])
def test_cross_file_collisions_without_comparable_scope_are_pending_not_two_formal_groups(unknown):
    from ft.domain.cash_source_matching import SourceCoverage
    first, second = _row(), _row()
    if unknown == "owner":
        first = replace(first, source_account_key=None)
        second = replace(second, source_account_key=None)
    else:
        second = replace(second, coverage={
            "coverage": SourceCoverage(), "filters": _coverage(filters={"kind": "expense"}),
            "precision": _coverage(time_precision="day"),
        }[unknown])
    results = _batch({**_file("a", [first]), **_file("b", [second])})
    assert all(row.action == "pending" and not row.bind for row in results.values())


def test_unknown_owner_crossing_a_known_owner_is_not_another_formal_record():
    rows = {**_file("a", [_row()]), **_file("b", [replace(_row(), source_account_key=None)])}
    results = _batch(rows)
    assert results["a:r1"].action == "new"
    assert results["b:r1"].action == "pending" and _equivalent_rows(results["b:r1"], results) == ("a:r1",)


def test_distinct_known_owners_are_independent_despite_overlapping_dates_and_internal_accounts():
    rows = {**_file("a", [_row()]), **_file("b", [replace(_row(), source_account_key="other-owner")])}
    assert len(_writes(_batch(rows))) == 2


def test_reliable_id_needs_no_range_and_shares_one_operation_across_files():
    from ft.domain.cash_source_matching import SourceCoverage
    row = replace(_row(provider_id="provider-A"), coverage=SourceCoverage())
    results = _batch({**_file("a", [row]), **_file("b", [row])})
    assert len(_writes(results)) == 1 and results["b:r1"].bind
    assert results["b:r1"].duplicate_of == "a:r1"


def test_known_aliases_share_one_pending_record_when_old_correspondence_is_unknown():
    row = _row(provider_id="provider-A")
    old = replace(_old(), source_account_key=None)
    results = _batch({**_file("a", [row]), **_file("b", [row])}, [old])
    assert results["a:r1"].action == "pending"
    assert results["b:r1"].duplicate_of == "a:r1"
    assert results["b:r1"].candidate_ids == results["a:r1"].candidate_ids


@pytest.mark.parametrize("fields", [{"counterparty": "更新"}, {"source_payload": {"状态": "交易成功"}}])
def test_reliable_identity_selects_the_newest_snapshot_as_the_only_batch_operation(fields):
    rows = {**_file("a", [_row(provider_id="A")]),
            **_file("b", [_row(provider_id="A", fields=fields)]),
            **_file("c", [_row(provider_id="C")])}
    results = _batch(rows, file_modified_at_ms={"a": 100, "b": 200})
    assert results["b:r1"].action == "new"
    assert results["a:r1"].action == "batch_duplicate" and results["a:r1"].duplicate_of == "b:r1"
    assert results["c:r1"].action == "new"


@pytest.mark.parametrize("timestamps", [{"a": 100, "b": 100}, {"a": 200}, {}])
def test_snapshot_timestamp_tie_or_missing_time_falls_back_to_later_selected_file(timestamps):
    rows = {**_file("a", [_row(provider_id="A")]), **_file("b", [_row(provider_id="A", name="更新")])}
    results = _batch(rows, file_modified_at_ms=timestamps)
    assert results["b:r1"].action == "new"
    assert results["a:r1"].action == "batch_duplicate" and results["a:r1"].duplicate_of == "b:r1"


def test_distinct_modification_times_override_file_selection_order():
    rows = {**_file("a", [_row(provider_id="A", name="较新")]),
            **_file("b", [_row(provider_id="A", name="较旧")])}
    results = _batch(rows, file_modified_at_ms={"a": 200, "b": 100})
    assert results["a:r1"].action == "new"
    assert results["b:r1"].action == "batch_duplicate" and results["b:r1"].duplicate_of == "a:r1"


def test_selected_snapshot_still_uses_existing_fixed_financial_conflict_rule():
    rows = {**_file("a", [_row(provider_id="A")]),
            **_file("b", [_row(provider_id="A", fields={"amount": "-21"})])}
    old = _old(provider_id="A")
    results = _batch(rows, [old], file_modified_at_ms={"a": 100, "b": 200})
    assert results["b:r1"].action == "conflict" and results["b:r1"].reason == "fixed_field_conflict"
    assert results["a:r1"].action == "conflict" and results["a:r1"].reason == "fixed_field_conflict"


def test_conflict_propagates_through_all_provider_and_verified_evidence_aliases():
    rows = {**_file("a", [_row(provider_id="A")]),
            **_file("b", [_row(provider_id="A", stable_evidence="verified:1")]),
            **_file("c", [_row(provider_id="B", stable_evidence="verified:1")])}
    assert all(row.action == "conflict" for row in _batch(rows).values())


def test_file_bindings_to_the_same_target_join_the_conflict_group_without_provider_ids():
    rows = {**_file("a", [_row()]), **_file("b", [_row(name="变化")])}
    old = _old(binding_keys=frozenset(key for row in rows.values() for key in row.binding_keys))
    assert all(row.action == "conflict" for row in _batch(rows, [old]).values())


def test_known_distinct_ids_do_not_collapse_when_the_financial_index_matches():
    rows = {**_file("a", [_row(provider_id="A")]), **_file("b", [_row(provider_id="B")])}
    assert len(_writes(_batch(rows))) == 2


def test_weak_row_can_report_equivalent_to_two_proven_distinct_ids_without_binding_either():
    rows = {**_file("a", [_row(provider_id="A"), _row(2, provider_id="B")]),
            **_file("b", [_row(), _row(2)])}
    results = _batch(rows)
    assert len(_writes(results)) == 2
    assert all(results[key].action == "batch_duplicate" and not results[key].bind
               and _equivalent_rows(results[key], results) == ("a:r1", "a:r2") for key in ("b:r1", "b:r2"))


def test_one_file_cannot_count_its_weak_occurrence_as_its_own_distinct_proven_occurrence():
    rows = {**_file("a", [_row(provider_id="A"), _row(2)]), **_file("b", [_row(provider_id="A")])}
    assert len(_writes(_batch(rows))) == 2


def test_proven_distinct_ids_in_different_files_survive_a_weak_equivalent_export():
    rows = {**_file("a", [_row(provider_id="A")]), **_file("b", [_row(provider_id="B")]),
            **_file("c", [_row(), _row(2)])}
    results = _batch(rows)
    assert len(_writes(results)) == 2
    assert results["c:r1"].action == results["c:r2"].action == "batch_duplicate"


def test_deleted_bindings_do_not_join_or_resurrect_previous_records():
    rows = {**_file("a", [_row(provider_id="A")]), **_file("b", [_row(provider_id="A")])}
    deleted = _old(provider_id="A", deleted=True)
    assert len(_writes(_batch(rows, [deleted]))) == 1
    assert all(row.transaction_id is None for row in _batch(rows, [deleted]).values())


def test_invalid_row_is_isolated_from_valid_rows_even_with_the_same_provider_id():
    rows = {**_file("a", [_row(provider_id="A", fields={"currency": []})]),
            **_file("b", [_row(provider_id="A")])}
    results = _batch(rows)
    assert results["a:r1"].action == "conflict" and results["a:r1"].reason == "invalid_field"
    assert results["b:r1"].action == "new"


def test_locator_collision_with_inconsistent_input_is_a_row_conflict_not_a_planner_exception():
    original = next(iter(_file("a", [_row()]).values()))
    results = _batch({"one": original, "two": replace(original, fields={**original.fields, "counterparty": "另一条"})})
    assert all(row.action == "conflict" for row in results.values())


def test_equivalent_decimal_and_time_values_do_not_create_a_false_identity_conflict():
    first = _row(provider_id="A")
    second = _row(provider_id="A", fields={"amount": "-20", "occurred_at": "2026-08-15T08:00:00+08:00"})
    assert len(_writes(_batch({**_file("a", [first]), **_file("b", [second])}))) == 1


@pytest.mark.parametrize("proof", ["verified", "file"])
def test_joint_identity_keeps_the_old_target_evidence_from_every_occurrence(proof):
    first = _row(provider_id="A", fields={"amount": "-21"})
    second = replace(first, stable_evidence="verified:old") if proof == "verified" else first
    rows = {**_file("a", [first]), **_file("b", [second])}
    old = (_old(stable_evidence="verified:old") if proof == "verified" else
           _old(binding_keys=frozenset({rows["b:r1"].file_binding_key})))
    results = _batch(rows, [old])
    assert all(row.action == "conflict" and row.reason == "fixed_field_conflict" for row in results.values())
    assert not any(row.bind for row in results.values())


def test_joint_identity_checks_the_combined_evidence_against_the_old_target():
    first = _row(provider_id="A")
    second = replace(first, stable_evidence="verified:conflicting")
    rows = {**_file("a", [first]), **_file("b", [second])}
    old = _old(provider_id="A", stable_evidence="verified:current")
    assert all(row.action == "conflict" and row.reason == "source_identity_conflict" for row in _batch(rows, [old]).values())


def test_an_explicit_null_patch_is_different_from_omitting_the_field():
    first = _row(provider_id="A", fields={"category_id": None})
    second = replace(first, fields={key: value for key, value in first.fields.items() if key != "category_id"})
    rows = {**_file("a", [first]), **_file("b", [second])}
    assert all(row.action == "conflict" and row.reason == "batch_identity_conflict" for row in _batch(rows, [_old(fields={"category_id": "old"})]).values())


def test_different_provider_ids_can_exclude_unknown_owner_cross_file_candidates():
    first = _row(provider_id="A")
    second = replace(_row(provider_id="B"), source_account_key=None)
    assert len(_writes(_batch({**_file("a", [first]), **_file("b", [second])}))) == 2


def test_selected_identity_still_blocks_its_unproven_cross_file_collision():
    rows = {**_file("a", [_row(provider_id="A")]), **_file("b", [_row(provider_id="A", name="变化")]),
            **_file("c", [_row()]), **_file("d", [_row(provider_id="D")]),
            **_file("e", [_row(fields={"amount": "-99"})])}
    results = _batch(rows)
    assert results["a:r1"].action == "batch_duplicate" and results["a:r1"].duplicate_of == "b:r1"
    assert results["b:r1"].action == "new"
    assert results["c:r1"].action == "pending"
    assert results["d:r1"].action == results["e:r1"].action == "new"


def test_a_distinct_occurrence_in_the_same_file_as_a_rejected_identity_is_preserved():
    rows = {**_file("a", [_row(provider_id="A"), _row(2)]),
            **_file("b", [_row(provider_id="A", name="变化")])}
    results = _batch(rows)
    assert results["a:r2"].action == "new"


@pytest.mark.performance
def test_large_uniform_multiset_results_use_shared_references_with_linear_size():
    count = 2000
    rows = {**_file("a", [_row(i) for i in range(count)]), **_file("b", [_row(i) for i in range(count)])}
    started = perf_counter()
    results = _batch(rows)
    assert len(_writes(results)) == count
    assert sum(len(group.row_refs) for group in results.plan.groups) <= count * 2
    assert len(json.dumps(asdict(results.plan), ensure_ascii=False).encode()) < count * 1200
    assert perf_counter() - started < 2


def test_different_raw_ids_with_unknown_owners_are_excluded_without_inventing_scope():
    first = replace(_row(provider_id="A"), source_account_key=None)
    second = replace(_row(provider_id="B"), source_account_key=None)
    assert len(_writes(_batch({**_file("a", [first]), **_file("b", [second])}))) == 2


@pytest.mark.performance
def test_large_ambiguous_sets_share_candidate_groups_instead_of_expanding_each_row():
    count = 1000
    rows = {**_file("a", [_row(i, "新") for i in range(count)]),
            **_file("b", [_row(i, "另一种") for i in range(count)])}
    started = perf_counter()
    results = _batch(rows, [_old(str(i), f"旧{i}") for i in range(count)])
    assert all(row.action == "pending" for row in results.values())
    assert sum(len(group.row_refs) + len(group.candidate_ids) for group in results.plan.groups) <= count * 3
    assert len(json.dumps(asdict(results.plan), ensure_ascii=False).encode()) < count * 1200
    assert perf_counter() - started < 2


@pytest.mark.performance
def test_large_rejected_identity_keeps_one_shared_collision_context():
    count = 1000
    rows = {**_file("a", [_row(i, "甲" if i % 2 else "乙", provider_id="A") for i in range(count)]),
            **_file("b", [_row(i) for i in range(count)])}
    started = perf_counter()
    results = _batch(rows)
    assert sum(row.action == "conflict" for row in results.values()) == count
    assert sum(row.action == "pending" for row in results.values()) == count
    assert sum(len(group.row_refs) for group in results.plan.groups) <= count
    assert len(json.dumps(asdict(results.plan), ensure_ascii=False).encode()) < count * 1200
    assert perf_counter() - started < 2


def test_business_result_counts_are_independent_of_file_input_order_and_input_is_unchanged():
    files = [_file("a", [_row(), _row(2)]), _file("b", [_row()]), _file("c", [_row(), _row(2), _row(3)])]
    before = deepcopy(files)
    for order in permutations(files):
        results = _batch({key: value for file in order for key, value in file.items()})
        assert len(_writes(results)) == 3
        assert sum(row.action == "batch_duplicate" for row in results.values()) == 3
    assert files == before


@pytest.mark.performance
def test_large_equal_multisets_have_bounded_planning_work():
    count = 1000
    rows = {**_file("a", [_row(i, f"对方{i}") for i in range(count)]),
            **_file("b", [_row(i, f"对方{count - i - 1}") for i in range(count)])}
    started = perf_counter()
    results = _batch(rows, [_old(str(i), f"对方{i}") for i in range(count)])
    assert not _writes(results) and not any(row.action == "pending" for row in results.values())
    assert perf_counter() - started < 2

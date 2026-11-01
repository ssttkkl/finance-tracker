"""Prepare original current-relation command inputs immediately before commit."""
from __future__ import annotations

import argparse
from copy import deepcopy
import inspect
import itertools
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest
from sqlalchemy import select

from export_cash_import_original_commit_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import AccountModel, CashRelationCandidateModel, CashTransactionModel, TransactionRelationModel
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_commit import CashMutationCommandService
from ft.application.cash_mutation_plans import CashMutationPlanService, _context_id
import tests.contract.test_cash_current_relation_commit as current
import tests.contract.test_cash_manual_associations as manual
import tests.contract.test_cash_mutation_commit as mutation
import tests.contract.test_cash_entry_editing as entries
import tests.contract.test_cash_transfer_public_edit as transfer_public


METHODS = {
    "unchanged": (current, "test_confirming_an_unchanged_current_plan_preserves_all_decisions_and_proofs"),
    "joint": (current, "test_joint_confirmation_updates_original_ids_as_one_group_and_keeps_full_refund_visible"),
    "inactive": (current, "test_cleanup_of_mixed_inactive_group_never_rewrites_inactive_members"),
    "note": (current, "test_same_command_note_patch_can_confirm_the_prechecked_candidate_without_refreshing_proof"),
    "coupled": (current, "test_component_removal_and_explicit_candidate_cleanup_share_one_atomic_unit"),
    "reselect": (current, "test_reselecting_current_candidate_rebuilds_the_old_counterpart_as_confirmed_singleton"),
    "parent": (current, "test_parent_only_draft_current_relation_can_be_saved_without_components"),
    "manual": (manual, "test_manual_association_previews_and_commits_outside_candidates_without_changing_sources"),
    "manual_clear": (manual, "test_manual_association_can_explicitly_clear_conflicting_public_note"),
    "manual_http": (manual, "test_manual_association_previews_and_commits_outside_candidates_without_changing_sources"),
    "joint_http": (current, "test_joint_confirmation_updates_original_ids_as_one_group_and_keeps_full_refund_visible"),
    "fault_joint": (current, "test_second_joint_member_failure_rolls_back_the_complete_relation_unit"),
    "fault_publish": (current, "test_publication_failure_rolls_back_current_relations_and_keeps_plan_retryable"),
    "stale_candidate": (current, "test_candidate_evidence_concurrency_refuses_old_commit_before_any_writes"),
    "fault_field": (current, "test_field_save_failure_blocks_the_dependent_relation_group"),
    "fault_coupled": (current, "test_coupled_candidate_cleanup_failure_preserves_draft_parts_and_different_evidence"),
    "defer_distinct": (current, "test_defer_does_not_discard_distinct_candidate_proofs_to_allow_component_removal"),
    "fault_candidate_field": (current, "test_failed_candidate_only_record_blocks_the_related_current_group"),
    "type_rejected": (current, "test_imported_type_patch_rejected_without_mutating_prechecked_relation"),
    "forged": (current, "test_unknown_reference_cannot_authorize_another_action_or_candidate"),
    "type_context": (current, "test_rejected_type_patch_preserves_existing_context_without_implicit_confirmation"),
    "manual_invalid": (manual, "test_invalid_manual_input_is_rejected_without_partial_success"),
    "manual_stale": (manual, "test_target_change_after_preview_rejects_commit"),
    "manual_contract_http": (manual, "test_http_search_and_association_use_explicit_target_contract"),
    "fresh_context": (current, "test_relation_writer_reads_one_fresh_context_and_keeps_full_validation"),
    "ordinary_posted": (mutation, "test_posted_edit_commits_once_and_replays_after_context_and_target_are_gone"),
    "ordinary_finalize": (mutation, "test_complete_draft_finalizes_same_identity_and_changes_balances_only_once"),
    "ordinary_common": (mutation, "test_common_fields_edit_updates_one_entry_and_keeps_sources_and_group"),
    "ordinary_member_failure": (mutation, "test_failure_after_second_category_member_rolls_back_the_entire_record_unit"),
    "ordinary_retained": (mutation, "test_retained_source_problem_body_and_revision_survive_formal_edit"),
    "ordinary_publication": (mutation, "test_publication_failure_rolls_back_record_and_keeps_context_retryable"),
    "ordinary_relation_failure": (mutation, "test_new_relation_failure_rolls_back_finalization_and_all_business_writes"),
    "ordinary_second_edge": (mutation, "test_failure_after_one_edge_rolls_back_the_whole_new_group_but_keeps_previous_graph"),
    "ordinary_connection": (mutation, "test_connection_failure_rolls_back_outer_transaction_and_keeps_original_plan"),
    "transfer_financial": (entries, "test_manual_transfer_finances_change_both_sides_atomically"),
    "transfer_public": (transfer_public, "test_transfer_public_edit_survives_financial_member_preparation"),
    "transfer_failure": (entries, "test_manual_transfer_failure_rolls_back_both_sides_entry_relation_and_balance"),
    "transfer_stale": (entries, "test_manual_transfer_current_dependencies_rechecked_before_commit"),
}


def scenarios():
    result = {}
    for label, (module, method) in METHODS.items():
        choices = []
        for mark in getattr(getattr(module, method), "pytestmark", ()):
            if mark.name != "parametrize":
                continue
            keys = [key.strip() for key in mark.args[0].split(",")]
            choices.append([dict(zip(keys, value if len(keys) > 1 else (value,))) for value in mark.args[1]])
        for index, values in enumerate(itertools.product(*choices) if choices else [()]):
            result[f"{label}_{index}"] = {"label": label, "method": method,
                "params": {key: value for group in values for key, value in group.items()}}
    return result


SCENARIOS = scenarios()


class CommitReached(BaseException):
    """Escape original expected-error handlers without changing the database."""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use external output")
    output = args.output / args.scenario
    export(args.database_url, output, "formal")
    (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    runtime = SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"])
    metadata = SCENARIOS[args.scenario]
    function = getattr(METHODS[metadata["label"]][0], metadata["method"])
    previews = []
    invoke = CashMutationPlanService.preview
    baseline = {}
    watched = [model.__table__ for model in (AccountModel, CashRelationCandidateModel, CashTransactionModel, TransactionRelationModel)]
    monkeypatch = pytest.MonkeyPatch()

    def preview(service, value):
        try:
            result = invoke(service, value)
        except ValueError as error:
            previews.append({"input": deepcopy(value), "error": getattr(error, "code", str(error))})
            raise
        previews.append({"input": deepcopy(value), "plan_id": result["plan_id"]})
        if metadata["label"] in {"stale_candidate", "manual_stale", "transfer_stale"}:
            with runtime.sessions() as session:
                for table in watched:
                    baseline[table.name] = {row["id"]: dict(row) for row in session.execute(select(table)).mappings()}
        return result

    def emit(plan_id=None, expected_plan=None, **kwargs):
        changes = []
        if baseline:
            with runtime.sessions.begin() as session:
                for table in watched:
                    for row in session.execute(select(table)).mappings():
                        before = baseline[table.name][row["id"]]
                        different = {key: value for key, value in row.items() if value != before[key]}
                        if different:
                            changes.append({"table": table.name, "id": row["id"], "before": {key: before[key] for key in different}, "after": different})
                            session.execute(table.update().where(table.c.id == row["id"]).values(before))
        (output / "case.json").write_text(json.dumps(_wire({"metadata": metadata,
            "previews": previews, "plan_id": plan_id, "commit": kwargs, "changes": changes,
            "expected_plan": expected_plan}), ensure_ascii=False, indent=2) + "\n")

    def stop(service, plan_id, **kwargs):
        stored = service._staging.get(_context_id(plan_id), workspace_id=service._workspace_id, user_id=service._user_id)
        emit(plan_id, expected_plan=stored.plan, **kwargs)
        raise CommitReached()

    try:
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch, **metadata["params"]}
        with patch.object(CashMutationPlanService, "preview", preview), patch.object(CashMutationCommandService, "commit", stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except CommitReached:
                pass
            else:
                if metadata["label"] not in {"forged", "manual_invalid", "manual_contract_http"}:
                    raise AssertionError("Original command did not reach commit")
                emit(next((item["plan_id"] for item in reversed(previews) if "plan_id" in item), None))
        print("prepared", args.scenario)
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

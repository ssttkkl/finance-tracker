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

from export_cash_import_original_commit_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_commit import CashMutationCommandService
from ft.application.cash_mutation_plans import CashMutationPlanService
import tests.contract.test_cash_current_relation_commit as current
import tests.contract.test_cash_manual_associations as manual


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

    def preview(service, value):
        result = invoke(service, value)
        previews.append({"input": deepcopy(value), "plan_id": result["plan_id"]})
        return result

    def stop(service, plan_id, **kwargs):
        (output / "case.json").write_text(json.dumps(_wire({"metadata": metadata,
            "previews": previews, "plan_id": plan_id, "commit": kwargs}), ensure_ascii=False, indent=2) + "\n")
        raise CommitReached()

    try:
        values = {"context_runtime": runtime, **metadata["params"]}
        with patch.object(CashMutationPlanService, "preview", preview), patch.object(CashMutationCommandService, "commit", stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except CommitReached:
                pass
            else:
                raise AssertionError("Original command did not reach commit")
        print("prepared", args.scenario)
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()

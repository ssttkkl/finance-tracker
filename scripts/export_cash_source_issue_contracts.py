"""Prepare each original source-issue test before its target preview or read."""
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

from export_cash_import_original_commit_contracts import export, NOW
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import CashTransactionModel, CashTransactionIssueModel
from sqlalchemy import select, update
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_business_plan import CashMutationBusinessPlanBuilder
from ft.application.cash_mutation_plans import CashMutationPlanService, _input
from ft.application.cash_record_maintenance import CashRecordMaintenanceService
import tests.contract.test_cash_source_issue_resolution as original

DETAIL_ONLY = {
    "test_valid_field_only_update_explicitly_has_no_body_update",
    "test_inline_legacy_body_has_the_same_presence_and_content_as_adoption",
    "test_contradictory_body_presence_is_unknown_and_not_adoptable",
}
IMPORT_ONLY = {
    "test_import_keep_existing_with_new_alias_preserves_all_prior_pending_content",
    "test_imported_pending_update_preserves_explicit_source_clear_presence",
}


def scenarios():
    result = {}
    for name, method in vars(original).items():
        if not name.startswith("test_") or "context_runtime" not in inspect.signature(method).parameters:
            continue
        choices = []
        for mark in getattr(method, "pytestmark", ()):
            if mark.name == "parametrize":
                keys = [key.strip() for key in mark.args[0].split(",")]
                choices.append([dict(zip(keys, values if len(keys) > 1 else (values,))) for values in mark.args[1]])
        for index, groups in enumerate(itertools.product(*choices) if choices else [()]):
            result[name.removeprefix("test_") + f"_{index}"] = {
                "method": name, "params": {key: value for group in groups for key, value in group.items()},
                "kind": "detail" if name in DETAIL_ONLY else "import" if name in IMPORT_ONLY else "maintenance",
            }
    return result


SCENARIOS = scenarios()
SCENARIOS["legacy_protected_source_body"] = {
    "method": "test_source_adoption_is_read_only_until_commit_and_preserves_manual_note",
    "params": {"protected": None, "explicit": None, "expected": "原始备注"},
    "kind": "maintenance", "supplement": "protected_source_payload",
}
SCENARIOS["legacy_issue_id_order"] = {
    "method": "test_keep_existing_clears_only_selected_instance_without_faking_parent_update",
    "params": {}, "kind": "maintenance", "supplement": "lexical_issue_ids",
}


class TargetReached(BaseException):
    """Escape original expected failures without executing the target action."""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", choices=SCENARIOS, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use external output")
    out = args.output / args.scenario
    export(args.database_url, out, "formal")
    config = json.loads((out / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    metadata = SCENARIOS[args.scenario]
    case = {"metadata": metadata}
    monkeypatch = pytest.MonkeyPatch()
    captured = {}

    def stop_maintenance(service, value):
        case["input"] = _wire(deepcopy(_input(value)[0]))
        raise TargetReached()

    import_preview = CashImportCommandService.preview

    def stop_import(service, session_id, **kwargs):
        if any(frame.function == "_posted" for frame in inspect.stack()):
            return import_preview(service, session_id, **kwargs)
        staged = service._staging.get(session_id, workspace_id=runtime.workspace_id, user_id=config["user"])
        captured["files"] = staged.files
        frame = next(frame for frame in inspect.stack() if frame.function == metadata["method"])
        case["transaction_id"] = str(frame.frame.f_locals["identifier"])
        case.update(session_id=session_id, session=json.loads(service._staging._encode(staged)), input=_wire(deepcopy(kwargs)))
        raise TargetReached()

    def stop_read(service, identifier):
        case["transaction_id"] = str(identifier)
        raise TargetReached()

    try:
        target, method, stop = ((CashRecordMaintenanceService, "read", stop_read) if metadata["kind"] == "detail"
            else (CashImportCommandService, "preview", stop_import) if metadata["kind"] == "import"
            else (CashMutationPlanService, "preview", stop_maintenance))
        function = getattr(original, metadata["method"])
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch, **metadata["params"]}
        with patch.object(target, method, stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except TargetReached:
                pass
            else:
                raise AssertionError("Original test did not reach target")
        monkeypatch.undo()
        if metadata.get("supplement") == "protected_source_payload":
            with sessions.begin() as session:
                row = session.get(CashTransactionModel, int(case["input"]["transaction_id"]))
                row.manual_overrides = {"source_payload": True}
                session.flush()
                case["input"]["expected_revision"] = row.row_revision
        if metadata.get("supplement") == "lexical_issue_ids":
            with sessions.begin() as session:
                first = int(case["input"]["issue_resolutions"][0]["issue_id"])
                assert first == 1
                session.execute(update(CashTransactionIssueModel).where(CashTransactionIssueModel.id == first).values(id=10))
                issues = {row.id: row for row in session.scalars(select(CashTransactionIssueModel).where(
                    CashTransactionIssueModel.transaction_id == int(case["input"]["transaction_id"])))}
                assert set(issues) == {2, 10}
                case["input"]["issue_resolutions"] = [{"issue_id": str(identifier),
                    "expected_issue_revision": issues[identifier].revision, "resolution": "keep_existing"} for identifier in (10, 2)]
        if metadata["kind"] == "detail":
            case["detail"] = original._records(runtime).read(case["transaction_id"])
        else:
            if metadata["kind"] == "maintenance":
                case["transaction_id"] = case["input"]["transaction_id"]
            with sessions() as session:
                try:
                    if metadata["kind"] == "import":
                        normalized = {**case["input"], "relation_choices": case["input"].get("relation_choices", [])}
                        case["expected"] = _wire(CashImportBusinessPlanBuilder(runtime.workspace_id, clock=lambda: NOW)(
                            session=session, files=captured["files"], preview_input=normalized, previous_plan=None, plan_id="fixture-only"))
                    else:
                        case["expected"] = _wire(CashMutationBusinessPlanBuilder(runtime.workspace_id, clock=lambda: NOW)(
                            session=session, preview_input=case["input"], previous_plan=None))
                except ValueError as error:
                    case["error"] = getattr(error, "code", str(error))
        (out / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario, case.get("error", "ok"))
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

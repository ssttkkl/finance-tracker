"""Prepare original draft merge tests before their target preview."""
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
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_business_plan import CashMutationBusinessPlanBuilder
from ft.application.cash_mutation_plans import CashMutationPlanService, _input
from ft.application.cash_record_maintenance import CashRecordMaintenanceService
import tests.contract.test_cash_draft_merge_plan as plans
import tests.contract.test_cash_draft_merge_commit as commits


def scenarios():
    result = {}
    for label, original in (("plan", plans), ("commit", commits)):
        for name, method in vars(original).items():
            if not name.startswith("test_") or "context_runtime" not in inspect.signature(method).parameters:
                continue
            choices = []
            for mark in getattr(method, "pytestmark", ()):
                if mark.name == "parametrize":
                    keys = [key.strip() for key in mark.args[0].split(",")]
                    choices.append([dict(zip(keys, values if len(keys) > 1 else (values,))) for values in mark.args[1]])
            for index, groups in enumerate(itertools.product(*choices) if choices else [()]):
                result[label + "_" + name.removeprefix("test_") + f"_{index}"] = {
                    "method": name, "params": {key: value for group in groups for key, value in group.items()},
                    "module": label, "kind": "detail" if name == "test_identity_candidates_show_current_data_without_updating_stored_proof" else "maintenance",
                }
    return result


SCENARIOS = scenarios()


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
    original = plans if metadata["module"] == "plan" else commits
    case = {"metadata": metadata}
    monkeypatch = pytest.MonkeyPatch()

    def capture_locals():
        frame = next(frame for frame in inspect.stack() if frame.function == metadata["method"])
        values = frame.frame.f_locals
        case["source_id"], case["target_id"] = str(values["source"]), str(values["target"])
        case["locals"] = {key: _wire(deepcopy(values[key])) for key in (
            "category", "expected_revision", "candidate_id", "relation_id", "target_candidate_id", "issue_id") if key in values}

    def stop_maintenance(service, value):
        case["input"] = _wire(deepcopy(_input(value)[0]))
        capture_locals()
        raise TargetReached()

    real_read = CashRecordMaintenanceService.read
    def stop_read(service, identifier):
        if inspect.stack()[1].function != metadata["method"]:
            return real_read(service, identifier)
        capture_locals()
        case["transaction_id"] = str(identifier)
        raise TargetReached()

    try:
        target, method, stop = ((CashRecordMaintenanceService, "read", stop_read) if metadata["kind"] == "detail" else (CashMutationPlanService, "preview", stop_maintenance))
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
        if metadata["kind"] == "detail":
            case["detail"] = plans._records(runtime).read(case["transaction_id"])
        else:
            case["transaction_id"] = case["input"]["transaction_id"]
            with sessions() as session:
                try:
                    case["expected"] = _wire(CashMutationBusinessPlanBuilder(runtime.workspace_id, clock=lambda: NOW)(
                        session=session, preview_input=case["input"], previous_plan=None))
                except ValueError as error:
                    case["error"] = getattr(error, "code", str(error))
                    case["error_fields"] = list(getattr(error, "fields", ()))
                    from ft.web.cash_plan_routes import plan_failure
                    response = plan_failure(error)
                    case["public_error"] = {"status": response.status_code, "body": json.loads(response.body)}
        if metadata["method"] == "test_merge_keeps_target_identity_balance_and_permanent_replay" and not metadata["params"]["draft_target"]:
            from tests.contract.test_cash_import_operations import _file
            from tests.contract.test_cash_import_commit import _plan
            importer, repeated = _plan(runtime, (_file(letter="c"),))
            session_id = repeated["session_id"]
            staged = importer._staging.get(session_id, workspace_id=runtime.workspace_id, user_id=config["user"])
            case["reimport_session_id"] = session_id
            case["reimport_session"] = json.loads(importer._staging._encode(staged))
        (out / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario, case.get("error", "ok"))
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

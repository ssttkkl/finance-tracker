"""Prepare original independent confirmation tests before their target preview."""
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
import tests.contract.test_cash_independent_identity as original


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
                "kind": "maintenance",
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
    case = {"metadata": metadata}
    monkeypatch = pytest.MonkeyPatch()

    def stop_maintenance(service, value):
        case["input"] = _wire(deepcopy(_input(value)[0]))
        if metadata["method"] == "test_multiple_identity_choices_count_one_record_and_only_clear_selected_issues" and value["intent"] == "finalize_draft":
            case["rejected_input"] = deepcopy(case["input"])
            return real_preview(service, value)
        raise TargetReached()

    real_preview = CashMutationPlanService.preview
    try:
        target, method, stop = CashMutationPlanService, "preview", stop_maintenance
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
        case["transaction_id"] = case["input"]["transaction_id"]
        with sessions() as session:
            try:
                case["expected"] = _wire(CashMutationBusinessPlanBuilder(runtime.workspace_id, clock=lambda: NOW)(
                    session=session, preview_input=case["input"], previous_plan=None))
            except ValueError as error:
                case["error"] = getattr(error, "code", str(error))
                from ft.web.cash_plan_routes import plan_failure
                response = plan_failure(error)
                case["public_error"] = {"status": response.status_code, "body": json.loads(response.body)}
        if metadata["method"] == "test_independent_confirmation_preserves_entity_bindings_and_replays_without_context":
            from tests.contract.test_cash_import_operations import _file
            from tests.contract.test_cash_import_commit import _plan
            importer, repeated = _plan(runtime, (_file(letter="b"),))
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

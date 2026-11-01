"""Prepare original current-relation/manual-association preview checkpoints."""
from __future__ import annotations

import argparse
import ast
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
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_business_plan import CashMutationBusinessPlanBuilder
from ft.application.cash_mutation_plans import CashMutationPlanService, _input
import tests.contract.test_cash_current_relation_plan as current
import tests.contract.test_cash_manual_associations as manual

MODULES = {"current": current, "manual": manual}


def scenarios():
    result = {}
    for module, source in (("current", current), ("manual", manual)):
        for method, function in vars(source).items():
            if not method.startswith("test_") or "context_runtime" not in inspect.signature(function).parameters:
                continue
            tree = ast.parse(inspect.getsource(function))
            previews = sum(isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in {"_preview", "_choose"}
                for node in ast.walk(tree))
            if module == "manual" and not previews and method != "test_http_search_and_association_use_explicit_target_contract":
                continue
            choices = []
            marks = [mark for mark in getattr(function, "pytestmark", ()) if mark.name == "parametrize"]
            for mark in marks:
                keys = [key.strip() for key in mark.args[0].split(",")]
                choices.append([dict(zip(keys, value if len(keys) > 1 else (value,))) for value in mark.args[1]])
            for index, values in enumerate(itertools.product(*choices) if choices else [()]):
                params = {key: value for group in values for key, value in group.items()}
                for checkpoint in range(1, max(1, previews) + 1):
                    name = module + "_" + method.removeprefix("test_") + (f"_p{index}" if marks else "") + f"_s{checkpoint}"
                    result[name] = {"module": module, "method": method, "params": params, "checkpoint": checkpoint}
    return result


SCENARIOS = scenarios()


class PreviewReached(Exception):
    pass


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
    sessions = create_session_factory(engine)
    runtime = SimpleNamespace(sessions=sessions, workspace_id=config["workspace"])
    metadata = SCENARIOS[args.scenario]
    function = getattr(MODULES[metadata["module"]], metadata["method"])
    previews = []
    invoke = CashMutationPlanService.preview
    monkeypatch = pytest.MonkeyPatch()
    def stop(service, value):
        if len(previews) + 1 < metadata["checkpoint"]:
            previews.append(None)
            return invoke(service, value)
        previous = None
        if value.get("edit_context_id"):
            previous = service._staging.get(value["edit_context_id"], workspace_id=service._workspace_id, user_id=service._user_id).plan
        previews.append({"input": deepcopy(value), "previous": _wire(deepcopy(previous))})
        raise PreviewReached()
    try:
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch, **metadata["params"]}
        with patch.object(CashMutationPlanService, "preview", stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except PreviewReached:
                pass
            except AssertionError:
                # The HTTP adapter turns the deliberate checkpoint exception into an error response.
                if metadata["method"] != "test_http_search_and_association_use_explicit_target_contract" or not previews or previews[-1] is None:
                    raise
        assert len(previews) == metadata["checkpoint"] and previews[-1] is not None
        case = {**previews[-1], "metadata": metadata}
        monkeypatch.undo()
        prepared = []
        build = CashImportBusinessPlanBuilder.build_operations
        def capture(builder, **kwargs):
            prepared.append(_wire(deepcopy({key: sorted(kwargs.get(key, ())) if key in {"additional_seed_ids", "decision_group_ids"} else kwargs.get(key)
                for key in ("operations", "rows", "additional_seed_ids", "decision_group_ids")})))
            return build(builder, **kwargs)
        with sessions() as session, patch.object(CashImportBusinessPlanBuilder, "build_operations", capture):
            try:
                data, _metadata = _input(case["input"])
                case["input"] = data
                case["expected"] = _wire(CashMutationBusinessPlanBuilder(runtime.workspace_id, clock=lambda: NOW)(
                    session=session, preview_input=data, previous_plan=case["previous"]))
            except ValueError as error:
                case["error"] = getattr(error, "code", str(error))
        if prepared:
            assert len(prepared) == 1
            case["prepared"] = prepared[0]
        (output / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario, case.get("error", "ok"))
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

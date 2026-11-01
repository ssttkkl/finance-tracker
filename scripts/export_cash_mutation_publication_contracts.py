"""Prepare original mutation-publication tests before their first preview."""
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

from export_cash_import_original_commit_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_plans import CashMutationPlanService
import tests.contract.test_cash_mutation_plan_publication as original


def scenarios():
    result = {}
    for name, method in vars(original).items():
        if not name.startswith("test_"):
            continue
        choices = []
        for mark in getattr(method, "pytestmark", ()):
            if mark.name == "parametrize":
                keys = [key.strip() for key in mark.args[0].split(",")]
                choices.append([dict(zip(keys, values if len(keys) > 1 else (values,))) for values in mark.args[1]])
        for index, groups in enumerate(itertools.product(*choices) if choices else [()]):
            result[name.removeprefix("test_") + f"_{index}"] = {"method": name,
                "params": {key: value for group in groups for key, value in group.items()}}
    return result


SCENARIOS = scenarios()


class PreviewReached(BaseException):
    """Stop the original test before publication without intercepting setup."""


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
    runtime = SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"])
    metadata = SCENARIOS[args.scenario]
    case = {"metadata": metadata, "base_input": original._input(runtime)}
    monkeypatch = pytest.MonkeyPatch()

    def stop(_service, value):
        case["input"] = _wire(deepcopy(value))
        raise PreviewReached()

    try:
        function = getattr(original, metadata["method"])
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch, **metadata["params"]}
        with patch.object(CashMutationPlanService, "preview", stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except PreviewReached:
                pass
            else:
                raise AssertionError("Original test did not reach first publication")
        (out / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario)
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

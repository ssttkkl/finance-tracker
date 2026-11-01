"""Prepare original maintenance preview failure inputs before the failing call."""
from __future__ import annotations

import argparse
from copy import deepcopy
import inspect
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import pytest

from export_cash_import_original_commit_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_operations import _wire
from ft.application.cash_mutation_plans import CashMutationPlanService, _input
import tests.contract.test_cash_mutation_commit as original

SCENARIOS = {
    "source_text": "test_posted_source_text_user_edit_is_rejected_and_full_refund_stays_confirmed",
    "finalize_discovery": "test_discovery_exception_stops_finalization_without_publishing_a_problem",
    "import_discovery": "test_import_discovery_exception_does_not_publish_a_plan_or_permanent_result",
    "component_discovery": "test_component_discovery_exception_stops_preview_without_creating_problems",
}


class PreviewReached(BaseException):
    """Escape original expected failures without executing the target preview."""


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
    config = json.loads((output / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    runtime = SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"])
    method = SCENARIOS[args.scenario]
    case = {"method": method}
    monkeypatch = pytest.MonkeyPatch()

    def stop_maintenance(service, value):
        case["input"] = _wire(deepcopy(_input(value)[0]))
        raise PreviewReached()

    def stop_import(service, session_id, **kwargs):
        staged = service._staging.get(session_id, workspace_id=runtime.workspace_id, user_id=config["user"])
        case.update(session_id=session_id, session=json.loads(service._staging._encode(staged)), input=_wire(deepcopy(kwargs)))
        raise PreviewReached()

    try:
        target, name, stop = ((CashImportCommandService, "preview", stop_import) if args.scenario == "import_discovery"
            else (CashMutationPlanService, "preview", stop_maintenance))
        values = {"context_runtime": runtime, "monkeypatch": monkeypatch}
        function = getattr(original, method)
        with patch.object(target, name, stop):
            try:
                function(**{key: values[key] for key in inspect.signature(function).parameters})
            except PreviewReached:
                pass
            else:
                raise AssertionError("Original test did not reach its target preview")
        (output / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario)
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

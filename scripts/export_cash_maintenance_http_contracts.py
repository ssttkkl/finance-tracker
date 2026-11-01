"""Prepare original maintenance HTTP inputs before creating the first client."""
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
from ft.application.cash_import_operations import _wire
import tests.contract.test_cash_maintenance_api as original

FUNCTIONS = {
    "draft": "test_draft_save_is_idempotent_without_a_file_and_keeps_explicit_current_detail",
    "finalize": "test_finalize_http_uses_one_plan_and_recovers_receipt_after_context_cleanup",
    "repreview": "test_repreview_replaces_complete_input_without_renewing_context",
    "issue": "test_current_source_issue_and_manual_patch_share_one_http_confirmation",
    "joint": "test_joint_current_relation_confirmation_is_one_http_task_and_keeps_zero_result",
    "merge": "test_identity_merge_http_returns_retained_target_and_preserves_original_result_reference",
    "ordinary": "test_ordinary_draft_plan_keeps_draft_and_can_resolve_after_original_context_is_gone",
    "account": "test_changed_account_after_finalize_preview_is_a_known_refusal",
}


class ClientReached(BaseException):
    """Keep original setup and stop before the first HTTP operation."""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--scenario", required=True, choices=FUNCTIONS)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    if not args.output.is_absolute() or args.output.resolve().is_relative_to(root):
        raise ValueError("Use external output")
    directory = args.output / args.scenario
    export(args.database_url, directory, "formal")
    config = json.loads((directory / "database.json").read_text())
    engine = create_relational_engine(args.database_url)
    runtime = SimpleNamespace(sessions=create_session_factory(engine), workspace_id=config["workspace"])
    method = FUNCTIONS[args.scenario]
    case = {"method": method}
    monkeypatch = pytest.MonkeyPatch()

    def stop(_runtime, _monkeypatch, **_kwargs):
        frame = next(frame for frame in inspect.stack() if frame.function == method)
        case["locals"] = {key: _wire(deepcopy(frame.frame.f_locals[key]))
            for key in ("identifier", "source", "target", "choice") if key in frame.frame.f_locals}
        identifier = case["locals"].get("identifier", case["locals"].get("source", "1101"))
        case["transaction_id"] = identifier
        detail = original._records(runtime).read(identifier)
        case["expected_revision"] = detail["row_revision"]
        if args.scenario in {"draft", "finalize", "ordinary", "account"}:
            case["complete_patch"] = original._complete(detail)
            case["empty_note_patch"] = original._complete(detail, note="")
        if args.scenario == "merge":
            from tests.contract.test_cash_draft_merge_plan import _choice
            case["issue_choice"] = _wire(_choice(runtime, case["locals"]["source"], case["locals"]["target"]))
        raise ClientReached()

    try:
        with patch.object(original, "_client", stop):
            try:
                getattr(original, method)(runtime, monkeypatch)
            except ClientReached:
                pass
            else:
                raise AssertionError("Original test did not reach its first client")
        (directory / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        (args.output / "scenarios.json").write_text(json.dumps(FUNCTIONS, indent=2) + "\n")
        print("prepared", args.scenario)
    finally:
        monkeypatch.undo()
        engine.dispose()


if __name__ == "__main__":
    main()

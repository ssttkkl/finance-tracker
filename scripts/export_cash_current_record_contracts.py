"""Prepare original current-record setup and input without expected native results."""
from __future__ import annotations

import argparse
import itertools
import json
from pathlib import Path
from types import SimpleNamespace

from sqlalchemy import delete

from export_cash_import_original_commit_contracts import export
from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import CashSourceBindingModel
from ft.application.cash_projections import CashProjectionService
from ft.domain.cash_source_matching import SourceOccurrence
from tests.contract.test_cash_import_business_plan import _existing_mirror
from tests.contract.test_cash_mutation_business_plan import _posted
import tests.contract.test_cash_maintenance_current_reuse as original

FUNCTIONS = {
    "record": "test_supplied_record_is_only_reused_while_current_in_this_session",
    "other_session": "test_object_from_another_session_cannot_override_the_current_record",
    "deleted": "test_deleted_supplied_object_is_not_reused",
    "workspace": "test_supplied_record_does_not_bypass_workspace_scope",
    "scopes": "test_reused_source_snapshot_matches_fresh_scope_for_additional_closure_members",
    "type": "test_posted_import_type_change_is_rejected_without_changing_members_or_relation",
    "component": "test_component_change_without_parent_revision_still_invalidates_confirmation",
}


def scenarios():
    result = {}
    for label, name in FUNCTIONS.items():
        choices = []
        for mark in getattr(getattr(original, name), "pytestmark", ()):
            if mark.name == "parametrize":
                keys = [key.strip() for key in mark.args[0].split(",")]
                choices.append([dict(zip(keys, values if len(keys) > 1 else (values,))) for values in mark.args[1]])
        for index, groups in enumerate(itertools.product(*choices) if choices else [()]):
            result[label + (f"_{index}" if choices else "")] = {"label": label, "method": name,
                "params": {key: value for group in groups for key, value in group.items()}}
    return result


SCENARIOS = scenarios()


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
    case = {"metadata": metadata, "transaction_id": "1001"}
    try:
        if metadata["label"] in {"scopes", "type"}:
            with runtime.sessions.begin() as session:
                _, relation_id = _existing_mirror(session, runtime)
                case["transaction_id"] = "1003"
                case["relation_id"] = relation_id
                if metadata["label"] == "scopes":
                    if not metadata["params"]["root_bound"]:
                        session.execute(delete(CashSourceBindingModel).where(CashSourceBindingModel.transaction_id == 1003))
                    CashSourceRepository(session, runtime.workspace_id).save_occurrence(1001, SourceOccurrence(
                        source_type="icbc_debit", source_account_key="synthetic-bank-scope", file_digest="b" * 64,
                        parser_version="fixture-v1", table_key="bank", position=1, fields={}))
            if metadata["label"] == "type":
                CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
        if metadata["label"] == "component":
            case["transaction_id"] = _posted(runtime)
        (out / "case.json").write_text(json.dumps(case, ensure_ascii=False, indent=2) + "\n")
        (args.output / "scenarios.json").write_text(json.dumps(SCENARIOS, ensure_ascii=False, indent=2) + "\n")
        print("prepared", args.scenario)
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()

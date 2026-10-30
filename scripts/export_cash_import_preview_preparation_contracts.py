"""Export anonymous post-operation import preview preparation contracts."""
from __future__ import annotations

import argparse
from dataclasses import asdict, replace
import json
from pathlib import Path

from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_funding import scan_cash_funding
from ft.application.cash_import_operations import CashImportOperationPlanner, _wire
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_relation_context import CashRelationGraphService

from export_cash_import_operation_contracts import NOW, WORKSPACE, connect, files, seed


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    try:
        seed(sessions)
        source_files = files()
        selections = [{"source_account_ref": group.source_account_ref, "account_id": "9201",
                       "expected_account_revision": 1} for group in scan_cash_funding(source_files).groups]
        choices = [{"row_ref": "f_a" + "0" * 31 + ":r1", "action": "keep_existing"},
                   {"row_ref": "f_a" + "0" * 31 + ":r2", "action": "apply",
                    "patch": {"note": "人工备注", "category_id": "op-food"}},
                   {"row_ref": "f_w" + "0" * 31 + ":r1", "action": "apply", "patch": {"note": "新备注"}}]
        draft_choices = [{"row_ref": choice["row_ref"], "action": "defer"} for choice in choices]
        builder = CashImportBusinessPlanBuilder(WORKSPACE, clock=lambda: NOW)
        cases = []
        for name, previous, entry_choices, extra in (
            ("normal", None, [], []),
            ("retained_target", {"entry_targets": {"ie-good": {"parent_ids": ["7501"]}}},
             [{"edit_ref": "ie-good"}], [7502]),
            ("file_scope_fallback", None, [], []),
            ("draft_only", None, [], []),
        ):
            with sessions() as session:
                selected_choices = draft_choices if name == "draft_only" else choices
                source_plan = CashImportSourcePlanner(session, WORKSPACE).prepare(source_files)
                if name == "file_scope_fallback":
                    ref = "f_a" + "0" * 31 + ":r2"
                    source_plan = replace(source_plan, rows={**source_plan.rows,
                        ref: replace(source_plan.rows[ref], source_account_key=None, provider_id=None)})
                raw = CashImportOperationPlanner(session, WORKSPACE).prepare(source_files,
                    account_selections=selections, row_choices=selected_choices, source_plan=source_plan)
                source_rows = {ref: {"source_type": row.source_type,
                    "source_account_key": row.source_account_key, "file_digest": row.file_digest,
                    "parser_version": row.parser_version, "table_key": row.table_key,
                    "position": row.position, "provider_id": row.provider_id}
                    for ref, row in raw.source_plan.rows.items()}
                operations, rows, candidate_sets, state = builder._prepare_operations(
                    session, source_files, {"account_selections": selections, "row_choices": selected_choices,
                    "entry_choices": entry_choices}, previous, source_plan)
                accounts, candidates, retained = state
                existing = {int(op["transaction_id"]) for op in operations if op.get("transaction_id")}
                context = CashRelationGraphService(session, WORKSPACE).load(existing | candidates | set(extra))
                cases.append({"name": name, "raw_operations": _wire(list(raw.operations)),
                    "source_rows": source_rows, "previous_plan": previous, "entry_choices": entry_choices,
                    "additional_seed_ids": extra, "operations": _wire(operations), "rows": _wire(rows),
                    "candidate_sets": candidate_sets, "accounts": accounts,
                    "candidate_ids": sorted(candidates), "retained_parent_ids": sorted(retained),
                    "context_seed_ids": sorted(context.seed_ids),
                    "context_transaction_ids": sorted(context.transaction_ids), "context_token": context.token})
                snapshot = builder._snapshot(session, context, operations, accounts, rows, normalized_operations=True)
                cases[-1]["snapshot"] = {
                    "values": _wire(snapshot.values),
                    "facts": {key: _wire(asdict(value)) for key, value in snapshot.facts.items()},
                    "components": {key: _wire(asdict(value)) for key, value in snapshot.components.items()},
                    "edges": {str(key): _wire(asdict(value)) for key, value in snapshot.edges.items()},
                    "funding": {str(key): _wire(value) for key, value in snapshot.funding.items()},
                    "parent_ops": {key: value["operation_ref"] for key, value in snapshot.parent_ops.items()},
                    "entry_public": _wire(snapshot.entry_public),
                    "removed_relation_ids": sorted(snapshot.removed_relations),
                    "removed_funding_ids": sorted(snapshot.removed_funding),
                    "operations": _wire(operations),
                    "rows": _wire(rows),
                }
        try:
            builder._entry_parents([{"edit_ref": "missing"}], {"entry_targets": {}})
            raise AssertionError("missing entry target unexpectedly accepted")
        except CashImportPlanError as error:
            invalid_target_error = error.code
        output.mkdir(parents=True, exist_ok=True)
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        (output / "preparation-cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "invalid-target.json").write_text(json.dumps({"code": invalid_target_error}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)

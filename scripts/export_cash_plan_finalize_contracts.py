"""Export balance, complete-entry edit, attention and row-result contracts."""
from __future__ import annotations

import argparse
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
from types import SimpleNamespace

from sqlalchemy import select

from export_cash_relation_repository_contracts import NOW, WORKSPACE, connect, seed
from export_cash_plan_relation_write_contracts import Writer, normalize, state as relation_state
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.cash_sources import CashSourceRepository
from ft.adapters.relational.models import AccountModel, CashTransactionModel, CashTransactionComponentModel, TransactionRelationModel, LedgerSnapshotModel, CashProjectionModel, CashProjectionStateModel
from ft.adapters.relational.repositories import RelationalSnapshotRepository
from ft.application.cash_import_entry_choices import save_entry_choices
from ft.application.cash_import_commit import CashImportCommandService
from ft.application.cash_import_plans import CashImportPlanError
from ft.application.cash_projections import CashProjectionService
from ft.domain.cash_record_policy import CashRecordPolicyError


def row(amount="-5.00", account="9301", **extra):
    return {"state": "posted", "record_origin": "import", "record_type": "consumption", "occurred_at": "2026-10-01T09:00:00Z",
        "components": [{"account_id": account, "amount": amount, "currency": "CNY"}], **extra}


def edit(parents=None, dependencies=None, patch=None):
    return {"target": {"parent_ids": parents or ["93001", "93005"], "relation_dependencies": dependencies or []}, "patch": patch or {"note": "whole entry note"}}


def definitions():
    cases = []
    def add(name, changes=(), edits=(), **extra):
        cases.append({"name": name, "changes": changes, "edits": edits, "identity": {}, "relation_results": [], **extra})
    add("no_changes")
    add("new_expense", [(None, row())])
    add("replace_amount", [(row(), row("-8.25"))])
    add("same_money", [(row("-5.0"), row("-5.00"))])
    add("same_values", [(row(), row())])
    add("public_only", [(row(), row(note="only note"))])
    add("draft", [(None, row(state="draft"))])
    add("delete", [(row(), None)])
    add("type_excluded", [(row(), row(record_type="transfer_out"))])
    add("manual_transfer_type_counts", [(None, row(record_origin="manual", record_type="transfer_out"))])
    add("aggregate", [(None, row(components=[{"account_id": "9301", "amount": "-3.00", "currency": "CNY"}, {"account_id": "9303", "amount": "2.00", "currency": "CNY"}]))])
    add("manual_transfer", [(row(record_origin="manual_transfer"), row("-7.00", record_origin="manual_transfer"))])
    add("transfer_security", [(None, row("3.00", "9304", record_origin="manual_transfer", record_type="transfer_in"))])
    add("mixed", [(row(record_origin="manual_transfer"), row("-7.00", record_origin="manual_transfer")), (None, row("-2.00", occurred_at="2026-10-02T09:00:00Z"))])
    add("singleton_edit", edits=[edit(["93002"])])
    add("whole_edit", edits=[edit()])
    add("virtual_edit", edits=[edit([str(2**65), "93005"])], identity={str(2**65): "93001"})
    add("unmapped_edit", edits=[edit([str(2**65), "93005"])])
    add("subset_edit", edits=[edit(["93001"])])
    add("several_entries", edits=[edit(["93001", "93002"])])
    add("missing_member", edits=[edit(["999999"])])
    dependency = [{"suggestion_id": "sg", "action": "accept"}]
    add("dependency_confirmed", edits=[edit(dependencies=dependency)], relation_results=[{"item_ref": "sg", "outcome": "confirmed"}])
    add("dependency_duplicate", edits=[edit(dependencies=dependency)], relation_results=[{"item_ref": "sg", "outcome": "duplicate"}])
    add("dependency_missing", edits=[edit(dependencies=dependency)])
    add("dependency_failed", edits=[edit(dependencies=dependency)], relation_results=[{"item_ref": "sg", "outcome": "failed"}])
    add("late_edit_failure", [(None, row())], [edit(["93002"]), edit(["93001"])])
    add("repeated_edit", edits=[edit(), edit()])
    add("attention", attention=True)
    return cases


def state(session):
    snapshot = session.get(LedgerSnapshotModel, WORKSPACE)
    fields = ("root_cash_transaction_id", "note", "counterparty", "category_id", "net_amount", "currency", "member_count", "attention_count", "built_projection_version")
    projections = [{key: getattr(row, key) for key in fields} for row in session.scalars(select(CashProjectionModel).where(
        CashProjectionModel.workspace_id == WORKSPACE).order_by(CashProjectionModel.root_cash_transaction_id))]
    return normalize({"snapshot": {"payload": snapshot.payload, "version": snapshot.version},
        "entries": relation_state(session)["entries"], "projections": projections,
        "projection_version": session.get(CashProjectionStateModel, WORKSPACE).projection_version})


def export(database_url, output):
    engine, sessions, jdbc = connect(database_url)
    seed(sessions)
    try:
        with sessions.begin() as session:
            session.get(CashTransactionModel, 93005).amount = Decimal("-100.00")
            session.get(CashTransactionComponentModel, 94005).amount = Decimal("-100.00")
            session.get(TransactionRelationModel, 9001).applied_amount = Decimal("100.00")
            for identifier, name, type_ in ((9303, "synthetic loan", "loan"), (9304, "synthetic investment", "security")):
                session.add(AccountModel(id=identifier, workspace_id=WORKSPACE, name=name, type=type_, active=True, currencies=["CNY"], metadata_json={}, revision=1, created_at=NOW, updated_at=NOW))
            session.flush()
            RelationalSnapshotRepository(session, WORKSPACE).save({"updated_at": "initial", "accounts": {"cash": {"9301": {"CNY": "1000.00"}, "9302": {"CNY": "200.00"}},
                "loan": {"9303": {"CNY": "-100.00"}}, "lend": {}, "security": {"9304": {"currency": "CNY", "positions": {"cny": {"shares": "10.00", "total_cost": "10.00", "cost_currency": "CNY"}}}}}})
        cases = definitions()
        for case in cases:
            with sessions() as session:
                transaction = session.begin()
                CashProjectionService.initialize_in_session(session, WORKSPACE)
                if case.get("attention"):
                    CashSourceRepository(session, WORKSPACE).save_issue(93001, "attention", code="source_update_pending")
                    CurrentCashRelationRepository(session, WORKSPACE).add({"kind": "refund_offset", "primary_transaction_id": 93006, "primary_component_id": 94006,
                        "anchor_transaction_id": 93006, "anchor_component_id": 94006, "secondary_transaction_id": 93002, "secondary_component_id": 94002})
                uow = SimpleNamespace(snapshot=RelationalSnapshotRepository(session, WORKSPACE), _state=lambda: SimpleNamespace(session=session))
                saved, error = None, None
                try:
                    Writer()._balances(uow, deepcopy(case["changes"]))
                    saved = save_entry_choices(session, WORKSPACE, case["edits"], case["identity"], case["relation_results"])
                except (CashImportPlanError, CashRecordPolicyError) as failure:
                    error = failure.code
                session.flush()
                case["expected"] = {"saved": saved, "error": error, "attention": Writer()._attention_count(session, {93001, 93002, 93005, 93006}), "state": state(session)}
                transaction.rollback()
        rows = {"row_decisions": [{"row_ref": "r1", "operation_ref": "op", "planned_action": "apply"}, {"row_ref": "r2", "operation_ref": "op", "planned_action": "duplicate_in_batch"},
            {"row_ref": "r3", "planned_action": "skip"}, {"row_ref": "r4", "planned_action": "skip", "code": "row_not_selected"}, {"row_ref": "r5", "planned_action": "duplicate", "transaction_id": "93001"}]}
        actual = {"op": {"outcome": "created_posted", "transaction_id": "93002"}}
        output.mkdir(parents=True, exist_ok=True)
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "row-results.json").write_text(json.dumps({"plan": rows, "actual": actual, "expected": CashImportCommandService._row_results(rows, actual)}, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        print(f"exported {len(cases)} finalize cases")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)

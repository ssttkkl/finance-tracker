"""Export anonymous shared cash-row writer contracts with transactional rollback."""
from __future__ import annotations

import argparse
from copy import deepcopy
from dataclasses import asdict
import json
from pathlib import Path

from sqlalchemy import select, text

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import (CashTransactionModel, CashTransactionComponentModel,
    CashTransactionIssueModel, CashIssueCandidateModel, CashSourceBindingModel, TransactionRelationModel,
    CashRelationCandidateModel, CashInvestmentFundingRelationModel, CashEntryModel, CashEntryMemberModel)
from ft.adapters.relational.cash_sources import SourceBindingConflict
from ft.application.cash_import_business_plan import CashImportBusinessPlanBuilder
from ft.application.cash_import_operations import _wire
from ft.application.cash_import_source_plan import CashImportSourcePlanner
from ft.application.cash_record_writer import CashRecordWriter, _issues_unchanged
from ft.application.cash_relation_context import CashRelationGraphService
from ft.application.cash_relation_type_updates import CashRelationTypeUpdateService
from ft.application.parsed_import_staging import _restore_file
from ft.domain.cash_record_policy import MUTABLE_CASH_FIELDS, CASH_FINANCIAL_FIELDS, CashRecordPolicyError
from ft.domain.cash_relation_policy import CashRelationError

from export_cash_import_operation_contracts import NOW, WORKSPACE
from export_cash_import_preview_preparation_contracts import export as export_preparation


def current(session, identifier):
    parent = session.get(CashTransactionModel, identifier)
    fields = (MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | {"state", "record_id", "source_type", "record_origin", "manual_transfer_id", "manual_overrides"}) - {"account_name", "components"}
    result = {key: deepcopy(getattr(parent, key)) for key in fields}
    if result["account_id"] is not None:
        result["account_id"] = str(result["account_id"])
    result["components"] = [{"component_id": str(part.id), "account_id": str(part.account_id) if part.account_id else None,
        "amount": part.amount, "currency": part.currency, "ordinal": part.ordinal}
        for part in session.scalars(select(CashTransactionComponentModel).where(CashTransactionComponentModel.workspace_id == WORKSPACE,
            CashTransactionComponentModel.cash_transaction_id == identifier).order_by(CashTransactionComponentModel.ordinal))]
    return _wire(result)


def state(session):
    def rows(model, fields, order):
        return [{key: getattr(row, key) for key in fields} for row in session.scalars(select(model).where(
            model.workspace_id == WORKSPACE).order_by(*[getattr(model, key) for key in order]))]
    parents = rows(CashTransactionModel, ("id", "state", "record_origin", "source_type", "record_id", "source_payload", "relation_metadata",
        "manual_overrides", "row_revision", "account_id", "amount", "currency", "occurred_at", "cash_granularity",
        "counterparty", "note", "category_id", "counterparty_account", "counterparty_account_attrs", "record_type", "record_subtype"), ("id",))
    parts = rows(CashTransactionComponentModel, ("id", "cash_transaction_id", "account_id", "amount", "currency", "ordinal", "label", "source_key", "metadata_json"), ("id",))
    bindings = rows(CashSourceBindingModel, ("transaction_id", "source_type", "source_scope_kind", "source_scope_key", "identity_kind", "identity_key", "identity_strength", "stable_evidence_key", "coverage", "revision"),
        ("transaction_id", "source_scope_kind", "source_scope_key", "identity_kind", "identity_key"))
    issues = rows(CashTransactionIssueModel, ("id", "transaction_id", "issue_key", "code", "field_path", "pending_fields", "pending_source_payload", "origin", "revision"), ("id",))
    candidates = rows(CashIssueCandidateModel, ("issue_id", "candidate_transaction_id", "expected_row_revision"), ("issue_id", "candidate_transaction_id"))
    relations = rows(TransactionRelationModel, ("id", "state", "active", "row_revision", "primary_transaction_id", "secondary_transaction_id", "anchor_transaction_id",
        "primary_component_id", "secondary_component_id", "anchor_component_id", "primary_role", "secondary_role", "decision_source", "decided_by", "decision_reason"), ("id",))
    relation_candidates = rows(CashRelationCandidateModel, ("relation_id", "candidate_transaction_id", "candidate_component_id", "expected_row_revision", "validity"), ("relation_id", "candidate_transaction_id"))
    funding = rows(CashInvestmentFundingRelationModel, ("id", "status", "active_slot", "decided_by", "decision_reason"), ("id",))
    entries = rows(CashEntryModel, ("root_transaction_id", "revision", "category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs", "manual_fields", "split_inherit"), ("root_transaction_id",))
    return _wire({"parents": parents, "components": parts, "bindings": bindings, "issues": issues, "issue_candidates": candidates,
        "relations": relations, "relation_candidates": relation_candidates, "funding": funding, "entries": entries})


def export(database_url, output):
    export_preparation(database_url, output / "preparation")
    engine = create_relational_engine(database_url)
    sessions = create_session_factory(engine)
    try:
        with sessions.begin() as session:
            session.add(CashTransactionModel(id=7510, workspace_id=WORKSPACE, state="draft", record_origin="import", source_type="wechat",
                record_id="writer-draft", amount="-9.00", currency="CNY", occurred_at=NOW, cash_granularity="aggregate", account_id=None,
                counterparty="synthetic", note="", counterparty_account="", counterparty_account_attrs=[], record_type="consumption", record_subtype="not_applicable",
                source_payload={}, relation_metadata={}, manual_overrides={}, row_revision=1, created_at=NOW)); session.flush()
            for identifier, ordinal, amount in ((9901, 0, "-4.00"), (9902, 1, "-5.00")):
                session.add(CashTransactionComponentModel(id=identifier, workspace_id=WORKSPACE, cash_transaction_id=7510,
                    account_id=9201, amount=amount, currency="CNY", ordinal=ordinal, label=f"part-{ordinal}", source_key=f"source-{ordinal}", metadata_json={"preserved": True}, created_at=NOW))
            session.flush()
            for identifier, primary, part, state_, active in ((80101, 7510, 9901, "ignored", False), (80102, 7507, 9508, "draft", True)):
                session.add(TransactionRelationModel(id=identifier, workspace_id=WORKSPACE, kind="payment_mirror", subtype="", state=state_, active=active,
                    primary_transaction_id=primary, primary_component_id=part, anchor_transaction_id=primary, anchor_component_id=part,
                    row_revision=1, evidence={}, applied_amount="0", decision_source="rule", created_by="system", decided_by="", decision_reason="", rule_id="", created_at=NOW))
            session.flush()
            session.add(CashRelationCandidateModel(workspace_id=WORKSPACE, relation_id=80101, candidate_transaction_id=7510,
                candidate_component_id=9901, expected_row_revision=1, evidence={}, validity="incomplete"))
            session.add(CashRelationCandidateModel(workspace_id=WORKSPACE, relation_id=80102, candidate_transaction_id=7501,
                candidate_component_id=9501, expected_row_revision=1, evidence={}, validity="valid"))
        stored = json.loads((output / "preparation/input-session.json").read_text())
        files = tuple(_restore_file(item) for item in stored["content"]["files"])
        inputs = next(item["input"] for item in json.loads((output / "preparation/business-plan-cases.json").read_text()) if item["name"] == "selected")
        exported = []
        names = ("normal", "defer_rows", "duplicate", "public_edit", "type_mirror", "type_negative_refund", "type_funding",
            "type_financial_changed", "reorder_draft", "replace_draft", "delete_confirmed_component", "pending_issue", "resolve_issues", "explanation_only", "merge_flag_only")
        for name in names:
            with sessions() as session:
                transaction = session.begin()
                if engine.dialect.name == "postgresql":
                    for table in ("cash_transactions", "cash_transaction_components", "cash_transaction_issues"):
                        session.execute(text(f"SELECT setval('{table}_id_seq', (SELECT max(id) FROM {table}), true)"))
                planner = CashImportBusinessPlanBuilder(WORKSPACE, clock=lambda: NOW)
                source = CashImportSourcePlanner(session, WORKSPACE).prepare(files)
                if name in {"normal", "defer_rows"}:
                    choices = deepcopy(inputs)
                    if name == "defer_rows":
                        choices["row_choices"] = [{"row_ref": item["row_ref"], "action": "defer"} for item in choices["row_choices"]]
                    plan = planner(session=session, files=files, preview_input=choices, previous_plan=None, plan_id="unused", source_plan=source)
                    operations = plan["operations"]
                else:
                    identifier = 7510 if name in {"reorder_draft", "replace_draft"} else 7505 if name == "type_funding" else 7501
                    before = current(session, identifier)
                    after = deepcopy(before)
                    if name.startswith("type_"):
                        after["record_type"] = "refund" if name == "type_negative_refund" else "consumption" if name == "type_funding" else "investment_out"
                    if name == "type_financial_changed":
                        after["amount"] = "-6.15"; after["components"][0]["amount"] = "-6.15"
                    if name == "reorder_draft":
                        after["components"][0]["ordinal"], after["components"][1]["ordinal"] = 1, 0
                    if name == "replace_draft":
                        after["components"] = [{"account_id": "9201", "amount": "-9.00", "currency": "CNY", "ordinal": 0}]
                    if name == "delete_confirmed_component":
                        after["components"] = []
                    existing = list(session.scalars(select(CashTransactionIssueModel).where(CashTransactionIssueModel.workspace_id == WORKSPACE,
                        CashTransactionIssueModel.transaction_id == identifier)))
                    operation = {"operation_ref": "op_test", "occurrence_key": "test", "transaction_id": str(identifier), "expected_revision": 1,
                        "planned_action": "duplicate" if name in {"duplicate", "public_edit", "resolve_issues"} else "update_draft" if identifier == 7510 else "update_posted",
                        "current": before, "values": after, "manual_fields": [], "pending_fields": {}, "source_updates": [],
                        "issues": [{"code": item.code, "field_path": item.field_path} for item in existing],
                        "expected_issues": [{"code": item.code, "field_path": item.field_path} for item in existing],
                        "retained_issue_ids": [str(item.id) for item in existing], "type_changed": name.startswith("type_"),
                        "preview_components": [{"ordinal": part["ordinal"], "component_id": str(2**64 + index)} for index, part in enumerate(after["components"], 1)]}
                    if name == "public_edit":
                        operation["entry_edit"] = {"patch": {"note": "public note"}, "revision": 1}
                    if name == "pending_issue":
                        operation["pending_fields"] = {"counterparty": "pending", "source_payload": {"raw": "pending"}}
                        operation["issues"].append({"code": "source_update_pending", "field_path": None})
                        operation["source_explanation"] = {"note": "source note"}
                    if name == "resolve_issues":
                        operation["issues"] = []; operation["retained_issue_ids"] = []
                    if name == "explanation_only":
                        operation["source_explanation"] = {"note": "updated metadata"}
                    if name == "merge_flag_only":
                        operation["merge_draft"] = {"draft_id": "7510"}
                    operations = [operation]
                case = {"name": name, "operations": deepcopy(operations), "source_rows": {key: _wire(asdict(row)) for key, row in source.rows.items()}}
                result = []
                try:
                    for operation in operations:
                        identifier, mapping, before, after, touched = CashRecordWriter(WORKSPACE).save(session, operation, source.rows)
                        result.append({"transaction_id": identifier, "mapping": mapping, "before": before, "after": after,
                            "touched": sorted(touched), "issues_unchanged": _issues_unchanged(operation)})
                    session.flush()
                    case["result"] = _wire(result)
                except (CashRelationError, CashRecordPolicyError, SourceBindingConflict) as error:
                    case["error"] = error.code
                case["expected"] = state(session)
                exported.append(case)
                transaction.rollback()
        (output / "cases.json").write_text(json.dumps(_wire(exported), ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text((output / "preparation/database.json").read_text())
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)

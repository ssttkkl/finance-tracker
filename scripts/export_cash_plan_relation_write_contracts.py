"""Export anonymous transactional relation-group application contracts."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path

from sqlalchemy import select, text

from export_cash_relation_repository_contracts import WORKSPACE, connect, seed
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashEntryModel, CashTransactionModel, CashTransactionComponentModel, CashTransactionIssueModel, CashRelationCandidateModel, TransactionRelationModel
from ft.application.cash_plan_execution import CashPlanWriteOperations
from ft.domain.cash_record_policy import CashRecordPolicyError
from ft.domain.cash_relation_policy import CashRelationError


class Writer(CashPlanWriteOperations):
    _workspace_id = WORKSPACE
    _user_id = "writer-user"


def normalize(value):
    if isinstance(value, Decimal):
        return format(value.normalize(), "f") if value else "0"
    if isinstance(value, datetime):
        return "<time>"
    if isinstance(value, dict):
        return {str(key): normalize(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [normalize(item) for item in value]
    return value


def edge(identifier=2**64 + 31, *, transfer=False, open_=False):
    return {"id": identifier, "kind": "transfer_pair" if transfer else "refund_offset",
        "primary_transaction_id": "93003" if transfer else "93001", "secondary_transaction_id": None if open_ else "93004" if transfer else "93002",
        "primary_component_id": "94003" if transfer else "94001", "secondary_component_id": None if open_ else "94004" if transfer else "94002",
        "primary_role": "outgoing" if transfer else "expense", "secondary_role": None if open_ else "incoming" if transfer else "refund",
        "subtype": "ordinary_transfer" if transfer else "", "applied_amount": None if transfer else "10.00", "state": "confirmed", "active": True}


def group(suggestion="sg-refund", *, transfer=False, action="accept", identifier=2**64 + 31, open_=False):
    value = edge(identifier, transfer=transfer, open_=open_)
    value["state"] = {"accept": "confirmed", "defer": "draft", "ignore": "ignored", "skip": "draft"}[action]
    return {"suggestion_id": suggestion, "action": action, "edges": [value],
        "anchors": {str(identifier): value["primary_component_id"]}, "evidence": {str(identifier): {
            "primary_original_expense": False, "secondary_original_expense": False,
            "investment_refund_hard_key": False, "p2p_return_hard_key": False}},
        "parent_ids": [p for p in (value["primary_transaction_id"], value["secondary_transaction_id"]) if p], "candidates": []}


def state(session):
    repo = CurrentCashRelationRepository(session, WORKSPACE)
    def rows(model, names, sort):
        return [{key: getattr(row, key) for key in names} for row in session.scalars(select(model).where(
            model.workspace_id == WORKSPACE).order_by(getattr(model, sort)))]
    return normalize({"relations": [repo.get(row.id) for row in session.scalars(select(TransactionRelationModel).where(
        TransactionRelationModel.workspace_id == WORKSPACE).order_by(TransactionRelationModel.id))],
        "issues": rows(CashTransactionIssueModel, ("transaction_id", "issue_key", "code", "revision", "field_path", "pending_fields", "pending_source_payload", "origin"), "transaction_id"),
        "entries": rows(CashEntryModel, ("root_transaction_id", "revision", "note", "counterparty", "category_id", "counterparty_account", "counterparty_account_attrs", "manual_fields", "split_inherit"), "root_transaction_id")})


def definitions():
    cases = []
    def add(name, groups=None, **extra):
        cases.append({"name": name, "plan": {"relation_groups": groups if groups is not None else [group()], "relation_failures": []},
            "identity": {}, "components": {}, "manual": [], "mode": "save", **extra})
    add("accept")
    add("manual_common", [dict(group(), common_fields={"note": "common note"})], manual=["sg-refund"])
    add("defer", [group(action="defer", open_=True)])
    add("ignore", [group(action="ignore")])
    joint = [dict(group(), commit_group_id="joint"), dict(group("sg-transfer", transfer=True, identifier=2**64 + 32), commit_group_id="joint")]
    add("joint", joint)
    add("joint_skip", [dict(joint[0], action="skip", code="relation_endpoint_incompatible"), joint[1]])
    add("skip_default", [dict(group(action="skip"), parent_ids=["93001", str(2**65)])])
    add("fresh_missing", fresh=[])
    add("fresh_action", fresh=[group(action="defer")])
    add("fresh_edges", fresh=[dict(group(), edges=[dict(edge(), applied_amount="11.00")])])
    add("virtual_mapping", [dict(group(), parent_ids=[str(2**65), "93002"], edges=[dict(edge(), primary_transaction_id=str(2**65), primary_component_id=str(2**66))],
        anchors={str(2**64 + 31): str(2**66)})], identity={str(2**65): "93001"}, components={str(2**66): "94001"})
    add("unmapped_parent", [dict(group(), edges=[dict(edge(), primary_transaction_id=str(2**65))])])
    add("wrong_component", [dict(group(), edges=[dict(edge(), primary_component_id="94003")])])
    add("unproven_role", setup={"id": 93001, "record_type": "refund"})
    add("manual_forbidden", setup={"id": 93001, "record_origin": "manual"})
    add("invalid_common", [dict(group(), common_fields={"category_id": "missing"})])
    deferred = group(action="defer", open_=True)
    deferred["candidates"] = [{"edges": [edge()], "evidence": deepcopy(deferred["evidence"])}]
    add("defer_candidates", [deferred])
    add("defer_deleted_candidate", [deferred], setup={"id": 93002, "deleted_at": "time"})
    unknown = deepcopy(deferred)
    unknown["candidates"].append({"edges": [dict(edge(), secondary_transaction_id=str(2**65), secondary_component_id=str(2**66))], "evidence": deepcopy(deferred["evidence"])})
    add("defer_virtual_candidate", [unknown])
    add("unavailable_candidate", [deferred], mode="write", unavailable=["93002"])
    for name, members in (("duplicate", [group()]), ("duplicate_joint", joint), ("duplicate_candidates", [deferred])):
        add(name, members, repeat=True)
    for mutation in ("amount", "anchor", "evidence", "state", "candidate_revision", "candidate_stale", "joint_extra", "joint_inactive"):
        members = [deferred] if mutation.startswith("candidate_") else joint if mutation.startswith("joint_") else [group()]
        add("existing_" + mutation, members, mode="existing", mutation=mutation)
    failures = [{"code": "relation_endpoint_incomplete", "parent_ids": ["93001", "93002", str(2**65)]},
        {"code": "relation_endpoint_incomplete", "parent_ids": ["93002", "93001", str(2**65)]},
        {"code": "relation_role_unproven", "parent_ids": [str(2**65)]},
        {"code": "relation_context_changed", "parent_ids": ["93001"], "suggestion_id": "sg-already-handled"}]
    add("discovery", [], mode="discovery")
    cases[-1]["plan"]["relation_failures"] = failures
    add("discovery_mapping", [], mode="discovery", identity={str(2**65): "93003"})
    cases[-1]["plan"]["relation_failures"] = failures
    add("late_group_failure", [group(), group("sg-transfer", transfer=True, identifier=2**64 + 32)], fresh=[group()])
    return cases


def export(database_url, output):
    engine, sessions, jdbc = connect(database_url)
    seed(sessions)
    with sessions.begin() as session:
        session.get(CashTransactionModel, 93005).amount = Decimal("-100.00")
        session.get(CashTransactionComponentModel, 94005).amount = Decimal("-100.00")
        session.get(TransactionRelationModel, 9001).applied_amount = Decimal("100.00")
    try:
        cases = definitions()
        for case in cases:
            with sessions() as session:
                transaction = session.begin()
                if engine.dialect.name == "postgresql":
                    for table in ("transaction_relations", "cash_transaction_issues"):
                        session.execute(text(f"SELECT setval('{table}_id_seq', COALESCE((SELECT max(id) FROM {table}), 1), EXISTS(SELECT 1 FROM {table}))"))
                if case.get("setup"):
                    setup = dict(case["setup"])
                    parent = session.get(CashTransactionModel, setup.pop("id"))
                    for key, value in setup.items(): setattr(parent, key, datetime.now().astimezone() if key == "deleted_at" else value)
                    session.flush()
                writer, plan, identity, parts = Writer(), case["plan"], case["identity"], case["components"]
                affected, results, error, existing = set(), [], None, None
                try:
                    if case["mode"] == "discovery":
                        writer._discovery_issues(session, plan, identity, affected)
                        results = writer._discovery_results(plan, identity)
                    elif case["mode"] == "write":
                        existing = writer._write_group(session, plan["relation_groups"], identity, parts, "direct", set(), unavailable=case["unavailable"])
                    else:
                        results = writer._save_groups(session, plan, {"relation_groups": case.get("fresh", plan["relation_groups"])}, identity, parts, affected, manual=set(case["manual"]))
                        if case.get("repeat"):
                            affected = set()
                            results = writer._save_groups(session, plan, plan, identity, parts, affected, manual=set(case["manual"]))
                        if case["mode"] == "existing":
                            added = session.get(TransactionRelationModel, int(results[0]["relation_id"]))
                            mutation = case["mutation"]
                            if mutation == "amount": added.applied_amount = Decimal("11")
                            elif mutation == "anchor": added.anchor_component_id, added.anchor_transaction_id = added.secondary_component_id, added.secondary_transaction_id
                            elif mutation == "evidence": added.evidence = {"changed": True}
                            elif mutation == "state": added.state = "ignored"
                            elif mutation == "candidate_revision": session.get(CashTransactionModel, 93002).row_revision += 1
                            elif mutation == "candidate_stale": session.scalar(select(CashRelationCandidateModel).where(CashRelationCandidateModel.relation_id == added.id)).validity = "stale"
                            elif mutation == "joint_inactive": session.get(TransactionRelationModel, int(results[1]["relation_id"])).active = False
                            elif mutation == "joint_extra":
                                CurrentCashRelationRepository(session, WORKSPACE).add({"kind": "payment_mirror", "state": "ignored", "active": False,
                                    "primary_transaction_id": 93007, "anchor_transaction_id": 93007, "primary_component_id": 94008,
                                    "anchor_component_id": 94008, "decision_group_id": "joint"})
                            session.flush()
                            existing = writer._existing_groups(session, plan["relation_groups"], identity, parts)
                    session.flush()
                except (CashRelationError, CashRecordPolicyError) as failure:
                    error = failure.code
                case["expected"] = normalize({"results": results, "affected": sorted(affected), "error": error, "existing": existing, "state": state(session)})
                transaction.rollback()
        output.mkdir(parents=True, exist_ok=True)
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        print(f"exported {len(cases)} relation group cases")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)

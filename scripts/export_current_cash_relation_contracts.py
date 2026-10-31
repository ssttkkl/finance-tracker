"""Export isolated current-role relation writes and rejection contracts."""
from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime
from decimal import Decimal
import json
from pathlib import Path

from sqlalchemy import select, text

from export_cash_relation_repository_contracts import NOW, WORKSPACE, connect, seed
from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
from ft.domain.cash_relation_policy import CashRelationError


def normalize(value):
    if isinstance(value, Decimal):
        return format(value.normalize(), "f") if value else "0"
    if isinstance(value, datetime):
        return "<time>"
    if isinstance(value, dict):
        return {key: normalize(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [normalize(item) for item in value]
    return value


def definitions():
    draft = {"kind": "refund_offset", "primary_transaction_id": 93001,
        "primary_component_id": 94001, "anchor_transaction_id": 93001,
        "anchor_component_id": 94001, "primary_role": "expense"}
    complete = {**draft, "secondary_transaction_id": 93002, "secondary_component_id": 94002,
        "secondary_role": "refund", "state": "confirmed", "applied_amount": "10.00"}
    candidate = {"candidate_transaction_id": 93002, "candidate_component_id": 94002,
        "expected_row_revision": 1, "evidence": {"note": "anonymous"}}
    cases = []
    def add(name, values=None, steps=(), setup=None):
        cases.append({"name": name, "setup": setup, "steps": [{"method": "add", "values": deepcopy(values or draft)}, *deepcopy(list(steps))]})
    add("draft")
    add("confirmed", complete)
    add("ignored", {**complete, "state": "ignored", "active": False})
    add("metadata", {**draft, "decision_group_id": "joint", "evidence": {"text": "中文", "array": [True, None, 2]},
        "created_by": "actor", "decided_by": "actor", "decided_at": "2026-10-01T09:00:00Z"})
    for name, patch in (
        ("unknown_field", {"oops": 1}), ("bad_kind", {"kind": "unknown"}),
        ("bad_state", {"state": "accepted"}), ("bool_active", {"active": 1}),
        ("bool_id", {"primary_transaction_id": True}), ("long_id", {"primary_transaction_id": "0" * 20 + "93001"}),
        ("missing_parent", {"primary_transaction_id": 999999}),
        ("wrong_component", {"primary_component_id": 94002}),
        ("missing_anchor", {"anchor_transaction_id": None}), ("wrong_anchor", {"anchor_transaction_id": 93003}),
        ("same_parent", {"secondary_transaction_id": 93001}), ("secondary_role_without_parent", {"secondary_role": "refund"}),
        ("bad_role", {"primary_role": "income"}), ("bad_evidence", {"evidence": []}),
        ("huge_evidence", {"evidence": {"text": "中" * 11000}}),
        ("negative_amount", {"applied_amount": "-1"}), ("nonfinite_amount", {"applied_amount": "NaN"}),
        ("overscale_amount", {"applied_amount": "0.0000000000000000001"}),
        ("long_actor", {"created_by": "x" * 129}), ("empty_group", {"decision_group_id": ""}),
        ("bad_date", {"decided_at": "not-a-time"}),
    ):
        add(name, {**draft, **patch})
    add("confirmed_missing_component", {**complete, "secondary_component_id": None})
    add("confirmed_wrong_roles", {**complete, "primary_role": "refund"})
    add("manual_blocked", setup={"id": 93001, "record_origin": "manual"})
    add("manual_ignored", {**draft, "state": "ignored"}, setup={"id": 93001, "record_origin": "manual"})
    add("manual_transfer_blocked", setup={"id": 93001, "record_origin": "manual_transfer", "manual_transfer_id": "pair"})
    add("replace", steps=({"method": "replace", "revision": 1, "values": {"decision_reason": "reviewed", "state": "ignored"}},))
    add("replace_conflict", steps=({"method": "replace", "revision": 2, "values": {"state": "ignored"}},))
    add("replace_bool_revision", steps=({"method": "replace", "revision": True, "values": {}},))
    add("replace_invalid", steps=({"method": "replace", "revision": 1, "values": {"secondary_component_id": 94002}},))
    add("candidates", steps=({"method": "candidates", "revision": 1, "values": [candidate]},))
    add("candidate_stale", steps=({"method": "candidates", "revision": 1, "values": [{**candidate, "expected_row_revision": 2}]},))
    add("candidate_incomplete", steps=({"method": "candidates", "revision": 1, "values": [{**candidate, "candidate_component_id": None}]},))
    add("candidate_deleted", steps=({"method": "candidates", "revision": 1, "values": [candidate]},), setup={"id": 93002, "deleted_at": "2026-10-01T09:00:00Z"})
    for name, values in (
        ("candidate_anchor", [{**candidate, "candidate_transaction_id": 93001, "candidate_component_id": 94001}]),
        ("candidate_missing", [{**candidate, "candidate_transaction_id": 999999}]),
        ("candidate_wrong_part", [{**candidate, "candidate_component_id": 94003}]),
        ("candidate_duplicate", [candidate, candidate]),
        ("candidate_bad_validity", [{**candidate, "validity": "bad"}]),
        ("candidate_bad_revision", [{**candidate, "expected_row_revision": True}]),
        ("candidate_late_invalid", [candidate, {**candidate, "candidate_transaction_id": 93003, "candidate_component_id": 94003, "evidence": []}]),
        ("candidate_limit", [candidate] * 101),
    ):
        add(name, steps=({"method": "candidates", "revision": 1, "values": [candidate]}, {"method": "candidates", "revision": 2, "values": values}))
    add("candidate_conflict", steps=({"method": "candidates", "revision": 2, "values": [candidate]},))
    add("confirmed_candidates", complete, steps=({"method": "candidates", "revision": 1, "values": [candidate]},))
    add("replace_clears_candidates", steps=({"method": "candidates", "revision": 1, "values": [candidate]},
        {"method": "replace", "revision": 2, "values": {"state": "ignored"}}))
    add("candidate_clear", steps=({"method": "candidates", "revision": 1, "values": [candidate]},
        {"method": "candidates", "revision": 2, "values": []}))
    return cases


def restore(values):
    result = deepcopy(values)
    if isinstance(result, dict) and isinstance(result.get("decided_at"), str) and result["decided_at"].endswith("Z"):
        result["decided_at"] = datetime.fromisoformat(result["decided_at"].replace("Z", "+00:00"))
    return result


def export(database_url, output):
    engine, sessions, jdbc = connect(database_url)
    seed(sessions)
    cases = definitions()
    try:
        for case in cases:
            with sessions() as session:
                transaction = session.begin()
                if engine.dialect.name == "postgresql":
                    session.execute(text("SELECT setval('transaction_relations_id_seq', (SELECT max(id) FROM transaction_relations), true)"))
                if case["setup"]:
                    setup = dict(case["setup"])
                    row = session.get(CashTransactionModel, setup.pop("id"))
                    for key, value in setup.items():
                        setattr(row, key, NOW if key == "deleted_at" else value)
                    session.flush()
                repo, results, identifier, error = CurrentCashRelationRepository(session, WORKSPACE), [], None, None
                for step in case["steps"]:
                    try:
                        values = restore(step["values"])
                        if step["method"] == "add":
                            row = repo.add(values)
                            identifier = row["id"]
                        elif step["method"] == "replace":
                            row = repo.replace(identifier, values, expected_revision=step["revision"])
                        else:
                            row = repo.set_candidates(identifier, values, expected_revision=step["revision"])
                        results.append(row)
                    except CashRelationError as failure:
                        error = failure.code
                        break
                rows = [repo.get(row.id) for row in session.scalars(select(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == WORKSPACE).order_by(TransactionRelationModel.id))]
                case["expected"] = normalize({"results": results, "error": error, "rows": rows})
                transaction.rollback()
        output.mkdir(parents=True, exist_ok=True)
        (output / "cases.json").write_text(json.dumps(cases, ensure_ascii=False, indent=2) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
        print(f"exported {len(cases)} current relation cases")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()
    export(arguments.database_url, arguments.output)

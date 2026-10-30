"""Export relation planning service results from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlparse

from alembic import command
from alembic.config import Config
from sqlalchemy import delete, inspect, select

from ft.adapters.relational import create_relational_engine, create_session_factory
from ft.adapters.relational.models import CashRelationCandidateModel, TransactionRelationModel
from ft.adapters.relational.repositories import (
    RelationalAccountAliasRepository, RelationalCashflowRepository, RelationalFactDeletionRepository,
    RelationalRelationRepository,
)
from ft.application.cash_import_operations import _wire
from ft.application.relations import RelationService, relation_proposal_key, serialize_import_relation_plan
from ft.domain.relations.core.types import RelationEvidence, RelationProposal
from scripts.export_cash_relation_repository_contracts import NOW, WORKSPACE, connect, norm, seed

FACT_KEYS = ("id", "amount", "currency", "account_id", "account_name", "account_type", "occurred_at",
    "counterparty", "counterparty_account", "counterparty_account_attrs", "payment_method", "note",
    "record_type", "record_subtype", "bill_source", "source", "record_id", "source_identity",
    "parent_id", "component_ordinal", "cash_granularity", "raw_payload", "relation_metadata")


class RealUoW:
    def __init__(self, session):
        self.workspace_id = WORKSPACE
        self.cashflows = RelationalCashflowRepository(session, WORKSPACE)
        self.relations = RelationalRelationRepository(session, WORKSPACE)
        self.account_aliases = RelationalAccountAliasRepository(session, WORKSPACE)


class TransactionalUoW:
    """与生产 UoW 相同的上下文入口，供审核决定方法打开事务。"""

    def __init__(self, sessions):
        self._sessions = sessions
        self._session = None

    def __enter__(self):
        self._session = self._sessions()
        self.workspace_id = WORKSPACE
        self.cashflows = RelationalCashflowRepository(self._session, WORKSPACE)
        self.relations = RelationalRelationRepository(self._session, WORKSPACE)
        self.account_aliases = RelationalAccountAliasRepository(self._session, WORKSPACE)
        self.fact_deletions = RelationalFactDeletionRepository(self._session, WORKSPACE)
        return self

    def __exit__(self, *exc_info):
        self._session.close()

    def commit(self):
        self._session.commit()

    def _state(self):
        return SimpleNamespace(session=self._session)


def fact_case(fact):
    data = asdict(fact)
    data["amount"] = format(fact.signed_amount, "f")
    data["account_id"] = "" if data["account_id"] is None else str(data["account_id"])
    data["parent_id"] = None if data["parent_id"] is None else str(data["parent_id"])
    return {key: _wire(data[key]) for key in FACT_KEYS if key in data}


def proposal_case(proposal, facts):
    return {"key": relation_proposal_key(proposal, facts), "body": _wire(asdict(proposal))}


def plan_case(plan):
    return {
        "facts": [fact_case(item) for item in plan.facts],
        "proposals": [proposal_case(item, plan.facts) for item in plan.proposals],
        "context_digest": plan.context_digest,
        "external_context_digest": plan.external_context_digest,
    }


def preview_row():
    return {"record_id": "PREVIEW-1", "amount": "-15.00", "currency": "CNY",
        "occurred_at": "2026-10-05T10:00:00+00:00", "record_type": "consumption",
        "record_subtype": "not_applicable", "counterparty": "预览商户", "note": "预览",
        "bill_source": "alipay", "source_type": "alipay", "account_id": 9301,
        "account_name": "现金", "account_type": "cash", "components": []}


def decision_cases(sessions):
    labels = {}
    ids = {}
    with sessions.begin() as session:
        repo = RelationalRelationRepository(session, WORKSPACE)

        def add(label, payload):
            identifier = repo.add(payload)
            labels[identifier] = label
            ids[label] = identifier
            return identifier

        add("r1", {"kind": "refund_offset", "primary_fact_id": "93001", "secondary_fact_id": "93002",
            "status": "pending_review", "rule_id": "refund.pending.v1", "applied_amount": "10.00"})
        add("r2", {"kind": "transfer_pair", "primary_fact_id": "93003", "status": "pending_review",
            "subtype": "ordinary_transfer", "rule_id": "transfer.open.v1", "anchor_fact_id": "93003",
            "candidate_fact_ids": [94004]})
        add("r3", {"kind": "refund_offset", "primary_fact_id": "93002", "status": "pending_review",
            "rule_id": "refund.open.v1", "anchor_fact_id": "93002", "candidate_fact_ids": [94001]})
        add("r4", {"kind": "refund_offset", "primary_fact_id": "93007", "secondary_fact_id": "93002",
            "status": "pending_review", "rule_id": "refund.reject.v1", "applied_amount": "5.00"})
        add("r6", {"kind": "refund_offset", "primary_fact_id": "93007", "secondary_fact_id": "93002",
            "status": "pending_review", "subtype": "ordinary", "rule_id": "refund.supersede.v1"})
        add("r8", {"kind": "transfer_pair", "primary_fact_id": "93007", "status": "pending_review",
            "subtype": "ordinary_transfer", "rule_id": "transfer.open2.v1", "anchor_fact_id": "93007",
            "candidate_fact_ids": [94003]})
        add("r9", {"kind": "refund_offset", "primary_fact_id": "93002", "status": "pending_review",
            "subtype": "alternate", "rule_id": "refund.open2.v1", "anchor_fact_id": "93002", "candidate_fact_ids": [94004, 94001]})
        add("r10", {"kind": "refund_offset", "primary_fact_id": "93005", "secondary_fact_id": "93002",
            "status": "pending_review", "subtype": "delete", "rule_id": "refund.delete.v1"})
    uow = TransactionalUoW(sessions)
    service = RelationService(uow)
    cases = {}

    def capture(call):
        try:
            result = call()
            return norm(result if not hasattr(result, "details") else
                {"ok": result.ok, "count": result.count, "message": result.message,
                 "details": dict(result.details)}, labels)
        except Exception as error:  # noqa: BLE001 - golden records public failure text
            return {"error": f"{type(error).__name__}: {error}"}

    reverse = {identifier: label for identifier, label in labels.items()}
    cases["pending_before"] = capture(lambda: [norm(item, labels) for item in service.list_pending()])
    cases["accept"] = [capture(lambda: service.accept(ids["r1"], actor="user", reason="确认")),
        capture(lambda: service.accept(ids["r2"], actor="user", other_fact_id=94004))]
    cases["accept_errors"] = [
        capture(lambda: service.accept(ids["r1"], actor="user")),
        capture(lambda: service.accept(999999, actor="user")),
        capture(lambda: service.accept(ids["r8"], actor="user")),
        capture(lambda: service.accept(ids["r8"], actor="user", other_fact_id=94006)),
        capture(lambda: service.accept(ids["r9"], actor="user", other_fact_id=94004)),
    ]
    cases["reject"] = capture(lambda: service.reject(ids["r4"], actor="user", reason="不要"))
    cases["reject_error"] = capture(lambda: service.reject(ids["r4"], actor="user"))
    replacement = {"kind": "refund_offset", "subtype": "p2p_return", "primary_fact_id": "93005",
        "secondary_fact_id": "93002", "status": "pending_review", "rule_id": "refund.replacement.v1"}
    cases["supersede"] = capture(lambda: service.supersede(ids["r6"], replacement=replacement))
    if isinstance(cases["supersede"].get("details"), dict):
        details = cases["supersede"]["details"]
        details["old_id"] = reverse.get(details.get("old_id"), details.get("old_id"))
        new_identifier = details.get("new_id")
        reverse[new_identifier] = "replacement"
        labels[new_identifier] = "replacement"
        details["new_id"] = "replacement"
    cases["delete"] = capture(lambda: service.logical_delete_cash(93005, actor="user", reason="误记"))
    cases["delete_error"] = capture(lambda: service.logical_delete_cash(93005, actor="user", reason="again"))
    def persist_rejected(proposal):
        with sessions.begin() as session:
            return service._persist_rejected_proposal(RealUoW(session), proposal)

    cases["persist_rejected"] = [
        capture(lambda: persist_rejected(RelationProposal(
            kind="refund_offset", primary_fact_id="94008", secondary_fact_id="94002",
            status="accepted", rule_id="refund.reject.v1",
            evidence=RelationEvidence(extras={"refund_amount": "5.00"})))),
        capture(lambda: persist_rejected(RelationProposal(
            kind="refund_offset", primary_fact_id="94007", secondary_fact_id="94002", subtype="p2p_return",
            status="accepted", rule_id="refund.new.v1",
            evidence=RelationEvidence(extras={"refund_amount": "30.00"})))),
    ]
    for index, item in enumerate(cases["persist_rejected"]):
        identifier = item.get("id")
        if isinstance(identifier, int) and identifier not in labels:
            labels[identifier] = f"persisted_{index + 1}"
            item["id"] = labels[identifier]
    cases["pending_after"] = capture(lambda: [norm(item, labels) for item in service.list_pending()])
    cases["project"] = capture(lambda: norm(service.project(), labels))
    with sessions() as session:
        rows = session.scalars(select(TransactionRelationModel).order_by(TransactionRelationModel.id)).all()
        cases["final_rows"] = sorted([norm({key: getattr(row, key) for key in (
            "id", "kind", "subtype", "primary_component_id", "secondary_component_id", "state", "active",
            "row_revision", "decision_source", "applied_amount", "rule_id", "created_by", "decided_by",
            "decision_reason")}, labels) for row in rows], key=lambda row: str(row["id"]))
        candidates = session.scalars(select(CashRelationCandidateModel)).all()
        cases["final_candidates"] = sorted([labels.get(row.relation_id, row.relation_id),
            row.candidate_component_id] for row in candidates)
    return cases


def export(database_url: str, output: Path) -> None:
    engine, sessions, jdbc = connect(database_url)
    cases = {}
    try:
        sessions = seed(sessions)
        with sessions.begin() as session:
            session.execute(delete(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == WORKSPACE))
            session.execute(delete(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == WORKSPACE))
        with sessions() as session:
            uow = RealUoW(session)
            service = RelationService(uow)
            plan = service.plan_in_uow(uow)
            preview = service.plan_in_uow(uow, preview_rows=[preview_row()], seed_ids=["93001"])
            external = service.external_context_digest_in_uow(uow)
            cached = serialize_import_relation_plan(plan)
            results = []
            for cached_plan, expected in ((cached, None), (cached, plan.context_digest),
                    (dict(cached, external_context_digest="0" * 64), None)):
                try:
                    service.validate_cached_import_plan_context_in_uow(uow, cached_plan,
                        expected_plan_digest=expected)
                    results.append(None)
                except ValueError as error:
                    results.append(str(error))
            cases = {"plan": plan_case(plan), "preview": plan_case(preview),
                "external_digest": external, "validation": results}
            cases["decisions"] = decision_cases(sessions)
        output.mkdir(parents=True, exist_ok=True)
        (output / "expected.json").write_text(json.dumps({"cases": cases}, ensure_ascii=False,
            indent=2, default=str) + "\n")
        (output / "database.json").write_text(json.dumps({"jdbc_url": jdbc}) + "\n")
    finally:
        engine.dispose()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.database_url, args.output)

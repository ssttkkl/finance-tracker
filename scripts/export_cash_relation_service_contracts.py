"""Export relation planning service results from a Python-seeded isolated database."""
from __future__ import annotations

import argparse
from dataclasses import asdict, replace
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
from ft.adapters.relational.models import (
    CashRelationCandidateModel, CashTransactionComponentModel, CashTransactionModel,
    TransactionRelationModel,
)
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


def persistence_cases(sessions):
    labels = {}
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel).where(
            CashRelationCandidateModel.workspace_id == WORKSPACE))
        session.execute(delete(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == WORKSPACE))
    cases = {}
    remaining = {"94001": Decimal("100.00"), "94005": Decimal("5.00"), "94008": Decimal("5.00")}

    def call(proposal, *, automatic=False, accepted=None, provision=(), label=None):
        with sessions.begin() as session:
            uow = RealUoW(session)
            service = RelationService(TransactionalUoW(sessions))
            if automatic:
                result = service._persist_automatic_proposal(uow, proposal, remaining,
                    facts=service._list_active_cash_facts(uow), accepted_relations=accepted,
                    validation_proposals=provision)
            else:
                result = service._persist_proposal(uow, proposal, remaining,
                    accepted_relations=accepted)
            if result is not None and label and result["id"] not in labels:
                labels[result["id"]] = label
            return norm(result, labels)

    def refund(primary, secondary, status="accepted", subtype="", amount="30.00", rule="rule.refund.v1"):
        return RelationProposal(kind="refund_offset", primary_fact_id=primary, secondary_fact_id=secondary,
            subtype=subtype, status=status, rule_id=rule, anchor_fact_id=primary,
            evidence=RelationEvidence(extras={"refund_amount": amount}))

    def closed(proposal):
        return {"key": relation_proposal_key(proposal), "body": _wire(asdict(proposal))}

    invalid = refund("94001", "94002", amount="130.00")
    cases["automatic_invalid"] = call(invalid, automatic=True)
    accepted = refund("94001", "94002", amount="10.00")
    cases["new_accepted"] = call(accepted, automatic=True, label="new_accepted")
    cases["repeat_accepted"] = call(accepted)
    open_proposal = RelationProposal(kind="transfer_pair", primary_fact_id="94003", secondary_fact_id=None,
        subtype="ordinary_transfer", status="pending_review", rule_id="open.rule",
        anchor_fact_id="94003", evidence=RelationEvidence(candidate_fact_ids=("94004",)))
    cases["new_open"] = call(open_proposal, label="new_open")
    open_updated = RelationProposal(kind="transfer_pair", primary_fact_id="94003", secondary_fact_id=None,
        subtype="ordinary_transfer", status="pending_review", rule_id="open.rule",
        anchor_fact_id="94003", evidence=RelationEvidence(candidate_fact_ids=("94001", "94004")))
    cases["update_candidates"] = call(open_updated)
    bind_proposal = RelationProposal(kind="transfer_pair", primary_fact_id="94003",
        secondary_fact_id="94004", subtype="ordinary_transfer", status="accepted",
        rule_id="bind.rule", anchor_fact_id="94003", evidence=RelationEvidence())
    cases["bind_open"] = call(bind_proposal)
    pending_proposal = refund("94005", "94002", status="pending_review", subtype="pending", amount="5.00")
    cases["new_pending"] = call(pending_proposal, label="new_pending")
    cases["auto_upgrade"] = call(replace(pending_proposal, status="accepted"))
    conflict_proposal = refund("94003", "94002", subtype="conflict", amount="10.00")
    cases["kind_conflict"] = call(conflict_proposal, label="new_conflict")
    with sessions.begin() as session:
        identifier = RelationalRelationRepository(session, WORKSPACE).add({
            "kind": "refund_offset", "primary_fact_id": "94005", "secondary_fact_id": "94002",
            "primary_component_id": 94005, "secondary_component_id": 94002,
            "anchor_fact_id": "94005", "anchor_component_id": 94005,
            "subtype": "human", "status": "accepted", "rule_id": "human.rule",
            "created_by": "user", "decided_by": "user", "applied_amount": "5.00"})
        labels[identifier] = "seed_human"
    human_proposal = refund("94005", "94002", subtype="human", amount="5.00")
    cases["human_preserved"] = call(human_proposal)
    over_proposal = refund("94008", "94002", subtype="over", amount="10.00")
    cases["over_remaining"] = call(over_proposal, label="new_over")
    with sessions.begin() as session:
        identifier = RelationalRelationRepository(session, WORKSPACE).add({
            "kind": "refund_offset", "primary_fact_id": "94002", "status": "rejected",
            "primary_component_id": 94002, "anchor_component_id": 94002,
            "subtype": "occ", "rule_id": "occ.rule", "anchor_fact_id": "94002",
            "candidate_fact_ids": [94001]})
        labels[identifier] = "seed_occ"
    occupancy_proposal = RelationProposal(kind="refund_offset", primary_fact_id="94002",
        secondary_fact_id=None, subtype="occ", status="pending_review", rule_id="occ.rule2",
        anchor_fact_id="94002", evidence=RelationEvidence(candidate_fact_ids=("94004",)))
    cases["rejected_occupancy"] = call(occupancy_proposal)
    with sessions.begin() as session:
        identifier = RelationalRelationRepository(session, WORKSPACE).add({
            "kind": "refund_offset", "primary_fact_id": "94002", "status": "pending_review",
            "primary_component_id": 94002, "anchor_component_id": 94002,
            "subtype": "humanopen", "rule_id": "human.open", "anchor_fact_id": "94002",
            "created_by": "user", "decided_by": "user", "candidate_fact_ids": [94001]})
        labels[identifier] = "seed_humanopen"
    human_open = RelationProposal(kind="refund_offset", primary_fact_id="94002", secondary_fact_id=None,
        subtype="humanopen", status="pending_review", rule_id="human.open2", anchor_fact_id="94002",
        evidence=RelationEvidence(candidate_fact_ids=("94004",)))
    cases["human_open_preserved"] = call(human_open)
    cases["proposals"] = {
        "accepted": closed(accepted), "open": closed(open_updated), "bind": closed(bind_proposal),
        "pending": closed(pending_proposal), "conflict": closed(conflict_proposal),
        "human": closed(human_proposal), "over": closed(over_proposal),
        "occupancy": closed(occupancy_proposal), "human_open": closed(human_open),
    }
    with sessions() as session:
        rows = session.scalars(select(TransactionRelationModel).order_by(TransactionRelationModel.id)).all()
        cases["final_rows"] = sorted([norm({key: getattr(row, key) for key in (
            "id", "kind", "subtype", "primary_component_id", "secondary_component_id", "state", "active",
            "row_revision", "applied_amount", "rule_id", "created_by", "decided_by", "decision_reason")},
            labels) for row in rows], key=lambda row: str(row["id"]))
        candidates = session.scalars(select(CashRelationCandidateModel)).all()
        cases["final_candidates"] = sorted([labels.get(row.relation_id, row.relation_id),
            row.candidate_component_id] for row in candidates)
    return cases


def reconciliation_cases(sessions):
    cases = {}

    def reset(seeds):
        labels = {}
        with sessions.begin() as session:
            session.execute(delete(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == WORKSPACE))
            session.execute(delete(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == WORKSPACE))
            repo = RelationalRelationRepository(session, WORKSPACE)
            for label, payload in seeds:
                labels[repo.add(payload)] = label
        with sessions() as session:
            uow = RealUoW(session)
            service = RelationService(TransactionalUoW(sessions))
            facts = service._list_active_cash_facts(uow)
            affected = service._reconcile_system_payment_mirrors(uow, facts,
                protected_fact_ids=seeder.get("protected"), blocked_pairs=seeder.get("blocked"))
            rows = session.scalars(select(TransactionRelationModel).order_by(TransactionRelationModel.id)).all()
            snapshot = sorted([norm({key: getattr(row, key) for key in (
                "id", "kind", "subtype", "primary_component_id", "secondary_component_id", "state", "active",
                "row_revision", "decision_source", "rule_id", "created_by", "decided_by", "decision_reason")},
                labels) for row in rows], key=lambda row: str(row["id"]))
            return sorted(affected), snapshot

    mirror = lambda label, primary, secondary, created_by="system", status="accepted", decided_by="": (
        label, {"kind": "payment_mirror", "primary_fact_id": primary, "secondary_fact_id": secondary,
            "primary_component_id": primary, "secondary_component_id": secondary,
            "anchor_fact_id": primary, "anchor_component_id": primary, "status": status,
            "rule_id": "mirror.rule", "applied_amount": "0", "created_by": created_by,
            "decided_by": decided_by})
    seeder = {}
    cases["obsolete_automatic"] = reset([mirror("auto", 94001, 94008)])
    seeder = {"protected": {94005, 94008}}
    cases["human_protected"] = reset([mirror("auto", 94001, 94008), mirror("human", 94005, 94008,
        created_by="user", decided_by="user")])
    seeder = {}
    cases["human_only"] = reset([mirror("human", 94001, 94008, created_by="user", decided_by="user")])
    seeder = {"blocked": {frozenset(("94001", "94008"))}}
    cases["blocked_pair"] = reset([mirror("human_rejected", 94001, 94008, created_by="user",
        status="rejected", decided_by="user")])
    return cases


def _ensure_bank_refund(sessions):
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel).where(
            CashRelationCandidateModel.workspace_id == WORKSPACE))
        session.execute(delete(TransactionRelationModel).where(
            TransactionRelationModel.workspace_id == WORKSPACE))
        session.execute(delete(CashTransactionComponentModel).where(
            CashTransactionComponentModel.workspace_id == WORKSPACE,
            CashTransactionComponentModel.id == 94009))
        session.execute(delete(CashTransactionModel).where(
            CashTransactionModel.workspace_id == WORKSPACE, CashTransactionModel.id == 93008))
        session.add(CashTransactionModel(id=93008, workspace_id=WORKSPACE, account_id=9302, state="posted",
            record_origin="import", cash_granularity="atomic", source_type="icbc_debit", record_id="REL-8",
            source_payload={}, occurred_at=NOW, amount="30.00", currency="CNY", counterparty="商户",
            counterparty_account="", counterparty_account_attrs=[], note="", record_type="refund",
            record_subtype="not_applicable", row_revision=1, created_at=NOW))
        session.flush()
        session.add(CashTransactionComponentModel(id=94009, workspace_id=WORKSPACE, cash_transaction_id=93008,
            account_id=9302, amount="30.00", currency="CNY", ordinal=0, label="", source_key="",
            metadata_json={}, created_at=NOW))
    return sessions


def refund_reconciliation_cases(sessions):
    sessions = _ensure_bank_refund(sessions)
    cases = {}

    def reset(seeds):
        labels = {}
        with sessions.begin() as session:
            session.execute(delete(CashRelationCandidateModel).where(
                CashRelationCandidateModel.workspace_id == WORKSPACE))
            session.execute(delete(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == WORKSPACE))
            repo = RelationalRelationRepository(session, WORKSPACE)
            for label, payload in seeds:
                labels[repo.add(payload)] = label
        with sessions() as session:
            uow = RealUoW(session)
            service = RelationService(TransactionalUoW(sessions))
            facts = service._list_active_cash_facts(uow)
            affected, superseded = service._reconcile_system_refund_offsets(uow, facts,
                skip_refund_ids=seeder.get("skip"))
            rows = session.scalars(select(TransactionRelationModel).order_by(TransactionRelationModel.id)).all()
            counter = 0
            for row in rows:
                if row.id not in labels:
                    counter += 1
                    labels[row.id] = f"new_{counter}"
            snapshot = sorted([norm({key: getattr(row, key) for key in (
                "id", "kind", "subtype", "primary_component_id", "secondary_component_id", "state", "active",
                "row_revision", "applied_amount", "rule_id", "created_by", "decided_by", "decision_reason")},
                labels) for row in rows], key=lambda row: str(row["id"]))
            return sorted(affected), sorted(superseded), snapshot

    refund = lambda label, created_by="system", status="accepted", decided_by="": (
        label, {"kind": "refund_offset", "primary_fact_id": "93007", "secondary_fact_id": "93008",
            "primary_component_id": 94008, "secondary_component_id": 94009,
            "anchor_fact_id": "93007", "anchor_component_id": 94008, "status": status,
            "rule_id": "refund.bank.v1", "applied_amount": "30.00", "created_by": created_by,
            "decided_by": decided_by})
    seeder = {}
    cases["new_bank_refund"] = reset([])
    seeder = {}
    cases["existing_auto_kept"] = reset([refund("auto")])
    seeder = {"skip": {"94009"}}
    cases["existing_skipped"] = reset([refund("auto")])
    seeder = {}
    cases["human_kept"] = reset([refund("human", created_by="user", decided_by="user")])
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
            cases["persistence"] = persistence_cases(sessions)
            cases["reconciliation"] = reconciliation_cases(sessions)
            cases["refund_reconciliation"] = refund_reconciliation_cases(sessions)
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

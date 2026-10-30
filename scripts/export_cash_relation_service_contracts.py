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
    Base, CashProjectionDatasetModel, CashProjectionStateModel, CashRelationCandidateModel,
    CashTransactionComponentModel, CashTransactionModel,
    TransactionRelationModel,
)
from ft.adapters.relational.repositories import (
    RelationalAccountAliasRepository, RelationalCashflowRepository, RelationalFactDeletionRepository,
    RelationalRelationRepository,
)
from ft.application.cash_import_operations import _wire
from ft.application.relations import RelationService, relation_proposal_key, serialize_import_relation_plan
from ft.application.statement_import import StatementImportService
from ft.domain.relations.core.types import RelationEvidence, RelationProposal
from scripts.export_cash_relation_repository_contracts import NOW, WORKSPACE, connect, norm, seed

FACT_KEYS = ("id", "amount", "currency", "account_id", "account_name", "account_type", "occurred_at",
    "counterparty", "counterparty_account", "counterparty_account_attrs", "payment_method", "note",
    "record_type", "record_subtype", "bill_source", "source", "record_id", "source_identity",
    "parent_id", "component_ordinal", "cash_granularity", "raw_payload", "relation_metadata")


class RealUoW:
    def __init__(self, session):
        self._session = session
        self.workspace_id = WORKSPACE
        self.cashflows = RelationalCashflowRepository(session, WORKSPACE)
        self.relations = RelationalRelationRepository(session, WORKSPACE)
        self.account_aliases = RelationalAccountAliasRepository(session, WORKSPACE)

    def _state(self):
        return SimpleNamespace(session=self._session)


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


def reset_service_baseline(sessions):
    # connect() only accepts a new isolated test database; no other workspaces exist.
    with sessions.begin() as session:
        for table in reversed(Base.metadata.sorted_tables):
            session.execute(delete(table))
    seed(sessions)
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel))
        session.execute(delete(TransactionRelationModel))


def refund_candidate_cases(sessions):
    with sessions() as session:
        service = RelationService(RealUoW(session))
        facts = service._list_active_cash_facts(service._uow)
        by_id = {str(fact.id): fact for fact in facts}
        # The unmodified baseline must include both same-account expenses.
        assert "94005" in by_id, "refund group inherited a deleted expense from the decision group"
        refund = replace(by_id["94002"], id="refund-probe", amount=Decimal("20.00"),
            record_type="refund", bill_source="icbc_debit", source="icbc_debit")
        expenses = [replace(by_id[key], id=key) for key in ("94001", "94005")]
        from ft.domain.relations.refund.match import evaluate_refund_offset
        result = {}
        for name, candidates in (
            ("tied", expenses),
            ("deleted", [expenses[0], replace(expenses[1], deleted=True)]),
            ("strong", [expenses[0], replace(expenses[1], counterparty="其他")]),
        ):
            proposal = evaluate_refund_offset(refund, candidates)
            assert proposal is not None
            result[name] = _wire(asdict(proposal))
        assert result["tied"]["status"] == "pending_review"
        assert result["tied"]["open_leg"] is True
        assert result["tied"]["evidence"]["candidate_count"] == 2
        assert result["deleted"]["status"] == "accepted"
        assert result["strong"]["status"] == "accepted"
        return result


def redundant_refund_cases(sessions):
    _ensure_bank_refund(sessions)
    cases = {}
    proposal = RelationProposal(kind="refund_offset", primary_fact_id="94001",
        secondary_fact_id="94002", status="accepted", rule_id="scan.test.platform_refund",
        evidence=RelationEvidence(rule_id="scan.test.platform_refund",
            extras={"refund_amount": "30.00"}))

    for name, old_creator, old_decider, proposed_status, skipped in (
        ("unique", "system", "", "accepted", False),
        ("mixed_scale", "system", "", "accepted", False),
        ("pending", "system", "", "pending_review", False),
        ("skipped", "system", "", "accepted", True),
        ("human", "user", "user", "accepted", False),
    ):
        with sessions.begin() as session:
            session.execute(delete(CashRelationCandidateModel))
            session.execute(delete(TransactionRelationModel))
            repo = RelationalRelationRepository(session, WORKSPACE)
            old_id = repo.add({"kind": "refund_offset", "primary_fact_id": "94001",
                "secondary_fact_id": "94009", "primary_component_id": 94001,
                "secondary_component_id": 94009, "anchor_fact_id": "94001",
                "anchor_component_id": 94001, "status": "accepted", "rule_id": "refund.bank.v1",
                "applied_amount": "30.00", "created_by": old_creator, "decided_by": old_decider})
        with sessions.begin() as session:
            uow = RealUoW(session)
            service = RelationService(TransactionalUoW(sessions))
            facts = service._list_active_cash_facts(uow)
            if name == "mixed_scale":
                facts = [replace(fact, amount=Decimal("30")) if str(fact.id) == "94009"
                    else fact for fact in facts]
            accepted = [dict(row) for row in uow.relations.list_active(status="accepted")]
            actual = replace(proposal, status=proposed_status)
            superseded = service._supersede_redundant_bank_refunds(uow, facts, [actual], accepted,
                skip_refund_ids={"94002"} if skipped else set(), proposed_pairs_only=True)
            rows = uow.relations.list_active()
            old = uow.relations.get(old_id)
            cases[name] = {"superseded": sorted(superseded), "old_status": old["status"],
                "accepted_pairs": sorted((str(row.get("primary_fact_id") or ""),
                    str(row.get("secondary_fact_id") or "")) for row in rows
                    if row["kind"] == "refund_offset" and row["status"] == "accepted")}
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel))
        session.execute(delete(TransactionRelationModel))
        session.add(CashTransactionModel(id=93009, workspace_id=WORKSPACE, account_id=9302,
            state="posted", record_origin="import", cash_granularity="atomic",
            source_type="icbc_debit", record_id="REL-9", source_payload={}, occurred_at=NOW,
            amount="30.00", currency="CNY", counterparty="商户", counterparty_account="",
            counterparty_account_attrs=[], note="", record_type="refund",
            record_subtype="not_applicable", row_revision=1, created_at=NOW))
        session.flush()
        session.add(CashTransactionComponentModel(id=94010, workspace_id=WORKSPACE,
            cash_transaction_id=93009, account_id=9302, amount="30.00", currency="CNY",
            ordinal=0, label="", source_key="", metadata_json={}, created_at=NOW))
        repo = RelationalRelationRepository(session, WORKSPACE)
        for bank_id in (94009, 94010):
            repo.add({"kind": "refund_offset", "primary_fact_id": "94001",
                "secondary_fact_id": str(bank_id), "primary_component_id": 94001,
                "secondary_component_id": bank_id, "anchor_fact_id": "94001",
                "anchor_component_id": 94001, "status": "accepted", "rule_id": "refund.bank.v1",
                "applied_amount": "30.00", "created_by": "system"})
    with sessions.begin() as session:
        uow = RealUoW(session)
        service = RelationService(TransactionalUoW(sessions))
        affected = service._supersede_redundant_bank_refunds(uow,
            service._list_active_cash_facts(uow), [proposal],
            [dict(row) for row in uow.relations.list_active(status="accepted")],
            proposed_pairs_only=True)
        rows = uow.relations.list_active()
        cases["ambiguous"] = {"superseded": sorted(affected),
            "bank_statuses": sorted(row["status"] for row in rows
                if str(row.get("secondary_fact_id") or "") in {"94009", "94010"}),
            "accepted_pairs": sorted((str(row.get("primary_fact_id") or ""),
                str(row.get("secondary_fact_id") or "")) for row in rows
                if row["kind"] == "refund_offset" and row["status"] == "accepted")}
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel))
        session.execute(delete(TransactionRelationModel))
        repo = RelationalRelationRepository(session, WORKSPACE)
        relation_ids = []
        for refund_id in (94009, 94002):
            relation_ids.append(repo.add({"kind": "refund_offset", "primary_fact_id": "94001",
                "secondary_fact_id": str(refund_id), "primary_component_id": 94001,
                "secondary_component_id": refund_id, "anchor_fact_id": "94001",
                "anchor_component_id": 94001, "status": "accepted", "rule_id": "refund.seed.v1",
                "applied_amount": "30.00", "created_by": "system"}))
    with sessions.begin() as session:
        uow = RealUoW(session)
        service = RelationService(TransactionalUoW(sessions))
        affected = service._supersede_redundant_bank_refunds(uow,
            service._list_active_cash_facts(uow), [],
            [dict(row) for row in uow.relations.list_active(status="accepted")],
            proposed_pairs_only=True)
        cases["cached_without_pair"] = {"superseded": sorted(affected),
            "statuses": [uow.relations.get(identifier)["status"] for identifier in relation_ids]}
    with sessions.begin() as session:
        session.execute(delete(CashRelationCandidateModel))
        session.execute(delete(TransactionRelationModel))
        session.get(CashTransactionModel, 93001).amount = Decimal("-30.00")
        session.get(CashTransactionComponentModel, 94001).amount = Decimal("-30.00")
        repo = RelationalRelationRepository(session, WORKSPACE)
        for kind, primary, secondary, amount in (
            ("payment_mirror", 94001, 94008, "0"),
            ("payment_mirror", 94002, 94009, "0"),
            ("refund_offset", 94008, 94009, "30.00"),
        ):
            repo.add({"kind": kind, "primary_fact_id": str(primary),
                "secondary_fact_id": str(secondary), "primary_component_id": primary,
                "secondary_component_id": secondary, "anchor_fact_id": str(primary),
                "anchor_component_id": primary, "status": "accepted", "rule_id": "mirror.seed.v1",
                "applied_amount": amount, "created_by": "system"})
    with sessions.begin() as session:
        uow = RealUoW(session)
        service = RelationService(TransactionalUoW(sessions))
        affected = service._supersede_redundant_bank_refunds(uow,
            service._list_active_cash_facts(uow), [proposal],
            [dict(row) for row in uow.relations.list_active(status="accepted")],
            proposed_pairs_only=True)
        rows = uow.relations.list_active()
        cases["mirrored_closure"] = {"superseded": sorted(affected),
            "accepted_pairs": sorted((str(row.get("primary_fact_id") or ""),
                str(row.get("secondary_fact_id") or "")) for row in rows
                if row["kind"] == "refund_offset" and row["status"] == "accepted")}
    return cases


def check_cases(sessions):
    cases = {}
    for name, seeds, trigger in (("parent_seed", ["93001"], "import_batch"),
            ("component_seed", ["94002"], "manual"),
            ("full_workspace", None, "import_batch")):
        reset_service_baseline(sessions)
        result = RelationService(TransactionalUoW(sessions)).check(
            seed_fact_ids=seeds, seed_batch_id="retired-batch" if name == "full_workspace" else None,
            trigger=trigger)
        assert result.ok
        cases[name] = {"count": result.count, "stats": result.details["stats"],
            "relations": sorted([{"kind": row["kind"], "status": row["status"],
                "primary_fact_id": str(row.get("primary_fact_id") or ""),
                "secondary_fact_id": str(row.get("secondary_fact_id") or ""),
                "rule_id": row["rule_id"]} for row in result.details["relations"]],
                key=lambda row: (row["kind"], row["primary_fact_id"], row["secondary_fact_id"]))}
    return cases


def import_application_cases(sessions):
    cases = {}

    def summary(rows):
        return sorted(({"kind": row["kind"], "status": row["status"],
            "primary_fact_id": str(row.get("primary_fact_id") or ""),
            "secondary_fact_id": str(row.get("secondary_fact_id") or ""),
            "rule_id": row["rule_id"]} for row in rows),
            key=lambda row: (row["kind"], row["primary_fact_id"], row["secondary_fact_id"]))

    for name in ("automatic", "rejected", "accepted_later", "skipped", "stale_digest",
            "invalid_secondary", "workspace"):
        reset_service_baseline(sessions)
        try:
            with sessions.begin() as session:
                uow = RealUoW(session)
                service = RelationService(TransactionalUoW(sessions))
                seeds = ["94002"] if name != "workspace" else [str(f.id)
                    for f in service._list_active_cash_facts(uow)]
                plan = service.plan_in_uow(uow, seed_ids=seeds)
                decisions = None
                if name in {"rejected", "accepted_later", "skipped", "invalid_secondary"}:
                    proposal = next(item for item in plan.proposals if item.kind == "refund_offset")
                    decision = {"proposal_key": relation_proposal_key(proposal, plan.facts),
                        "primary_fact_id": "94001", "secondary_fact_id": "94002",
                        "status": {"rejected": "rejected", "accepted_later": "accepted",
                            "skipped": "skipped", "invalid_secondary": "accepted"}[name]}
                    if name == "invalid_secondary":
                        decision["secondary_fact_id"] = "94008"
                    decisions = [decision]
                created, affected = service.apply_import_plan_in_uow(uow, seed_ids=seeds,
                    relation_decisions=decisions,
                    expected_digest="0" * 64 if name == "stale_digest" else plan.context_digest)
                cases[name] = {"created": summary(created), "affected": sorted(affected),
                    "active": summary(uow.relations.list_active()), "error": None}
        except ValueError as error:
            with sessions() as session:
                uow = RealUoW(session)
                cases[name] = {"created": [], "affected": [],
                    "active": summary(uow.relations.list_active()), "error": str(error)}
    return cases


def cached_application_cases(sessions):
    cases = {}

    def summary(rows):
        return sorted(({"kind": row["kind"], "status": row["status"],
            "primary_fact_id": str(row.get("primary_fact_id") or ""),
            "secondary_fact_id": str(row.get("secondary_fact_id") or ""),
            "rule_id": row["rule_id"]} for row in rows),
            key=lambda row: (row["kind"], row["primary_fact_id"], row["secondary_fact_id"]))

    for name in ("automatic", "rejected", "accepted_later", "skipped", "stale_digest",
            "corrupt_key", "invalid_secondary", "workspace"):
        reset_service_baseline(sessions)
        try:
            with sessions.begin() as session:
                uow = RealUoW(session)
                service = RelationService(TransactionalUoW(sessions))
                seeds = ["94002"] if name != "workspace" else [str(f.id)
                    for f in service._list_active_cash_facts(uow)]
                plan = service.plan_in_uow(uow, seed_ids=seeds)
                cached = serialize_import_relation_plan(plan)
                decisions = None
                if name in {"rejected", "accepted_later", "skipped", "invalid_secondary"}:
                    proposal = next(item for item in plan.proposals if item.kind == "refund_offset")
                    decisions = [{"proposal_key": relation_proposal_key(proposal, plan.facts),
                        "primary_fact_id": "94001",
                        "secondary_fact_id": "94008" if name == "invalid_secondary" else "94002",
                        "status": {"rejected": "rejected", "accepted_later": "accepted",
                            "skipped": "skipped", "invalid_secondary": "accepted"}[name]}]
                if name == "corrupt_key":
                    cached["proposals"][0]["proposal_key"] = "0" * 64
                created, affected, accepted = service.apply_cached_import_plan_in_uow(uow,
                    cached_plan=cached, relation_decisions=decisions,
                    expected_digest="0" * 64 if name == "stale_digest" else cached["plan_digest"])
                cases[name] = {"created": summary(created), "affected": sorted(affected),
                    "active": summary(uow.relations.list_active()),
                    "accepted_later": [item["status"] for item in accepted], "error": None}
        except ValueError as error:
            with sessions() as session:
                uow = RealUoW(session)
                cases[name] = {"created": [], "affected": [],
                    "active": summary(uow.relations.list_active()), "accepted_later": [],
                    "error": str(error)}
    return cases


def import_manual_decision_cases(sessions):
    cases = {}

    def summary(rows):
        return sorted(({"kind": row["kind"], "status": row["status"],
            "primary_fact_id": str(row.get("primary_fact_id") or ""),
            "secondary_fact_id": str(row.get("secondary_fact_id") or ""),
            "created_by": row["created_by"], "decided_by": row.get("decided_by") or ""}
            for row in rows), key=lambda row: (row["kind"], row["primary_fact_id"], row["secondary_fact_id"]))

    for name in ("accepted", "rejected", "skipped", "missing", "same", "invalid_kind",
            "preexisting_only", "source_ref", "preview_ref"):
        reset_service_baseline(sessions)
        try:
            with sessions.begin() as session:
                session.execute(delete(CashProjectionStateModel).where(
                    CashProjectionStateModel.workspace_id == WORKSPACE))
                session.execute(delete(CashProjectionDatasetModel).where(
                    CashProjectionDatasetModel.workspace_id == WORKSPACE))
                session.add(CashProjectionDatasetModel(id="manual-ready", workspace_id=WORKSPACE,
                    state="active", source_revision=0, source_digest="0" * 64,
                    rules_version="projection-v1", created_at=NOW, published_at=NOW))
                session.add(CashProjectionStateModel(workspace_id=WORKSPACE,
                    active_dataset_id="manual-ready", projection_version=1, source_revision=0,
                    rules_version="projection-v1", availability="ready", last_build_status="succeeded",
                    projection_count=0, member_count=0, updated_at=NOW))
                session.flush()
                uow = RealUoW(session)
                relation_service = RelationService(TransactionalUoW(sessions))
                service = StatementImportService(None, None, relation_service)
                decision = {"kind": "refund_offset", "primary_fact_id": "93001",
                    "secondary_fact_id": "93002", "status": "accepted"}
                if name == "rejected": decision["status"] = "rejected"
                if name == "skipped": decision["status"] = "skipped"
                if name == "missing": decision["secondary_fact_id"] = "999999"
                if name == "same": decision["secondary_fact_id"] = "93001"
                if name == "invalid_kind": decision["kind"] = "invalid"
                if name == "source_ref":
                    decision.pop("secondary_fact_id")
                    decision["secondary_record_ref"] = "wechat:REL-2"
                if name == "preview_ref": decision["secondary_fact_id"] = "preview:REL-2"
                accepted = service._apply_relation_decisions(uow, [decision],
                    fact_id_by_record_id={"REL-2": 93002},
                    fact_id_by_source_record_ref={"wechat:REL-2": 93002},
                    new_fact_ids=set() if name == "preexisting_only" else {93002})
                cases[name] = {"accepted_count": len(accepted),
                    "active": summary(uow.relations.list_active()), "error": None}
        except ValueError as error:
            with sessions() as session:
                uow = RealUoW(session)
                cases[name] = {"accepted_count": 0,
                    "active": summary(uow.relations.list_active()), "error": str(error)}
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
        for name, build in (
            ("decisions", decision_cases), ("persistence", persistence_cases),
            ("reconciliation", reconciliation_cases), ("refund_reconciliation", refund_reconciliation_cases),
            ("refund_candidates", refund_candidate_cases),
            ("redundant_refunds", redundant_refund_cases),
            ("check", check_cases),
            ("import_application", import_application_cases),
            ("cached_application", cached_application_cases),
            ("import_manual_decisions", import_manual_decision_cases),
        ):
            reset_service_baseline(sessions)
            cases[name] = build(sessions)
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

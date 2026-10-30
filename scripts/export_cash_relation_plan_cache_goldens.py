"""Export cached relation plan decoding and decision matching from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.application.relations import (
    RelationPlan, RelationService, _fact_detail_row, _fact_view_from_row,
    relation_proposal_key, serialize_import_relation_plan,
)
from ft.domain.relations.core.types import FactView, RelationEvidence, RelationProposal


class FakeUoW:
    def __init__(self, rows, relations=(), aliases=()):
        self.workspace_id = "w"
        self._rows = rows
        self._relations = [dict(item) for item in relations]
        self.relations = self
        self.cashflows = self
        self.account_aliases = self
        self._aliases = [dict(item) for item in aliases]

    def list_detailed(self, include_deleted=False):
        return [dict(item) for item in self._rows]

    def list_active(self, *, kind=None, status=None):
        return [dict(item) for item in self._relations
            if (kind is None or item.get("kind") == kind)
            and (status is None or item.get("status") == status)]

    def list(self):
        return [dict(item) for item in self._aliases]


def fact(**changes):
    base = FactView(id="p1", amount="-100.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="R1")
    return replace(base, **changes)


def proposal(**changes):
    base = RelationProposal(kind="refund_offset", primary_fact_id="p1", secondary_fact_id="q1",
        status="accepted", rule_id="scan.refund", anchor_fact_id="p1",
        evidence=RelationEvidence(amount_delta="0", time_delta_seconds=5, signals=("refund",),
            candidate_fact_ids=("q1",), extras={"refund_amount": "30.00"}))
    return replace(base, **changes)


def main_export():
    refund = fact(id="q1", amount="30.00", record_type="refund", bill_source="wechat", source="wechat",
        record_id="R2", occurred_at=datetime(2026, 10, 2, 10, 0, tzinfo=timezone.utc))
    open_leg = proposal(kind="transfer_pair", primary_fact_id="q1", secondary_fact_id=None,
        status="pending_review", rule_id="scan.open", anchor_fact_id="",
        evidence=RelationEvidence(open_leg=True, candidate_fact_ids=("p1",)))
    pending = proposal(status="pending_review", confidence="weak",
        evidence=RelationEvidence(amount_delta="0.00", time_delta_seconds=None,
            candidate_fact_ids=("q1", "p1"), extras={"refund_amount": "30.00"}))
    facts = (fact(), refund)
    plan = RelationPlan(facts=facts, proposals=(proposal(), open_leg, pending),
        context_digest="d" * 64, external_context_digest="e" * 64)
    cached = serialize_import_relation_plan(plan)
    rows = [_fact_detail_row(item) for item in facts]
    uow = FakeUoW(rows)
    service = RelationService(uow)
    decoded_facts, decoded = service._cached_proposals_in_uow(uow, cached)
    cases = {
        "cached": _wire(cached),
        "decoded": [{"key": relation_proposal_key(item, decoded_facts), "body": _wire(asdict(item))}
            for item in decoded],
        "decoded_facts": [item.id for item in decoded_facts],
        "by_reference": sorted(service._facts_by_cached_reference(decoded_facts)),
        "resolve_ref": [service._resolve_fact_reference(value, decoded_facts)
            for value in ("p1", "preview:p1", "alipay:R1", "R2", "missing")],
        "seeds": {
            "expanded": service._resolve_seeds(uow, seed_fact_ids=["p1", "unknown", "q1"], seed_batch_id=None),
            "all": service._resolve_seeds(uow, seed_fact_ids=None, seed_batch_id=None),
        },
        "aliases": service._alias_indexes(FakeUoW(rows, aliases=[
            {"alias_type": "card_tail", "alias_value": "1234", "account_id": "a"},
            {"alias_type": "card_tail", "alias_value": "12-34", "account_id": "b"},
            {"alias_type": "card_tail", "alias_value": "12345", "account_id": "c"},
            {"alias_type": "account_identifier", "alias_value": "622202123456", "account_id": "a"},
            {"alias_type": "account_identifier", "alias_value": "1234", "account_id": "d"},
        ])),
    }
    tails, identifiers = cases["aliases"]
    cases["aliases"] = {
        "card_tails": {key: sorted(value) for key, value in sorted(tails.items())},
        "account_identifiers": {key: sorted(value) for key, value in sorted(identifiers.items())},
    }
    rows_with_components = [
        {"id": "p1", "record_id": "R1", "amount": "-100.00", "currency": "CNY", "record_type": "consumption",
            "cash_granularity": "aggregate", "components": [
                {"id": "c1", "ordinal": 1, "amount": "-60.00", "currency": "CNY", "account_id": "a"},
                {"id": "c2", "ordinal": 2, "amount": "-40.00", "currency": "CNY", "account_id": "b"}]},
        {"id": "q1", "record_id": "R2", "amount": "30.00", "currency": "CNY", "record_type": "refund",
            "components": [{"id": "c3", "ordinal": 1, "amount": "30.00", "currency": "CNY", "account_id": "a"}]},
        {"id": "s1", "record_id": "R3", "amount": "-5.00", "currency": "CNY", "record_type": "consumption"},
    ]
    expanded = RelationService._expand_component_rows(rows_with_components)
    cases["expanded"] = [{key: _wire(item.get(key)) for key in
        ("id", "parent_id", "component_ordinal", "amount", "account_id", "cash_granularity", "currency", "record_id")}
        for item in expanded]

    relations = [
        {"id": 1, "kind": "refund_offset", "status": "accepted", "primary_fact_id": "p1",
            "secondary_fact_id": "q1", "applied_amount": "10.00", "created_by": "system", "decided_by": ""},
        {"id": 2, "kind": "refund_offset", "status": "accepted", "primary_fact_id": "s1",
            "secondary_fact_id": "missing", "created_by": "system", "decided_by": ""},
        {"id": 3, "kind": "payment_mirror", "status": "accepted", "primary_fact_id": "p1",
            "secondary_fact_id": "s1", "created_by": "system", "decided_by": ""},
    ]
    cases["remaining"] = {key: str(value) for key, value in service._refund_remaining(
        FakeUoW(rows, relations=relations), decoded_facts).items()}
    decision = {"proposal_key": relation_proposal_key(proposal(), decoded_facts),
        "primary_record_ref": "alipay:R1", "secondary_record_ref": "R2"}
    cases["decisions"] = {
        "mirror": service._decision_mirror_endpoints(proposal(
            kind="payment_mirror", anchor_fact_id="p1"), decision, decoded_facts),
        "matches": service._decision_matches(proposal(), decision, decoded_facts),
        "primary": service._decision_primary_matches(proposal(), decision, decoded_facts),
        "wrong_key": service._decision_matches(proposal(), dict(decision, proposal_key="proposal:bad"), decoded_facts),
        "candidate": service._decision_matches(proposal(), dict(decision, secondary_record_ref="R1"), decoded_facts),
    }
    cases["legality"] = []
    for accepted in (True, False):
        relations_for_case = [{"kind": "refund_offset", "status": "accepted",
            "primary_fact_id": "p1", "secondary_fact_id": "q1", "applied_amount": "10.00"}] if accepted else []
        uow = FakeUoW(rows, relations=relations_for_case)
        try:
            RelationService(uow)._validate_cached_plan_legality_in_uow(uow, decoded_facts,
                [proposal(status="accepted" if accepted else "pending_review",
                    evidence=RelationEvidence(extras={"refund_amount": "30.00" if accepted else "130.00"}))])
            cases["legality"].append(None)
        except ValueError as error:
            cases["legality"].append(str(error))
    try:
        service._cached_proposals_in_uow(uow, dict(cached, version=2))
        cases["decode_error"] = None
    except ValueError as error:
        cases["decode_error"] = str(error)
    broken = json.loads(json.dumps(cached))
    broken["proposals"][0]["proposal_key"] = "proposal:bad"
    try:
        service._cached_proposals_in_uow(uow, broken)
        cases["key_error"] = None
    except ValueError as error:
        cases["key_error"] = str(error)
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(main_export(), ensure_ascii=False, indent=2, default=str) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation plan cache golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

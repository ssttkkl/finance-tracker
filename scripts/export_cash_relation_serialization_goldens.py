"""Export relation plan helpers and serialization from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.application.relations import (
    RelationPlan, _fact_detail_row, _fact_view_from_row, _initial_remaining, _is_human_decision,
    _stable_fact_ref, relation_proposal_key, serialize_import_relation_plan,
)
from ft.domain.relations.core.types import FactView, RelationEvidence, RelationProposal


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return replace(base, **changes)


def export():
    rows = [
        {"id": "1", "amount": "-10.00", "record_id": "ALI-1", "source_type": "alipay",
            "raw_payload": {"订单号": "A1"}, "counterparty_account_attrs": ["tail"],
            "record_type": "transfer_out", "record_subtype": "not_applicable", "ordinal": "3"},
        {"id": "2", "amount": "5.00", "source_payload": {"交易单号": "W1"}, "bill_source": "wechat",
            "deleted_at": "2026-10-01T00:00:00+00:00", "account_name": "现金"},
        {"id": "3", "amount": "1.00", "component_ordinal": "bad", "currency": "",
            "fact_type": "investment"},
    ]
    facts = [_fact_view_from_row(row) for row in rows]
    proposal = RelationProposal(kind="refund_offset", primary_fact_id="1", secondary_fact_id="2",
        status="pending_review", rule_id="scan.rule", anchor_fact_id="1",
        evidence=RelationEvidence(amount_delta="0", time_delta_seconds=5, signals=("refund",),
            candidate_fact_ids=("2", "preview:9"),
            extras={"amount": Decimal("1.20"), "when": datetime(2026, 10, 1, tzinfo=timezone.utc), "items": {2, 1}}))
    open_leg = RelationProposal(kind="transfer_pair", primary_fact_id="preview:7", secondary_fact_id=None,
        status="pending_review", rule_id="scan.rule2", anchor_fact_id="preview:7",
        evidence=RelationEvidence(open_leg=True, anchor_role="out", candidate_fact_ids=("1",)))
    plan = RelationPlan(facts=tuple(facts) + (fact(id="7", record_id=""),), proposals=(proposal, open_leg),
        context_digest="d" * 64, external_context_digest="e" * 64)
    cases = {
        "rows": [_wire(_fact_detail_row(item)) for item in facts],
        "human": [_is_human_decision({"created_by": "system", "decided_by": "user"}),
            _is_human_decision({"created_by": "system", "decided_by": "system"}),
            _is_human_decision({"created_by": "admin"})],
        "refs": [_stable_fact_ref("1", {"1": fact()}), _stable_fact_ref("preview:9"),
            _stable_fact_ref("missing")],
        "keys": [relation_proposal_key(proposal, list(plan.facts)), relation_proposal_key(open_leg, list(plan.facts))],
        "remaining": {key: str(value) for key, value in _initial_remaining(list(plan.facts),
            [{"kind": "refund_offset", "status": "accepted", "primary_fact_id": "1",
              "secondary_fact_id": "2", "applied_amount": "3.50"},
             {"kind": "refund_offset", "status": "pending_review", "primary_fact_id": "1",
              "secondary_fact_id": "2", "applied_amount": "99"}]).items()},
        "serialized": serialize_import_relation_plan(plan),
    }
    try:
        serialize_import_relation_plan(RelationPlan(facts=(), proposals=(), context_digest=""))
    except ValueError as error:
        cases["error"] = str(error)
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2, default=str) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation serialization golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

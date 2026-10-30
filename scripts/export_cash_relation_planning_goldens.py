"""Export application-layer relation planning results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.application.relations import (
    _build_projection_validation_context, _external_relation_context_digest,
    _filter_illegal_automatic_proposals, _projection_validation_reason,
    _refund_blocked_ids, _relation_context_digest,
    plan_relation_proposals, relation_proposal_key,
)
from ft.domain.relations.core.types import FactView, RelationEvidence, RelationProposal


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return replace(base, **changes)


def wire(value):
    return _wire(value)


def proposal_json(items):
    ordered = sorted(items, key=lambda item: relation_proposal_key(item))
    return [{"key": relation_proposal_key(item), "body": wire(asdict(item))} for item in ordered]


def export():
    expense = fact(id="e1", amount="-100.00", record_id="E1")
    refund = fact(id="r1", amount="30.00", record_id="R1", record_type="refund",
        occurred_at=expense.occurred_at + timedelta(days=1))
    component_a = fact(id="c1", amount="-20.00", record_id="C1", parent_id="e1", component_ordinal=1)
    component_b = fact(id="c2", amount="-5.00", record_id="C2", parent_id="e1", component_ordinal=2)
    foreign = fact(id="x1", amount="-1.00", record_id="X1", currency="USD")

    context = _build_projection_validation_context([expense, refund, component_a, component_b])
    cases = {
        "context": {
            "facts": [{"id": item.id, "amount": format(Decimal(str(item.amount)).normalize(), "f"), "currency": item.currency,
                "record_id": item.record_id, "counterparty": item.counterparty, "note": item.note,
                "source_type": item.source_type, "cash_granularity": item.cash_granularity}
                for item in context.projection_facts],
            "component_to_parent": [[key, value] for key, value in context.component_to_parent],
            "active_ids": [item.id for item in context.active_facts],
        },
    }
    try:
        _build_projection_validation_context([foreign, fact(id="x2", amount="-2.00", record_id="X2")])
        cases["context_error"] = None
    except Exception as error:  # noqa: BLE001 - golden records the public failure text
        cases["context_error"] = str(error)

    accepted = RelationProposal(kind="refund_offset", primary_fact_id="e1", secondary_fact_id="r1",
        status="accepted", rule_id="scan.refund", evidence=RelationEvidence(extras={"refund_amount": "30.00"}),
        anchor_fact_id="e1")
    legal = RelationProposal(kind="refund_offset", primary_fact_id="e1", secondary_fact_id="r1",
        status="accepted", rule_id="scan.refund", evidence=RelationEvidence(extras={"refund_amount": "30.00"}),
        anchor_fact_id="e1")
    illegal = RelationProposal(kind="refund_offset", primary_fact_id="e1", secondary_fact_id="r1",
        status="accepted", rule_id="scan.refund", evidence=RelationEvidence(extras={"refund_amount": "130.00"}),
        anchor_fact_id="e1")
    rows = [{"kind": "refund_offset", "status": "accepted", "primary_fact_id": "e1",
        "secondary_fact_id": "r1", "applied_amount": "20.00", "rule_id": "scan.refund",
        "created_by": "system", "decided_by": "user"}]
    open_leg = RelationProposal(kind="transfer_pair", primary_fact_id="e1", secondary_fact_id=None,
        status="pending_review", rule_id="scan.open", evidence=RelationEvidence(open_leg=True))
    cases.update({
        "reason": [
            _projection_validation_reason([expense, refund], [], legal),
            _projection_validation_reason([expense, refund], [], illegal),
            _projection_validation_reason([expense, refund], [], open_leg),
            _projection_validation_reason([expense, refund], rows, legal),
        ],
        "filtered": proposal_json(_filter_illegal_automatic_proposals(
            [expense, refund], [], [legal, illegal, open_leg])),
        "blocked": sorted(_refund_blocked_ids([expense, refund, component_a], [
            {"kind": "refund_offset", "status": "accepted", "primary_fact_id": "e1",
                "secondary_fact_id": "r1"},
            {"kind": "refund_offset", "status": "superseded", "primary_fact_id": "c1",
                "secondary_fact_id": "r1"},
            {"kind": "payment_mirror", "status": "accepted", "primary_fact_id": "c2",
                "secondary_fact_id": "c1"},
        ], {"e1": Decimal("80.00"), "c1": Decimal("20.00")})),
        "context_digest": _relation_context_digest([expense, refund], rows, [legal]),
        "external_digest": _external_relation_context_digest([expense, refund], rows,
            {"1234": ["a", "b"]}, {"6222": ["a"]}),
    })

    p1 = fact(id="p1", amount="-100.00", bill_source="alipay", source="alipay", record_id="P1")
    b1 = fact(id="b1", amount="-100.00", bill_source="icbc_debit", source="",
        occurred_at=p1.occurred_at + timedelta(seconds=5), record_id="B1")
    wechat_expense = fact(id="we1", amount="-30.00", bill_source="wechat", source="wechat", record_id="WE1")
    wechat_refund = fact(id="wr1", amount="30.00", record_type="refund", bill_source="wechat",
        source="wechat", occurred_at=wechat_expense.occurred_at + timedelta(days=2), record_id="WR1")
    transfer_out = fact(id="to1", amount="-50.00", record_type="transfer_out", account_id="c",
        bill_source="ccb_debit", source="", record_id="TO1", occurred_at=datetime(2026, 10, 1, 12, 0, tzinfo=timezone.utc))
    transfer_in = fact(id="ti1", amount="50.00", record_type="transfer_in", account_id="d",
        bill_source="icbc_debit", source="", record_id="TI1",
        occurred_at=datetime(2026, 10, 1, 12, 0, 5, tzinfo=timezone.utc))
    plan_facts = [p1, b1, wechat_expense, wechat_refund, transfer_out, transfer_in]
    plan = plan_relation_proposals(plan_facts, workspace_id="w")
    seeded = plan_relation_proposals(plan_facts, seed_ids=["p1", "we1"], workspace_id="w")
    strict = plan_relation_proposals(plan_facts, require_unique_mirrors=True, workspace_id="w")
    cases["plan"] = {
        "facts": [item.id for item in plan.facts],
        "proposals": proposal_json(plan.proposals),
        "context_digest": plan.context_digest,
        "seeded_keys": [relation_proposal_key(item) for item in seeded.proposals],
        "seeded_digest": seeded.context_digest,
        "strict_keys": [relation_proposal_key(item) for item in strict.proposals],
        "strict_digest": strict.context_digest,
    }
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2, default=str) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation planning golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

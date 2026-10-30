"""Export relation pipeline orchestration results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactCandidateIndex, FactView, RelationEvidence, RelationProposal
from ft.domain.relations.pipeline import (
    _demote_overlapping_phase_a_refunds, _share_refund_remaining_across_mirror_events, bank_refund_seed_ids,
    match_canonical_payment_mirrors, needs_relation_discovery, run_relation_phases,
)


def fact(**changes):
    base = FactView(id="p1", amount="-100.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="P1", payment_method="余额")
    return replace(base, **changes)


def wire(values):
    return _wire([asdict(item) for item in values])


def export():
    p1 = fact()
    b1 = fact(id="b1", bill_source="icbc_debit", source="", occurred_at=p1.occurred_at + timedelta(seconds=5), record_id="B1")
    to = fact(id="to", amount="-50.00", record_type="transfer_out", account_id="c", bill_source="ccb_debit", source="",
        record_id="TO")
    ti = fact(id="ti", amount="50.00", record_type="transfer_in", account_id="d", bill_source="icbc_debit", source="",
        occurred_at=to.occurred_at + timedelta(seconds=5), record_id="TI")
    expense = fact(id="re", amount="-30.00", bill_source="wechat", source="wechat", record_id="RE")
    refund = fact(id="rr", amount="30.00", record_type="refund", bill_source="wechat", source="wechat",
        occurred_at=expense.occurred_at + timedelta(days=2), record_id="RR")
    bank_refund = fact(id="br", amount="30.00", record_type="refund", bill_source="icbc_debit", source="",
        occurred_at=expense.occurred_at + timedelta(days=2), record_id="BR")
    facts = [p1, b1, to, ti, expense, refund, bank_refund]
    index = FactCandidateIndex(facts, source_group=lambda item: __import__("ft.domain.relations.core.routing", fromlist=["source_group"]).source_group(item))
    phases = run_relation_phases(facts, index=index)
    cases = {
        "phases": wire(phases),
        "discovery_true": needs_relation_discovery(facts),
        "discovery_seed_consumption": needs_relation_discovery(facts, seed_ids=["p1"]),
        "discovery_seed_positive": needs_relation_discovery(facts, seed_ids=["rr"]),
        "discovery_empty": needs_relation_discovery([]),
        "canonical": wire(match_canonical_payment_mirrors([p1, b1])),
        "bank_seeds": bank_refund_seed_ids(facts, blocked={"rr"}),
        "shared": {key: str(value) for key, value in _share_refund_remaining_across_mirror_events(
            [p1, b1], {p1.id: frozenset({"p1", "b1"}), b1.id: frozenset({"p1", "b1"})},
            {"p1": Decimal("60.00"), "b1": Decimal("100.00")}).items()},
    }
    evidence = RelationEvidence(rule_id="scan.alipay.order_prefix.v1", extras={"refund_amount": "100.00"})
    phase_a = [RelationProposal(kind="refund_offset", primary_fact_id="p1", secondary_fact_id="rr",
        status="accepted", rule_id="scan.alipay.order_prefix.v1", evidence=evidence, anchor_fact_id="p1")]
    demoted = _demote_overlapping_phase_a_refunds([p1, b1, refund, bank_refund], phase_a,
        [{"kind": "refund_offset", "status": "accepted", "primary_fact_id": "b1", "secondary_fact_id": "br"}],
        [("p1", "b1")])
    cases["demote"] = wire(demoted)
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation pipeline golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

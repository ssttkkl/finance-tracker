"""Export payment mirror matching results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone, timedelta
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactCandidateIndex, FactView
from ft.domain.relations.mirror.match import evaluate_payment_mirror, match_payment_mirrors_greedy


def fact(**changes):
    base = FactView(id="p1", amount="-10.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="P1",
        payment_method="余额")
    return replace(base, **changes)


def proposal(value):
    return None if value is None else _wire(asdict(value))


def export():
    platform = fact()
    bank = fact(id="b1", bill_source="icbc_debit", source="", occurred_at=platform.occurred_at + timedelta(seconds=5),
        record_id="B1")
    same_day_late = fact(id="b2", bill_source="icbc_debit", source="",
        occurred_at=platform.occurred_at + timedelta(minutes=10), record_id="B2")
    date_only = fact(id="b3", bill_source="icbc_debit", source="",
        occurred_at=platform.occurred_at.replace(hour=16), raw_payload={"date": "2026-10-01"}, record_id="B3")
    no_text = fact(id="b4", bill_source="icbc_debit", source="", counterparty="",
        occurred_at=platform.occurred_at.replace(hour=16), raw_payload={"date": "2026-10-01"}, record_id="B4")
    diff_amount = fact(id="b5", bill_source="icbc_debit", source="", amount="-11.00",
        occurred_at=platform.occurred_at + timedelta(seconds=30), record_id="B5")
    cases = {
        "strong": proposal(evaluate_payment_mirror(platform, [bank])),
        "date_only": proposal(evaluate_payment_mirror(platform, [date_only])),
        "date_only_no_identity": proposal(evaluate_payment_mirror(platform, [no_text])),
        "short_text": proposal(evaluate_payment_mirror(platform,
            [replace(bank, id="b6", occurred_at=platform.occurred_at + timedelta(seconds=30), record_id="B6")])),
        "late_text": proposal(evaluate_payment_mirror(platform, [same_day_late])),
        "amount_delta": proposal(evaluate_payment_mirror(platform, [diff_amount])),
        "cross_account": proposal(evaluate_payment_mirror(platform, [replace(bank, account_id="b")])),
        "same_group": proposal(evaluate_payment_mirror(platform, [replace(bank, bill_source="alipay", source="alipay")])),
        "multi_strong": proposal(evaluate_payment_mirror(platform, [bank,
            replace(bank, id="b7", record_id="B7")])),
        "refund_dual": proposal(evaluate_payment_mirror(
            fact(id="pr", amount="10.00", record_type="refund", occurred_at=platform.occurred_at + timedelta(days=3)),
            [fact(id="br", amount="10.00", record_type="refund", bill_source="icbc_debit", source="",
                occurred_at=platform.occurred_at + timedelta(days=3), raw_payload={"date": "2026-10-04"},
                record_id="BR")])),
    }
    grouped = [
        fact(id="g1", record_id="G1"),
        fact(id="g2", record_id="G2"),
        fact(id="g3", bill_source="icbc_debit", source="", record_id="G3",
            occurred_at=platform.occurred_at + timedelta(seconds=5)),
        fact(id="g4", bill_source="icbc_debit", source="", record_id="G4",
            occurred_at=platform.occurred_at + timedelta(seconds=50)),
    ]
    cases["greedy"] = _wire([asdict(item) for item in match_payment_mirrors_greedy(grouped)])
    cases["greedy_seed"] = _wire([asdict(item) for item in match_payment_mirrors_greedy(grouped, seed_ids=["g3"])])
    cases["greedy_unique"] = _wire([asdict(item) for item in match_payment_mirrors_greedy(grouped,
        require_unique_mirrors=True)])
    cases["greedy_blocked"] = _wire([asdict(item) for item in match_payment_mirrors_greedy(grouped,
        blocked_pairs={frozenset(("g1", "g3"))})])
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("mirror match golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

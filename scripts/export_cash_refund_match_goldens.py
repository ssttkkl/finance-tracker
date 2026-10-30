"""Export refund seed/candidate matching results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone, timedelta
from decimal import Decimal
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactView
from ft.domain.relations.refund.match import evaluate_refund_offset


def fact(**changes):
    base = FactView(id="r1", amount="10.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 2, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="refund", bill_source="alipay", source="alipay", record_id="R1")
    return replace(base, **changes)


def expense(**changes):
    base = fact(id="e1", amount="-10.00", record_type="consumption", counterparty="商户", note="",
        record_id="E1", occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), bill_source="icbc_debit",
        source="")
    return replace(base, **changes)


def case(seed, candidates, remaining=None, events=None):
    try:
        result = evaluate_refund_offset(seed, candidates, remaining_by_expense=remaining,
            candidate_event_ids=events)
    except Exception as error:
        return {"error": type(error).__name__}
    return None if result is None else _wire(asdict(result))


def export():
    seed = fact()
    cases = {
        "merchant": case(seed, [expense()]),
        "order_lock_late": case(seed, [expense(record_id="R1", occurred_at=seed.occurred_at - timedelta(days=20))]),
        "title": case(fact(note="退款-商品", counterparty="未知"), [expense(note="商品", counterparty="别的")]),
        "account": case(fact(counterparty_account="6222"), [expense(counterparty_account="6222", counterparty="甲", note="x"),
            expense(id="e2", counterparty="乙", note="y", record_id="E2")]),
        "weak": case(fact(counterparty="退款", note="", record_id="R1"), [expense(counterparty="消费", record_id="E1")]),
        "nearest": case(seed, [expense(), expense(id="e2", record_id="E2", occurred_at=seed.occurred_at - timedelta(days=2))]),
        "tie": case(seed, [expense(), expense(id="e2", record_id="E2")]),
        "account_only_multi": case(fact(counterparty_account="6222"), [expense(counterparty_account="6222", counterparty="甲"),
            expense(id="e2", counterparty="乙", record_id="E2", counterparty_account="6222")]),
        "zero_candidates": case(seed, []),
        "transfer_candidate": case(seed, [expense(record_type="transfer_reversal")]),
        "other_account": case(seed, [expense(account_id="b")]),
        "other_currency": case(seed, [expense(currency="USD")]),
        "too_late": case(seed, [expense(occurred_at=seed.occurred_at - timedelta(days=40))]),
        "remaining": case(seed, [expense(amount="-5.00")]),
        "title_account_conflict": case(
            fact(counterparty_account="6222", note="退款-商品", counterparty="未知"),
            [expense(counterparty_account="6222", counterparty="甲", note="其他"),
             expense(id="e2", counterparty="别的", note="商品", record_id="E2")]),
        "event_collapse": case(seed, [expense(id="e1", record_id="E1"),
            expense(id="e2", record_id="E2", occurred_at=seed.occurred_at - timedelta(days=5))],
            events={"e1": "event-1", "e2": "event-1"}),
    }
    return {"cases": cases}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("refund match golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

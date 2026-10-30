"""Export relation projection balance/PnL results from Python."""
from __future__ import annotations
import argparse
from dataclasses import replace
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path

from ft.domain.relations.core.projection import project_balances_and_pnl
from ft.domain.relations.core.types import FactView


def fact(**changes):
    base = FactView(id="1", amount="-100.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return replace(base, **changes)


def export():
    facts = [
        fact(id="m1", amount="-10.00", bill_source="alipay"),
        fact(id="m2", amount="-10.00", bill_source="icbc_debit", source="", record_id="B-1"),
        fact(id="t1", amount="-50.00", record_type="transfer_out", bill_source="ccb_debit"),
        fact(id="t2", amount="50.00", record_type="transfer_in", bill_source="icbc_debit"),
        fact(id="e1", amount="-100.00", bill_source="wechat"),
        fact(id="r1", amount="30.00", record_type="refund", bill_source="wechat"),
        fact(id="i1", amount="20.00", record_type="income", bill_source="wechat"),
        fact(id="d1", amount="99.00", record_type="income", deleted=True),
        fact(id="o1", amount="-7.00", bill_source="wechat"),
    ]
    relations = [
        {"kind": "payment_mirror", "status": "accepted", "primary_fact_id": "m1", "secondary_fact_id": "m2"},
        {"kind": "transfer_pair", "status": "accepted", "primary_fact_id": "t1", "secondary_fact_id": "t2"},
        {"kind": "refund_offset", "status": "accepted", "primary_fact_id": "e1", "secondary_fact_id": "r1"},
        {"kind": "refund_offset", "status": "accepted", "primary_fact_id": "o1", "secondary_fact_id": None},
        {"kind": "payment_mirror", "status": "rejected", "primary_fact_id": "o1", "secondary_fact_id": "i1"},
    ]
    result = project_balances_and_pnl(facts, relations)
    return {
        "balances": {f"{account}|{currency}": str(value) for (account, currency), value in result.balances.items()},
        "expenses": {key: str(value) for key, value in result.expenses.items()},
        "income": {key: str(value) for key, value in result.income.items()},
        "excluded": sorted(result.excluded_transfer_fact_ids),
        "groups": sorted(sorted(group) for group in result.mirror_groups),
        "net": {key: str(value) for key, value in result.net_expense_by_group.items()},
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation projection golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

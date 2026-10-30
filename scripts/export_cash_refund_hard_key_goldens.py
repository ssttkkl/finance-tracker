"""Export Phase A platform hard-key refund proposals from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactView
from ft.domain.relations.refund.hard_key import match_phase_a_platform_refunds


def fact(**changes):
    base = FactView(id="1", amount="-100.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="O1")
    return replace(base, **changes)


def row(fact_value, **changes):
    value = {"id": fact_value.id, "txn_id": fact_value.record_id, "amount": str(fact_value.amount),
        "raw_payload": {}, "bill_source": fact_value.bill_source, "source": fact_value.source,
        "date": "2026-10-01", "record_type": fact_value.record_type, "account_id": fact_value.account_id,
        "currency": fact_value.currency}
    value.update(changes)
    return value


def export():
    facts = {
        "o1": fact(id="o1", record_id="O1", amount="-100.00"),
        "o2": fact(id="o2", record_id="O2", amount="-50.00"),
        "r1": fact(id="r1", record_id="O1_1", amount="100.00", record_type="refund"),
        "r2": fact(id="r2", record_id="O2_1", amount="60.00", record_type="refund"),
        "hold": fact(id="hold", record_id="H1", amount="-10.00", record_type="consumption"),
        "free": fact(id="free", record_id="H1_U", amount="10.00", record_type="refund"),
        "inv": fact(id="inv", record_id="I1", amount="-20.00", record_type="investment_out"),
        "invr": fact(id="invr", record_id="I1_1", amount="20.00", record_type="refund"),
        "we": fact(id="we", record_id="W1", amount="-30.00", bill_source="wechat", source="wechat",
            record_type="consumption"),
        "wi": fact(id="wi", record_id="W2", amount="30.00", bill_source="wechat", source="wechat",
            record_type="refund"),
        "te": fact(id="te", record_id="T1", amount="-40.00", bill_source="wechat", source="wechat",
            record_type="transfer_reversal"),
        "ti": fact(id="ti", record_id="T2", amount="40.00", bill_source="wechat", source="wechat",
            record_type="transfer_reversal"),
    }
    rows = [
        row(facts["o1"]), row(facts["r1"]), row(facts["o2"]), row(facts["r2"]),
        row(facts["hold"], status="芝麻免押下单成功"), row(facts["free"], status="解冻成功"),
        row(facts["inv"]), row(facts["invr"]),
    ]
    proposals = match_phase_a_platform_refunds(rows, facts_by_id=facts)
    remaining = match_phase_a_platform_refunds(rows, facts_by_id=facts,
        remaining_by_expense={"o1": __import__("decimal").Decimal("0")})
    linked = match_phase_a_platform_refunds(rows, facts_by_id=facts,
        linked_pairs={("o1", "r1")})
    wechat_rows = [
        {**row(facts["we"], txn_id="W1"), "status": "已全额退款", "merchant_order_id": "W2", "payment_method": "零钱"},
        {**row(facts["wi"], txn_id="W2"), "status": "已退款", "txn_type": "微信支付-退款", "payment_method": "零钱"},
        {**row(facts["te"], txn_id="T1"), "status": "已退款", "merchant_order_id": "T2", "payment_method": "零钱"},
        {**row(facts["ti"], txn_id="T2"), "status": "已退款", "txn_type": "微信转账-退款", "payment_method": "零钱"},
    ]
    wechat = match_phase_a_platform_refunds(wechat_rows, facts_by_id=facts)
    return {
        "alipay": _wire([asdict(item) for item in proposals]),
        "remaining": _wire([asdict(item) for item in remaining]),
        "linked": _wire([asdict(item) for item in linked]),
        "wechat": _wire([asdict(item) for item in wechat]),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("refund hard key golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

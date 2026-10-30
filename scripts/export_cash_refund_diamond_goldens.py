"""Export refund text gates and diamond matching from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone
import json
from pathlib import Path

from ft.application.cash_import_operations import _wire
from ft.domain.relations.core.types import FactView
from ft.domain.relations.refund.diamond import match_diamond_bank_refunds
from ft.domain.relations.refund.signals import (
    has_refund_signal, has_refund_signal_for_fact, is_p2p_style_refund, is_p2p_transfer_family,
    is_platform_import_refund_source, is_refund_excluded_leg, p2p_subtype, refund_title_exact_match,
    strip_refund_description_prefix,
)


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return replace(base, **changes)


def export():
    texts = ["退款-商品", "消费退货 商品", "微信红包-退款", "微信红包", "转账备注", "群收款", "提现",
        "refund item", "普通消费", ""]
    facts = [
        fact(id="bank_pay", amount="-100.00", bill_source="icbc_debit", source="", note="商品", record_id="B1"),
        fact(id="bank_ref", amount="100.00", record_type="refund", bill_source="icbc_debit", source="",
            note="退款-商品", record_id="B2"),
        fact(id="plat_pay", amount="-100.00", bill_source="alipay", note="商品", record_id="A1"),
        fact(id="plat_ref", amount="100.00", record_type="refund", bill_source="alipay", note="退款-商品", record_id="A2"),
        fact(id="other", amount="-5.00", bill_source="ccb_debit", source="", record_id="C1"),
    ]
    proposals = match_diamond_bank_refunds(facts,
        accepted_mirrors=[("bank_ref", "plat_ref"), ("plat_pay", "bank_pay")],
        accepted_platform_refunds=[("plat_pay", "plat_ref")])
    return {
        "texts": [{"text": text, "signal": has_refund_signal(text), "p2p": is_p2p_transfer_family(text),
                   "p2p_refund": is_p2p_style_refund(text), "excluded": is_refund_excluded_leg(text),
                   "subtype": p2p_subtype(text), "stripped": strip_refund_description_prefix(text)}
                  for text in texts],
        "facts": [{"id": item.id,
                   "platform": is_platform_import_refund_source(item),
                   "refund_signal": has_refund_signal_for_fact(item)} for item in facts],
        "title": [refund_title_exact_match(facts[1], facts[0]), refund_title_exact_match(facts[3], facts[2]),
                  refund_title_exact_match(fact(note="退款-美团App"), fact(note="美团App")),
                  refund_title_exact_match(fact(note="退款-"), fact(note=""))],
        "diamond": _wire([asdict(item) for item in proposals]),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("refund diamond golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

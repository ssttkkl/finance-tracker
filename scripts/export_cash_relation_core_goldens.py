"""Export relation core classification, key and ordering results from Python."""
from __future__ import annotations
import argparse
from dataclasses import asdict, replace
from datetime import datetime, timezone
import json
from pathlib import Path

from ft.domain.relations.core.keys import (compatible_platform_scope, is_open_leg_relation, open_leg_business_key,
    ordered_fact_pair, relation_business_key, stable_fact_order_key, stable_fact_reference, top_k_candidate_ids)
from ft.domain.relations.core.record_types import (is_fx_in_record, is_fx_out_record, is_original_refund_expense,
    is_payment_mirror_expense, is_payment_mirror_refund, is_refund_expense_candidate, is_refund_in,
    is_transfer_in_record, is_transfer_out_record)
from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.types import FactView


def facts():
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="账户",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="备注",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return [
        base,
        replace(base, id="2", amount="10.00", record_type="refund"),
        replace(base, id="3", amount="-10.00", record_type="refund", raw_payload={"offset_role": "expense"}),
        replace(base, id="4", amount="10.00", record_type="refund", bill_source="wechat", source="wechat",
            source_identity="named", occurred_at="2026-10-01T10:00:00Z", record_id="W-1"),
        replace(base, id="5", amount="-10.00", record_type="transfer_out", bill_source="icbc_debit"),
        replace(base, id="6", amount="10.00", record_type="transfer_in", bill_source="ccb_debit"),
        replace(base, id="7", amount="-10.00", record_type="fx_out", bill_source=""),
        replace(base, id="8", amount="10.00", record_type="fx_in", bill_source="", note="支付宝换汇"),
        replace(base, id="9", amount="-10.00", record_type="other", bill_source="", source="",
            note="工行储蓄卡 尾号1234", account_name=""),
        replace(base, id="10", amount="-10.00", record_type="other", bill_source="unknown", source="",
            note="支付宝", parent_id="1", component_ordinal=2, occurred_at="2026-10-01 10:00:00"),
        replace(base, id="11", amount="0.00", record_type="refund", occurred_at="bad-time", record_id=""),
    ]


def export():
    values = facts()
    result = {"facts": [asdict(fact) for fact in values], "cases": []}
    for fact in values:
        result["cases"].append({
            "refund_in": is_refund_in(fact), "original_refund": is_original_refund_expense(fact),
            "refund_candidate": is_refund_expense_candidate(fact), "mirror_expense": is_payment_mirror_expense(fact),
            "mirror_refund": is_payment_mirror_refund(fact), "fx_out": is_fx_out_record(fact),
            "fx_in": is_fx_in_record(fact), "transfer_out": is_transfer_out_record(fact),
            "transfer_in": is_transfer_in_record(fact), "source_group": source_group(fact),
            "reference": stable_fact_reference(fact), "order": list(stable_fact_order_key(fact)),
        })
    result["compatibility"] = [
        compatible_platform_scope(values[3], replace(values[3], id="12")),
        compatible_platform_scope(values[3], replace(values[3], id="12", source_identity="other")),
        compatible_platform_scope(values[0], values[3]),
    ]
    result["pairs"] = [list(ordered_fact_pair(a, b)) for a, b in (
        ("10", "2"), ("2", "10"), ("010", "2"), ("abc", "abd"), (None, ""), ("5", None), ("+5", "5"))]
    result["business"] = list(relation_business_key("w", "payment_mirror", "10", "2", ""))
    result["open_leg"] = list(open_leg_business_key("w", "transfer_pair", "7", ""))
    result["open_leg_row"] = [is_open_leg_relation({"secondary_fact_id": None}),
        is_open_leg_relation({"secondary_fact_id": ""}), is_open_leg_relation({}),
        is_open_leg_relation({"secondary_fact_id": "2"})]
    result["top_k"] = list(top_k_candidate_ids(["3", "", "1", "3", "2"], k=2))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2, default=str) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation core golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

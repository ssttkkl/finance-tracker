"""Export relation geometry and mirror graph results from Python."""
from __future__ import annotations
import argparse
from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path

from ft.domain.relations.core.geometry import (
    business_day_utc, extract_card_tails, fact_is_bank_date_only, is_date_only_business_string,
    _same_calendar_day, main_style_cross_verify, same_business_day_utc, _time_delta_seconds,
)
from ft.domain.relations.core.compatibility import cross_kind_compatible
from ft.domain.relations.core.mirror_graph import build_mirror_components, canonical_mirror_fact, platform_score
from ft.domain.relations.core.types import FactView


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="备注",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="ALI-1")
    return replace(base, **changes)


def export():
    base = fact()
    return {
        "delta": [_time_delta_seconds("2026-10-01T10:00:00+08:00", "2026-10-01T02:00:05+00:00"),
            _time_delta_seconds("2026-10-01T10:00:00", "2026-10-01T10:00:02Z")],
        "same_day": [_same_calendar_day("2026-10-01T23:00:00+00:00", "2026-10-01T00:00:01+00:00"),
            _same_calendar_day("2026-10-01T23:00:00+00:00", "2026-10-02T00:00:01+00:00")],
        "date_only": [is_date_only_business_string(value) for value in (
            "2026-10-01", "20261001", "2026-10-01T00:00:00Z", "2026-10-01 00:00", "2026/10/01",
            "2026-10-01T10:00:00Z", "")],
        "business_day": [value.isoformat() if value else None for value in
            [business_day_utc(replace(base, raw_payload={"date": value})) for value in ("2026-10-01", "20261002")]
            + [business_day_utc(replace(base, occurred_at="2026-10-01 10:00:00")),
               business_day_utc(replace(base, occurred_at="bad")),
               business_day_utc(replace(base, occurred_at=""))]],
        "bank_date_only": [fact_is_bank_date_only(replace(base, bill_source="icbc_debit",
                raw_payload={"date": "2026-10-01"})),
            fact_is_bank_date_only(replace(base, bill_source="icbc_debit", raw_payload={"date": "2026-10-01T10:00:00"})),
            fact_is_bank_date_only(replace(base, bill_source="icbc_debit", occurred_at="2026-10-01T16:00:00+00:00")),
            fact_is_bank_date_only(replace(base, bill_source="alipay", occurred_at="2026-10-01T16:00:00+00:00"))],
        "tails": [sorted(extract_card_tails(value)) for value in (
            "尾号：1234", "****abcd", "储蓄卡1234", "卡号 6222 1234", "订单1234", "")],
        "cross": [main_style_cross_verify("支付宝商户", "支付宝"), main_style_cross_verify("abc", "xyz"),
            main_style_cross_verify({"counterparty": "商户", "note": "很长备注"}, {"counterparty": "其他", "note": "备注"})],
        "cross_kind": [cross_kind_compatible(["payment_mirror"], "transfer_pair"),
            cross_kind_compatible(["transfer_pair", "payment_mirror"], "refund_offset")],
        "mirror": {
            "scores": [platform_score(base), platform_score(fact(bill_source="icbc_debit", source="")),
                platform_score(fact(bill_source="unknown", source=""))],
            "canonical": [canonical_mirror_fact([base]).id if canonical_mirror_fact([base]) else None,
                (canonical_mirror_fact([base, fact(id="2", bill_source="wechat", source="wechat",
                    source_identity="named")]) or FactView(id="none", amount="0", currency="CNY", account_id="")).id,
                (canonical_mirror_fact([base, fact(id="2", bill_source="alipay", source="alipay",
                    counterparty="商户", note="备注")]) or FactView(id="none", amount="0", currency="CNY", account_id="")).id],
            "components": sorted([sorted(group) for group in build_mirror_components(
                ["1", "2", "3", "4"], [("1", "2"), ("2", "3")])]),
        },
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("relation geometry golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

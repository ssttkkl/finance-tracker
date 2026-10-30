"""Export relation candidate index buckets from Python."""
from __future__ import annotations
import argparse
from dataclasses import replace
from datetime import datetime, timezone
import json
from pathlib import Path

from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.types import FactCandidateIndex, FactView


def fact(**changes):
    base = FactView(id="1", amount="-10.00", currency="CNY", account_id="a", account_name="现金",
        occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc), counterparty="商户", note="",
        record_type="consumption", bill_source="alipay", source="alipay", record_id="A1")
    return replace(base, **changes)


def ids(items):
    return [item.id for item in items]


def export():
    facts = [
        fact(id="m1", amount="-10.00"),
        fact(id="b1", amount="-10.00", bill_source="icbc_debit", source=""),
        fact(id="mr", amount="10.00", record_type="refund"),
        fact(id="br", amount="10.00", record_type="refund", bill_source="icbc_debit", source=""),
        fact(id="out", amount="-50.00", record_type="transfer_out", record_subtype="ordinary_transfer",
            bill_source="ccb_debit", source=""),
        fact(id="in1", amount="50.00", record_type="transfer_in", record_subtype="ordinary_transfer",
            bill_source="icbc_debit", source="", occurred_at=datetime(2026, 10, 1, 10, 1, tzinfo=timezone.utc)),
        fact(id="in2", amount="50.00", record_type="transfer_in", record_subtype="credit_repayment",
            bill_source="icbc_debit", source=""),
        fact(id="fx_out", amount="-100.00", currency="CNY", record_type="fx_out",
            record_subtype="currency_exchange", bill_source="ccb_debit", source=""),
        fact(id="fx_in", amount="100.00", currency="USD", record_type="fx_in",
            record_subtype="currency_exchange", bill_source="icbc_debit", source=""),
        fact(id="ref", amount="10.00", record_type="refund", occurred_at=datetime(2026, 10, 3, 10, 0, tzinfo=timezone.utc)),
        fact(id="exp1", amount="-10.00", occurred_at=datetime(2026, 10, 1, 10, 0, tzinfo=timezone.utc)),
        fact(id="exp2", amount="-10.00", occurred_at=datetime(2026, 10, 2, 10, 0, tzinfo=timezone.utc)),
        fact(id="exp_other", amount="-10.00", account_id="b"),
        fact(id="late", amount="-10.00", occurred_at=datetime(2026, 8, 1, 10, 0, tzinfo=timezone.utc)),
        fact(id="p2p", amount="10.00", record_type="refund", note="微信红包"),
        fact(id="p2p_exp", amount="-10.00", note="微信红包"),
        fact(id="deleted", amount="-10.00", deleted=True),
    ]
    index = FactCandidateIndex(facts, source_group=source_group)
    by_id = {item.id: item for item in facts}
    return {
        "mirror": [ids(index.mirror_candidates(by_id[key])) for key in ("m1", "b1", "mr", "br")],
        "transfer_in": ids(index.transfer_in_candidates(by_id["out"], day_pad=1)),
        "transfer_pair": [
            ids(index.transfer_pair_candidates(by_id["out"], window_seconds=300)),
            ids(index.transfer_pair_candidates(by_id["out"], window_seconds=30)),
            ids(index.transfer_pair_candidates(by_id["fx_out"], window_seconds=300)),
        ],
        "refund": [
            ids(index.refund_candidates(by_id["ref"])),
            ids(index.refund_candidates(by_id["exp2"])),
            ids(index.refund_candidates(by_id["p2p"])),
        ],
        "by_id": sorted(index.by_id),
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(export(), ensure_ascii=False, indent=2) + "\n"
    if args.check:
        if args.output.read_text() != content:
            raise SystemExit("fact candidate golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

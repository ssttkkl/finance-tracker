"""Export source-account scanning and component allocation oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.application.statement_account_mapping import (
    alipay_component_allocation_status,
    build_component_allocation_draft,
    parse_alipay_payment_components,
    scan_source_rows_with_issues,
    source_component_identity_keys,
    source_identity_key,
)


def alipay(**changes):
    row = {"bill_source": "alipay", "amount": "-20.00", "currency": "CNY", "record_id": "r1",
        "payment_method": "账户余额", "source_type": "alipay"}
    row.update(changes)
    return row


def rows():
    return [
        alipay(),
        alipay(payment_method="工商银行信用卡(1200)"),
        alipay(payment_method="余额宝&银行卡(1200)"),
        alipay(payment_method="余额宝 ¥5.00&银行卡 15.00元"),
        alipay(payment_method="红包&优惠券"),
        alipay(payment_method=""),
        alipay(payment_method="", amount="0.00"),
        alipay(payment_method="支付宝余额&余额宝", amount="20.00"),
        alipay(payment_method="花呗分期(3期)"),
        alipay(payment_method="支付宝余额", amount="-20.00"),
        {"bill_source": "wechat", "source_type": "wechat", "amount": "-20.00", "currency": "CNY",
            "payment_method": "零钱", "record_id": "w1"},
        {"bill_source": "wechat", "source_type": "wechat", "amount": "-20.00", "currency": "CNY",
            "payment_method": "/", "record_id": "w2"},
        {"bill_source": "wechat", "source_type": "wechat", "amount": "-20.00", "currency": "USD",
            "payment_method": "工商银行", "record_id": "w3"},
        {"bill_source": "icbc_credit", "source_type": "icbc_credit", "amount": "-20.00", "currency": "CNY",
            "_source_account_identifier": "6222000000001234", "record_id": "c1"},
        {"bill_source": "icbc_credit", "source_type": "icbc_credit", "amount": "-20.00", "currency": "CNY",
            "file_account_key": "6222******1234", "source_display_name": "信用卡A", "record_id": "c2"},
        {"bill_source": "ccb_debit", "source_type": "ccb_debit", "amount": "-20.00", "currency": "CNY",
            "card_number": "6222000011112222", "record_id": "b1"},
        {"bill_source": "icbc_debit", "source_type": "icbc_debit", "amount": "-20.00", "currency": "CNY",
            "_source_account_identifier": "6212000000000001234", "record_id": "d1"},
        {"bill_source": "icbc_asia", "source_type": "icbc_asia", "amount": "-20.00", "currency": "HKD",
            "_source_account_identifier": "1234567890", "card_number": "7890", "record_id": "a1"},
        {"bill_source": "other", "amount": "-20.00", "record_id": "x1"},
        {"bill_source": "icbc_credit", "amount": "-20.00", "record_id": "x2"},
        {"bill_source": "icbc_credit", "amount": "-20.00", "currency": 0, "_source_account_identifier": "6222000000001234",
            "file_account_key": "6222000000009999", "record_id": "x3"},
        {"bill_source": "icbc_credit", "amount": "-20.00", "currency": "CNY",
            "_source_account_identifier": "   ", "file_account_key": "6222000000009999", "record_id": "x4"},
        {"bill_source": "ccb_debit", "amount": "-20.00", "currency": "CNY", "card_number": "12", "record_id": "x5"},
        {"bill_source": "icbc_asia", "amount": "-20.00", "currency": "HKD",
            "_source_account_identifier": "1234567890", "record_id": "x6"},
        {"bill_source": "wechat", "amount": "-20.00", "currency": "USD", "payment_method": "工商银行", "record_id": "x7"},
        alipay(payment_method="余额宝 15.00\u3000元", amount="-15.00"),
        alipay(payment_method="宝¥\u30005.00", amount="-5.00"),
        alipay(payment_method="花呗分期(３期)"),
        alipay(payment_method=" １５元", amount="-15.00"),
        alipay(payment_method="银行卡15.00元", amount="-15.00"),
    ]


def export(output: Path) -> None:
    all_rows = rows()
    components = []
    for index, row in enumerate(all_rows[:10] + all_rows[20:]):
        components.append({"index": index, "row": row, "components": parse_alipay_payment_components(row),
            "status": alipay_component_allocation_status(row)})

    allocations = []
    for name, row, value in [
        ("single", alipay(payment_method="账户余额"), None),
        ("single-supply", alipay(payment_method="账户余额"), [{"amount": "20.00"}]),
        ("composite-supply", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "5.00"}, {"amount": "15.00", "account_id": 7}]),
        ("composite-negative", alipay(payment_method="余额宝&银行卡(1200)", amount="-20.00"), None),
        ("incomplete", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "20.00"}]),
        ("negative-amount", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "-5.00"}, {"amount": "15.00"}]),
        ("conservation", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "1.00"}, {"amount": "2.00"}]),
        ("invalid", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "bad"}, {"amount": "15.00"}]),
        ("map-empty-components", alipay(payment_method="余额宝&银行卡(1200)"), {"components": [], "allocations": [{"amount": "5.00"}, {"amount": "15.00"}]}),
        ("fact-id", alipay(payment_method="账户余额", record_id=0, _fact_id="fact-1"), None),
        ("account-bool", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "5.00", "account_id": False}, {"amount": "15.00"}]),
        ("account-float", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "5.00", "account_id": 7.9}, {"amount": "15.00"}]),
        ("fullwidth-amount", alipay(payment_method="余额宝&银行卡(1200)"), [{"amount": "５.００"}, {"amount": "１５.００"}]),
    ]:
        try:
            allocations.append({"name": name, "row": row, "allocations": value,
                "draft": build_component_allocation_draft(row, allocations=value)})
        except ValueError as failure:
            allocations.append({"name": name, "row": row, "allocations": value, "error": str(failure)})

    identities = []
    for index, row in enumerate(all_rows):
        try:
            keys = source_component_identity_keys(row)
            identities.append({"index": index, "row": row, "keys": [list(key) for key in keys],
                "identity": list(source_identity_key(row))})
        except ValueError as failure:
            identities.append({"index": index, "row": row, "error": str(failure)})

    groups, issues = scan_source_rows_with_issues([row for index, row in enumerate(all_rows) if index not in {5, 18, 19, 21, 22}])
    group_export = [{"group_id": group.group_id, "source_type": group.source_type,
        "identity_kind": group.identity_kind, "source_account_key": group.source_account_key,
        "display_name": group.display_name, "masked_evidence": group.masked_evidence,
        "currencies": list(group.currencies), "row_count": group.row_count,
        "legacy_source_account_keys": list(group.legacy_source_account_keys)} for group in groups]
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"components": components, "allocations": allocations,
        "identities": identities, "groups": group_export,
        "issues": [{"row_index": issue.row_index, "code": issue.code} for issue in issues]},
        ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)

"""工行退款摘要与对手方解析回归测试。"""
from __future__ import annotations


def _credit_target_lines():
    merchant = "美团支付-美团App山葵村烤肉"
    return [
        "2026-05-25", "19:11:37", "6225990041051200", "借", "人民币", "-272.00",
        "消费", merchant,
        "2026-05-25", "19:13:04", "6225990041051200", "贷", "人民币", "272.00",
        "退货", merchant,
        "2026-05-25", "19:16:39", "6225990041051200", "借", "人民币", "-222.00",
        "消费", merchant,
    ]


def test_icbc_credit_uses_one_counterparty_path_for_expense_and_return():
    from ft.convert import _parse_icbc_lines

    records, _tracking = _parse_icbc_lines(_credit_target_lines(), is_credit=True)

    assert len(records) == 3
    assert {record["counterparty"] for record in records} == {"山葵村烤肉"}
    assert {record["_raw_cp"] for record in records} == {"美团支付-美团App山葵村烤肉"}
    refund = next(record for record in records if record["amount"] == 272)
    assert refund["summary"] == "退货"
    assert refund["refund_signal"] == "icbc_credit_return"
    assert refund["counterparty"] != "退货"


def test_icbc_credit_refund_summary_is_the_only_formal_signal():
    from ft.convert import _parse_icbc_lines

    lines = _credit_target_lines()
    lines[14] = "退款"
    records, _tracking = _parse_icbc_lines(lines, is_credit=True)

    refund = next(record for record in records if record["amount"] == 272)
    assert refund["summary"] == "退款"
    assert refund["refund_signal"] == ""


def test_icbc_debit_refund_signal_uses_exact_return_summary():
    from ft.convert import _parse_icbc_debit_row

    row = [
        "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
        "人民币", "钞", "退货", "0200", "+100.00", "1076.16",
        "支付宝（中国）网络技术有限公司", "3602****5565", "网上银行",
    ]
    record = _parse_icbc_debit_row(
        row,
        source_account_identifier="6212000000000003697",
    )

    assert record["summary"] == "退货"
    assert record["refund_signal"] == "icbc_debit_return"


def test_icbc_debit_refund_word_does_not_create_formal_signal():
    from ft.convert import _parse_icbc_debit_row

    row = [
        "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
        "人民币", "钞", "退款", "0200", "+100.00", "1076.16",
        "支付宝（中国）网络技术有限公司", "3602****5565", "网上银行",
    ]
    record = _parse_icbc_debit_row(
        row,
        source_account_identifier="6212000000000003697",
    )

    assert record["summary"] == "退款"
    assert record["refund_signal"] == ""

"""Export platform refund pairing results from Python."""
from __future__ import annotations
import argparse
import json
from pathlib import Path

from ft.domain.platform_refund import (
    alipay_find_origin_index, alipay_is_auth_hold, alipay_is_failed_repay, alipay_is_paid_closed_expense,
    alipay_is_unfreeze, alipay_is_unpaid_closed, alipay_order_match, pair_wechat_refunds,
    pair_wechat_transfer_returns, wechat_counterparty_compatible, wechat_embedded_refund_amount,
    wechat_find_expense_for_refund, wechat_is_refund_income_leg, wechat_is_refund_origin_expense,
    wechat_merchant_key_match, wechat_refund_pay_compatible,
)


def expense(**changes):
    row = {"id": "e1", "amount": "-100.00", "currency": "CNY", "record_type": "consumption",
        "status": "", "pay": "零钱", "cp": "商户", "date": "2026-10-01 10:00:00", "mer": "M1", "txn": "T1"}
    row.update(changes)
    return row


def income(**changes):
    row = {"id": "i1", "amount": "100.00", "currency": "CNY", "record_type": "refund", "status": "已退款",
        "pay": "零钱", "cp": "商户", "date": "2026-10-02 10:00:00", "txn": "T2", "type": "微信支付"}
    row.update(changes)
    return row


def match(expenses, inc, used=()):
    value = wechat_find_expense_for_refund(inc, expenses, used_expense_indexes=used)
    return None if value is None else {"index": value.expense_index, "rule": value.rule_id}


def export():
    expenses = [
        expense(id="merchant", mer="M1"),
        expense(id="txn", mer="X", txn="TX"),
        expense(id="full", status="已全额退款", mer="A", txn="A"),
        expense(id="full2", status="已全额退款", mer="B", txn="B", date="2026-10-02 09:00:00"),
        expense(id="partial", status="已退款(¥50.00)", mer="C", txn="C"),
        expense(id="residual", status="已退款(¥150.00)", amount="-200.00", mer="D", txn="D"),
        expense(id="other", status="", mer="E", txn="E", record_type="transfer_reversal"),
    ]
    scenarios = {
        "r1_merchant": match(expenses, income(txn="M1")),
        "r1_txn": match(expenses, income(txn="TX")),
        "r1_ambiguous": match(expenses, income(txn="")),
        "r2": match(expenses[:3], income(txn="Z")),
        "r2_closest": match([expenses[2], expenses[3]], income(txn="Z")),
        "r3": match([expenses[4]], income(amount="50.00")),
        "r4": match([expenses[5]], income(amount="50.00")),
        "transfer_type": match(expenses, income(type="微信转账")),
        "none": match([expenses[6]], income()),
    }
    refund_rows = [
        {"id": "e1", "amount": "-100.00", "record_type": "consumption", "date": "2026-10-01",
            "merchant_order_id": "M1", "status": "已全额退款", "payment_method": "零钱"},
        {"id": "i1", "amount": "100.00", "record_type": "refund", "date": "2026-10-02", "status": "已退款",
            "payment_method": "零钱"},
        {"id": "i2", "amount": "30.00", "record_type": "refund", "date": "2026-10-03", "status": "已退款",
            "txn_type": "微信支付-退款"},
        {"id": "e2", "amount": "5.00", "record_type": "refund", "date": "2026-10-04", "status": "已退款"},
        {"id": "other", "amount": "1.00", "record_type": "income", "date": "2026-10-05"},
    ]
    transfer_rows = [
        {"id": "e1", "amount": "-20.00", "currency": "CNY", "record_type": "transfer_reversal",
            "date": "2026-10-01 10:00:00", "merchant_order_id": "M1", "payment_method": "零钱", "account_id": "a"},
        {"id": "i1", "amount": "20.00", "currency": "CNY", "record_type": "transfer_reversal",
            "date": "2026-10-02 10:00:00", "txn_id": "M1", "status": "已退款", "account_id": "a"},
        {"id": "i2", "amount": "20.00", "currency": "USD", "record_type": "transfer_reversal",
            "date": "2026-10-02 10:00:00", "txn_id": "M1", "status": "退款", "account_id": "a"},
    ]
    return {
        "alipay": {
            "order": [alipay_order_match("A_1", "A"), alipay_order_match("A*1", "A"),
                alipay_order_match("A_1", "B"), alipay_order_match("", "A")],
            "index": alipay_find_origin_index("A_1", ["B", "A", "A_2"]),
            "statuses": [alipay_is_unpaid_closed("交易关闭", "不计收支", ""),
                alipay_is_failed_repay("还款失败", "不计收支", ""),
                alipay_is_paid_closed_expense("交易关闭", "支出"),
                alipay_is_auth_hold("芝麻免押下单成功"), alipay_is_unfreeze("解冻成功")],
        },
        "wechat": {
            "embedded": [None if value is None else str(value) for value in (
                wechat_embedded_refund_amount("已退款(¥18.00)"), wechat_embedded_refund_amount("已退款¥18"),
                wechat_embedded_refund_amount("已全额退款"), wechat_embedded_refund_amount("其他"))],
            "origin": [wechat_is_refund_origin_expense("支出", "已全额退款"),
                wechat_is_refund_origin_expense("收入", "已全额退款"),
                wechat_is_refund_origin_expense("支出", "已退款(¥1.00)")],
            "income_leg": [wechat_is_refund_income_leg("收入", "已退款", ""),
                wechat_is_refund_income_leg("支出", "已退款", ""),
                wechat_is_refund_income_leg("收入", "", "微信支付-退款")],
            "pay": [wechat_refund_pay_compatible("零钱", "零钱"), wechat_refund_pay_compatible("零钱", "银行卡"),
                wechat_refund_pay_compatible("/", "零钱")],
            "merchant": [wechat_merchant_key_match("M1", "M1"), wechat_merchant_key_match("", "M1")],
            "cp": [wechat_counterparty_compatible("商户", "商户"),
                wechat_counterparty_compatible("京东", "京东商城"), wechat_counterparty_compatible("/", "商户", "微信转账"),
                wechat_counterparty_compatible("商户", "其他", "微信支付-退款")],
            "scenarios": scenarios,
            "pairs": [list(item) for item in pair_wechat_refunds(refund_rows)],
            "transfer_pairs": [list(item) for item in pair_wechat_transfer_returns(transfer_rows)],
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
            raise SystemExit("platform refund golden mismatch")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(content)

from decimal import Decimal

import pytest

from ft.convert import _build_output_row


def _classify(row, bill_type):
    return _build_output_row(
        {
            "date": "2026-06-13 12:00:00",
            "amount": Decimal("-10.00"),
            "currency": "CNY",
            "counterparty": "样例",
            "note": "样例",
            "category": "expense",
            **row,
        },
        bill_type=bill_type,
        account="测试账户",
        currency="CNY",
    )["record_type"]


@pytest.mark.parametrize(
    ("bill_type", "row", "expected"),
    [
        ("wechat", {"txn_type": "商户消费", "_wechat_direction": "支出"}, "consumption"),
        ("wechat", {"txn_type": "转账", "_wechat_direction": "收入", "category": "income", "amount": Decimal("10")}, "transfer_in"),
        ("wechat", {"txn_type": "微信红包", "_wechat_direction": "支出"}, "transfer_out"),
        ("wechat", {"txn_type": "转账-退款", "status": "已全额退款", "_wechat_direction": "收入", "category": "income", "amount": Decimal("10")}, "transfer_reversal"),
        ("wechat", {"txn_type": "微信红包（单发）", "status": "已全额退款"}, "transfer_reversal"),
        ("wechat", {"txn_type": "商户消费", "counterparty": "QQ红包", "status": "已退款(¥13.05)"}, "transfer_reversal"),
        ("wechat", {"txn_type": "零钱提现", "_wechat_direction": "支出"}, "withdrawal_out"),
        ("wechat", {"txn_type": "零钱提现", "_wechat_direction": "收入", "category": "income", "amount": Decimal("10")}, "withdrawal_in"),
        ("wechat", {"txn_type": "信用卡还款"}, "repayment"),
        ("alipay", {"txn_type": "信用借还", "_alipay_direction": "支出"}, "repayment"),
        ("alipay", {"txn_type": "账户提现", "_alipay_direction": "不计收支"}, "withdrawal_out"),
        ("alipay", {"txn_type": "账户提现", "_alipay_direction": "收入", "category": "income", "amount": Decimal("10")}, "withdrawal_in"),
        ("alipay", {"txn_type": "转账红包", "platform_status": "退款成功", "_alipay_direction": "收入", "category": "income", "amount": Decimal("10")}, "transfer_reversal"),
        ("alipay", {"txn_type": "充值缴费"}, "consumption"),
        ("icbc_credit", {"summary": "退货", "category": "income", "amount": Decimal("10")}, "refund"),
        ("icbc_credit", {"summary": "撤销交易", "_is_reversal": True, "category": "income", "amount": Decimal("10")}, "reversal"),
        ("ccb_debit", {"summary": "冲正", "category": "income", "amount": Decimal("10")}, "reversal"),
        ("ccb_debit", {"summary": "支付机构提现", "category": "income", "amount": Decimal("10")}, "withdrawal_in"),
        ("ccb_debit", {"summary": "无卡自助交易"}, "consumption"),
        ("ccb_debit", {"summary": "无卡支付"}, "consumption"),
        ("icbc_debit", {"summary": "ATM取款", "category": "expense", "amount": Decimal("-10")}, "withdrawal_out"),
        ("icbc_debit", {"summary": "购汇还款"}, "repayment"),
        ("ccb_debit", {"summary": "代理收款", "category": "income", "amount": Decimal("995.36")}, "repayment"),
        ("icbc_debit", {"summary": "工资", "category": "income", "amount": Decimal("100")}, "income"),
        ("ccb_debit", {"summary": "基金赎回", "category": "income", "amount": Decimal("100")}, "investment_in"),
        ("icbc_credit", {"offset_type": "campaign_cashback", "category": "income", "amount": Decimal("10")}, "income"),
        ("icbc_credit", {"offset_type": "fee_reversal", "category": "income", "amount": Decimal("10")}, "fee"),
        ("icbc_debit", {"summary": "理财"}, "investment_out"),
        ("ccb_debit", {"summary": "未知摘要"}, "other"),
    ],
)
def test_build_output_row_assigns_source_record_type(bill_type, row, expected):
    assert _classify(row, bill_type) == expected


def test_zero_amount_keeps_source_semantics_without_special_zero_type():
    assert _classify(
        {"txn_type": "充值", "_wechat_direction": "/", "amount": Decimal("0")},
        "wechat",
    ) == "consumption"


def test_reversal_signal_wins_over_refund_signal():
    assert _classify(
        {
            "summary": "撤销交易",
            "_is_reversal": True,
            "_is_refund": True,
            "category": "income",
            "amount": Decimal("10"),
        },
        "icbc_credit",
    ) == "reversal"

"""Export cash record classification and counterparty-account oracle cases."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ft.domain.record_type import (
    _source_payload_digest,
    VALID_CASH_RECORD_SUBTYPES,
    VALID_CASH_RECORD_TYPES,
    classify_cash_record,
    classify_cash_record_type,
    default_cash_record_subtype,
    normalize_counterparty_account,
    validate_cash_record_subtype,
    validate_counterparty_account,
)


def rows():
    cases = []

    def add(source, **row):
        cases.append({"source": source, "row": row})

    # Reversal and P2P return precedence.
    add("wechat", txn_type="商户消费", summary="消费", _is_reversal=True)
    add("wechat", txn_type="商户消费", _debit_offset_type="reversal")
    add("wechat", txn_type="商户消费", summary="冲正")
    add("wechat", txn_type="撤销交易", summary="消费")
    add("wechat", txn_type="转账", summary="转账", status="已退款", amount="-20.00")
    add("wechat", txn_type="群收款", summary="群收款", _refund_signal="x", amount="20.00")
    add("wechat", txn_type="商户消费", note="QQ红包", status="退款", amount="20.00")
    add("alipay", txn_type="转账红包", summary="转账", refund_signal="x", amount="20.00")
    add("wechat", txn_type="商户消费", summary="消费", _refund_signal="x", amount="-20.00")
    add("wechat", txn_type="商户消费", summary="消费", _debit_offset_type="refund")
    add("ccb_debit", txn_type="", summary="退款")
    add("ccb_debit", txn_type="交易退款", summary="")
    add("ccb_debit", txn_type="消费-退货", summary="")
    # Repayment.
    add("ccb_debit", txn_type="消费", summary="代理收款", amount="20.00")
    add("icbc_credit", txn_type="信用借还", summary="")
    add("icbc_credit", txn_type="", summary="自动还款")
    add("icbc_credit", txn_type="", summary="购汇还款")
    # Offset types.
    add("alipay", txn_type="投资理财", summary="", offset_type="benefit_rebate")
    add("alipay", txn_type="投资理财", summary="", offset_type="campaign_cashback")
    add("ccb_debit", txn_type="", summary="手续费", _debit_offset_type="fee_reversal")
    # WeChat branches.
    for direction in ("收入", "支出"):
        add("wechat", txn_type="零钱提现", summary="", _wechat_direction=direction)
        add("wechat", txn_type="转账", summary="", _wechat_direction=direction)
        add("wechat", txn_type="群收款", summary="", _wechat_direction=direction)
        add("wechat", txn_type="微信红包", summary="", _wechat_direction=direction)
        add("wechat", txn_type="微信红包（单发）", summary="", direction=direction)
        add("wechat", txn_type="微信红包（群红包）", summary="", direction=direction)
        add("wechat", txn_type="二维码收款", summary="", direction=direction)
        add("wechat", txn_type="购买理财通", summary="", direction=direction)
        add("wechat", txn_type="理财通", summary="赎回", direction=direction)
        add("wechat", txn_type="零钱通存取", summary="转出", direction=direction)
        add("wechat", txn_type="商户消费", summary="", direction=direction)
        add("wechat", txn_type="扫二维码付款", summary="", direction=direction)
        add("wechat", txn_type="二维码付款", summary="", direction=direction)
        add("wechat", txn_type="充值", summary="", direction=direction)
        add("wechat", txn_type="零钱充值", summary="", direction=direction)
        add("wechat", txn_type="缴费", summary="", direction=direction)
        add("wechat", txn_type="充值缴费", summary="", direction=direction)
        add("wechat", txn_type="其他类型", summary="", direction=direction)
    add("wechat", txn_type="其他类型", summary="", category="income")
    add("wechat", txn_type="其他类型", summary="", amount="20.00")
    add("wechat", txn_type="其他类型", summary="", amount="-20.00")
    add("wechat", txn_type="其他类型", summary="", amount="bad")
    # Alipay branches.
    for direction in ("收入", "支出"):
        add("alipay", txn_type="账户存取", summary="", _alipay_direction=direction)
        add("alipay", txn_type="账户提现", summary="", direction=direction)
        add("alipay", txn_type="其他", summary="提现-银行卡", direction=direction)
        add("alipay", txn_type="其他", summary="提现到银行卡", direction=direction)
        add("alipay", txn_type="其他", summary="转出到银行卡", direction=direction)
        add("alipay", txn_type="转账红包", summary="", direction=direction)
        add("alipay", txn_type="投资理财", summary="收益发放", direction=direction)
        add("alipay", txn_type="投资理财", summary="账户结息", direction=direction)
        add("alipay", txn_type="投资理财", summary="卖出", direction=direction)
        add("alipay", txn_type="投资理财", summary="赎回", direction=direction)
        add("alipay", txn_type="投资理财", summary="买入", direction=direction)
        add("alipay", txn_type="充值缴费", summary="", direction=direction)
        add("alipay", txn_type="其他", summary="", direction=direction)
    add("alipay", txn_type="其他", summary="", amount="20.00")
    add("alipay", txn_type="其他", summary="", amount="-20.00")
    # ICBC Asia branches.
    for token in ("轉賬", "轉帳", "转账", "转帐", "匯款", "汇款"):
        add("icbc_asia", txn_type=token, summary="", direction="收入")
    for token in ("提現", "提现", "取現", "取现"):
        add("icbc_asia", txn_type=token, summary="", direction="支出")
    for token in ("消費", "消费"):
        add("icbc_asia", txn_type="", summary=token, direction="支出")
    # Bank summaries.
    for summary in ("基金购买", "买入基金", "理财", "基金赎回", "卖出基金", "利息", "结息", "利息存入",
                    "手续费", "管理费", "购汇", "个人购汇", "预约购汇", "外汇", "汇兑", "银转证",
                    "银行转证券", "证转银", "证券转银行", "转账", "转帐", "转账支取", "支付宝转账",
                    "跨境汇款", "跨行汇款", "转账存入", "网转", "他行汇入", "银联入账", "电子汇入",
                    "存款", "ATM存款", "提现", "取现", "预约取现", "支付机构提现", "ATM取款", "消费",
                    "银联消费", "有卡自助消费", "无卡自助交易", "无卡支付", "跨行其他渠道消费", "充值",
                    "缴费", "工资", "奖金", "年终奖", "年度奖金", "月日约定提取", "机构", "其他"):
        for direction in ("收入", "支出"):
            add("ccb_debit", txn_type="", summary=summary, direction=direction)
    add("ccb_debit", txn_type="", summary="其他", amount="20.00")
    add("ccb_debit", txn_type="", summary="其他", amount="-20.00")
    add("other", txn_type="", summary="其他", amount="-20.00")
    add(None, bill_source="wechat", txn_type="商户消费", summary="", amount="-20.00")
    add(None, source_type="alipay", txn_type="其他", summary="", amount="-20.00")
    add("ccb_debit", txn_type="", summary="", note="柜台转账", amount="-20.00")
    add("ccb_debit", txn_type="", summary="", location="利息收入", amount="20.00")
    add("ccb_debit", txn_type="", summary="", acct_name_raw="工资代发", amount="20.00")
    add("ccb_debit", txn_type="", summary="跨行转账手续费", amount="-20.00")
    add("ccb_debit", txn_type="", summary="个人购汇支出", amount="-20.00")
    add("icbc_debit", txn_type="", summary="跨境汇款", direction="支出")
    add("icbc_debit", txn_type="", summary="跨境汇款", direction="收入")
    add("wechat", txn_type="商户消费", summary="消费\u00a0", amount="-20.00")
    add("ccb_debit", txn_type="", summary="工资\u00a0", amount="20.00")
    for amount in (" 20.00", "２０", "1_000", "+20.00", "1e3", "-0.00"):
        add("wechat", txn_type="其他类型", summary="", amount=amount)
    return cases


def account_cases():
    cases = []

    def add(value, *, source="", reference="", payload=None):
        cases.append({"value": value, "source": source, "reference": reference, "payload": payload})

    for value in (None, "", " ", "/", "-", "--", "(空)", "（空）"):
        add(value)
    for value in ("6222****1234", "6222 ＊＊＊＊ 1234", "6222-****-1234", "****", "****1234"):
        add(value)
    add("6222********1234", source="icbc_asia", reference="6222000011111234", payload={"a": 1})
    add("6222********1234", source="icbc_asia", reference="6223000011111234", payload={"a": 1})
    add("6222********1234", source="icbc_asia", reference="622200001111", payload={"a": 1})
    add("6222********1234", source="icbc_asia", reference="6222000011111234")
    add("6222****1234", source="ccb_debit", reference="6222000011111234")
    add("123", source="")
    add("1234", source="")
    add("12345", source="")
    add("6222 0000 1111 1234", source="")
    add("6222-0000-1111-1234", source="")
    add("尾号1234", source="")
    add("abc1234xyz", source="abc")
    add("a@example.test", source="")
    add("支付宝账户", source="")
    add("()", source="")
    add("（）", source="")
    add("( )", source="")
    add("- -", source="")
    add("6222\u00a00000\u00a01111\u00a01234", source="")
    add("\u00a0", source="")
    add("尾号１２３４", source="")
    add("１２３４", source="")
    return cases


def validate_cases():
    cases = []
    for record_type in sorted(VALID_CASH_RECORD_TYPES):
        cases.append({"kind": "default", "type": record_type,
            "subtype": default_cash_record_subtype(record_type)})
        for subtype in sorted(VALID_CASH_RECORD_SUBTYPES):
            try:
                validate_cash_record_subtype(record_type, subtype)
                error = None
            except ValueError as failure:
                error = str(failure)
            cases.append({"kind": "subtype", "type": record_type, "subtype": subtype, "error": error})
    for record_type in ("unknown", "", None):
        for subtype in ("not_applicable", "ordinary_transfer", "unknown", None):
            try:
                validate_cash_record_subtype(record_type, subtype)
                error = None
            except (ValueError, TypeError) as failure:
                error = str(failure)
            cases.append({"kind": "subtype", "type": record_type, "subtype": subtype, "error": error})
    accounts = [
        ("", [], None), ("", ["tail"], None), ("1234", ["tail"], None), ("1234", [], None),
        ("1234", ["full"], None), ("1234", ["masked"], None), ("1234", ["masked", "reconstructed"], None),
        ("6222****1234", ["masked"], None), ("6222000011111234", ["masked", "reconstructed"], None),
        ("6222000011111234", ["full"], None), ("abc", ["full"], None), ("abc", ["tail"], None),
        ("", None, None), ("", ["bad"], None), ("", ["tail", "full"], None),
    ]
    for value, attrs, error in accounts:
        try:
            validate_counterparty_account(value, attrs)
            error = None
        except ValueError as failure:
            error = str(failure)
        cases.append({"kind": "account_validation", "value": value, "attrs": attrs, "error": error})
    return cases


def export(output: Path) -> None:
    result = {
        "rows": [
            {**case, "type": classify_cash_record_type(case["source"], case["row"]),
             "record_type": classify_cash_record(case["source"], case["row"])[0],
             "record_subtype": classify_cash_record(case["source"], case["row"])[1]}
            for case in rows()
        ],
        "accounts": [
            {**case,
             "account": normalize_counterparty_account(case["value"], source=case["source"],
                source_account_identifier=case["reference"], source_payload=case["payload"]).value,
             "attrs": list(normalize_counterparty_account(case["value"], source=case["source"],
                source_account_identifier=case["reference"], source_payload=case["payload"]).attrs),
             "digest": _source_payload_digest(case["payload"])}
            for case in account_cases()
        ],
        "validation": validate_cases(),
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    export(parser.parse_args().output)

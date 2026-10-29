"""Export anonymous, deterministic single-row source-effect contracts from Python."""
from __future__ import annotations

import argparse
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import random

from ft import convert
from ft.adapters.cash_statement_effects import prepare_cash_source_effects
from ft.application.cash_import_operations import _wire


TARGET = Path(__file__).resolve().parents[1] / "compose/backend/src/test/resources/python-cash-source-effects.json"
SOURCES = ("wechat", "alipay", "ccb_debit", "icbc_asia", "icbc_credit", "icbc_debit", "unknown")
IDENTITY = {"card_number": "1234", "file_account_key": "trusted", "_source_account_identifier": "trusted",
    "source_display_name": "匿名来源", "_is_refund": True, "offset_group": "discard", "txn_id": "old"}


def case(source, payload, *, amount="12.00", patch=None, protected=(), explanation=None):
    values = {"note": "已有原文", "amount": amount, "record_type": "consumption", "record_subtype": "not_applicable",
        "source_payload": payload, "account_id": "41", "components": [{"amount": "-12.00", "ordinal": 0}]}
    return {"source": source, "values": values, "patch": {"source_payload": payload} if patch is None else patch,
        "protected": list(protected), "explanation": IDENTITY if explanation is None else explanation}


def cases():
    payloads = [None, {}, [], ["退款"], 1, True, "退款", {"备注": "  保留\n原文  ", "商品": ""},
        {"商品": "\t商品\u0085", "摘要": "退款", "当前状态": "退款成功", "交易状态": "退款成功"},
        {"摘要": None, "交易场所": ["退款"], "对方户名": {"value": "退货"}, "支付方式": False},
        {"摘要": "退货", "摘\n要": "消费", "备\n注": "原\n文", "备注": "冲突", "渠道": "网银"},
        {"_source_account_identifier": "forged", "card_number": "9999", "交易卡号": "9999", "_is_refund": True},
        {"nested": {"integer": 2**80, "float": 1.5, "boolean": True, "null": None}},
    ]
    for source in SOURCES:
        for payload in payloads:
            for amount in [None, "0", "-0.000", "12.00", "-12", "0.000000000000000001"]:
                yield case(source, payload, amount=amount)
        for patch in [{}, {"note": ""}, {"record_type": "transfer"}, {"record_subtype": None},
                {"amount": "12"}, {"source_payload": None}, {"source_payload": None, "note": "explicit"}]:
            for protected in [(), ("note",), ("source_payload",)]:
                yield case(source, {"备注": "源备注", "商品": "商品"}, patch=patch, protected=protected)
        for explanation in [{}, [], "identity", {"card_number": 1234, "file_account_key": True, "_is_refund": 1},
                {"card_number": "", "file_account_key": " ", "_is_refund": False, "_is_reversal": True},
                {"payment_method": "可信", "offset_group": "discard", "_is_refund": False}]:
            for patch in [{"source_payload": {}}, {"record_type": "transfer"}]:
                yield case(source, {}, patch=patch, explanation=explanation)
    methods = ["", "零钱", "零钱通", "账户余额", "余额宝", "余额宝(自动转入)", "余利宝—支出", "尾号1234",
        "云闪付-中国工商银行储蓄卡（尾号 3697）", "云闪付-中国工商银行信用卡（尾号 9166）",
        "中国银行信用卡1234", "中国建设银行储蓄卡尾号9999", "中国银行", "云闪付-尾号1234",
        "工商银行储蓄卡(１２３４)", "工商银行储蓄卡(12345)", "工商银行储蓄卡(1234)(￥1,200.05)",
        "工商银行信用卡(1234)分期（12期）", "工商银行信用卡分期(١٢期)(1234)",
        "工商银行信用卡分期(𝟙𝟚期)(1234)", "工商银行信用卡分期(\U00011f51期)(1234)",
        "工商银行信用卡(1234) 3期 ￥+1,200.50", "工商银行储蓄卡1234【-20.25元】", "&账户余额&&",
        "中国工商银行储蓄卡(1234)￥10&余额宝￥20", "未知金额（￥12）", "中国银行1234(12.5元)"]
    for method in methods:
        for source, field in [("wechat", "支付方式"), ("alipay", "收/付款方式")]:
            for direction in ["收入", "/"]:
                yield case(source, {field: method, "收/支": direction, "交易类型": "零钱提现"})
    for whitespace in ["\t", "\n", "\r", "\u0085", "\u001c", "\u00a0", "\u2007", "\u2028", "\u202f", "\u3000", "\u200b"]:
        for source in SOURCES[:-1]:
            yield case(source, {"摘" + whitespace + "要": "退" + whitespace + "货", "备" + whitespace + "注": whitespace + "原文" + whitespace,
                "支付方式": "中国" + whitespace + "银行(1234)", "收/付款方式": "中国银行分期(" + whitespace + "12期)(1234)"})
    descriptions = ["余额宝转出到银行卡", "余利宝转出到银行卡", "余额宝-转出到余额", "支付宝转入到余利宝",
        "余额宝-单次转入", "余额宝收益发放", "卖出至余额宝", "退款", "普通消费", "x" * 79 + "退款",
        "😀" * 79 + "退款", "😀" * 76 + "退款", "淘宝退款订单", "合成商户（退款）外卖订单", "门店 - 高德地图"]
    for description in descriptions:
        for counterparty in ["", "淘宝", "先骑后付", "财付通-合成商户", "高德地图"]:
            for amount in [None, "0", "-12"]:
                yield case("alipay", {"交易对方": counterparty, "商品说明": description, "收/付款方式": "余利宝(渠道)"}, amount=amount)
    for keywords, _ in convert.PLATFORM_RULES:
        for keyword in sorted(keywords):
            for source in ["alipay", "icbc_credit"]:
                for prefix in ["", "财付通-", "1/12 支付宝-"]:
                    counterparty = prefix + keyword + "官方旗舰店"
                    yield case(source, {"对方户名": counterparty, "交易对方": counterparty})
    for summary in ["", "退货", "退款", "撤销交易", "减免年费退款", "刷卡金退款", "返现退款", "Rebate退款", "rebate", "X无1卡2支3付Y"]:
        for counterparty in ["", "A", "工商", "12.5-", "١٢.٣", "\U00011f51", "😀", "退货", "财付通-退款", "基金快速赎回", "原始商户"]:
            for source in ["icbc_credit", "icbc_debit"]:
                yield case(source, {"摘要": summary, "对方户名": counterparty, "交易场所": "支付宝-地点", "渠道": "网\n银"})
    for location in ["***", "消费-财付通-微信支付-消费-门店", "消费-支付宝-支付宝-转账-淘宝-门店",
            "美团支付-消费-消费-商户", "银行转证券1234转入086", "证券转银行𝟙𝟚𝟛", "银行转证券1234\u0085",
            "银行转证券1234\r", "支付宝-", "pAyPaL_TEST", "消费-PayPal_Test"]:
        for account in ["", "编号/商户", "编号/商户/附加", "  对方名称  "]:
            yield case("ccb_debit", {"摘要": "冲正", "交易地点/附言": location, "对方账号与户名": account})
    rng = random.Random(20261029)
    fields = ["摘要", "备注", "商品", "当前状态", "交易状态", "收/支", "支付方式", "交易类型", "交易对方",
        "商品说明", "收/付款方式", "对方户名", "交易场所", "交易地点/附言", "对方账号与户名", "渠道"]
    atoms = ["", None, 42, False, {"value": "退款"}, ["退款"], "退款成功", "退款", "不计收支", "收入", "零钱提现",
        "先骑后付", "北京东子", "消费", "工\n资", "基金赎回", "合成商户", "  来源原文  ", *methods, *descriptions]
    for _ in range(300):
        yield case(rng.choice(SOURCES), {field: rng.choice(atoms) for field in rng.sample(fields, 6)},
            amount=rng.choice([None, "12", "-12", "0", "-0.00"]))


def content():
    result = []
    for item in cases():
        before = deepcopy(item)
        values = deepcopy(item["values"])
        if values["amount"] is not None:
            values["amount"] = Decimal(values["amount"])
        untouched = deepcopy(values)
        final, explanation = prepare_cash_source_effects(values, item["patch"], source_type=item["source"],
            source_explanation=item["explanation"], protected_fields=set(item["protected"]))
        assert values == untouched and item == before
        result.append({"input": before, "values": _wire(final), "explanation": explanation})
    return json.dumps(result, ensure_ascii=True, separators=(",", ":")) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = content()
    if args.check:
        if not TARGET.exists() or TARGET.read_text() != expected:
            raise SystemExit("source effects golden differs; regenerate explicitly")
    else:
        TARGET.write_text(expected)
    print(f"{len(json.loads(expected))} source-effect contracts {'checked' if args.check else 'generated'}")


if __name__ == "__main__":
    main()

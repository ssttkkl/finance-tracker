"""来源正文更新沿用单行解释，不能改写账户身份或财务字段。"""
from copy import deepcopy
from decimal import Decimal

import pytest


def test_source_patch_replaces_note_and_stale_explanation_without_creating_manual_override():
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    values = {"note": "旧原文", "amount": Decimal("12"), "record_type": "refund", "record_subtype": "not_applicable",
        "source_payload": {"备注": "  新原文\n完整保留  ", "交易单号": "new-id", "当前状态": "支付成功", "收/支": "收入"}}
    before = deepcopy(values)
    final, evidence = prepare_cash_source_effects(values, {"source_payload": values["source_payload"]},
        source_type="wechat", source_explanation={"txn_id": "old-id", "_refund_signal": "wechat_status",
            "platform_status": "退款成功", "offset_group": "old-choice", "_source_account_identifier": "trusted-owner"})
    assert final["note"] == "  新原文\n完整保留  " and final["amount"] == Decimal("12")
    assert final["record_type"] == "refund" and final["source_payload"] == before["source_payload"]
    assert evidence["txn_id"] == "new-id" and evidence["platform_status"] == "支付成功"
    assert evidence["_source_account_identifier"] == "trusted-owner"
    assert "_refund_signal" not in evidence and "offset_group" not in evidence
    assert values == before


@pytest.mark.parametrize("protected,patch,note", [(set(), {"note": ""}, ""),
    ({"note"}, {}, "人工值"), ({"note"}, {"note": "本次明确值"}, "本次明确值"), (set(), {}, "")])
def test_explicit_note_and_existing_protection_take_priority(protected, patch, note):
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    final, _ = prepare_cash_source_effects({"note": patch.get("note", "人工值"), "source_payload": {}, "amount": None},
        {"source_payload": {}, **patch}, source_type="alipay", protected_fields=protected, source_explanation={})
    assert final["note"] == note


@pytest.mark.parametrize("payload", [None, {}, {"摘要": None, "交易场所": ["退款"], "对方户名": {"value": "退货"}},
    {"摘要": "退货", "摘\n要": "消费", "备注": 42}])
def test_arbitrary_json_does_not_forge_refund_evidence_or_replace_trusted_owner(payload):
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    _, evidence = prepare_cash_source_effects({"note": "旧", "source_payload": payload, "amount": None},
        {"source_payload": payload}, source_type="icbc_credit", source_explanation={
            "file_account_key": "trusted", "card_number": "1234", "_source_account_identifier": "trusted",
            "source_display_name": "工商银行信用卡", "_is_refund": True, "offset_type": "merchant_refund"})
    assert evidence["_source_account_identifier"] == "trusted" and evidence["card_number"] == "1234"
    assert "_is_refund" not in evidence and "offset_type" not in evidence


def test_pdf_note_normalizes_only_column_names_and_preserves_raw_values():
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    payload = {"备\n注": "  原始\n备注  ", "交易卡号": "untrusted-replacement", "渠道": "新渠道", "摘要": "退款"}
    final, evidence = prepare_cash_source_effects({"note": "旧", "source_payload": payload, "amount": Decimal("12")},
        {"source_payload": payload}, source_type="icbc_debit", source_explanation={
            "_source_account_identifier": "trusted-owner", "file_account_key": "trusted-owner", "card_number": "1234"})
    assert final["note"] == "  原始\n备注  " and final["source_payload"] == payload
    assert evidence["_source_account_identifier"] == "trusted-owner" and evidence["payment_method"] == "新渠道"
    assert evidence["_is_refund"] is True and evidence["_refund_signal"] == "icbc_debit_refund"


def test_note_only_does_not_reinterpret_source_and_type_only_drops_cross_row_cache():
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    evidence = {"_is_refund": True, "platform_status": "退款成功", "offset_role": "refund", "offset_group": "old"}
    values = {"note": "人工", "source_payload": {"当前状态": "支付成功"}, "record_type": "transfer"}
    _, unchanged = prepare_cash_source_effects(values, {"note": "人工"}, source_type="wechat", source_explanation=evidence)
    assert unchanged is None
    final, current = prepare_cash_source_effects(values, {"record_type": "transfer"}, source_type="wechat", source_explanation=evidence)
    assert final == values and current == {"_is_refund": True, "platform_status": "退款成功"}


@pytest.mark.parametrize("source_type", ["ccb_debit", "icbc_debit"])
@pytest.mark.parametrize("amount,expected", [("12", True), ("-12", False)])
def test_draft_amount_patch_refreshes_direction_dependent_evidence_without_replacing_note(source_type, amount, expected):
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    final, evidence = prepare_cash_source_effects({"note": "已有原文", "amount": Decimal(amount), "source_payload": {"摘要": "退款"}},
        {"amount": amount}, source_type=source_type, source_explanation={"_refund_signal": "old-positive"})
    assert final["note"] == "已有原文"
    assert bool(evidence.get("_refund_signal")) == expected


@pytest.mark.parametrize("source_type,payload,amount,expected", [
    ("alipay", {"交易分类": "退款", "交易状态": "退款成功", "收/支": "不计收支", "交易订单号": "p1", "商家订单号": "m1",
        "收/付款方式": "余额宝", "商品说明": "来源原文"}, "12", {"_refund_signal": "alipay_status", "payment_method": "余额宝", "txn_id": "p1"}),
    ("wechat", {"交易类型": "零钱提现", "当前状态": "提现成功", "收/支": "/", "支付方式": "尾号9999"}, "-12",
        {"payment_method": "零钱", "_wechat_direction": "/"}),
    ("ccb_debit", {"摘要": "退款", "交易地点/附言": "财付通-微信支付-合成商户", "对方账号与户名": "synthetic-account/原商户"}, "12",
        {"_ccb_location_cp": "合成商户", "_ccb_acct_cp": "原商户", "_refund_signal": "ccb_debit_refund", "payment_method": "微信支付"}),
    ("icbc_asia", {"摘要": "转入", "業務類型": "转账", "交易場所": "来源地点", "對方戶名": "原商户"}, "12",
        {"summary": "转入", "txn_type": "转账", "location": "来源地点", "_raw_cp": "原商户", "payment_method": "工银亚洲活期账户(1234)"}),
    ("icbc_credit", {"摘要": "退货", "对方户名": "合成商户", "交易场所": "原始地点"}, "12",
        {"offset_type": "merchant_refund", "_is_refund": True, "refund_signal": "icbc_credit_return"}),
    ("icbc_debit", {"摘要": "撤销交易", "对方户名": "原商户", "渠道": "网银"}, "-12",
        {"_debit_offset_type": "reversal", "_is_refund": False, "_is_reversal": True, "payment_method": "网银"}),
])
def test_each_source_uses_its_native_single_row_rules(source_type, payload, amount, expected):
    from ft.adapters.cash_statement_effects import prepare_cash_source_effects
    _, explanation = prepare_cash_source_effects({"note": "旧", "source_payload": payload, "amount": Decimal(amount)},
        {"source_payload": payload}, source_type=source_type, source_explanation={"card_number": "1234"})
    assert all(explanation.get(key) == value for key, value in expected.items())

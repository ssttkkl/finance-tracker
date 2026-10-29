from dataclasses import replace
from decimal import Decimal

import pytest

from ft.adapters.cash_statement_document import CashStatementDocument, RawCashRow
from ft.domain.cash_source_matching import SourceCoverage


def _payload(source="alipay", **changes):
    if source == "alipay":
        value = {"交易时间": "2026-08-15 10:00:00", "交易分类": "日用百货", "交易对方": "测试商户", "对方账号": "cp-account",
                 "商品说明": "原始描述", "收/支": "支出", "金额": "20.00", "收/付款方式": "测试银行卡",
                 "交易状态": "交易成功", "交易订单号": "0000123", "商家订单号": "merchant-1"}
    else:
        value = {"交易时间": "2026-08-15 10:00:00", "交易类型": "商户消费", "交易对方": "测试商户", "商品": "原始商品",
                 "收/支": "支出", "金额(元)": "20.00", "支付方式": "零钱", "当前状态": "支付成功",
                 "交易单号": "0000123", "商户单号": "merchant-1", "备注": "原始备注"}
    return dict(value, **changes)


def _document(*payloads, source="alipay"):
    return CashStatementDocument(source, "csv:0", "platform-rows-v1",
        tuple(RawCashRow(index + 1, payload) for index, payload in enumerate(payloads)),
        "fixture-owner", "fixture-owner", SourceCoverage(
            start="2026-08-01T00:00:00+08:00", end="2026-09-01T00:00:00+08:00",
            complete=True, evidence="fixture", filters={"export_type": "all"}), len(payloads), len(payloads))


def _convert(document):
    from ft.adapters.cash_statement_rows import normalize_platform_document
    return normalize_platform_document(document)


@pytest.mark.parametrize("source,amount_field", [("alipay", "金额"), ("wechat", "金额(元)")])
def test_native_conversion_keeps_occurrences_and_preserves_other_rows_on_bad_amount(source, amount_field):
    first = _payload(source)
    parsed = _convert(_document(first, _payload(source, **{amount_field: "invalid"}), first, source=source))
    assert len(parsed.rows) == 3
    assert [row.position for row in parsed.rows] == [1, 2, 3]
    assert parsed.rows[0].fields["amount"] == parsed.rows[2].fields["amount"] == "-20.00"
    assert parsed.rows[1].fields["amount"] is None
    assert parsed.rows[1].issue_codes == ("amount_unreadable",)
    assert parsed.rows[1].error_code is None
    assert parsed.rows[0].fields["occurred_at"] == "2026-08-15T02:00:00+00:00"
    assert parsed.rows[0].fields["source_payload"] == first
    assert not parsed.coverage.complete


@pytest.mark.parametrize("source,changes,expected_hint,amount,record_type", [
    ("alipay", {"交易分类": "账户提现", "收/支": "不计收支", "商品说明": "提现-实时提现"}, "测试银行卡", "-20.00", "withdrawal_out"),
    ("alipay", {"交易分类": "投资理财", "收/支": "不计收支", "商品说明": "余额宝-转出到银行卡"}, "余额宝", "-20.00", "withdrawal_out"),
    ("wechat", {"交易类型": "零钱提现", "收/支": "/", "支付方式": "目标银行卡"}, "零钱", "-20.00", "withdrawal_out"),
    ("wechat", {"交易类型": "信用卡还款", "收/支": "/"}, "零钱", "-20.00", "repayment"),
])
def test_uses_existing_native_financial_and_classification_rules(source, changes, expected_hint, amount, record_type):
    row = _convert(_document(_payload(source, **changes), source=source)).rows[0]
    assert row.fields["amount"] == amount
    assert row.payment_method == expected_hint
    assert row.fields["record_type"] == record_type


@pytest.mark.parametrize("source,changes,expected", [
    ("alipay", {"商品说明": "  完整原文\n" + "长" * 150 + "  "}, "  完整原文\n" + "长" * 150 + "  "),
    ("alipay", {"商品说明": ""}, ""),
    ("wechat", {"备注": "  原始备注\n  ", "商品": "其他商品"}, "其他商品"),
    ("wechat", {"备注": "", "商品": "原始商品描述"}, "原始商品描述"),
    ("wechat", {"备注": "", "商品": ""}, ""),
])
def test_note_is_exact_native_text_without_generated_fallback(source, changes, expected):
    row = _convert(_document(_payload(source, **changes), source=source)).rows[0]
    assert row.fields["note"] == expected


def test_wechat_counterparty_and_product_are_mapped_exactly_and_snapshot_is_unchanged():
    payload = _payload("wechat", 交易对方="财付通-美团平台商户", 商品=" 原始商品  \n", 备注="独立备注")
    row = _convert(_document(payload, source="wechat")).rows[0]
    assert row.fields["counterparty"] == payload["交易对方"]
    assert row.fields["note"] == payload["商品"]
    assert row.fields["source_payload"] == payload


def test_wechat_empty_product_does_not_fall_back_to_remark():
    payload = _payload("wechat", 商品="", 备注="不得回退到这里")
    row = _convert(_document(payload, source="wechat")).rows[0]
    assert row.fields["note"] == ""
    assert row.fields["source_payload"] == payload


@pytest.mark.parametrize("source,field,raw,expected", [
    ("alipay", "收/付款方式", "云闪付-中国工商银行储蓄卡（尾号 3697）", "工商银行储蓄卡(3697)"),
    ("wechat", "支付方式", "云闪付-中国工商银行储蓄卡（尾号 3697）", "工商银行储蓄卡(3697)"),
    ("alipay", "收/付款方式", "中国工商银行信用卡（尾号 9166）", "工商银行信用卡(9166)"),
    ("wechat", "支付方式", "工商银行 ( 3697 )", "工商银行(3697)"),
    ("wechat", "支付方式", "中国工商银行储蓄卡", "工商银行储蓄卡"),
    ("wechat", "支付方式", "中国银行信用卡(3697)", "中国银行信用卡(3697)"),
    ("wechat", "支付方式", "云闪付-微信零钱", "微信零钱"),
    ("wechat", "支付方式", "星巴克储值卡", "星巴克储值卡"),
    ("alipay", "收/付款方式", "余额（¥30）", "余额¥30"),
])
def test_platform_channel_converters_clean_only_supported_bank_account_labels(source, field, raw, expected):
    row = _convert(_document(_payload(source, **{field: raw}), source=source)).rows[0]
    assert row.payment_method == expected
    assert row.fields["source_payload"][field] == raw


def test_alipay_blank_transaction_id_remains_without_reliable_provider_identity():
    parsed = _convert(_document(_payload("alipay", **{"交易订单号": ""}), _payload("alipay", **{"交易订单号": "000123"}), source="alipay"))
    assert parsed.rows[0].provider_id is None
    assert parsed.rows[1].provider_id == "000123"


@pytest.mark.parametrize("identifier", ["", " ", "/", "--"])
def test_wechat_transaction_id_is_required_for_every_statement_row(identifier):
    from ft.adapters.cash_statement_document import StatementDocumentError
    with pytest.raises(StatementDocumentError, match="statement_unreadable"):
        _convert(_document(_payload("wechat", **{"交易单号": identifier}), source="wechat"))


@pytest.mark.parametrize("source", ["alipay", "wechat"])
def test_unknown_direction_remains_draft_without_inventing_sign(source):
    row = _convert(_document(_payload(source, **{"收/支": "未知"}), source=source)).rows[0]
    assert row.fields["amount"] is None
    assert row.fields["record_type"] is None
    assert row.issue_codes == ("direction_unrecognized",)
    assert row.error_code is None


def test_bad_time_keeps_other_fields_and_is_never_sliced_into_a_valid_timestamp():
    parsed = _convert(_document(_payload(交易时间="2026-08-15 10:00:00 invalid"), _payload()))
    assert parsed.rows[0].fields["occurred_at"] is None
    assert parsed.rows[0].fields["amount"] == "-20.00"
    assert parsed.rows[0].issue_codes == ("time_unreadable",)
    assert parsed.rows[1].issue_codes == ()
    assert not parsed.coverage.complete


def test_known_whitelist_skip_has_reason_and_does_not_drop_other_occurrences():
    parsed = _convert(_document(_payload(交易状态="交易关闭", **{"收/支": "收入", "收/付款方式": ""}), _payload()))
    assert len(parsed.rows) == 2
    assert parsed.rows[0].disposition == "skipped"
    assert parsed.rows[0].error_code == "unpaid_closed"
    assert parsed.rows[1].disposition == "candidate"


def test_unexpected_importer_error_propagates_to_file_boundary(monkeypatch):
    from ft import convert
    original = convert._parse_alipay_source_row
    def failing(payload, *, position):
        if payload["交易对方"] == "bad":
            raise RuntimeError("technical secret from importer")
        return original(payload, position=position)
    monkeypatch.setattr(convert, "_parse_alipay_source_row", failing)
    with pytest.raises(RuntimeError, match="technical secret from importer"):
        _convert(_document(_payload(交易对方="bad"), _payload()))


def test_structural_error_is_retained_without_creating_an_empty_cash_record():
    document = _document(_payload())
    document = replace(document, rows=(RawCashRow(1, None, "row_shape_invalid"), *document.rows))
    parsed = _convert(document)
    assert parsed.rows[0].error_code == "row_shape_invalid"
    assert parsed.rows[0].fields is None
    assert len(parsed.rows) == 2


@pytest.mark.parametrize("source,amount_field", [("alipay", "金额"), ("wechat", "金额(元)")])
def test_sign_routing_does_not_round_38_digit_values(source, amount_field):
    value = "12345678901234567890.123456789012345678"
    row = _convert(_document(_payload(source, **{amount_field: value}), source=source)).rows[0]
    assert Decimal(row.fields["amount"]) == Decimal(value).copy_negate()


@pytest.mark.parametrize("value", ["NaN", "Infinity", "1e10000", "0.0000000000000000001"])
def test_non_finite_or_unrepresentable_amount_stays_unknown(value):
    row = _convert(_document(_payload(金额=value))).rows[0]
    assert row.fields["amount"] is None
    assert row.issue_codes == ("amount_unreadable",)


@pytest.mark.parametrize("source", ["alipay", "wechat"])
def test_native_source_evidence_is_captured_in_the_same_single_row_call(monkeypatch, source):
    from ft import convert
    name = "_parse_alipay_source_row" if source == "alipay" else "_parse_wechat_source_row"
    original, calls = getattr(convert, name), []
    def parse(payload, *, position):
        calls.append(position)
        return dict(original(payload, position=position), summary=["invalid hint"], _is_refund="bad",
            offset_group="do not keep", note="do not replace raw note")
    monkeypatch.setattr(convert, name, parse)
    row = _convert(_document(_payload(source), source=source)).rows[0]
    assert calls == [1]
    assert row.source_explanation["txn_id"] == "0000123"
    assert row.source_explanation["merchant_order_id"] == "merchant-1"
    assert row.source_explanation["platform_status"] in {"交易成功", "支付成功"}
    assert not {"summary", "_is_refund", "offset_group", "note", "_fact_id"} & row.source_explanation.keys()
    assert row.fields["note"] == ("原始描述" if source == "alipay" else "原始商品")
    assert row.fields["amount"] == "-20.00" and row.error_code is None


@pytest.mark.parametrize("channel", ["platform", "bank"])
def test_converter_programming_error_is_not_a_skipped_source_row(monkeypatch, channel):
    import ft.adapters.cash_statement_rows as module
    converter, operation = (module.normalize_platform_document, "_normalize_row") if channel == "platform" else (module.normalize_bank_document, "_bank_row")
    def broken(*args, **kwargs):
        raise RuntimeError("synthetic converter programming error")
    monkeypatch.setattr(module, operation, broken)
    with pytest.raises(RuntimeError, match="synthetic converter programming error"):
        converter(_document(_payload(), source="alipay" if channel == "platform" else "ccb_debit"))

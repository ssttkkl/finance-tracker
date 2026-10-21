from copy import deepcopy

import pytest


def test_source_explanation_keeps_only_single_row_scalar_evidence():
    from ft.adapters.cash_statement_evidence import extract_source_explanation, valid_source_explanation
    native = {
        "txn_id": "000123", "merchant_order_id": "order-1", "txn_type": "退款",
        "platform_status": "退款成功", "_wechat_direction": "收入", "summary": "退货",
        "_raw_cp": "原始商户", "_is_refund": True, "_is_reversal": False,
        "_icbc_refund_merchant_trusted": True, "_refund_signal": "icbc_credit_return",
        "_ccb_location_cp": "商户", "_ccb_acct_cp": "收款方",
        "amount": "20.00", "category": "income", "note": "生成描述",
        "_fact_id": "generated-hash", "_source_payload": {"原始": "数据"},
        "offset_group": "group", "offset_role": "refund", "offset_strength": "hard",
        "offset_source": "matcher", "offset_rule_hint": "rule", "offset_match_type": "direct",
        "candidate_ids": [1, 2], "password": "secret",
    }
    before = deepcopy(native)
    explanation = extract_source_explanation(native)
    assert explanation == {key: value for key, value in native.items() if key in {
        "txn_id", "merchant_order_id", "txn_type", "platform_status", "_wechat_direction", "summary",
        "_raw_cp", "_is_refund", "_is_reversal", "_icbc_refund_merchant_trusted", "_refund_signal",
        "_ccb_location_cp", "_ccb_acct_cp",
    }}
    assert valid_source_explanation(explanation)
    assert native == before


@pytest.mark.parametrize("native", [None, [], "bad", {
    "txn_id": ["not", "scalar"], "summary": 42, "_is_refund": 1, "_is_reversal": "true",
    "platform_status": None, "txn_type": "", "unknown": "private",
}])
def test_invalid_native_hints_are_ignored_without_rejecting_financial_row(native):
    from ft.adapters.cash_statement_evidence import extract_source_explanation
    assert extract_source_explanation(native) == {}


@pytest.mark.parametrize("value", [None, [], {"unknown": "private"}, {"note": "generated"},
    {"txn_id": []}, {"summary": 1}, {"_is_refund": 1}, {"_is_reversal": "true"}])
def test_staging_explanation_shape_is_strict(value):
    from ft.adapters.cash_statement_evidence import valid_source_explanation
    assert not valid_source_explanation(value)

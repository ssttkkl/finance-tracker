"""原生单行解释白名单；不保存跨行配对决定或生成的流水字段。"""
from __future__ import annotations


_TEXT_KEYS = frozenset({
    "txn_type", "txn_id", "merchant_order_id", "platform_status", "status",
    "_wechat_direction", "_alipay_direction", "direction", "summary",
    "refund_signal", "_refund_signal", "_ccb_refund_signal", "_raw_cp",
    "offset_type", "_debit_offset_type", "location", "acct_name_raw",
    "_ccb_location_cp", "_ccb_acct_cp", "card_number", "_source_account_identifier",
    "file_account_key", "source_display_name", "payment_method",
})
_BOOL_KEYS = frozenset({"_is_refund", "_is_reversal", "_icbc_refund_merchant_trusted"})


def extract_source_explanation(native) -> dict[str, str | bool]:
    if not isinstance(native, dict):
        return {}
    return {key: value for key, value in native.items()
        if key in _TEXT_KEYS and type(value) is str and value
        or key in _BOOL_KEYS and type(value) is bool}


def valid_source_explanation(value) -> bool:
    return isinstance(value, dict) and all(
        key in _TEXT_KEYS and type(item) is str
        or key in _BOOL_KEYS and type(item) is bool
        for key, item in value.items()
    )

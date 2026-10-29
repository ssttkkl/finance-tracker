"""分别计算资金账户映射范围和来源交易身份范围。"""
from __future__ import annotations

ALIPAY_TRANSACTION_SCOPE = "alipay:transaction:v1"
WECHAT_DEFAULT_SCOPE = "wechat:default:v1"
_INVALID_PLATFORM_TRANSACTION_IDS = {"", "/", "-", "--"}


def is_valid_platform_transaction_id(value: object) -> bool:
    return (
        isinstance(value, str)
        and value == value.strip()
        and value not in _INVALID_PLATFORM_TRANSACTION_IDS
        and len(value) <= 512
    )


def source_account_key(source_type: str, owner: str | None = None) -> str | None:
    if source_type == "wechat":
        return WECHAT_DEFAULT_SCOPE
    if owner is not None and (not isinstance(owner, str) or not owner or len(owner) > 256):
        raise ValueError("source_account_invalid")
    return owner


def source_transaction_scope_key(source_type: str, owner: str | None = None) -> str | None:
    """返回来源流水身份范围；资金账户分组继续使用 source_account_key。"""
    owner_key = source_account_key(source_type, owner)
    if source_type == "alipay":
        return ALIPAY_TRANSACTION_SCOPE
    return owner_key

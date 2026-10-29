"""账单来源范围；微信使用固定内部范围，其他渠道按来源账户隔离。"""
from __future__ import annotations

WECHAT_DEFAULT_SCOPE = "wechat:default:v1"


def source_account_key(source_type: str, owner: str | None = None) -> str | None:
    if source_type == "wechat":
        return WECHAT_DEFAULT_SCOPE
    if owner is not None and (not isinstance(owner, str) or not owner or len(owner) > 256):
        raise ValueError("source_account_invalid")
    return owner

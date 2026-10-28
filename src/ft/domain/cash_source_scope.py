"""账单来源范围；微信默认单一来源与内部资金账户无关。"""
from __future__ import annotations

import unicodedata


WECHAT_DEFAULT_SCOPE = "wechat:default:v1"
_WECHAT_NAMED_PREFIX = "wechat:named:v1:"


class CashSourceScopeError(ValueError):
    def __init__(self, code="source_scope_invalid"):
        self.code = code
        super().__init__(code)


def normalize_scope_label(value: str | None) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or any(unicodedata.category(char).startswith("C") for char in value):
        raise CashSourceScopeError
    value = unicodedata.normalize("NFC", value).strip()
    if len(value) > 64 or len(value.encode("utf-8")) > 256:
        raise CashSourceScopeError
    return value or None


def source_account_key(source_type: str, owner: str | None = None, *, scope_label: str | None = None) -> str | None:
    label = normalize_scope_label(scope_label)
    if source_type == "wechat":
        return _WECHAT_NAMED_PREFIX + label if label else WECHAT_DEFAULT_SCOPE
    if label is not None:
        raise CashSourceScopeError("source_scope_unsupported")
    if owner is not None and (not isinstance(owner, str) or not owner or len(owner) > 256):
        raise CashSourceScopeError
    return owner

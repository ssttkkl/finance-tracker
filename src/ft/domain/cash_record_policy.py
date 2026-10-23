"""现金流水的统一字段约束；不依赖导入、HTTP 或持久化入口。"""
from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import localcontext
import math
from typing import Any

from ft.domain.decimal import exact_decimal
from ft.domain.record_type import validate_cash_record_subtype


MUTABLE_CASH_FIELDS = frozenset({
    "counterparty", "counterparty_account", "counterparty_account_attrs", "note",
    "category_id", "record_type", "record_subtype", "source_payload",
})
CASH_FINANCIAL_FIELDS = frozenset({
    "amount", "currency", "occurred_at", "account_id", "account_name",
    "components", "cash_granularity",
})
CASH_SOURCE_IDENTITY_FIELDS = frozenset({
    "record_id", "source_type", "source_scope_key", "source_account_key", "record_origin", "manual_transfer_id",
})
COMMON_CASH_FIELDS = frozenset({"category_id", "note", "counterparty", "counterparty_account", "counterparty_account_attrs"})
_TEXT_FIELDS = frozenset({"counterparty", "counterparty_account", "note"})


class CashRecordPolicyError(ValueError):
    """只携带稳定原因及字段名，避免异常正文泄露来源值。"""

    def __init__(self, code: str, fields: tuple[str, ...]):
        self.code = code
        self.fields = fields
        super().__init__(code)


@dataclass(frozen=True)
class CashRecordUpdate:
    values: dict[str, Any]
    type_changed: bool
    protected_fields: tuple[str, ...] = ()


def _timestamp(value: Any) -> datetime:
    parsed = value if isinstance(value, datetime) else datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError("timezone required")
    return parsed.astimezone(timezone.utc)


def _component_values(value: Any) -> list[dict]:
    if not isinstance(value, list):
        raise ValueError("components must be an array")
    result = []
    for component in value:
        if not isinstance(component, Mapping):
            raise ValueError("component must be an object")
        item = deepcopy(dict(component))
        if any(not isinstance(key, str) for key in item):
            raise ValueError("component field names must be strings")
        for field in ("component_id", "account_id"):
            if item.get(field) is not None:
                item[field] = _reference(item[field])
        if item.get("amount") is not None:
            if isinstance(item["amount"], (float, bool)):
                raise ValueError("decimal string required")
            item["amount"] = exact_decimal(item["amount"])
        if "ordinal" in item and (type(item["ordinal"]) is not int or not 0 <= item["ordinal"] < 2 ** 31):
            raise ValueError("ordinal must fit a nonnegative 32-bit integer")
        if item.get("currency") is not None:
            item["currency"] = _currency(item["currency"])
        result.append(item)
    return result


def _reference(value: Any) -> str:
    if type(value) not in (str, int) or not str(value) or isinstance(value, int) and value <= 0:
        raise ValueError("reference required")
    return str(value)


def _currency(value: Any) -> str:
    if not isinstance(value, str) or len(value) != 3 or not value.isascii() or not value.isalpha() or value != value.upper():
        raise ValueError("currency code required")
    return value


def _json_value(value: Any, depth: int = 0) -> None:
    if depth > 64:
        raise ValueError("source payload too deep")
    if value is None or type(value) in (str, bool, int):
        return
    if type(value) is float and math.isfinite(value):
        return
    if isinstance(value, dict):
        if any(not isinstance(key, str) for key in value):
            raise ValueError("JSON object keys must be strings")
        for item in value.values():
            _json_value(item, depth + 1)
        return
    if isinstance(value, list):
        for item in value:
            _json_value(item, depth + 1)
        return
    raise ValueError("invalid JSON value")


def _normalize(field: str, value: Any) -> Any:
    if field in _TEXT_FIELDS:
        if not isinstance(value, str):
            raise ValueError("text required")
        return value
    if field == "amount":
        if isinstance(value, (float, bool)):
            raise ValueError("decimal string required")
        return None if value is None else exact_decimal(value)
    if field == "occurred_at":
        return None if value is None else _timestamp(value)
    if field == "account_id":
        return None if value is None else _reference(value)
    if field == "cash_granularity":
        if not isinstance(value, str) or value not in {"atomic", "aggregate"}:
            raise ValueError("invalid cash granularity")
        return value
    if field == "currency":
        return None if value is None else _currency(value)
    if field == "components":
        return _component_values(value)
    if field == "counterparty_account_attrs":
        if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
            raise ValueError("attributes must be a string array")
        # 这里只校验协议结构，不解释账号或属性之间的语义。
        return list(value)
    if field == "source_payload":
        if value is not None and not isinstance(value, dict):
            raise ValueError("source payload must be an object")
        _json_value(value)
        return deepcopy(value)
    if field in {"record_type", "record_subtype", "category_id"}:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("string required")
        return value
    return deepcopy(value)


def prepare_cash_record_update(
    current: Mapping[str, Any],
    patch: Mapping[str, Any],
    *,
    protected_fields: set[str] | frozenset[str] = frozenset(),
) -> CashRecordUpdate:
    """生成经过统一约束的补丁；保护现值是调用方的明确合并决定。

    固定字段允许重传等价值，但不会放入待写补丁。先校验整条请求，
    再决定保护值，防止只应用名称而漏掉同一来源的金额冲突。
    """
    if not isinstance(patch, Mapping):
        raise CashRecordPolicyError("invalid_field", ())
    if any(not isinstance(field, str) for field in patch):
        raise CashRecordPolicyError("invalid_field", ())
    state = current.get("state", "posted")
    if state not in {"draft", "posted"}:
        raise CashRecordPolicyError("invalid_state", ("state",))
    recognized = MUTABLE_CASH_FIELDS | CASH_FINANCIAL_FIELDS | CASH_SOURCE_IDENTITY_FIELDS
    unknown = tuple(sorted(set(patch) - recognized))
    if unknown:
        raise CashRecordPolicyError("field_not_editable", unknown)
    normalized = {}
    for field, value in patch.items():
        try:
            normalized[field] = _normalize(field, value)
        except (ValueError, TypeError, ArithmeticError):
            raise CashRecordPolicyError("invalid_field", (field,)) from None
    fixed = CASH_SOURCE_IDENTITY_FIELDS
    if state == "posted" and current.get("record_origin", "legacy") not in {"manual", "manual_transfer"}:
        fixed |= CASH_FINANCIAL_FIELDS | {"record_type", "record_subtype"}
    conflicts = []
    for field in sorted(fixed & normalized.keys()):
        try:
            unchanged = _normalize(field, current.get(field)) == normalized[field]
        except (ValueError, TypeError, ArithmeticError):
            unchanged = False
        if not unchanged:
            conflicts.append(field)
    if conflicts:
        raise CashRecordPolicyError("fixed_field_conflict", tuple(conflicts))
    protected = tuple(sorted(set(normalized) & protected_fields & MUTABLE_CASH_FIELDS))
    effective = {
        field: value for field, value in normalized.items()
        if field not in fixed and field not in protected_fields
    }
    record_type = effective.get("record_type", current.get("record_type"))
    subtype = effective.get("record_subtype", current.get("record_subtype"))
    if state == "posted" or record_type is not None and subtype is not None:
        try:
            validate_cash_record_subtype(record_type, subtype)
        except ValueError:
            raise CashRecordPolicyError("invalid_record_type", ("record_type", "record_subtype")) from None
    elif record_type is not None or subtype is not None:
        # 草稿可以缺一项，但已给出的枚举不能是未知值。
        from ft.domain.record_type import VALID_CASH_RECORD_TYPES, VALID_CASH_RECORD_SUBTYPES
        if record_type is not None and record_type not in VALID_CASH_RECORD_TYPES:
            raise CashRecordPolicyError("invalid_record_type", ("record_type",))
        if subtype is not None and subtype not in VALID_CASH_RECORD_SUBTYPES:
            raise CashRecordPolicyError("invalid_record_type", ("record_subtype",))
    changed = {}
    for field, value in effective.items():
        try:
            unchanged = _normalize(field, current.get(field)) == value
        except (ValueError, TypeError, ArithmeticError):
            unchanged = False
        if not unchanged:
            changed[field] = value
    return CashRecordUpdate(
        values=changed,
        type_changed=any(field in changed for field in ("record_type", "record_subtype")),
        protected_fields=protected,
    )


def source_note(source_payload: Mapping[str, Any] | None) -> str:
    """直接读取来源的注释／描述／备注，不拼接、截断或生成替代文案。"""
    for field in (
        "备注", "備註", "注释", "描述", "商品说明", "商品", "交易描述", "附言", "交易地点/附言", "摘要",
        "remark", "remarks", "comment", "comments", "description", "note",
    ):
        value = (source_payload or {}).get(field)
        if isinstance(value, str) and value.strip():
            return value
    return ""


def validate_cash_record_for_posting(record: Mapping[str, Any]) -> None:
    """校验转正式所需的父记录和分摊；不改变草稿状态或查询账户。"""
    required = {"amount", "currency", "occurred_at", "record_type", "record_subtype"}
    if record.get("cash_granularity", "atomic") == "atomic":
        required.add("account_id")
    missing = tuple(sorted(field for field in required if record.get(field) in (None, "")))
    if missing:
        raise CashRecordPolicyError("incomplete_record", missing)
    if record.get("cash_granularity") == "aggregate" and record.get("account_id") is not None:
        raise CashRecordPolicyError("component_account_mismatch", ("account_id",))
    normalized = {}
    for field in required | {"components", "cash_granularity"}:
        try:
            normalized[field] = _normalize(field, record.get(field, "atomic") if field == "cash_granularity" else record.get(field))
        except (ValueError, TypeError, ArithmeticError):
            raise CashRecordPolicyError("invalid_field", (field,)) from None
    try:
        validate_cash_record_subtype(normalized["record_type"], normalized["record_subtype"])
    except ValueError:
        raise CashRecordPolicyError("invalid_record_type", ("record_type", "record_subtype")) from None
    components = normalized["components"]
    if not components or any(any(item.get(field) is None for field in ("account_id", "amount", "currency")) for item in components):
        raise CashRecordPolicyError("incomplete_record", ("components",))
    if any(item.get("ordinal") is None for item in components):
        raise CashRecordPolicyError("incomplete_record", ("components",))
    if len({item["ordinal"] for item in components}) != len(components):
        raise CashRecordPolicyError("duplicate_component_ordinal", ("components",))
    if any(item["currency"] != normalized["currency"] for item in components):
        raise CashRecordPolicyError("component_currency_mismatch", ("components",))
    amount = normalized["amount"]
    if any((amount < 0 and item["amount"] > 0) or (amount > 0 and item["amount"] < 0)
        or (amount == 0 and item["amount"] != 0) for item in components):
        raise CashRecordPolicyError("component_direction_mismatch", ("components",))
    if normalized["cash_granularity"] == "atomic":
        if len(components) != 1 or components[0]["account_id"] != normalized["account_id"]:
            raise CashRecordPolicyError("component_account_mismatch", ("components",))
    with localcontext() as arithmetic:
        arithmetic.prec = 80
        total = sum((item["amount"] for item in components), exact_decimal("0"))
    if total != normalized["amount"]:
        raise CashRecordPolicyError("component_total_mismatch", ("components",))

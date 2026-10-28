"""Stable JSON serialization for deterministic projection fingerprints."""
from __future__ import annotations

from dataclasses import fields, is_dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from enum import Enum
import hashlib
import json
from typing import Any


class CanonicalSerializationError(ValueError):
    """A value cannot be represented by the canonical JSON format."""


def _canonical_decimal(value: Decimal) -> str:
    try:
        decimal = value if type(value) is Decimal else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise CanonicalSerializationError("canonical.invalid_decimal") from exc
    if not decimal.is_finite():
        raise CanonicalSerializationError("canonical.invalid_decimal")
    parts = decimal.as_tuple()
    digits = parts.digits
    exponent = parts.exponent
    scale = max(0, -exponent)
    integer_digits = max(0, len(digits) + exponent)
    if scale > 18 or integer_digits > 20 or integer_digits + scale > 38:
        raise CanonicalSerializationError("canonical.invalid_decimal")
    return format(decimal, "f")


def _canonical_value(value: Any) -> Any:
    if value is None or type(value) in (str, int, bool):
        return value
    if isinstance(value, Decimal):
        return _canonical_decimal(value)
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise CanonicalSerializationError("canonical.invalid_timestamp")
        return value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.value
    if is_dataclass(value):
        return {item.name: _canonical_value(getattr(value, item.name)) for item in fields(value)}
    if isinstance(value, dict):
        return {str(key): _canonical_value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_canonical_value(item) for item in value]
    if isinstance(value, float):
        raise CanonicalSerializationError("canonical.invalid_value")
    return value


def canonical_bytes(value: Any) -> bytes:
    """Encode JSON-compatible values to stable, UTF-8 canonical bytes."""
    return json.dumps(
        _canonical_value(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()

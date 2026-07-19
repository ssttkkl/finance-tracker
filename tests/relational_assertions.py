"""Backend-neutral helpers for relational contract tests."""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from decimal import Decimal


_PHYSICAL_KEYS = frozenset({"id", "record_id", "created_at", "updated_at"})


def normalize(value):
    """Remove generated storage details while retaining business/audit values."""
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, Mapping):
        return {
            key: normalize(item)
            for key, item in value.items()
            if key not in _PHYSICAL_KEYS
        }
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [normalize(item) for item in value]
    return value


def assert_equivalent(left, right) -> None:
    assert normalize(left) == normalize(right)

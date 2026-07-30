"""收支投影双后端响应比较。"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
from decimal import Decimal


def normalize_projection_response(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, Mapping):
        return {key: normalize_projection_response(item) for key, item in value.items() if key not in {"id", "created_at", "updated_at"}}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [normalize_projection_response(item) for item in value]
    return value


def assert_projection_responses_equal(sqlite_response, postgres_response) -> None:
    assert normalize_projection_response(sqlite_response) == normalize_projection_response(postgres_response)

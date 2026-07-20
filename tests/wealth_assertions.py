"""Stable wealth-test helpers that preserve deterministic business identities."""
from __future__ import annotations

import hashlib
import json
import random
from collections.abc import Mapping
from decimal import Decimal


WEALTH_FIXED_SEED = 20260719


def canonical_bytes(value: object) -> bytes:
    """Serialize values using the wealth canonical JSON rules for assertions."""
    def default(item: object) -> str:
        if isinstance(item, Decimal):
            return format(item, "f")
        raise TypeError(f"unsupported canonical value: {type(item).__name__}")

    return json.dumps(
        value, default=default, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


def canonical_digest(value: object) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def fixed_random() -> random.Random:
    return random.Random(WEALTH_FIXED_SEED)


def assert_canonical_equal(left: Mapping[str, object], right: Mapping[str, object]) -> None:
    """Compare complete canonical DTOs; never strip business identity fields."""
    assert canonical_bytes(left) == canonical_bytes(right)

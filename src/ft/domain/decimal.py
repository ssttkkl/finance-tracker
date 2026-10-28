"""Exact decimal validation shared by domain and persistence boundaries."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation


def exact_decimal(value, name: str = "value") -> Decimal:
    try:
        decimal = value if type(value) is Decimal else Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be decimal-compatible") from exc
    if not decimal.is_finite():
        raise ValueError(f"{name} must be finite")
    parts = decimal.as_tuple()
    digits = parts.digits
    exponent = parts.exponent
    length = len(digits)
    while exponent < 0 and length and digits[length - 1] == 0:
        length -= 1
        exponent += 1
    scale = max(0, -exponent)
    integer_digits = 0 if not decimal else max(0, length + exponent)
    if scale > 18:
        raise ValueError(
            f"{name} exceeds NUMERIC(38,18): must have at most 18 decimal places"
        )
    if integer_digits > 20 or integer_digits + scale > 38:
        raise ValueError(f"{name} exceeds NUMERIC(38,18)")
    return decimal

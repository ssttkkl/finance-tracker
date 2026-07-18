"""Exact decimal validation shared by domain and persistence boundaries."""
from __future__ import annotations

from decimal import Decimal, InvalidOperation


def exact_decimal(value, name: str = "value") -> Decimal:
    try:
        decimal = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise ValueError(f"{name} must be decimal-compatible") from exc
    if not decimal.is_finite():
        raise ValueError(f"{name} must be finite")
    digits = list(decimal.as_tuple().digits)
    exponent = decimal.as_tuple().exponent
    while exponent < 0 and digits and digits[-1] == 0:
        digits.pop()
        exponent += 1
    scale = max(0, -exponent)
    integer_digits = 0 if not decimal else max(0, len(digits) + exponent)
    if scale > 18:
        raise ValueError(
            f"{name} exceeds NUMERIC(38,18): must have at most 18 decimal places"
        )
    if integer_digits > 20 or integer_digits + scale > 38:
        raise ValueError(f"{name} exceeds NUMERIC(38,18)")
    return decimal

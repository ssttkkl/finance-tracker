"""Transport-neutral values and canonical serialization for wealth attribution."""
from __future__ import annotations

from dataclasses import dataclass, fields, is_dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from enum import Enum, IntEnum
from functools import total_ordering
import hashlib
import json
import re
from typing import Any, Literal


CALCULATION_VERSION = "wealth-attribution-v0.1"
BASE_CURRENCY = "CNY"
TIMEZONE = "Asia/Shanghai"
_MONTH = re.compile(r"^\d{4}-(0[1-9]|1[0-2])$")


class WealthError(ValueError):
    """A safe, stable wealth application error."""

    def __init__(self, code: str, **details: Any) -> None:
        self.code = code
        self.details = details
        super().__init__(code)


@total_ordering
class WealthStatus(str, Enum):
    """Canonical public status text with the mandated severity ordering."""

    COMPLETE = "complete"
    STALE = "stale"
    PARTIAL = "partial"
    UNSUPPORTED = "unsupported"

    @property
    def severity(self) -> int:
        return ("complete", "stale", "partial", "unsupported").index(self.value)

    def __lt__(self, other: object) -> bool:
        if not isinstance(other, WealthStatus):
            return NotImplemented
        return self.severity < other.severity


class CoverageDisposition(str, Enum):
    SUPPORTED = "supported"
    MISSING = "missing"
    UNSUPPORTED = "unsupported"
    UNVALUED = "unvalued"
    NOT_APPLICABLE = "not_applicable"


class ComponentKind(str, Enum):
    EXTERNAL_CASHFLOW = "external_cashflow"
    INVESTMENT_RETURN = "investment_return"
    FX_IMPACT = "fx_impact"
    LIABILITY_REVALUATION = "liability_revaluation"
    EXPLAINED_OTHER_ADJUSTMENT = "explained_other_adjustment"
    UNEXPLAINED_ADJUSTMENT = "unexplained_adjustment"


@dataclass(frozen=True)
class WealthChangeQuery:
    month: str

    def __post_init__(self) -> None:
        if not _MONTH.fullmatch(self.month):
            raise WealthError("wealth.invalid_month")


@dataclass(frozen=True)
class WealthSeriesQuery:
    date_from: date
    date_to: date
    granularity: Literal["day", "week", "month"]


@dataclass(frozen=True)
class ImmutableEvidenceRef:
    component_id: str
    result_revision: str
    evidence_manifest_id: str


@dataclass(frozen=True)
class AttributionComponent:
    component_key: str
    component_id: str
    result_revision: str
    kind: ComponentKind
    status: WealthStatus
    amount: Decimal | None
    evidence_ref: ImmutableEvidenceRef


def decimal_value(value: object, *, field: str = "value") -> Decimal:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError) as exc:
        raise WealthError("wealth.invalid_decimal", field=field) from exc
    if not result.is_finite():
        raise WealthError("wealth.invalid_decimal", field=field)
    digits = result.as_tuple().digits
    exponent = result.as_tuple().exponent
    scale = max(0, -exponent)
    integer_digits = max(0, len(digits) + exponent)
    if scale > 18 or integer_digits > 20 or integer_digits + scale > 38:
        raise WealthError("wealth.invalid_decimal", field=field)
    return result


def decimal_text(value: Decimal) -> str:
    """Emit a non-exponent exact decimal string without changing its value."""
    return format(decimal_value(value), "f")


def _canonical_value(value: Any) -> Any:
    if isinstance(value, Decimal):
        return decimal_text(value)
    if isinstance(value, datetime):
        if value.tzinfo is None:
            raise WealthError("wealth.invalid_timestamp")
        return value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Enum):
        return value.value
    if is_dataclass(value):
        # ``asdict`` recursively deep-copies the complete source manifest before
        # canonicalization.  Iterating declared fields preserves identical bytes
        # while allowing large immutable manifests to be streamed through the
        # normal canonical rules without a second object graph.
        return {item.name: _canonical_value(getattr(value, item.name)) for item in fields(value)}
    if isinstance(value, dict):
        return {str(key): _canonical_value(item) for key, item in value.items()}
    if isinstance(value, (tuple, list)):
        return [_canonical_value(item) for item in value]
    if isinstance(value, float):
        raise WealthError("wealth.invalid_canonical_value")
    return value


def canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        _canonical_value(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"),
    ).encode("utf-8")


def canonical_digest(value: Any) -> str:
    return hashlib.sha256(canonical_bytes(value)).hexdigest()

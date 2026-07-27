"""Real-time asset valuation domain types and pure helpers."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from enum import Enum

from ft.domain.decimal import exact_decimal
from ft.schema import CRYPTO_IDS


class ValuationError(ValueError):
    """Stable application/domain error for valuation inputs."""

    def __init__(self, code: str, **details) -> None:
        self.code = code
        self.details = details
        super().__init__(code)


class AssetKind(str, Enum):
    SECURITY = "security"
    CRYPTO = "crypto"
    PREDICTION_MARKET = "prediction_market"
    CASH = "cash"


class QuoteStatus(str, Enum):
    COMPLETE = "complete"
    STALE = "stale"
    PARTIAL = "partial"
    UNSUPPORTED = "unsupported"


class FxStatus(str, Enum):
    COMPLETE = "complete"
    PARTIAL = "partial"
    NOT_APPLICABLE = "not_applicable"


@dataclass(frozen=True)
class AssetRef:
    identity: str
    kind: AssetKind
    quantity: Decimal | None = None


@dataclass(frozen=True)
class QuoteResult:
    identity: str
    kind: AssetKind
    status: QuoteStatus
    unit_price: Decimal | None = None
    quote_currency: str | None = None
    observed_at: datetime | None = None
    market_value: Decimal | None = None
    quantity: Decimal | None = None
    reason: str = "ok"
    provider: str | None = None


@dataclass(frozen=True)
class QuoteBatchResult:
    results: tuple[QuoteResult, ...]

    @property
    def complete_count(self) -> int:
        return sum(1 for item in self.results if item.status is QuoteStatus.COMPLETE)

    @property
    def failed_count(self) -> int:
        return sum(
            1
            for item in self.results
            if item.status in {QuoteStatus.PARTIAL, QuoteStatus.UNSUPPORTED}
        )


@dataclass(frozen=True)
class ProviderTick:
    price: Decimal
    quote_currency: str
    observed_at: datetime
    provider: str


def parse_asset_kind(value: str | AssetKind) -> AssetKind:
    if isinstance(value, AssetKind):
        return value
    try:
        return AssetKind(str(value).strip().lower())
    except ValueError as exc:
        raise ValuationError("valuation.invalid_kind", kind=value) from exc


def validate_identity(identity: str) -> str:
    text = str(identity or "").strip()
    if not text:
        raise ValuationError("valuation.invalid_identity")
    return text


def validate_quantity(quantity) -> Decimal | None:
    if quantity is None:
        return None
    try:
        value = exact_decimal(quantity, "quantity")
    except ValueError as exc:
        raise ValuationError("valuation.invalid_quantity") from exc
    if value < 0:
        raise ValuationError("valuation.invalid_quantity")
    return value


_KNOWN_DISPLAY = frozenset({
    "USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD",
})


def validate_display_currency(currency: str | None) -> str | None:
    if currency is None:
        return None
    text = str(currency).strip().upper()
    if text == "RMB":
        text = "CNY"
    if text not in _KNOWN_DISPLAY:
        raise ValuationError("valuation.invalid_display_currency", currency=currency)
    return text


def make_asset_ref(identity: str, kind: str | AssetKind, quantity=None) -> AssetRef:
    return AssetRef(
        identity=validate_identity(identity),
        kind=parse_asset_kind(kind),
        quantity=validate_quantity(quantity),
    )


def compute_market_value(unit_price: Decimal, quantity: Decimal) -> Decimal:
    return exact_decimal(unit_price, "unit_price") * exact_decimal(quantity, "quantity")


def is_finite_decimal(value) -> bool:
    try:
        decimal = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return False
    return decimal.is_finite()


def quote_freshness(
    observed_at: datetime,
    *,
    now: datetime | None = None,
    kind: AssetKind,
) -> QuoteStatus:
    """Map quote age to complete/stale/partial (cash callers should skip)."""
    if kind is AssetKind.CASH:
        return QuoteStatus.COMPLETE
    if observed_at.tzinfo is None:
        raise ValuationError("valuation.invalid_observed_at")
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None:
        current = current.replace(tzinfo=timezone.utc)
    age = current - observed_at.astimezone(current.tzinfo)
    if age < timedelta(0):
        age = timedelta(0)
    if kind is AssetKind.CRYPTO:
        freshness, maximum = timedelta(hours=24), timedelta(days=7)
    else:
        freshness, maximum = timedelta(days=5), timedelta(days=30)
    if age > maximum:
        return QuoteStatus.PARTIAL
    if age > freshness:
        return QuoteStatus.STALE
    return QuoteStatus.COMPLETE


_FIAT_CASH = frozenset({
    "USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD",
})


def infer_asset_kind(
    identity: str,
    *,
    cash_tickers: set[str] | frozenset[str] = frozenset(),
    configured_currencies: set[str] | frozenset[str] = frozenset(),
) -> AssetKind | None:
    """Return kind for portfolio path; None means skip market valuation (legacy configured non-cash)."""
    raw = str(identity or "").strip()
    if not raw:
        raise ValuationError("valuation.invalid_identity")
    upper = raw.upper()
    lower = raw.lower()
    cash_upper = {item.upper() for item in cash_tickers}
    configured_upper = {item.upper() for item in configured_currencies}
    if upper in cash_upper or upper in _FIAT_CASH:
        return AssetKind.CASH
    if upper in configured_upper:
        return None
    if lower.startswith("pm:"):
        return AssetKind.PREDICTION_MARKET
    if lower in CRYPTO_IDS:
        return AssetKind.CRYPTO
    return AssetKind.SECURITY


def identity_kind_mismatch(identity: str, kind: AssetKind) -> bool:
    lower = identity.strip().lower()
    if kind is AssetKind.CASH:
        return lower.startswith("pm:") or ("." in lower and lower.split(".")[-1] in {"us", "hk", "sh", "sz"})
    if kind is AssetKind.PREDICTION_MARKET:
        return not lower.startswith("pm:")
    if kind is AssetKind.CRYPTO:
        return lower.startswith("pm:") or "." in lower
    if kind is AssetKind.SECURITY:
        return lower.startswith("pm:") or lower in CRYPTO_IDS
    return False


def ledger_security_to_yfinance(identity: str) -> str | None:
    """Map ledger equity ticker to yfinance symbol; None if unsupported."""
    token = str(identity or "").strip()
    if not token:
        return None
    lower = token.lower()
    if lower.startswith("pm:") or lower in CRYPTO_IDS:
        return None
    if "." in lower:
        head, _, tail = lower.partition(".")
        if tail == "us":
            return head.upper() if head else None
        if tail == "hk":
            digits = "".join(ch for ch in head if ch.isdigit())
            if not digits:
                return f"{head.upper()}.HK" if head else None
            # yfinance HK: strip leading zeros to ~4 digits (0700.HK)
            normalized = str(int(digits))
            if len(normalized) < 4:
                normalized = normalized.zfill(4)
            return f"{normalized}.HK"
        if tail == "sh":
            return f"{head.upper()}.SS"
        if tail == "sz":
            return f"{head.upper()}.SZ"
        if tail in {"ss", "sz", "hk"}:
            return f"{head.upper()}.{tail.upper()}"
    # bare code: treat as US
    if lower.isalpha() or (lower.isalnum() and not lower.isdigit()):
        return token.upper()
    return None


def parse_prediction_market_identity(identity: str) -> tuple[str, str] | None:
    parts = identity.strip().lower().split(":")
    if len(parts) >= 3 and parts[0] == "pm" and parts[-1] in {"yes", "no"}:
        slug = ":".join(parts[1:-1])
        if slug:
            return slug, parts[-1]
    return None

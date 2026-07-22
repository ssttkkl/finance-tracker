"""Historical mid FX rates for credit-repayment matching (evidence only).

Not a ledger authority: rates never rewrite formal fact amounts.
"""
from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
import json
import os
from typing import Callable
from urllib.parse import quote
import urllib.request

from zoneinfo import ZoneInfo

_SHANGHAI = ZoneInfo("Asia/Shanghai")
_DEFAULT_TIMEOUT = 8

# (day_iso, base, quote) -> Decimal quote-per-base, or None if miss
_RATE_CACHE: dict[tuple[str, str, str], Decimal | None] = {}


def _decimal(value) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() and result > 0 else None


def business_day_shanghai(occurred_at) -> str:
    """Return Asia/Shanghai calendar day YYYY-MM-DD for an occurred_at value."""
    if isinstance(occurred_at, datetime):
        dt = occurred_at
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=_SHANGHAI)
        return dt.astimezone(_SHANGHAI).date().isoformat()
    text = str(occurred_at or "").strip()
    if not text:
        return date.today().isoformat()
    # Prefer leading date portion
    if len(text) >= 10 and text[4] == "-" and text[7] == "-":
        return text[:10]
    try:
        dt = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=_SHANGHAI)
        return dt.astimezone(_SHANGHAI).date().isoformat()
    except ValueError:
        return text[:10] if len(text) >= 10 else date.today().isoformat()


def _json_get(url: str):
    headers = {"User-Agent": "ft-fx/1.0", "Accept": "application/json"}
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy, "https": proxy})
    ) if proxy else urllib.request.build_opener()
    with opener.open(urllib.request.Request(url, headers=headers), timeout=_DEFAULT_TIMEOUT) as response:
        return json.load(response)


def _fetch_frankfurter(day: str, base: str, quote: str) -> Decimal | None:
    """Frankfurter (ECB) historical: quote units per 1 base unit."""
    base_u, quote_u = base.upper(), quote.upper()
    if base_u == quote_u:
        return Decimal("1")

    def _try(day_iso: str):
        url = f"https://api.frankfurter.dev/v1/{day_iso}?base={base_u}&symbols={quote_u}"
        try:
            return _json_get(url)
        except Exception:
            return None

    payload = _try(day)
    if payload is None:
        try:
            d = date.fromisoformat(day)
        except ValueError:
            return None
        for back in range(1, 6):
            payload = _try((d - timedelta(days=back)).isoformat())
            if payload is not None:
                break
    if payload is None:
        return None
    rates = payload.get("rates") if isinstance(payload, dict) else None
    if not isinstance(rates, dict):
        return None
    return _decimal(rates.get(quote_u))


def get_mid_rate(
    day: str,
    base: str,
    quote: str,
    *,
    fetcher: Callable[[str, str, str], Decimal | None] | None = None,
) -> Decimal | None:
    """Return market mid: how many *quote* units for 1 *base* unit on *day*.

    Cached per process. ``fetcher`` overrides network for tests.
    """
    base_u, quote_u = str(base or "").upper(), str(quote or "").upper()
    day_s = str(day or "")[:10]
    if not day_s or len(base_u) != 3 or len(quote_u) != 3:
        return None
    if base_u == quote_u:
        return Decimal("1")
    key = (day_s, base_u, quote_u)
    if fetcher is None and key in _RATE_CACHE:
        return _RATE_CACHE[key]
    fn = fetcher or _fetch_frankfurter
    rate = fn(day_s, base_u, quote_u)
    if fetcher is None:
        _RATE_CACHE[key] = rate
    return rate


def clear_rate_cache() -> None:
    _RATE_CACHE.clear()


def rate_error(
    cash_abs: Decimal,
    loan_abs: Decimal,
    cash_currency: str,
    loan_currency: str,
    market_quote_per_cash: Decimal | None,
) -> Decimal | None:
    """Relative error of implied FX vs market.

    Market is *loan_currency* units per 1 *cash_currency* (quote per base=cash).
    Implied = loan_abs / cash_abs (same units).
    """
    if market_quote_per_cash is None or cash_abs <= 0 or loan_abs <= 0:
        return None
    if str(cash_currency).upper() == str(loan_currency).upper():
        return None
    implied = loan_abs / cash_abs
    if market_quote_per_cash <= 0:
        return None
    return abs(implied / market_quote_per_cash - Decimal("1"))

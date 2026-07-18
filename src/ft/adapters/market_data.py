"""Read-only market-price adapter used by PostgreSQL-backed portfolio queries."""
from __future__ import annotations

from collections import defaultdict
from decimal import Decimal, InvalidOperation
import json
import os
from urllib.parse import quote
import urllib.request

from ft.schema import CRYPTO_IDS


def _decimal(value):
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def _normalize_ticker(ticker: str) -> str:
    value = ticker.strip()
    if value.lower().startswith("pm:"):
        return value.lower()
    value = value.upper()
    if value.endswith(".HK"):
        code = value[:-3]
        return f"{int(code):04d}.HK" if code.isdigit() and len(code) <= 5 else value
    return value[:-3] if value.endswith(".US") else value


def _json_get(url: str):
    headers = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy, "https": proxy})
    ) if proxy else urllib.request.build_opener()
    with opener.open(urllib.request.Request(url, headers=headers), timeout=15) as response:
        return json.load(response)


def _fetch_crypto(tickers: list[str]) -> dict[str, Decimal]:
    id_to_ticker = {
        CRYPTO_IDS[ticker.strip().lower()]: ticker
        for ticker in tickers if ticker.strip().lower() in CRYPTO_IDS
    }
    if not id_to_ticker:
        return {}
    url = (
        "https://api.coingecko.com/api/v3/simple/price?ids="
        f"{quote(','.join(sorted(id_to_ticker)))}&vs_currencies=usd"
    )
    try:
        payload = _json_get(url)
    except Exception:
        return {}
    prices = {}
    for identifier, ticker in id_to_ticker.items():
        value = _decimal((payload.get(identifier) or {}).get("usd"))
        if value is not None:
            prices[ticker] = value
    return prices


def _fetch_polymarket(tickers: list[str]) -> dict[str, Decimal]:
    grouped = defaultdict(list)
    for ticker in tickers:
        parts = ticker.lower().split(":")
        if len(parts) >= 3 and parts[0] == "pm" and parts[-1] in {"yes", "no"}:
            grouped[":".join(parts[1:-1])].append((ticker, parts[-1]))
    prices = {}
    for slug, items in grouped.items():
        try:
            payload = _json_get(
                f"https://gamma-api.polymarket.com/markets?slug={quote(slug)}"
            )
        except Exception:
            continue
        if isinstance(payload, list):
            markets = payload
        elif isinstance(payload, dict):
            markets = payload.get("data") or payload.get("markets") or [payload]
        else:
            markets = []
        market = next((item for item in markets if item.get("slug") == slug), None)
        if market is None:
            try:
                search = _json_get(
                    "https://gamma-api.polymarket.com/public-search?q="
                    f"{quote(slug.replace('-', ' '))}"
                )
            except Exception:
                search = None
            events = search.get("events") or search.get("data") or [] if isinstance(search, dict) else search or []
            for event in events if isinstance(events, list) else []:
                for candidate in event.get("markets", []) if isinstance(event, dict) else []:
                    if isinstance(candidate, dict) and candidate.get("slug") == slug:
                        market = candidate
                        break
                if market is not None:
                    break
        if not market:
            continue
        outcomes = market.get("outcomes", [])
        values = market.get("outcomePrices", [])
        if isinstance(outcomes, str):
            outcomes = json.loads(outcomes)
        if isinstance(values, str):
            values = json.loads(values)
        normalized = [str(item).lower() for item in outcomes]
        for ticker, side in items:
            index = normalized.index(side) if side in normalized else (0 if side == "yes" else 1)
            if index < len(values):
                value = _decimal(values[index])
                if value is not None:
                    prices[ticker] = value
    return prices


def _extract_close(data, normalized: str):
    if data is None or getattr(data, "empty", False):
        return None
    try:
        close = data["Close"]
        series = close if not hasattr(close, "columns") else (
            close[normalized] if normalized in close.columns else close.iloc[:, 0]
        )
        return _decimal(series.iloc[-1])
    except Exception:
        return None


def fetch_prices(tickers: list[str]) -> dict[str, Decimal]:
    if not tickers:
        return {}
    normalized_to_original = {_normalize_ticker(item): item for item in tickers}
    crypto = [original for normalized, original in normalized_to_original.items()
              if normalized.lower() in CRYPTO_IDS]
    polymarket = [normalized for normalized in normalized_to_original if normalized.startswith("pm:")]
    regular = [normalized for normalized in normalized_to_original
               if not normalized.startswith("pm:") and normalized.lower() not in CRYPTO_IDS]
    prices = _fetch_crypto(crypto)
    for normalized, value in _fetch_polymarket(polymarket).items():
        prices[normalized_to_original[normalized]] = value
    if not regular:
        return prices
    try:
        import yfinance as yf  # type: ignore[import-untyped]
    except ImportError:
        return prices
    for normalized in regular:
        try:
            value = _extract_close(
                yf.download(normalized, period="1d", progress=False, auto_adjust=False),
                normalized,
            )
        except Exception:
            value = None
        if value is not None:
            prices[normalized_to_original[normalized]] = value
    return prices


class MarketDataProvider:
    def get_prices(self, tickers, *, quote_currency):
        return fetch_prices(list(tickers))

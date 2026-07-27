"""Market quote adapters: symbol map + composite providers (injectable IO)."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import json
import os
from urllib.parse import quote
import urllib.request

from ft.application.valuation import UnsupportedQuote
from ft.domain.valuation import (
    AssetKind,
    ProviderTick,
    ledger_security_to_yfinance,
    parse_prediction_market_identity,
)
from ft.schema import CRYPTO_IDS


def _decimal(value) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _json_get(url: str, *, timeout: float = 15):
    headers = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    opener = urllib.request.build_opener(
        urllib.request.ProxyHandler({"http": proxy, "https": proxy})
    ) if proxy else urllib.request.build_opener()
    with opener.open(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
        return json.load(response)


def map_security_symbol(identity: str) -> str:
    symbol = ledger_security_to_yfinance(identity)
    if not symbol:
        raise UnsupportedQuote(identity)
    return symbol


def quote_currency_for_security_symbol(symbol: str) -> str:
    upper = str(symbol or "").upper()
    if upper.endswith(".HK"):
        return "HKD"
    if upper.endswith(".SS") or upper.endswith(".SZ"):
        return "CNY"
    return "USD"


class CashQuoteProvider:
    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is not AssetKind.CASH:
            raise UnsupportedQuote(identity)
        return ProviderTick(
            price=Decimal("1"),
            quote_currency=identity.strip().upper(),
            observed_at=_now(),
            provider="cash",
        )


class SecurityQuoteProvider:
    def __init__(self, download=None, *, clock=None):
        self._download = download
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = map_security_symbol(identity)
        if self._download is not None:
            price = self._download(symbol)
        else:
            price = self._yfinance_price(symbol)
        if price is None:
            return None
        return ProviderTick(
            price=price,
            quote_currency=quote_currency_for_security_symbol(symbol),
            observed_at=self._clock(),
            provider="yfinance",
        )

    @staticmethod
    def _yfinance_price(symbol: str) -> Decimal | None:
        try:
            import yfinance as yf  # type: ignore[import-untyped]
        except ImportError:
            return None
        try:
            data = yf.download(symbol, period="1d", progress=False, auto_adjust=False)
        except Exception:
            return None
        if data is None or getattr(data, "empty", False):
            return None
        try:
            close = data["Close"]
            series = close if not hasattr(close, "columns") else (
                close[symbol] if symbol in close.columns else close.iloc[:, 0]
            )
            return _decimal(series.iloc[-1])
        except Exception:
            return None


class CryptoQuoteProvider:
    def __init__(self, fetch_json=None, *, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is not AssetKind.CRYPTO:
            raise UnsupportedQuote(identity)
        key = identity.strip().lower()
        if key not in CRYPTO_IDS:
            raise UnsupportedQuote(identity)
        cg_id = CRYPTO_IDS[key]
        url = (
            "https://api.coingecko.com/api/v3/simple/price?ids="
            f"{quote(cg_id)}&vs_currencies=usd"
        )
        try:
            payload = self._fetch_json(url)
        except Exception:
            return None
        value = _decimal((payload.get(cg_id) or {}).get("usd"))
        if value is None:
            return None
        return ProviderTick(
            price=value,
            quote_currency="USD",
            observed_at=self._clock(),
            provider="coingecko",
        )


class PredictionMarketQuoteProvider:
    def __init__(self, fetch_json=None, *, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is not AssetKind.PREDICTION_MARKET:
            raise UnsupportedQuote(identity)
        parsed = parse_prediction_market_identity(identity)
        if not parsed:
            raise UnsupportedQuote(identity)
        slug, side = parsed
        market = self._load_market(slug)
        if not market:
            return None
        outcomes = market.get("outcomes", [])
        values = market.get("outcomePrices", [])
        if isinstance(outcomes, str):
            outcomes = json.loads(outcomes)
        if isinstance(values, str):
            values = json.loads(values)
        normalized = [str(item).lower() for item in outcomes]
        index = normalized.index(side) if side in normalized else (0 if side == "yes" else 1)
        if index >= len(values):
            return None
        value = _decimal(values[index])
        if value is None:
            return None
        return ProviderTick(
            price=value,
            quote_currency="USD",
            observed_at=self._clock(),
            provider="polymarket",
        )

    def _load_market(self, slug: str):
        try:
            payload = self._fetch_json(
                f"https://gamma-api.polymarket.com/markets?slug={quote(slug)}"
            )
        except Exception:
            payload = None
        market = None
        if isinstance(payload, list):
            markets = payload
        elif isinstance(payload, dict):
            markets = payload.get("data") or payload.get("markets") or [payload]
        else:
            markets = []
        market = next((item for item in markets if isinstance(item, dict) and item.get("slug") == slug), None)
        if market is not None:
            return market
        try:
            search = self._fetch_json(
                "https://gamma-api.polymarket.com/public-search?q="
                f"{quote(slug.replace('-', ' '))}"
            )
        except Exception:
            return None
        events = search.get("events") or search.get("data") or [] if isinstance(search, dict) else search or []
        for event in events if isinstance(events, list) else []:
            for candidate in event.get("markets", []) if isinstance(event, dict) else []:
                if isinstance(candidate, dict) and candidate.get("slug") == slug:
                    return candidate
        return None


class CompositeQuoteProvider:
    """Route by AssetKind; never let one item's exception escape uncaught at batch level
    when used via ValuationService (service catches). Still catch per call here for safety.
    """

    def __init__(
        self,
        *,
        security: SecurityQuoteProvider | None = None,
        crypto: CryptoQuoteProvider | None = None,
        prediction_market: PredictionMarketQuoteProvider | None = None,
        cash: CashQuoteProvider | None = None,
    ):
        self._security = security or SecurityQuoteProvider()
        self._crypto = crypto or CryptoQuoteProvider()
        self._pm = prediction_market or PredictionMarketQuoteProvider()
        self._cash = cash or CashQuoteProvider()

    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is AssetKind.CASH:
            return self._cash.raw_quote(identity, kind)
        if kind is AssetKind.SECURITY:
            return self._security.raw_quote(identity, kind)
        if kind is AssetKind.CRYPTO:
            return self._crypto.raw_quote(identity, kind)
        if kind is AssetKind.PREDICTION_MARKET:
            return self._pm.raw_quote(identity, kind)
        raise UnsupportedQuote(identity)


class MarketDataProvider:
    """Backward-compatible price map built on CompositeQuoteProvider + ValuationService."""

    def __init__(self, composite: CompositeQuoteProvider | None = None):
        self._composite = composite or CompositeQuoteProvider()

    def get_prices(self, tickers, *, quote_currency):
        # Legacy: best-effort map without status; prefer ValuationService in new code.
        from ft.application.valuation import ValuationService
        from ft.domain.valuation import AssetKind, AssetRef, QuoteStatus, infer_asset_kind

        service = ValuationService(self._composite)
        prices = {}
        for ticker in tickers:
            kind = infer_asset_kind(str(ticker), cash_tickers=set(), configured_currencies=set())
            if kind is None:
                kind = AssetKind.SECURITY
            result = service.quote(AssetRef(str(ticker), kind))
            if result.status in {QuoteStatus.COMPLETE, QuoteStatus.STALE} and result.unit_price is not None:
                prices[ticker] = result.unit_price
        return prices


# --- legacy helpers kept for any direct imports ---

def fetch_prices(tickers: list[str]) -> dict[str, Decimal]:
    return MarketDataProvider().get_prices(tickers, quote_currency="USD")

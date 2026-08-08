"""Market quote adapters: symbol map + composite providers (injectable IO)."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import json
import logging
import os
from queue import Empty, Queue
from threading import Thread
import time
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

_QUOTE_TIMEOUT_SECONDS = 4.0


def _decimal(value) -> Decimal | None:
    try:
        result = Decimal(str(value))
    except (InvalidOperation, TypeError, ValueError):
        return None
    return result if result.is_finite() else None


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _json_get(url: str, *, timeout: float = _QUOTE_TIMEOUT_SECONDS):
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
    def __init__(self, download=None, *, download_history=None, clock=None):
        self._download = download
        self._download_history = download_history
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

    def raw_quote_at(self, identity: str, kind: AssetKind, *, at: datetime) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = map_security_symbol(identity)
        if at.tzinfo is None:
            raise ValueError("historical quote boundary must be timezone-aware")
        if self._download_history is not None:
            price = self._download_history(symbol, at=at)
        else:
            price = self._yfinance_price_at(symbol, at)
        price = _decimal(price)
        if price is None:
            return None
        return ProviderTick(
            price=price,
            quote_currency=quote_currency_for_security_symbol(symbol),
            observed_at=at,
            provider="yfinance-history",
        )

    def raw_quote_many(self, refs, *, timeout: float | None = None):
        symbols = {}
        results = {}
        for ref in refs:
            if ref.kind is not AssetKind.SECURITY:
                results[ref.identity] = UnsupportedQuote(ref.identity)
                continue
            try:
                symbols[ref.identity] = map_security_symbol(ref.identity)
            except UnsupportedQuote as exc:
                results[ref.identity] = exc
        if not symbols:
            return results
        try:
            prices = self._download_many(list(dict.fromkeys(symbols.values())), timeout=timeout)
        except Exception:
            return {**results, **{identity: RuntimeError("provider_error") for identity in symbols}}
        for identity, symbol in symbols.items():
            price = prices.get(symbol)
            results[identity] = None if price is None else ProviderTick(
                price=price,
                quote_currency=quote_currency_for_security_symbol(symbol),
                observed_at=self._clock(),
                provider="yfinance",
            )
        return results

    def _download_many(self, symbols: list[str], *, timeout: float | None) -> dict[str, Decimal | None]:
        if self._download is not None:
            payload = self._download(symbols, timeout=timeout)
            if isinstance(payload, dict):
                return {symbol: _decimal(payload.get(symbol)) for symbol in symbols}
            if len(symbols) == 1:
                return {symbols[0]: _decimal(payload)}
            return {symbol: None for symbol in symbols}
        return self._yfinance_prices(symbols, timeout=timeout)

    @staticmethod
    def _yfinance_price(symbol: str) -> Decimal | None:
        try:
            import yfinance as yf  # type: ignore[import-untyped]
        except ImportError:
            return None
        logger = logging.getLogger("yfinance")
        previous_disabled = logger.disabled
        logger.disabled = True
        try:
            data = yf.download(
                symbol, period="1d", progress=False, auto_adjust=False,
                timeout=_QUOTE_TIMEOUT_SECONDS,
            )
        except Exception:
            return None
        finally:
            logger.disabled = previous_disabled
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

    @staticmethod
    def _yfinance_price_at(symbol: str, at: datetime) -> Decimal | None:
        try:
            import yfinance as yf  # type: ignore[import-untyped]
        except ImportError:
            return None
        logger = logging.getLogger("yfinance")
        previous_disabled = logger.disabled
        logger.disabled = True
        try:
            start = (at - timedelta(days=3)).isoformat()
            end = (at + timedelta(days=1)).isoformat()
            data = yf.download(
                symbol, start=start, end=end, interval="1h", progress=False,
                auto_adjust=False, timeout=_QUOTE_TIMEOUT_SECONDS,
            )
        except Exception:
            return None
        finally:
            logger.disabled = previous_disabled
        if data is None or getattr(data, "empty", False):
            return None
        try:
            close = data["Close"]
            series = close if not hasattr(close, "columns") else (
                close[symbol] if symbol in close.columns else close.iloc[:, 0]
            )
            index = getattr(series, "index", ())
            candidates = [
                (timestamp.to_pydatetime() if hasattr(timestamp, "to_pydatetime") else timestamp, value)
                for timestamp, value in zip(index, series, strict=True)
                if (timestamp.to_pydatetime() if hasattr(timestamp, "to_pydatetime") else timestamp) <= at
            ]
            return _decimal(candidates[-1][1]) if candidates else _decimal(series.iloc[0])
        except Exception:
            return None
    @staticmethod
    def _yfinance_prices(symbols: list[str], *, timeout: float | None) -> dict[str, Decimal | None]:
        try:
            import yfinance as yf  # type: ignore[import-untyped]
        except ImportError:
            return {symbol: None for symbol in symbols}
        logger = logging.getLogger("yfinance")
        previous_disabled = logger.disabled
        logger.disabled = True
        try:
            data = yf.download(
                symbols, period="1d", progress=False, auto_adjust=False,
                timeout=timeout if timeout is not None else _QUOTE_TIMEOUT_SECONDS,
                threads=False,
            )
        except Exception:
            return {symbol: None for symbol in symbols}
        finally:
            logger.disabled = previous_disabled
        if data is None or getattr(data, "empty", False):
            return {symbol: None for symbol in symbols}
        try:
            close = data["Close"]
            if not hasattr(close, "columns"):
                return {symbols[0]: _decimal(close.iloc[-1])}
            return {
                symbol: _decimal(close[symbol].iloc[-1]) if symbol in close.columns else None
                for symbol in symbols
            }
        except Exception:
            return {symbol: None for symbol in symbols}


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
            payload = self._fetch(url)
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

    def raw_quote_many(self, refs, *, timeout: float | None = None):
        crypto_ids = {}
        results = {}
        for ref in refs:
            if ref.kind is not AssetKind.CRYPTO:
                results[ref.identity] = UnsupportedQuote(ref.identity)
                continue
            key = ref.identity.strip().lower()
            if key not in CRYPTO_IDS:
                results[ref.identity] = UnsupportedQuote(ref.identity)
                continue
            crypto_ids[ref.identity] = CRYPTO_IDS[key]
        if not crypto_ids:
            return results
        url = (
            "https://api.coingecko.com/api/v3/simple/price?ids="
            f"{quote(','.join(dict.fromkeys(crypto_ids.values())))}&vs_currencies=usd"
        )
        try:
            payload = self._fetch(url, timeout=timeout)
        except Exception:
            return {**results, **{identity: RuntimeError("provider_error") for identity in crypto_ids}}
        for identity, cg_id in crypto_ids.items():
            value = _decimal((payload.get(cg_id) or {}).get("usd"))
            results[identity] = None if value is None else ProviderTick(
                price=value,
                quote_currency="USD",
                observed_at=self._clock(),
                provider="coingecko",
            )
        return results

    def _fetch(self, url: str, *, timeout: float | None = None):
        if timeout is None:
            return self._fetch_json(url)
        try:
            return self._fetch_json(url, timeout=timeout)
        except TypeError:
            return self._fetch_json(url)


class PredictionMarketQuoteProvider:
    def __init__(self, fetch_json=None, *, clock=None, max_in_flight: int = 4):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now
        self._max_in_flight = max(1, max_in_flight)

    def raw_quote(
        self, identity: str, kind: AssetKind, *, timeout: float | None = None,
    ) -> ProviderTick | None:
        if kind is not AssetKind.PREDICTION_MARKET:
            raise UnsupportedQuote(identity)
        parsed = parse_prediction_market_identity(identity)
        if not parsed:
            raise UnsupportedQuote(identity)
        slug, side = parsed
        deadline = None if timeout is None else time.monotonic() + max(timeout, 0)
        market = self._load_market(slug, deadline=deadline)
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

    def raw_quote_many(self, refs, *, timeout: float | None = None):
        deadline = None if timeout is None else time.monotonic() + max(timeout, 0)
        pending = list(enumerate(refs))
        results = {}
        completed = Queue()
        in_flight = {}

        def read_one(index, ref, remaining):
            try:
                outcome = self.raw_quote(ref.identity, ref.kind, timeout=remaining)
            except Exception as exc:
                outcome = exc
            completed.put((index, ref, outcome))

        while pending or in_flight:
            while pending and len(in_flight) < self._max_in_flight:
                if deadline is not None and time.monotonic() >= deadline:
                    break
                index, ref = pending.pop(0)
                if ref.kind is not AssetKind.PREDICTION_MARKET:
                    results[ref.identity] = UnsupportedQuote(ref.identity)
                    continue
                remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
                in_flight[index] = ref
                Thread(target=read_one, args=(index, ref, remaining), daemon=True).start()
            if not in_flight:
                break
            remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
            if remaining is not None and remaining <= 0:
                break
            try:
                index, ref, outcome = completed.get(timeout=remaining)
            except Empty:
                break
            in_flight.pop(index, None)
            results[ref.identity] = outcome
        for _, ref in pending:
            results.setdefault(ref.identity, None)
        for ref in in_flight.values():
            results.setdefault(ref.identity, None)
        return results

    def _load_market(self, slug: str, *, deadline: float | None = None):
        def remaining_timeout():
            if deadline is None:
                return None
            return max(deadline - time.monotonic(), 0)

        try:
            timeout = remaining_timeout()
            if timeout is not None and timeout <= 0:
                return None
            payload = self._fetch(
                f"https://gamma-api.polymarket.com/markets?slug={quote(slug)}", timeout=timeout
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
        timeout = remaining_timeout()
        if timeout is not None and timeout <= 0:
            return None
        try:
            search = self._fetch(
                "https://gamma-api.polymarket.com/public-search?q="
                f"{quote(slug.replace('-', ' '))}", timeout=timeout
            )
        except Exception:
            return None
        events = search.get("events") or search.get("data") or [] if isinstance(search, dict) else search or []
        for event in events if isinstance(events, list) else []:
            for candidate in event.get("markets", []) if isinstance(event, dict) else []:
                if isinstance(candidate, dict) and candidate.get("slug") == slug:
                    return candidate
        return None

    def _fetch(self, url: str, *, timeout: float | None = None):
        if timeout is None:
            return self._fetch_json(url)
        try:
            return self._fetch_json(url, timeout=timeout)
        except TypeError:
            return self._fetch_json(url)


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

    def raw_quote_at(self, identity: str, kind: AssetKind, *, at: datetime) -> ProviderTick | None:
        if kind is AssetKind.CASH:
            return self._cash.raw_quote(identity, kind)
        provider = {
            AssetKind.SECURITY: self._security,
            AssetKind.CRYPTO: self._crypto,
            AssetKind.PREDICTION_MARKET: self._pm,
        }.get(kind)
        method = getattr(provider, "raw_quote_at", None)
        if not callable(method):
            raise UnsupportedQuote(identity)
        return method(identity, kind, at=at)

    def raw_quote_many(self, refs, *, timeout: float | None = None):
        by_kind = defaultdict(list)
        for ref in refs:
            by_kind[ref.kind].append(ref)
        results = {}
        providers = {
            AssetKind.CASH: self._cash,
            AssetKind.SECURITY: self._security,
            AssetKind.CRYPTO: self._crypto,
            AssetKind.PREDICTION_MARKET: self._pm,
        }
        for kind, group in by_kind.items():
            provider = providers.get(kind)
            if provider is None:
                results.update({ref.identity: UnsupportedQuote(ref.identity) for ref in group})
                continue
            batch = getattr(provider, "raw_quote_many", None)
            if callable(batch):
                results.update(batch(group, timeout=timeout))
            else:
                for ref in group:
                    try:
                        results[ref.identity] = provider.raw_quote(ref.identity, ref.kind)
                    except Exception as exc:
                        results[ref.identity] = exc
        return results


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

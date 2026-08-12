"""Market quote adapters: symbol map + composite providers (injectable IO)."""
from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import json
import logging
import os
from queue import Empty, Queue
import re
from threading import Thread
import time
from urllib.parse import quote
import urllib.request
from zoneinfo import ZoneInfo

from ft.application.valuation import UnsupportedQuote
from ft.domain.valuation import (
    AssetKind,
    ProviderTick,
    ledger_security_to_yfinance,
    parse_prediction_market_identity,
)
from ft.schema import CRYPTO_IDS

_QUOTE_TIMEOUT_SECONDS = 4.0
_PRIMARY_QUOTE_TIMEOUT_SECONDS = 0.25
_FALLBACK_QUOTE_TIMEOUT_SECONDS = 2.5


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
    if upper.endswith(".TW"):
        return "TWD"
    if upper.endswith(".T"):
        return "JPY"
    if upper.endswith((".KS", ".KQ")):
        return "KRW"
    return "USD"


def _quote_session_for_symbol(symbol: str, observed_at: datetime | None) -> str:
    """Classify a source timestamp using the symbol's exchange-local session.

    This is deliberately conservative: an unrecognised venue is ``unknown``;
    it is never silently presented as a regular close.
    """
    if observed_at is None:
        return "unknown"
    upper = str(symbol or "").upper()
    if upper.endswith(".HK"):
        zone, regular_windows, post_window = "Asia/Hong_Kong", (((9, 30), (12, 0)), ((13, 0), (16, 0))), ((16, 0), (20, 0))
        pre_window = overnight_window = None
    elif upper.endswith((".SS", ".SZ")):
        zone, regular_windows, post_window = "Asia/Shanghai", (((9, 30), (11, 30)), ((13, 0), (15, 0))), None
        pre_window = overnight_window = None
    elif upper.endswith((".T", ".JP")):
        zone, regular_windows, post_window = "Asia/Tokyo", (((9, 0), (11, 30)), ((12, 30), (15, 0))), None
        pre_window = overnight_window = None
    elif upper.endswith((".KS", ".KQ")):
        zone, regular_windows, post_window = "Asia/Seoul", (((9, 0), (15, 30)),), ((15, 40), (18, 0))
        pre_window = overnight_window = None
    elif upper.endswith((".TW", ".TWO")):
        zone, regular_windows, post_window = "Asia/Taipei", (((9, 0), (13, 30)),), ((14, 0), (14, 30))
        pre_window = overnight_window = None
    elif "." in upper:
        return "unknown"
    else:
        zone, regular_windows, post_window = "America/New_York", (((9, 30), (16, 0)),), ((16, 0), (20, 0))
        pre_window, overnight_window = ((4, 0), (9, 30)), ((20, 0), (24, 0))
    try:
        local = observed_at.astimezone(ZoneInfo(zone))
    except (TypeError, ValueError):
        return "unknown"
    minute = local.hour * 60 + local.minute
    def in_window(window):
        if window is None:
            return False
        start, end = window
        start_minute = start[0] * 60 + start[1]
        end_minute = end[0] * 60 + end[1]
        return start_minute <= minute < end_minute

    if in_window(pre_window):
        return "pre_market"
    if any(in_window(window) for window in regular_windows):
        return "regular"
    if in_window(post_window):
        return "post_market"
    if in_window(overnight_window) or (overnight_window is not None and minute < 4 * 60):
        return "overnight"
    return "unknown"


def _pandas_timestamp(value) -> datetime | None:
    try:
        timestamp = value.to_pydatetime() if hasattr(value, "to_pydatetime") else value
        if timestamp.tzinfo is None:
            return None
        return timestamp.astimezone(timezone.utc)
    except (AttributeError, TypeError, ValueError, OverflowError):
        return None


def _yfinance_tick_from_series(symbol: str, series) -> ProviderTick | None:
    try:
        values = series.dropna()
        if len(values) == 0:
            return None
        observed_at = _pandas_timestamp(values.index[-1])
        price = _decimal(values.iloc[-1])
        return None if price is None else ProviderTick(
            price, quote_currency_for_security_symbol(symbol), observed_at,
            "yfinance", _quote_session_for_symbol(symbol, observed_at),
        )
    except Exception:
        return None


def _quoted_decimal(value) -> Decimal | None:
    """Parse providers' human-formatted dollar fields without using float."""
    text = str(value or "").strip().replace(",", "")
    match = re.search(r"[-+]?\d+(?:\.\d+)?", text)
    return _decimal(match.group(0)) if match else None


def _us_security_symbol(identity: str) -> str | None:
    """Return a public-US-source symbol, rejecting explicitly non-US tickers."""
    token = str(identity or "").strip()
    lower = token.lower()
    if not token or ("." in lower and not lower.endswith(".us")):
        return None
    symbol = map_security_symbol(token)
    return symbol if symbol and "." not in symbol else None


class YahooChartQuoteProvider:
    """Direct Yahoo chart transport used only when yfinance batch data is absent."""

    def __init__(self, fetch_json=None, *, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind, *, timeout: float | None = None) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = map_security_symbol(identity)
        try:
            payload = self._fetch(
                "https://query1.finance.yahoo.com/v8/finance/chart/"
                f"{quote(symbol)}?range=1d&interval=1m",
                timeout=timeout,
            )
            result = ((payload.get("chart") or {}).get("result") or [None])[0] or {}
            meta = result.get("meta") or {}
            price, observed_at, session = _yahoo_latest_quote(meta)
            currency = str(meta.get("currency") or quote_currency_for_security_symbol(symbol)).upper()
        except Exception:
            return None
        if price is None or observed_at is None or not currency:
            return None
        return ProviderTick(price, currency, observed_at, "yahoo-chart", session)

    def _fetch(self, url: str, *, timeout: float | None):
        try:
            return self._fetch_json(url, timeout=timeout or _FALLBACK_QUOTE_TIMEOUT_SECONDS)
        except TypeError:
            return self._fetch_json(url)


class CboeQuoteProvider:
    """Cboe's public delayed US-equity quote endpoint."""

    def __init__(self, fetch_json=None, *, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind, *, timeout: float | None = None) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = _us_security_symbol(identity)
        if symbol is None:
            return None
        try:
            payload = self._fetch(
                "https://cdn.cboe.com/api/global/delayed_quotes/quotes/"
                f"{quote(symbol)}.json",
                timeout=timeout,
            )
            price = _quoted_decimal((payload.get("data") or {}).get("current_price"))
            observed_at = _cboe_datetime(payload.get("timestamp"))
        except Exception:
            return None
        if price is None or observed_at is None:
            return None
        return ProviderTick(price, "USD", observed_at, "cboe-delayed", _quote_session_for_symbol(symbol, observed_at))

    def _fetch(self, url: str, *, timeout: float | None):
        try:
            return self._fetch_json(url, timeout=timeout or _FALLBACK_QUOTE_TIMEOUT_SECONDS)
        except TypeError:
            return self._fetch_json(url)


class NasdaqQuoteProvider:
    """Nasdaq's public US-equity quote endpoint, which may be delayed."""

    def __init__(self, fetch_json=None, *, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind, *, timeout: float | None = None) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = _us_security_symbol(identity)
        if symbol is None:
            return None
        try:
            payload = self._fetch(
                "https://api.nasdaq.com/api/quote/"
                f"{quote(symbol)}/info?assetclass=stocks",
                timeout=timeout,
            )
            primary = (payload.get("data") or {}).get("primaryData") or {}
            price = _quoted_decimal(primary.get("lastSalePrice"))
            observed_at = _nasdaq_date(primary.get("lastTradeTimestamp"))
        except Exception:
            return None
        if price is None or observed_at is None:
            return None
        return ProviderTick(price, "USD", observed_at, "nasdaq-delayed", "unknown")

    def _fetch(self, url: str, *, timeout: float | None):
        try:
            return self._fetch_json(url, timeout=timeout or _FALLBACK_QUOTE_TIMEOUT_SECONDS)
        except TypeError:
            return self._fetch_json(url)


class FinnhubQuoteProvider:
    """Optional key-backed real-time US quote fallback."""

    def __init__(self, fetch_json=None, *, api_key: str | None = None, clock=None):
        self._fetch_json = fetch_json or _json_get
        self._api_key = api_key if api_key is not None else os.environ.get("FT_FINNHUB_API_KEY", "")
        self._clock = clock or _now

    def raw_quote(self, identity: str, kind: AssetKind, *, timeout: float | None = None) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = _us_security_symbol(identity)
        if symbol is None or not self._api_key:
            return None
        try:
            payload = self._fetch(
                "https://finnhub.io/api/v1/quote?symbol="
                f"{quote(symbol)}&token={quote(self._api_key)}",
                timeout=timeout,
            )
            price = _decimal(payload.get("c"))
            observed_at = _epoch_datetime(payload.get("t"))
        except Exception:
            return None
        if price is None or observed_at is None:
            return None
        return ProviderTick(price, "USD", observed_at, "finnhub", _quote_session_for_symbol(symbol, observed_at))

    def _fetch(self, url: str, *, timeout: float | None):
        try:
            return self._fetch_json(url, timeout=timeout or _FALLBACK_QUOTE_TIMEOUT_SECONDS)
        except TypeError:
            return self._fetch_json(url)


def _epoch_datetime(value) -> datetime | None:
    try:
        return datetime.fromtimestamp(int(value), tz=timezone.utc)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def _yahoo_latest_quote(meta: dict) -> tuple[Decimal | None, datetime | None, str]:
    """Choose the newest regular/pre/post Yahoo quote without using fetch time."""
    candidates = (
        ("regularMarketTime", "regularMarketPrice", "regular"),
        ("preMarketTime", "preMarketPrice", "pre_market"),
        ("postMarketTime", "postMarketPrice", "post_market"),
    )
    available = []
    for time_key, price_key, session in candidates:
        observed_at = _epoch_datetime(meta.get(time_key))
        price = _decimal(meta.get(price_key))
        if observed_at is not None and price is not None:
            available.append((observed_at, price, session))
    if not available:
        return None, None, "unknown"
    observed_at, price, session = max(available, key=lambda item: item[0])
    return price, observed_at, session


def _cboe_datetime(value) -> datetime | None:
    try:
        return datetime.strptime(str(value), "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None


def _nasdaq_date(value) -> datetime | None:
    try:
        return datetime.strptime(str(value), "%b %d, %Y").replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        return None


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
    def __init__(
        self, download=None, *, download_history=None, fallbacks=None,
        primary_timeout_seconds: float = _PRIMARY_QUOTE_TIMEOUT_SECONDS,
        fallback_timeout_seconds: float = _FALLBACK_QUOTE_TIMEOUT_SECONDS,
        max_fallback_in_flight: int = 8, clock=None,
    ):
        self._download = download
        self._download_history = download_history
        self._clock = clock or _now
        # Injected primary downloads are used by deterministic tests and callers
        # that own their own source chain.  Production keeps the public fallback
        # chain enabled by default.
        self._fallbacks = tuple(fallbacks) if fallbacks is not None else (
            () if download is not None else (
                YahooChartQuoteProvider(clock=self._clock),
                CboeQuoteProvider(clock=self._clock),
                NasdaqQuoteProvider(clock=self._clock),
                FinnhubQuoteProvider(clock=self._clock),
            )
        )
        self._primary_timeout = max(float(primary_timeout_seconds), 0.1)
        self._fallback_timeout = max(float(fallback_timeout_seconds), 0.1)
        self._max_fallback_in_flight = max(1, int(max_fallback_in_flight))

    def raw_quote(self, identity: str, kind: AssetKind) -> ProviderTick | None:
        if kind is not AssetKind.SECURITY:
            raise UnsupportedQuote(identity)
        symbol = map_security_symbol(identity)
        if self._download is not None:
            outcome = self._download(symbol)
            if isinstance(outcome, ProviderTick):
                return outcome
            price = _decimal(outcome)
        else:
            return self._yfinance_quote(symbol)
        return None if price is None else ProviderTick(
            price=price, quote_currency=quote_currency_for_security_symbol(symbol),
            observed_at=None, provider="yfinance", quote_session="unknown",
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
        requested_at = time.monotonic() if timeout is not None else None
        primary_timeout = self._primary_timeout if timeout is None else min(self._primary_timeout, timeout)
        try:
            prices = self._download_many_bounded(
                list(dict.fromkeys(symbols.values())), timeout=primary_timeout,
            )
        except Exception:
            results.update({identity: RuntimeError("provider_error") for identity in symbols})
        else:
            for identity, symbol in symbols.items():
                results[identity] = prices.get(symbol)
        missing = [
            ref for ref in refs
            if ref.identity in symbols and (
                results.get(ref.identity) is None or isinstance(results.get(ref.identity), Exception)
            )
        ]
        if missing and self._fallbacks:
            fallback_timeout = None if requested_at is None else max(timeout - (time.monotonic() - requested_at), 0)
            for identity, tick in self._fallback_missing(missing, timeout=fallback_timeout).items():
                if tick is not None:
                    results[identity] = tick
        return results

    def _download_many_bounded(self, symbols: list[str], *, timeout: float | None):
        """Honor the primary deadline even when a third-party client ignores it.

        `yfinance.download()` accepts a timeout argument, but its own retry and
        session machinery can outlive it.  The abandoned worker is a daemon so
        a slow upstream cannot delay this refresh, its SSE subscriber, or
        process shutdown; the caller immediately moves to the independent
        fallback chain.
        """
        if timeout is None:
            return self._download_many(symbols, timeout=None)
        completed = Queue(maxsize=1)

        def read_primary():
            try:
                completed.put((True, self._download_many(symbols, timeout=timeout)))
            except Exception as exc:
                completed.put((False, exc))

        Thread(target=read_primary, daemon=True).start()
        try:
            succeeded, outcome = completed.get(timeout=max(timeout, 0))
        except Empty as exc:
            raise TimeoutError("primary_quote_timeout") from exc
        if not succeeded:
            raise outcome
        return outcome

    def _fallback_missing(self, refs, *, timeout: float | None):
        deadline = None if timeout is None else time.monotonic() + max(timeout, 0)
        pending = list(refs)
        in_flight = 0
        completed = Queue()
        resolved = {}

        def read_one(ref):
            tick = None
            for source in self._fallbacks:
                remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
                if remaining is not None and remaining <= 0:
                    break
                source_timeout = self._fallback_timeout if remaining is None else min(self._fallback_timeout, remaining)
                try:
                    candidate = source.raw_quote(ref.identity, ref.kind, timeout=source_timeout)
                except Exception:
                    candidate = None
                if candidate is not None:
                    tick = candidate
                    break
            completed.put((ref.identity, tick))

        while pending or in_flight:
            while pending and in_flight < self._max_fallback_in_flight:
                if deadline is not None and time.monotonic() >= deadline:
                    break
                ref = pending.pop(0)
                Thread(target=read_one, args=(ref,), daemon=True).start()
                in_flight += 1
            if not in_flight:
                break
            remaining = None if deadline is None else max(deadline - time.monotonic(), 0)
            if remaining is not None and remaining <= 0:
                break
            try:
                identity, tick = completed.get(timeout=remaining)
            except Empty:
                break
            in_flight -= 1
            resolved[identity] = tick
        return resolved

    def _download_many(self, symbols: list[str], *, timeout: float | None) -> dict[str, ProviderTick | None]:
        if self._download is not None:
            payload = self._download(symbols, timeout=timeout)
            if isinstance(payload, dict):
                return {symbol: self._coerce_download_quote(symbol, payload.get(symbol)) for symbol in symbols}
            if len(symbols) == 1:
                return {symbols[0]: self._coerce_download_quote(symbols[0], payload)}
            return {symbol: None for symbol in symbols}
        return self._yfinance_quotes(symbols, timeout=timeout)

    @staticmethod
    def _coerce_download_quote(symbol: str, value) -> ProviderTick | None:
        if isinstance(value, ProviderTick):
            return value
        price = _decimal(value)
        if price is None:
            return None
        return ProviderTick(
            price, quote_currency_for_security_symbol(symbol), None, "yfinance", "unknown",
        )

    def _yfinance_quote(self, symbol: str) -> ProviderTick | None:
        return self._yfinance_quotes([symbol], timeout=None).get(symbol)

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
            # yfinance's period parser accepts calendar dates reliably but can
            # reject timezone-aware ISO timestamps containing microseconds.
            # The requested boundary is applied against the returned index
            # below, so date-only bounds still preserve the exact lookup.
            start = (at - timedelta(days=3)).date().isoformat()
            end = (at + timedelta(days=1)).date().isoformat()
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
    def _yfinance_quotes(symbols: list[str], *, timeout: float | None) -> dict[str, ProviderTick | None]:
        try:
            import yfinance as yf  # type: ignore[import-untyped]
        except ImportError:
            return {symbol: None for symbol in symbols}
        logger = logging.getLogger("yfinance")
        previous_disabled = logger.disabled
        logger.disabled = True
        try:
            data = yf.download(
                symbols, period="1d", interval="5m", progress=False, auto_adjust=False,
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
                series = close
                observed_at = _pandas_timestamp(series.index[-1])
                price = _decimal(series.iloc[-1])
                return {symbols[0]: None if price is None else ProviderTick(
                    price, quote_currency_for_security_symbol(symbols[0]), observed_at,
                    "yfinance", _quote_session_for_symbol(symbols[0], observed_at),
                )}
            return {
                symbol: _yfinance_tick_from_series(symbol, close[symbol]) if symbol in close.columns else None
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

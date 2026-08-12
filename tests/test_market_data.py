from datetime import datetime, timezone
from decimal import Decimal
import sys
import threading
import time
from types import SimpleNamespace

from ft.adapters.market_data import (
    CboeQuoteProvider,
    CryptoQuoteProvider,
    FinnhubQuoteProvider,
    NasdaqQuoteProvider,
    PredictionMarketQuoteProvider,
    SecurityQuoteProvider,
    YahooChartQuoteProvider,
    _quote_session_for_symbol,
)
from ft.domain.valuation import AssetKind, AssetRef, ProviderTick


def test_polymarket_adapter_accepts_direct_market_payload_and_parent_search_fallback(monkeypatch):
    """PredictionMarketQuoteProvider: direct slug market + public-search nested fallback."""
    from ft.adapters.market_data import PredictionMarketQuoteProvider
    from ft.domain.valuation import AssetKind

    direct = {"slug": "direct-market", "outcomes": ["Yes", "No"], "outcomePrices": ["0.7", "0.3"]}
    provider = PredictionMarketQuoteProvider(fetch_json=lambda _url: direct)
    tick = provider.raw_quote("pm:direct-market:yes", AssetKind.PREDICTION_MARKET)
    assert tick is not None
    assert tick.price == Decimal("0.7")
    assert tick.quote_currency == "USD"

    responses = iter([
        [],
        {"events": [{"markets": [{
            "slug": "nested-market", "outcomes": '["Yes", "No"]',
            "outcomePrices": '["0.6", "0.4"]',
        }]}]},
    ])
    provider = PredictionMarketQuoteProvider(fetch_json=lambda _url: next(responses))
    tick = provider.raw_quote("pm:nested-market:no", AssetKind.PREDICTION_MARKET)
    assert tick is not None
    assert tick.price == Decimal("0.4")


def test_security_batch_downloads_multiple_symbols_once_and_keeps_missing_items():
    calls = []

    def download(symbols, *, timeout):
        calls.append((symbols, timeout))
        return {"AAPL": Decimal("12.5"), "0700.HK": Decimal("400")}

    provider = SecurityQuoteProvider(
        download=download, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    results = provider.raw_quote_many([
        AssetRef("aapl.us", AssetKind.SECURITY),
        AssetRef("0700.hk", AssetKind.SECURITY),
        AssetRef("missing.us", AssetKind.SECURITY),
    ], timeout=0.5)

    assert len(calls) == 1
    assert set(calls[0][0]) == {"AAPL", "0700.HK", "MISSING"}
    assert results["aapl.us"].price == Decimal("12.5")
    assert results["0700.hk"].quote_currency == "HKD"
    assert results["missing.us"] is None


def test_security_provider_reads_a_historical_price_at_the_requested_boundary():
    requested = []
    boundary = datetime(2026, 7, 24, 12, tzinfo=timezone.utc)

    def download_history(symbol, *, at):
        requested.append((symbol, at))
        return Decimal("99.5")

    provider = SecurityQuoteProvider(
        download_history=download_history,
        clock=lambda: datetime(2026, 7, 25, 12, tzinfo=timezone.utc),
    )

    tick = provider.raw_quote_at("aapl.us", AssetKind.SECURITY, at=boundary)

    assert tick is not None
    assert tick.price == Decimal("99.5")
    assert tick.observed_at == boundary
    assert requested == [("AAPL", boundary)]


def test_yfinance_historical_query_uses_date_only_boundaries(monkeypatch):
    import pandas as pd

    boundary = datetime(2026, 8, 12, 8, 42, 31, 371430, tzinfo=timezone.utc)
    frame = pd.DataFrame(
        [[100], [101]],
        index=pd.DatetimeIndex(["2026-08-12 08:00:00+00:00", "2026-08-12 09:00:00+00:00"]),
        columns=pd.MultiIndex.from_tuples([("Close", "AAPL")]),
    )
    calls = []

    def download(symbol, **kwargs):
        calls.append((symbol, kwargs))
        return frame

    monkeypatch.setitem(sys.modules, "yfinance", SimpleNamespace(download=download))

    tick = SecurityQuoteProvider._yfinance_price_at("AAPL", boundary)

    assert tick == Decimal("100")
    assert calls == [("AAPL", {
        "start": "2026-08-09", "end": "2026-08-13", "interval": "1h",
        "progress": False, "auto_adjust": False, "timeout": 4.0,
    })]


def test_security_batch_with_ten_symbols_uses_fewer_than_ten_downloads():
    calls = []

    def download(symbols, *, timeout):
        calls.append(tuple(symbols))
        return {symbol: Decimal("1") for symbol in symbols}

    provider = SecurityQuoteProvider(
        download=download, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    refs = [AssetRef(f"security-{index}.us", AssetKind.SECURITY) for index in range(10)]
    results = provider.raw_quote_many(refs, timeout=0.5)

    assert len(calls) < len(refs)
    assert len(calls) == 1
    assert set(results) == {ref.identity for ref in refs}


def test_security_batch_uses_ordered_fallbacks_only_for_primary_misses():
    now = datetime(2026, 8, 12, 6, tzinfo=timezone.utc)
    calls = []

    class Fallback:
        def __init__(self, name, outcomes):
            self.name = name
            self.outcomes = outcomes

        def raw_quote(self, identity, kind, *, timeout):
            calls.append((self.name, identity, kind, timeout))
            outcome = self.outcomes.get(identity)
            if isinstance(outcome, BaseException):
                raise outcome
            return outcome

    first = Fallback("first", {"missing.us": None, "broken.us": RuntimeError("unavailable")})
    second = Fallback("second", {
        "missing.us": ProviderTick(Decimal("20"), "USD", now, "second"),
        "broken.us": ProviderTick(Decimal("30"), "USD", now, "second"),
    })
    provider = SecurityQuoteProvider(
        download=lambda _symbols, *, timeout: {"AAPL": Decimal("10"), "MISSING": None, "BROKEN": None},
        fallbacks=(first, second), fallback_timeout_seconds=0.2,
        clock=lambda: now,
    )

    results = provider.raw_quote_many([
        AssetRef("aapl.us", AssetKind.SECURITY),
        AssetRef("missing.us", AssetKind.SECURITY),
        AssetRef("broken.us", AssetKind.SECURITY),
    ])

    assert results["aapl.us"].price == Decimal("10")
    assert results["missing.us"].price == Decimal("20")
    assert results["broken.us"].price == Decimal("30")
    assert {identity for _, identity, _, _ in calls} == {"missing.us", "broken.us"}
    assert [name for name, identity, _, _ in calls if identity == "missing.us"] == ["first", "second"]
    assert [name for name, identity, _, _ in calls if identity == "broken.us"] == ["first", "second"]
    assert all(timeout <= 0.2 for *_, timeout in calls)


def test_security_fallbacks_preserve_unknown_when_every_source_fails_and_skip_finnhub_without_key():
    now = datetime(2026, 8, 12, 6, tzinfo=timezone.utc)

    class Fallback:
        def raw_quote(self, _identity, _kind, *, timeout):
            assert timeout <= 0.1
            return None

    provider = SecurityQuoteProvider(
        download=lambda _symbols, *, timeout: {"MISSING": None},
        fallbacks=(Fallback(),), fallback_timeout_seconds=0.1, clock=lambda: now,
    )

    result = provider.raw_quote_many([AssetRef("missing.us", AssetKind.SECURITY)])["missing.us"]

    assert result is None
    requested = []
    finnhub = FinnhubQuoteProvider(fetch_json=lambda url, *, timeout: requested.append((url, timeout)), api_key=None)
    assert finnhub.raw_quote("aapl.us", AssetKind.SECURITY, timeout=0.1) is None
    assert requested == []


def test_security_primary_batch_is_bounded_before_running_fallbacks():
    timeouts = []
    provider = SecurityQuoteProvider(
        download=lambda _symbols, *, timeout: timeouts.append(timeout) or {"MISSING": None},
        fallbacks=(), primary_timeout_seconds=0.2,
    )

    assert provider.raw_quote_many([AssetRef("missing.us", AssetKind.SECURITY)])["missing.us"] is None
    assert timeouts == [0.2]


def test_security_uses_fallback_when_primary_ignores_its_timeout():
    now = datetime(2026, 8, 12, 6, tzinfo=timezone.utc)

    class Fallback:
        def raw_quote(self, _identity, _kind, *, timeout):
            return ProviderTick(Decimal("42"), "USD", now, "fallback")

    def blocked_primary(_symbols, *, timeout):
        time.sleep(0.2)
        return {"MISSING": Decimal("1")}

    provider = SecurityQuoteProvider(
        download=blocked_primary,
        fallbacks=(Fallback(),), primary_timeout_seconds=0.05,
        fallback_timeout_seconds=0.05,
        clock=lambda: now,
    )

    started = time.monotonic()
    result = provider.raw_quote_many([AssetRef("missing.us", AssetKind.SECURITY)])["missing.us"]

    assert time.monotonic() - started < 0.15
    assert result == ProviderTick(Decimal("42"), "USD", now, "fallback")


def test_security_fallback_adapters_parse_yahoo_cboe_and_nasdaq_prices():
    now = datetime(2026, 8, 12, 6, tzinfo=timezone.utc)
    yahoo = YahooChartQuoteProvider(fetch_json=lambda _url, *, timeout: {
        "chart": {"result": [{"meta": {
            "regularMarketPrice": "101.25", "regularMarketTime": 1786514400, "currency": "USD",
        }}]},
    }, clock=lambda: now)
    cboe = CboeQuoteProvider(fetch_json=lambda _url, *, timeout: {
        "timestamp": "2026-08-12 05:59:00", "data": {"current_price": "102.5"},
    }, clock=lambda: now)
    nasdaq = NasdaqQuoteProvider(fetch_json=lambda _url, *, timeout: {
        "data": {"primaryData": {"lastSalePrice": "$103.75", "lastTradeTimestamp": "Aug 12, 2026"}},
    }, clock=lambda: now)

    assert yahoo.raw_quote("aapl.us", AssetKind.SECURITY, timeout=0.1) == ProviderTick(
        Decimal("101.25"), "USD", datetime.fromtimestamp(1786514400, tz=timezone.utc), "yahoo-chart", "regular",
    )
    assert cboe.raw_quote("aapl.us", AssetKind.SECURITY, timeout=0.1) == ProviderTick(
        Decimal("102.5"), "USD", datetime(2026, 8, 12, 5, 59, tzinfo=timezone.utc), "cboe-delayed", "overnight",
    )
    assert nasdaq.raw_quote("aapl.us", AssetKind.SECURITY, timeout=0.1) == ProviderTick(
        Decimal("103.75"), "USD", datetime(2026, 8, 12, tzinfo=timezone.utc), "nasdaq-delayed", "unknown",
    )


def test_yfinance_batch_preserves_source_timestamp_and_extended_session(monkeypatch):
    import pandas as pd

    index = pd.DatetimeIndex(["2026-08-12 20:00:00+00:00", "2026-08-12 21:00:00+00:00"])
    frame = pd.DataFrame(
        [[100], [101]], index=index,
        columns=pd.MultiIndex.from_tuples([("Close", "AAPL")]),
    )
    monkeypatch.setitem(sys.modules, "yfinance", SimpleNamespace(
        download=lambda *_args, **_kwargs: frame,
    ))

    provider = SecurityQuoteProvider(clock=lambda: datetime(2030, 1, 1, tzinfo=timezone.utc))
    result = provider.raw_quote_many([AssetRef("aapl.us", AssetKind.SECURITY)])

    tick = result["aapl.us"]
    assert tick == ProviderTick(
        Decimal("101"), "USD", datetime(2026, 8, 12, 21, tzinfo=timezone.utc), "yfinance",
        "post_market",
    )


def test_yfinance_timestamp_is_unknown_when_index_has_no_timezone(monkeypatch):
    import pandas as pd

    frame = pd.DataFrame(
        [[100]], index=pd.DatetimeIndex(["2026-08-12 21:00:00"]),
        columns=pd.MultiIndex.from_tuples([("Close", "AAPL")]),
    )
    monkeypatch.setitem(sys.modules, "yfinance", SimpleNamespace(
        download=lambda *_args, **_kwargs: frame,
    ))

    provider = SecurityQuoteProvider(clock=lambda: datetime(2030, 1, 1, tzinfo=timezone.utc))
    tick = provider.raw_quote_many([AssetRef("aapl.us", AssetKind.SECURITY)])["aapl.us"]

    assert tick is not None
    assert tick.observed_at is None
    assert tick.quote_session == "unknown"


def test_yahoo_chart_uses_latest_extended_market_timestamp_and_session():
    provider = YahooChartQuoteProvider(fetch_json=lambda _url, **_kwargs: {
        "chart": {"result": [{"meta": {
            "regularMarketPrice": "101", "regularMarketTime": 1786564800,
            "postMarketPrice": "102", "postMarketTime": 1786572000,
            "preMarketPrice": "99", "preMarketTime": 1786536000, "currency": "USD",
        }}]},
    })

    tick = provider.raw_quote("aapl.us", AssetKind.SECURITY, timeout=0.1)

    assert tick is not None
    assert tick.price == Decimal("102")
    assert tick.quote_session == "post_market"


def test_quote_session_mapping_covers_us_extended_hours_and_overnight():
    assert _quote_session_for_symbol("AAPL", datetime(2026, 8, 12, 13, 0, tzinfo=timezone.utc)) == "pre_market"
    assert _quote_session_for_symbol("AAPL", datetime(2026, 8, 12, 15, 0, tzinfo=timezone.utc)) == "regular"
    assert _quote_session_for_symbol("AAPL", datetime(2026, 8, 12, 21, 0, tzinfo=timezone.utc)) == "post_market"
    assert _quote_session_for_symbol("AAPL", datetime(2026, 8, 13, 1, 0, tzinfo=timezone.utc)) == "overnight"


def test_quote_session_mapping_respects_non_us_market_boundaries():
    assert _quote_session_for_symbol("0700.hk", datetime(2026, 8, 12, 1, 30, tzinfo=timezone.utc)) == "regular"
    assert _quote_session_for_symbol("0700.hk", datetime(2026, 8, 12, 8, 0, tzinfo=timezone.utc)) == "post_market"
    assert _quote_session_for_symbol("600000.ss", datetime(2026, 8, 12, 3, 30, tzinfo=timezone.utc)) == "unknown"
    assert _quote_session_for_symbol("7203.jp", datetime(2026, 8, 12, 0, 0, tzinfo=timezone.utc)) == "regular"
    assert _quote_session_for_symbol("005930.ks", datetime(2026, 8, 12, 7, 0, tzinfo=timezone.utc)) == "post_market"
    assert _quote_session_for_symbol("2330.tw", datetime(2026, 8, 12, 6, 0, tzinfo=timezone.utc)) == "post_market"


def test_crypto_batch_merges_known_ids_and_does_not_pollute_missing_items():
    calls = []

    def fetch_json(url, *, timeout):
        calls.append((url, timeout))
        return {"bitcoin": {"usd": "100"}, "ethereum": {}}

    provider = CryptoQuoteProvider(
        fetch_json=fetch_json, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    results = provider.raw_quote_many([
        AssetRef("btc", AssetKind.CRYPTO),
        AssetRef("eth", AssetKind.CRYPTO),
    ], timeout=0.5)

    assert len(calls) == 1
    assert "bitcoin" in calls[0][0] and "ethereum" in calls[0][0]
    assert results["btc"].price == Decimal("100")
    assert results["eth"] is None


def test_prediction_market_batch_bounds_concurrency_and_isolates_missing_items():
    active = 0
    maximum_active = 0
    lock = threading.Lock()

    def fetch_json(url, *, timeout):
        nonlocal active, maximum_active
        slug = url.split("slug=", 1)[1]
        with lock:
            active += 1
            maximum_active = max(maximum_active, active)
        try:
            time.sleep(0.02)
            if slug == "missing":
                return []
            return {"slug": slug, "outcomes": ["Yes", "No"], "outcomePrices": ["0.7", "0.3"]}
        finally:
            with lock:
                active -= 1

    provider = PredictionMarketQuoteProvider(
        fetch_json=fetch_json,
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
        max_in_flight=2,
    )
    results = provider.raw_quote_many([
        AssetRef(f"pm:market-{index}:yes", AssetKind.PREDICTION_MARKET)
        for index in range(4)
    ] + [AssetRef("pm:missing:yes", AssetKind.PREDICTION_MARKET)], timeout=0.5)

    assert maximum_active <= 2
    assert results["pm:market-0:yes"].price == Decimal("0.7")
    assert results["pm:missing:yes"] is None

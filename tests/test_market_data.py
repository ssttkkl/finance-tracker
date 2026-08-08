from datetime import datetime, timezone
from decimal import Decimal
import threading
import time

from ft.adapters.market_data import (
    CryptoQuoteProvider,
    PredictionMarketQuoteProvider,
    SecurityQuoteProvider,
)
from ft.domain.valuation import AssetKind, AssetRef


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

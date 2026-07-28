from decimal import Decimal


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

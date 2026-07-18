from decimal import Decimal


def test_polymarket_adapter_accepts_direct_market_payload_and_parent_search_fallback(monkeypatch):
    from ft.adapters import market_data

    direct = {"slug": "direct-market", "outcomes": ["Yes", "No"], "outcomePrices": ["0.7", "0.3"]}
    monkeypatch.setattr(market_data, "_json_get", lambda _url: direct)
    assert market_data._fetch_polymarket(["pm:direct-market:yes"]) == {
        "pm:direct-market:yes": Decimal("0.7"),
    }

    responses = iter([
        [],
        {"events": [{"markets": [{
            "slug": "nested-market", "outcomes": '["Yes", "No"]',
            "outcomePrices": '["0.6", "0.4"]',
        }]}]},
    ])
    monkeypatch.setattr(market_data, "_json_get", lambda _url: next(responses))
    assert market_data._fetch_polymarket(["pm:nested-market:no"]) == {
        "pm:nested-market:no": Decimal("0.4"),
    }

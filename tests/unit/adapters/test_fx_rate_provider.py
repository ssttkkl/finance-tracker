"""FxRateProvider injectable tests."""
from decimal import Decimal

from ft.adapters.fx_rates import FxRateProvider, clear_rate_cache


def test_fx_provider_uses_fetcher_and_same_currency():
    clear_rate_cache()
    calls = []

    def fetch(day, base, quote):
        calls.append((day, base, quote))
        return Decimal("7.2")

    provider = FxRateProvider(fetcher=fetch)
    assert provider.get_mid("USD", "USD") == Decimal("1")
    assert provider.get_mid("USD", "CNY", day="2026-07-25") == Decimal("7.2")
    assert calls and calls[0][1:] == ("USD", "CNY")

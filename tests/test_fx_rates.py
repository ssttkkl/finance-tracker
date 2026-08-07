"""Unit tests for FX mid-rate helper (no network)."""
from decimal import Decimal

from ft.adapters.fx_rates import business_day_utc, rate_error, get_mid_rate, clear_rate_cache


def test_business_day_utc_from_naive_string():
    assert business_day_utc("2025-12-19 06:30:57") == "2025-12-19"


def test_rate_error_close_match():
    # cash 144.61 CNY → loan 159.40 HKD; market 1.1051 HKD/CNY
    err = rate_error(
        Decimal("144.61"), Decimal("159.40"), "CNY", "HKD", Decimal("1.1051"),
    )
    assert err is not None
    assert err < Decimal("0.015")


def test_rate_error_wrong_pair_large():
    err = rate_error(
        Decimal("144.61"), Decimal("2240"), "CNY", "JPY", Decimal("1.1051"),
    )
    assert err is not None
    assert err > Decimal("0.5")


def test_get_mid_rate_uses_fetcher():
    clear_rate_cache()
    calls = []

    def fetcher(day, base, quote):
        calls.append((day, base, quote))
        return Decimal("7.1")

    r1 = get_mid_rate("2025-12-19", "USD", "CNY", fetcher=fetcher)
    assert r1 == Decimal("7.1")
    assert calls == [("2025-12-19", "USD", "CNY")]

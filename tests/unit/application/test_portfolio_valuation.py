"""Portfolio valuation P0: native + display currency."""
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.application.investment import PortfolioQueryService
from ft.application.valuation import UnsupportedQuote, ValuationService
from ft.domain.valuation import AssetKind, ProviderTick, QuoteStatus, ValuationError


class FakePortfolioRepo:
    def load_portfolio(self):
        return {
            "accounts": {
                "Multi": {
                    "currency": "USD",
                    "positions": {
                        "usd": {"shares": 10, "total_cost": 10, "cost_currency": "USD"},
                        "aapl.us": {"shares": 2, "total_cost": 6, "cost_currency": "USD"},
                        "0700.hk": {"shares": 1, "total_cost": 100, "cost_currency": "HKD"},
                        "unknown.xyz": {"shares": 1, "total_cost": 1, "cost_currency": "USD"},
                    },
                }
            },
            "base_currencies": {"Multi": ("USD",)},
            "configured_currencies": ("USD",),
        }


class FakeProvider:
    def raw_quote(self, identity, kind):
        if identity == "aapl.us":
            return ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")
        if identity == "0700.hk":
            return ProviderTick(Decimal("300"), "HKD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")
        raise UnsupportedQuote(identity)


class FakeFx:
    def __init__(self, rates=None):
        self.rates = dict(rates or {})

    def get_mid(self, base, quote, *, day=None):
        if base.upper() == quote.upper():
            return Decimal("1")
        return self.rates.get((base.upper(), quote.upper()))


def test_native_portfolio_multi_currency_and_status():
    service = PortfolioQueryService(
        FakePortfolioRepo(),
        ValuationService(FakeProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    )
    result = service.get_portfolio()
    by = {p.ticker: p for p in result.accounts[0].positions}
    assert by["usd"].is_cash and by["usd"].current_price == Decimal("1")
    assert by["usd"].quote_status == QuoteStatus.COMPLETE.value
    assert by["aapl.us"].market_value == Decimal("10")
    assert by["aapl.us"].quote_currency == "USD"
    assert by["0700.hk"].market_value == Decimal("300")
    assert by["0700.hk"].quote_currency == "HKD"
    assert by["unknown.xyz"].market_value is None
    assert by["unknown.xyz"].quote_status == QuoteStatus.UNSUPPORTED.value
    assert by["aapl.us"].display_market_value is None


def test_display_currency_fx_and_fail_closed():
    valuation = ValuationService(
        FakeProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    fx = FakeFx({("USD", "CNY"): Decimal("7"), ("HKD", "CNY"): Decimal("0.9")})
    service = PortfolioQueryService(FakePortfolioRepo(), valuation, fx_rates=fx)
    result = service.get_portfolio(display_currency="cny")
    by = {p.ticker: p for p in result.accounts[0].positions}
    assert by["aapl.us"].display_currency == "CNY"
    assert by["aapl.us"].display_market_value == Decimal("70")
    assert by["aapl.us"].fx_rate == Decimal("7")
    assert by["0700.hk"].display_market_value == Decimal("270")
    assert by["unknown.xyz"].display_market_value is None

    # FX missing must not use 1:1
    service2 = PortfolioQueryService(FakePortfolioRepo(), valuation, fx_rates=FakeFx({}))
    by2 = {p.ticker: p for p in service2.get_portfolio(display_currency="CNY").accounts[0].positions}
    assert by2["aapl.us"].market_value == Decimal("10")
    assert by2["aapl.us"].display_market_value is None
    assert by2["aapl.us"].fx_status == "partial"

    with pytest.raises(ValuationError):
        service.get_portfolio(display_currency="US")

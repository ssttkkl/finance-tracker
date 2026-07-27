"""Application tests for ValuationService with fake providers."""
from datetime import datetime, timezone
from decimal import Decimal

import pytest

from ft.application.valuation import UnsupportedQuote, ValuationService
from ft.domain.valuation import AssetKind, AssetRef, ProviderTick, QuoteStatus, ValuationError


class FakeProvider:
    def __init__(self, ticks=None, unsupported=None, errors=None):
        self.ticks = dict(ticks or {})
        self.unsupported = set(unsupported or ())
        self.errors = set(errors or ())

    def raw_quote(self, identity: str, kind: AssetKind):
        if identity in self.errors:
            raise RuntimeError("boom")
        if identity in self.unsupported:
            raise UnsupportedQuote(identity)
        tick = self.ticks.get(identity)
        return tick


def _tick(price, currency="USD", provider="fake"):
    return ProviderTick(
        price=Decimal(str(price)),
        quote_currency=currency,
        observed_at=datetime(2026, 7, 25, tzinfo=timezone.utc),
        provider=provider,
    )


def test_quote_cash_and_security_success():
    service = ValuationService(
        FakeProvider({"aapl.us": _tick("5")}),
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
    )
    cash = service.quote(identity="usd", kind="cash", quantity="3")
    assert cash.status is QuoteStatus.COMPLETE
    assert cash.unit_price == Decimal("1")
    assert cash.market_value == Decimal("3")
    sec = service.quote(AssetRef("aapl.us", AssetKind.SECURITY, Decimal("2")))
    assert sec.status is QuoteStatus.COMPLETE
    assert sec.market_value == Decimal("10")


def test_quote_many_partial_and_unsupported():
    service = ValuationService(
        FakeProvider(
            ticks={"ok": _tick("1")},
            unsupported={"bad"},
            errors={"err"},
        ),
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
    )
    batch = service.quote_many([
        AssetRef("ok", AssetKind.SECURITY),
        AssetRef("bad", AssetKind.SECURITY),
        AssetRef("err", AssetKind.SECURITY),
    ])
    assert [r.status for r in batch.results] == [
        QuoteStatus.COMPLETE,
        QuoteStatus.UNSUPPORTED,
        QuoteStatus.PARTIAL,
    ]
    assert batch.results[1].unit_price is None
    assert batch.results[2].unit_price is None


def test_invalid_quantity_whole_batch():
    service = ValuationService(FakeProvider())
    with pytest.raises(ValuationError):
        service.quote(identity="a", kind="security", quantity="NaN")

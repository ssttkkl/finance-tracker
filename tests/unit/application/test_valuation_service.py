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


def test_quote_many_uses_provider_batch_results_by_input_identity():
    class BatchProvider(FakeProvider):
        def raw_quote_many(self, refs, *, timeout=None):
            return {
                "ok": _tick("1"),
                "missing": None,
                "unsupported": UnsupportedQuote("unsupported"),
                "broken": RuntimeError("boom"),
            }

    service = ValuationService(
        BatchProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    batch = service.quote_many([
        AssetRef("ok", AssetKind.SECURITY),
        AssetRef("missing", AssetKind.SECURITY),
        AssetRef("unsupported", AssetKind.SECURITY),
        AssetRef("broken", AssetKind.SECURITY),
    ])

    by_identity = {result.identity: result for result in batch.results}
    assert by_identity["ok"].status is QuoteStatus.COMPLETE
    assert by_identity["missing"].reason == "empty_provider_response"
    assert by_identity["unsupported"].status is QuoteStatus.UNSUPPORTED
    assert by_identity["broken"].reason == "provider_error"


def test_quote_many_rejects_identity_kind_mismatch_before_provider_batch_call():
    class BatchProvider(FakeProvider):
        def __init__(self):
            super().__init__()
            self.batch_calls = 0

        def raw_quote_many(self, refs, *, timeout=None):
            self.batch_calls += 1
            return {ref.identity: _tick("1") for ref in refs}

    provider = BatchProvider()
    result = ValuationService(provider).quote_many([
        AssetRef("btc", AssetKind.SECURITY),
    ]).results[0]

    assert result.status is QuoteStatus.UNSUPPORTED
    assert result.reason == "identity_kind_mismatch"
    assert provider.batch_calls == 0


def test_invalid_quantity_whole_batch():
    service = ValuationService(FakeProvider())
    with pytest.raises(ValuationError):
        service.quote(identity="a", kind="security", quantity="NaN")

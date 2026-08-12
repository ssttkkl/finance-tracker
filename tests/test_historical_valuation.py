from datetime import datetime, timezone
from decimal import Decimal


def test_valuation_service_reads_a_quote_at_a_historical_boundary():
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import AssetKind, AssetRef, ProviderTick

    boundary = datetime(2026, 7, 24, 12, tzinfo=timezone.utc)

    class Provider:
        def raw_quote(self, identity, kind):
            raise AssertionError("current quote should not be used")

        def raw_quote_at(self, identity, kind, *, at):
            assert (identity, kind, at) == ("aapl.us", AssetKind.SECURITY, boundary)
            return ProviderTick(Decimal("99.5"), "USD", at, "history")

    result = ValuationService(Provider()).quote_at(
        AssetRef("aapl.us", AssetKind.SECURITY, Decimal("10")), at=boundary,
    )

    assert result.unit_price == Decimal("99.5")
    assert result.market_value == Decimal("995.0")
    assert result.quote_currency == "USD"

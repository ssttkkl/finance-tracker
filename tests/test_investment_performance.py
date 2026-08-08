from datetime import datetime, timedelta, timezone
from decimal import Decimal

import pytest


UTC = timezone.utc
START = datetime(2026, 8, 1, tzinfo=UTC)
END = START + timedelta(hours=24)


def test_total_period_pnl_includes_realized_sale_gain_without_treating_trade_as_external_flow():
    from ft.domain.investment_performance import calculate_total_period_pnl

    # 24h ago: securities 1,000 + cash 500; all securities are sold for 1,200.
    assert calculate_total_period_pnl(
        opening_assets=Decimal("1500"),
        closing_assets=Decimal("1700"),
        external_flows=(),
    ) == Decimal("200")


def test_total_period_pnl_excludes_external_deposit_from_return():
    from ft.domain.investment_performance import calculate_total_period_pnl

    assert calculate_total_period_pnl(
        opening_assets=Decimal("1000"),
        closing_assets=Decimal("2100"),
        external_flows=(Decimal("1000"),),
    ) == Decimal("100")


def test_instrument_period_pnl_adjusts_for_buys_and_sells():
    from ft.domain.investment_performance import InstrumentFlow, calculate_instrument_period_pnl

    assert calculate_instrument_period_pnl(
        opening_market_value=Decimal("1000"),
        closing_market_value=Decimal("1320"),
        trade_flows=(
            InstrumentFlow(START + timedelta(hours=4), Decimal("220")),
            InstrumentFlow(START + timedelta(hours=16), Decimal("-120")),
        ),
    ) == Decimal("220")


def test_period_return_uses_time_weighted_capital_and_not_simple_opening_value():
    from ft.domain.investment_performance import PeriodFlow, calculate_period_return

    # A 500 contribution at the midpoint supports a 100 gain on 1,000 opening
    # capital. The Dietz base is 1,000 + 500 * 0.5 = 1,250, so return is 8%.
    assert calculate_period_return(
        pnl=Decimal("100"),
        opening_assets=Decimal("1000"),
        flows=(PeriodFlow(START + timedelta(hours=12), Decimal("500")),),
        period_start=START,
        period_end=END,
    ) == Decimal("0.08")


def test_period_return_is_empty_when_time_weighted_capital_is_not_positive():
    from ft.domain.investment_performance import calculate_period_return

    assert calculate_period_return(
        pnl=Decimal("10"),
        opening_assets=Decimal("0"),
        flows=(),
        period_start=START,
        period_end=END,
    ) is None


def test_period_return_rejects_naive_or_reversed_period_boundaries():
    from ft.domain.investment_performance import calculate_period_return

    with pytest.raises(ValueError, match="timezone-aware"):
        calculate_period_return(
            pnl=Decimal("1"), opening_assets=Decimal("1"), flows=(),
            period_start=datetime(2026, 8, 1), period_end=END,
        )
    with pytest.raises(ValueError, match="period"):
        calculate_period_return(
            pnl=Decimal("1"), opening_assets=Decimal("1"), flows=(),
            period_start=END, period_end=START,
        )

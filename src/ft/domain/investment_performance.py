"""Exact arithmetic for investment-period performance attribution."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Sequence

from ft.domain.decimal import exact_decimal


ZERO = Decimal("0")


@dataclass(frozen=True)
class PeriodFlow:
    """A signed capital flow: deposit/buy is positive, withdrawal/sell is negative."""

    occurred_at: datetime
    amount: Decimal


@dataclass(frozen=True)
class InstrumentFlow:
    """A signed instrument capital flow: buy is positive, sell proceeds negative."""

    occurred_at: datetime
    capital_change: Decimal


def _money(value: object, field: str) -> Decimal:
    return exact_decimal(value, field)


def _validate_period(period_start: datetime, period_end: datetime) -> None:
    if period_start.tzinfo is None or period_end.tzinfo is None:
        raise ValueError("period boundaries must be timezone-aware")
    if period_end <= period_start:
        raise ValueError("period end must be after period start")


def _flow_weight(flow_at: datetime, period_start: datetime, period_end: datetime) -> Decimal:
    if flow_at.tzinfo is None:
        raise ValueError("period flow must be timezone-aware")
    if not period_start <= flow_at <= period_end:
        raise ValueError("period flow must fall within period")
    duration = Decimal(str((period_end - period_start).total_seconds()))
    remaining = Decimal(str((period_end - flow_at).total_seconds()))
    return remaining / duration


def calculate_total_period_pnl(
    *, opening_assets: Decimal, closing_assets: Decimal,
    external_flows: Sequence[Decimal],
) -> Decimal:
    """Return asset change after removing signed external funding flows."""
    opening = _money(opening_assets, "opening_assets")
    closing = _money(closing_assets, "closing_assets")
    flows = tuple(_money(flow, "external_flow") for flow in external_flows)
    result = closing - opening - sum(flows, ZERO)
    return ZERO if result == ZERO else result


def calculate_instrument_period_pnl(
    *, opening_market_value: Decimal, closing_market_value: Decimal,
    trade_flows: Sequence[InstrumentFlow],
    investment_income: Decimal = ZERO, costs: Decimal = ZERO,
) -> Decimal:
    """Return one instrument's period result after trade cash-flow adjustment.

    ``capital_change`` is positive for buy settlement and negative for net sell
    proceeds. Costs are positive amounts and are subtracted once here; callers
    that provide net settlement amounts should leave ``costs`` at zero.
    """
    opening = _money(opening_market_value, "opening_market_value")
    closing = _money(closing_market_value, "closing_market_value")
    flows = tuple(_money(flow.capital_change, "instrument_capital_change") for flow in trade_flows)
    income = _money(investment_income, "investment_income")
    fee_cost = _money(costs, "costs")
    result = closing - opening - sum(flows, ZERO) + income - fee_cost
    return ZERO if result == ZERO else result


def calculate_period_return(
    *, pnl: Decimal, opening_assets: Decimal, flows: Sequence[PeriodFlow],
    period_start: datetime, period_end: datetime,
) -> Decimal | None:
    """Return Modified Dietz rate using exact time-weighted capital.

    A non-positive capital base has no meaningful percentage return and is
    represented as ``None`` rather than an invented zero percentage.
    """
    _validate_period(period_start, period_end)
    opening = _money(opening_assets, "opening_assets")
    gain = _money(pnl, "pnl")
    normalized = tuple(
        (_money(flow.amount, "period_flow"), _flow_weight(flow.occurred_at, period_start, period_end))
        for flow in flows
    )
    capital = opening + sum((amount * weight for amount, weight in normalized), ZERO)
    if capital <= ZERO:
        return None
    result = gain / capital
    return ZERO if result == ZERO else result


__all__ = [
    "InstrumentFlow", "PeriodFlow", "calculate_instrument_period_pnl",
    "calculate_period_return", "calculate_total_period_pnl",
]

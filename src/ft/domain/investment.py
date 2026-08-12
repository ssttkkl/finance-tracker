"""Investment command and portfolio DTOs."""
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal


@dataclass(frozen=True)
class InvestmentCommandDTO:
    record_type: str
    account: str
    currency: str | None = None
    ticker: str = ""
    quantity: Decimal | None = None
    price: Decimal | None = None
    commission: Decimal = Decimal("0")
    commission_asset: str = ""
    from_ticker: str = ""
    to_ticker: str = ""
    to_quantity: Decimal | None = None
    amount: Decimal | None = None
    note: str = ""
    date: str | None = None


@dataclass(frozen=True)
class PortfolioPeriodBaselineDTO:
    account: str
    ticker: str
    occurred_at: datetime


@dataclass(frozen=True)
class PortfolioPositionDTO:
    ticker: str
    shares: Decimal
    total_cost: Decimal
    cost_currency: str
    is_cash: bool
    current_price: Decimal | None = None
    market_value: Decimal | None = None
    profit: Decimal | None = None
    quote_status: str | None = None
    quote_reason: str | None = None
    quote_currency: str | None = None
    quote_observed_at: datetime | None = None
    quote_session: str | None = None
    display_currency: str | None = None
    display_market_value: Decimal | None = None
    fx_rate: Decimal | None = None
    fx_status: str | None = None
    fx_reason: str | None = None
    period_profit: Decimal | None = None
    period_profit_rate: Decimal | None = None
    period_baselines: tuple[PortfolioPeriodBaselineDTO, ...] = ()


@dataclass(frozen=True)
class PortfolioAccountDTO:
    name: str
    currency: str
    positions: tuple[PortfolioPositionDTO, ...]


@dataclass(frozen=True)
class PortfolioDTO:
    accounts: tuple[PortfolioAccountDTO, ...]
    total_market_value: Decimal | None = None
    total_profit: Decimal | None = None
    total_profit_rate: Decimal | None = None
    period_profit: Decimal | None = None
    period_profit_rate: Decimal | None = None
    period_baselines: tuple[PortfolioPeriodBaselineDTO, ...] = ()

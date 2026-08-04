"""Investment command and portfolio DTOs."""
from dataclasses import dataclass
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
    display_currency: str | None = None
    display_market_value: Decimal | None = None
    fx_rate: Decimal | None = None
    fx_status: str | None = None
    fx_reason: str | None = None


@dataclass(frozen=True)
class PortfolioAccountDTO:
    name: str
    currency: str
    positions: tuple[PortfolioPositionDTO, ...]


@dataclass(frozen=True)
class PortfolioDTO:
    accounts: tuple[PortfolioAccountDTO, ...]

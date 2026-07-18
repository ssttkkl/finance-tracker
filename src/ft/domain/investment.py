"""Investment command and portfolio DTOs."""
from dataclasses import dataclass
from decimal import Decimal


@dataclass(frozen=True)
class InvestmentCommandDTO:
    action: str
    account: str
    currency: str | None = None
    ticker: str = ""
    quantity: Decimal | None = None
    price: Decimal | None = None
    commission: Decimal = Decimal("0")
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


@dataclass(frozen=True)
class PortfolioAccountDTO:
    name: str
    currency: str
    positions: tuple[PortfolioPositionDTO, ...]


@dataclass(frozen=True)
class PortfolioDTO:
    accounts: tuple[PortfolioAccountDTO, ...]

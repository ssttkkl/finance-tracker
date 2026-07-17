"""Investment write and portfolio query application services."""
from decimal import Decimal, InvalidOperation

from ft.domain.application import ExportPayload, OperationResult
from ft.domain.investment import (
    InvestmentCommandDTO,
    PortfolioAccountDTO,
    PortfolioDTO,
    PortfolioPositionDTO,
)
from ft.schema import CSV_FIELDS


def _finite_decimal(value, field):
    try:
        result = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ValueError(f"{field} must be decimal-compatible") from exc
    if not result.is_finite():
        raise ValueError(f"{field} must be finite")
    return result


class InvestmentService:
    def __init__(self, *, repository, importer, change_sets):
        self._repository = repository
        self._importer = importer
        self._change_sets = change_sets

    def buy(self, ticker, shares, price, commission, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "buy", account, currency, ticker=ticker,
            quantity=_finite_decimal(shares, "shares"),
            price=_finite_decimal(price, "price"),
            commission=_finite_decimal(commission, "commission"),
            note=note, date=date,
        ))

    def sell(self, ticker, shares, price, commission, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "sell", account, currency, ticker=ticker,
            quantity=_finite_decimal(shares, "shares"),
            price=_finite_decimal(price, "price"),
            commission=_finite_decimal(commission, "commission"),
            note=note, date=date,
        ))

    def swap(self, account, from_ticker, from_quantity, to_ticker, to_quantity,
             currency=None, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "swap", account, currency,
            from_ticker=from_ticker,
            quantity=_finite_decimal(from_quantity, "from_quantity"),
            to_ticker=to_ticker,
            to_quantity=_finite_decimal(to_quantity, "to_quantity"),
            note=note, date=date,
        ))

    def deposit(self, amount, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "deposit", account, currency,
            amount=_finite_decimal(amount, "amount"), note=note, date=date,
        ))

    def withdraw(self, amount, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "withdraw", account, currency,
            amount=_finite_decimal(amount, "amount"), note=note, date=date,
        ))

    def dividend(self, ticker, amount, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "dividend", account, currency, ticker=ticker,
            amount=_finite_decimal(amount, "amount"), note=note, date=date,
        ))

    def checkin_ticker(self, ticker, shares, avg_cost, currency, account,
                       note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "checkin_ticker", account, currency, ticker=ticker,
            quantity=_finite_decimal(shares, "shares"),
            price=_finite_decimal(avg_cost, "avg_cost"), note=note, date=date,
        ))

    def checkin_cash(self, cash, currency, account, note="", date=None):
        return self._execute(InvestmentCommandDTO(
            "checkin_cash", account, currency,
            amount=_finite_decimal(cash, "cash"), note=note, date=date,
        ))

    def convert(self, command) -> OperationResult:
        rows = self._importer.convert(command)
        return OperationResult(
            ok=bool(rows), count=len(rows),
            export=ExportPayload(tuple(rows), fieldnames=tuple(CSV_FIELDS)),
            message="converted" if rows else "no data",
        )

    def append(self, source) -> OperationResult:
        rows = self._importer.read_converted(source)
        if not rows:
            return OperationResult(ok=False, message="CSV 为空")
        count = self._repository.append_investments(rows)
        self._change_sets.stage()
        return OperationResult(ok=True, count=count, message="imported")

    def _execute(self, command):
        result = self._repository.execute(command)
        self._change_sets.stage()
        return result


class PortfolioQueryService:
    def __init__(self, repository, market_data):
        self._repository = repository
        self._market_data = market_data

    def get_portfolio(self) -> PortfolioDTO:
        raw = self._repository.load_portfolio()
        configured = {item.upper() for item in raw.get("configured_currencies", ())}
        accounts = []
        for name, account in raw.get("accounts", {}).items():
            currency = (account.get("currency") or "").upper()
            allowed_cash = {
                item.upper() for item in raw.get("base_currencies", {}).get(name, ())
            }
            positions = account.get("positions", {})
            price_tickers = [
                ticker for ticker, position in positions.items()
                if ticker.upper() not in configured
                and _finite_decimal(position.get("shares", 0), "shares") != 0
            ]
            prices = self._market_data.get_prices(
                price_tickers, quote_currency=currency
            ) if price_tickers else {}
            items = []
            for ticker, position in positions.items():
                shares = _finite_decimal(position.get("shares", 0), "shares")
                if shares == 0:
                    continue
                total_cost = _finite_decimal(position.get("total_cost", 0), "total_cost")
                ticker_currency = ticker.upper()
                is_cash = ticker_currency in allowed_cash
                current_price = Decimal("1") if is_cash else (
                    _finite_decimal(prices[ticker], "price") if ticker in prices else None
                )
                market_value = shares * current_price if current_price is not None else None
                cost_currency = (
                    ticker_currency if ticker_currency in configured
                    else (position.get("cost_currency") or currency).upper()
                )
                items.append(PortfolioPositionDTO(
                    ticker=ticker,
                    shares=shares,
                    total_cost=total_cost,
                    cost_currency=cost_currency,
                    is_cash=is_cash,
                    current_price=current_price,
                    market_value=market_value,
                    profit=market_value - total_cost if market_value is not None else None,
                ))
            accounts.append(PortfolioAccountDTO(name, currency, tuple(items)))
        return PortfolioDTO(tuple(accounts))

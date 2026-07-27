"""Investment write and portfolio query application services."""
from decimal import Decimal

from ft.application.valuation import ValuationService
from ft.domain.decimal import exact_decimal
from ft.domain.investment import (
    InvestmentCommandDTO,
    PortfolioAccountDTO,
    PortfolioDTO,
    PortfolioPositionDTO,
)
from ft.domain.valuation import (
    AssetKind,
    AssetRef,
    FxStatus,
    QuoteStatus,
    ValuationError,
    infer_asset_kind,
    validate_display_currency,
)


def _finite_decimal(value, field):
    return exact_decimal(value, field)


class InvestmentService:
    def __init__(self, *, repository):
        self._repository = repository

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
             currency=None, note="", date=None, commission="0", commission_asset=""):
        commission_dec = _finite_decimal(commission or "0", "commission")
        asset = (commission_asset or "").strip().lower()
        if commission_dec != 0 and not asset:
            asset = str(from_ticker or "").strip().lower()
        return self._execute(InvestmentCommandDTO(
            "swap", account, currency,
            from_ticker=from_ticker,
            quantity=_finite_decimal(from_quantity, "from_quantity"),
            to_ticker=to_ticker,
            to_quantity=_finite_decimal(to_quantity, "to_quantity"),
            commission=commission_dec,
            commission_asset=asset,
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

    def _execute(self, command):
        return self._repository.execute(command)


class PortfolioQueryService:
    """Portfolio mark-to-market using ValuationService (+ optional FX display)."""

    def __init__(self, repository, valuation: ValuationService, *, fx_rates=None):
        self._repository = repository
        self._valuation = valuation
        self._fx_rates = fx_rates

    def get_portfolio(self, *, display_currency: str | None = None) -> PortfolioDTO:
        display = validate_display_currency(display_currency)
        raw = self._repository.load_portfolio()
        configured = {item.upper() for item in raw.get("configured_currencies", ())}
        accounts = []
        for name, account in raw.get("accounts", {}).items():
            currency = (account.get("currency") or "").upper()
            # Prefer per-account bases; fall back to workspace-configured currencies when
            # snapshot keys (e.g. legacy UUID) do not match account.name in base_currencies.
            allowed_cash = {
                item.upper() for item in raw.get("base_currencies", {}).get(name, ())
            }
            if not allowed_cash:
                allowed_cash = set(configured)
            positions = account.get("positions", {})
            items = []
            for ticker, position in positions.items():
                shares = _finite_decimal(position.get("shares", 0), "shares")
                if shares == 0:
                    continue
                total_cost = _finite_decimal(position.get("total_cost", 0), "total_cost")
                ticker_currency = ticker.upper()
                is_cash = ticker_currency in allowed_cash
                cost_currency = (
                    ticker_currency if ticker_currency in configured
                    else (position.get("cost_currency") or currency).upper()
                )
                kind = infer_asset_kind(
                    ticker,
                    cash_tickers=allowed_cash,
                    configured_currencies=configured - allowed_cash,
                )
                quote_status = None
                quote_reason = None
                quote_currency = None
                current_price = None
                market_value = None
                if kind is None:
                    # configured non-cash (e.g. USDT as currency unit) — no market path
                    quote_status = QuoteStatus.UNSUPPORTED.value
                    quote_reason = "unsupported_identity"
                else:
                    result = self._valuation.quote(
                        AssetRef(identity=str(ticker), kind=kind, quantity=shares)
                    )
                    quote_status = result.status.value
                    quote_reason = result.reason
                    quote_currency = result.quote_currency
                    if result.status in {QuoteStatus.COMPLETE, QuoteStatus.STALE}:
                        current_price = result.unit_price
                        market_value = result.market_value
                        if is_cash and current_price is None:
                            current_price = Decimal("1")
                            market_value = shares
                    elif is_cash:
                        current_price = Decimal("1")
                        market_value = shares
                        quote_status = QuoteStatus.COMPLETE.value
                        quote_reason = "ok"
                        quote_currency = ticker_currency

                profit = None
                if (
                    market_value is not None
                    and cost_currency
                    and quote_currency
                    and cost_currency.upper() == quote_currency.upper()
                ):
                    profit = market_value - total_cost

                display_mv = None
                fx_rate = None
                fx_status = None
                fx_reason = None
                display_ccy = None
                if display is not None:
                    display_ccy = display
                    display_mv, fx_rate, fx_status, fx_reason = self._convert_display(
                        market_value, quote_currency, display
                    )

                items.append(PortfolioPositionDTO(
                    ticker=ticker,
                    shares=shares,
                    total_cost=total_cost,
                    cost_currency=cost_currency,
                    is_cash=is_cash,
                    current_price=current_price,
                    market_value=market_value,
                    profit=profit,
                    quote_status=quote_status,
                    quote_reason=quote_reason,
                    quote_currency=quote_currency,
                    display_currency=display_ccy,
                    display_market_value=display_mv,
                    fx_rate=fx_rate,
                    fx_status=fx_status,
                    fx_reason=fx_reason,
                ))
            accounts.append(PortfolioAccountDTO(name, currency, tuple(items)))
        return PortfolioDTO(tuple(accounts))

    def _convert_display(self, market_value, quote_currency, display: str):
        if market_value is None or not quote_currency:
            return None, None, FxStatus.NOT_APPLICABLE.value, "currency_unspecified"
        base = str(quote_currency).upper()
        if base == display:
            return market_value, Decimal("1"), FxStatus.COMPLETE.value, "ok"
        if self._fx_rates is None:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        rate = self._fx_rates.get_mid(base, display)
        if rate is None:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        try:
            rate_d = exact_decimal(rate, "fx_rate")
        except ValueError:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        if rate_d <= 0:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        return market_value * rate_d, rate_d, FxStatus.COMPLETE.value, "ok"

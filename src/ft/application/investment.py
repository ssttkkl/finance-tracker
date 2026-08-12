"""Investment write and portfolio query application services."""
from collections import defaultdict
from dataclasses import dataclass, replace
from datetime import datetime, timedelta, timezone
import time
from decimal import Decimal
from queue import Empty, Queue
from threading import Thread
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from ft.application.valuation import ValuationService
from ft.domain.decimal import exact_decimal
from ft.domain.investment import (
    InvestmentCommandDTO,
    PortfolioAccountDTO,
    PortfolioDTO,
    PortfolioPeriodBaselineDTO,
    PortfolioPositionDTO,
)
from ft.domain.investment_performance import (
    InstrumentFlow,
    PeriodFlow,
    calculate_instrument_period_pnl,
    calculate_period_return,
    calculate_total_period_pnl,
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


def _event_at(event):
    value = event.get("occurred_at")
    if isinstance(value, datetime):
        result = value
    else:
        result = datetime.fromisoformat(str(value))
    if result.tzinfo is None:
        raise ValueError("investment event timestamp must be timezone-aware")
    return result


def _event_marker(event):
    return _event_at(event), int(event.get("_period_sequence", 0))


def _period_bounds(now: datetime, period: str, timezone_name: str | None = None):
    local_now = now
    if timezone_name:
        try:
            local_now = now.astimezone(ZoneInfo(timezone_name))
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise ValueError("invalid investment timezone") from exc
    if period == "24h":
        return now - timedelta(hours=24), now
    if period == "30d":
        return now - timedelta(days=30), now
    if period == "90d":
        return now - timedelta(days=90), now
    if period == "365d":
        return now - timedelta(days=365), now
    if period == "week_to_date":
        start = (local_now - timedelta(days=local_now.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)
        return start, now
    if period == "month_to_date":
        return local_now.replace(day=1, hour=0, minute=0, second=0, microsecond=0), now
    if period == "year_to_date":
        return local_now.replace(month=1, day=1, hour=0, minute=0, second=0, microsecond=0), now
    raise ValueError("invalid investment period")


def _is_current_cash(current, account: str, ticker: str, cash_keys=()) -> bool:
    position = current.get((account, ticker.lower()))
    return bool((position is not None and position.is_cash) or (account, ticker.lower()) in cash_keys)


def _instrument_flows(events, account_name: str, ticker: str, current, cash_keys=(), *, after=None):
    trade_flows = []
    income = Decimal("0")
    costs = Decimal("0")
    for event in events:
        if str(event.get("account") or event.get("account_name") or "") != account_name:
            continue
        if after is not None and _event_marker(event) <= after:
            continue
        event_ticker_from = str(event.get("from_ticker") or "").lower()
        event_ticker_to = str(event.get("to_ticker") or "").lower()
        record_type = event.get("record_type")
        if record_type == "trade":
            commission = _finite_decimal(event.get("commission") or 0, "commission")
            if event_ticker_to == ticker and _is_current_cash(current, account_name, event_ticker_from, cash_keys):
                trade_flows.append(InstrumentFlow(_event_at(event), _finite_decimal(event.get("from_amount") or 0, "from_amount")))
                costs += commission
            elif event_ticker_from == ticker and _is_current_cash(current, account_name, event_ticker_to, cash_keys):
                trade_flows.append(InstrumentFlow(_event_at(event), -_finite_decimal(event.get("to_amount") or 0, "to_amount")))
                costs += commission
        elif record_type == "income" and event_ticker_from == ticker:
            income += _finite_decimal(event.get("to_amount") or 0, "to_amount")
    return tuple(trade_flows), income, costs


@dataclass(frozen=True)
class _PeriodPerformanceContext:
    events: tuple
    period_start: datetime
    period_end: datetime
    cash_keys: set
    net_changes: dict
    snapshots: dict


@dataclass(frozen=True)
class _SnapshotBaseline:
    marker: tuple[datetime, int]
    quantity: Decimal

    @property
    def occurred_at(self):
        return self.marker[0]


@dataclass(frozen=True)
class _HistoricalQuoteRequest:
    key: tuple[str, str]
    ticker: str
    quantity: Decimal
    quote_at: datetime


class _HistoricalQuotePrefetch:
    """Bounded historical quote fan-out for one portfolio query."""

    MAX_WORKERS = 8

    def __init__(self, valuation, requests, *, deadline: float | None, monotonic):
        requests = tuple(requests)
        self._valuation = valuation
        self._deadline = deadline
        self._monotonic = monotonic
        self._expected = {request.key for request in requests}
        self._results = {}
        self._completed = Queue()
        self._pending = Queue()
        for request in requests:
            self._pending.put(request)
        for _ in range(min(self.MAX_WORKERS, len(requests))):
            Thread(target=self._read_next, daemon=True).start()

    def _read_next(self):
        while True:
            try:
                request = self._pending.get_nowait()
            except Empty:
                return
            try:
                if self._deadline is not None and self._monotonic() >= self._deadline:
                    result = None
                else:
                    kind = infer_asset_kind(request.ticker, cash_tickers=set(), configured_currencies=set())
                    result = None if kind is None else self._valuation.quote_at(
                        AssetRef(identity=request.ticker, kind=kind, quantity=request.quantity),
                        at=request.quote_at,
                    )
            except Exception:
                result = None
            finally:
                self._pending.task_done()
            self._completed.put((request.key, result))

    def result_for(self, key, deadline: float | None):
        if key not in self._expected:
            return None
        while key not in self._results:
            if deadline is None:
                request_key, result = self._completed.get()
            else:
                remaining = deadline - self._monotonic()
                if remaining <= 0:
                    return None
                try:
                    request_key, result = self._completed.get(timeout=remaining)
                except Empty:
                    return None
            self._results[request_key] = result
        return self._results[key]


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

    def __init__(
        self, repository, valuation: ValuationService, *, fx_rates=None,
        query_deadline_seconds: float | None = 2.0, monotonic=None, clock=None,
    ):
        self._repository = repository
        self._valuation = valuation
        self._fx_rates = fx_rates
        self._query_deadline_seconds = query_deadline_seconds
        self._monotonic = monotonic or time.monotonic
        self._clock = clock or (lambda: datetime.now(timezone.utc))

    def get_holdings(self) -> PortfolioDTO:
        """Return the local portfolio snapshot without contacting valuation sources."""
        raw = self._repository.load_portfolio()
        configured = {item.upper() for item in raw.get("configured_currencies", ())}
        accounts = []
        for name, account in raw.get("accounts", {}).items():
            currency = (account.get("currency") or "").upper()
            allowed_cash = {
                item.upper() for item in raw.get("base_currencies", {}).get(name, ())
            }
            if not allowed_cash:
                allowed_cash = set(configured)
            positions = []
            for ticker, position in account.get("positions", {}).items():
                shares = _finite_decimal(position.get("shares", 0), "shares")
                if shares == 0:
                    continue
                total_cost = _finite_decimal(position.get("total_cost", 0), "total_cost")
                ticker_currency = ticker.upper()
                cost_currency = (
                    ticker_currency if ticker_currency in configured
                    else (position.get("cost_currency") or currency).upper()
                )
                positions.append(PortfolioPositionDTO(
                    ticker=ticker,
                    shares=shares,
                    total_cost=total_cost,
                    cost_currency=cost_currency,
                    is_cash=ticker_currency in allowed_cash,
                ))
            accounts.append(PortfolioAccountDTO(name, currency, tuple(positions)))
        return PortfolioDTO(accounts=tuple(accounts))

    def get_portfolio(
        self, *, display_currency: str | None = None, period: str = "24h", timezone: str | None = None,
        on_update=None,
    ) -> PortfolioDTO:
        display = validate_display_currency(display_currency)
        clock_now = self._clock()
        if clock_now.tzinfo is None:
            raise ValueError("portfolio clock must be timezone-aware")
        _period_bounds(clock_now, period, timezone)
        deadline = None if self._query_deadline_seconds is None else self._monotonic() + self._query_deadline_seconds
        raw = self._repository.load_portfolio()
        configured = {item.upper() for item in raw.get("configured_currencies", ())}
        pending_accounts = []
        requests = {}
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
            pending_positions = []
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
                request_key = None
                if kind is not None and kind is not AssetKind.CASH:
                    ref = AssetRef(identity=str(ticker), kind=kind, quantity=shares)
                    request_key = (kind, str(ticker).strip().lower())
                    requests.setdefault(request_key, ref)

                pending_positions.append((
                    ticker, shares, total_cost, cost_currency, is_cash, kind, request_key,
                ))
            pending_accounts.append((name, currency, pending_positions))

        base_accounts = self._base_accounts(pending_accounts)
        period_context = self._period_context(
            raw, period=period, timezone=timezone, now=clock_now,
        )
        historical_quotes = _HistoricalQuotePrefetch(
            self._valuation,
            self._historical_quote_requests(period_context, base_accounts)
            if deadline is None or self._monotonic() < deadline else (),
            deadline=deadline,
            monotonic=self._monotonic,
        )
        quote_results = self._quote_requests(requests, deadline)
        accounts = []
        for name, currency, pending_positions in pending_accounts:
            items = []
            for ticker, shares, total_cost, cost_currency, is_cash, kind, request_key in pending_positions:
                quote_status = None
                quote_reason = None
                quote_currency = None
                current_price = None
                market_value = None
                if kind is None:
                    quote_status = QuoteStatus.UNSUPPORTED.value
                    quote_reason = "unsupported_identity"
                elif kind is AssetKind.CASH:
                    current_price = Decimal("1")
                    market_value = shares
                    quote_status = QuoteStatus.COMPLETE.value
                    quote_reason = "ok"
                    quote_currency = str(ticker).upper()
                else:
                    result = quote_results[request_key]
                    quote_status = result.status.value
                    quote_reason = result.reason
                    quote_currency = result.quote_currency
                    if result.status in {QuoteStatus.COMPLETE, QuoteStatus.STALE}:
                        current_price = result.unit_price
                        market_value = result.unit_price * shares if result.unit_price is not None else None

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
                        market_value, quote_currency, display, deadline=deadline,
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
        total_market_value, total_profit, total_profit_rate = self._current_totals(
            accounts, display_currency=display,
        )
        if callable(on_update):
            try:
                on_update(PortfolioDTO(
                    accounts=tuple(accounts),
                    total_market_value=total_market_value,
                    total_profit=total_profit,
                    total_profit_rate=total_profit_rate,
                ))
            except Exception:
                pass
        period_positions, period_profit, period_rate, period_baselines = self._period_performance(
            accounts, context=period_context, historical_quotes=historical_quotes, deadline=deadline,
        )
        accounts = tuple(
            PortfolioAccountDTO(
                account.name,
                account.currency,
                tuple(
                    replace(
                        position,
                        period_profit=period_positions.get((account.name, position.ticker.lower()), (None, None, ()))[0],
                        period_profit_rate=period_positions.get((account.name, position.ticker.lower()), (None, None, ()))[1],
                        period_baselines=period_positions.get((account.name, position.ticker.lower()), (None, None, ()))[2],
                    )
                    for position in account.positions
                ),
            )
            for account in accounts
        )
        return PortfolioDTO(
            accounts=accounts,
            total_market_value=total_market_value,
            total_profit=total_profit,
            total_profit_rate=total_profit_rate,
            period_profit=period_profit,
            period_profit_rate=period_rate,
            period_baselines=period_baselines,
        )

    @staticmethod
    def _base_accounts(pending_accounts):
        return tuple(
            PortfolioAccountDTO(
                name,
                currency,
                tuple(
                    PortfolioPositionDTO(
                        ticker=ticker,
                        shares=shares,
                        total_cost=total_cost,
                        cost_currency=cost_currency,
                        is_cash=is_cash,
                    )
                    for ticker, shares, total_cost, cost_currency, is_cash, _, _ in pending_positions
                ),
            )
            for name, currency, pending_positions in pending_accounts
        )

    def _period_context(self, raw, *, period: str, timezone: str | None, now: datetime):
        all_events = tuple(raw.get("investment_events", ()))
        if now.tzinfo is None:
            raise ValueError("portfolio clock must be timezone-aware")
        period_start, period_end = _period_bounds(now, period, timezone)
        events = tuple(
            dict(event, _period_sequence=sequence)
            for sequence, event in enumerate(sorted(
                (event for event in all_events if period_start <= _event_at(event) < period_end),
                key=_event_at,
            ))
        )
        cash_keys = set()
        for account_name, account in raw.get("accounts", {}).items():
            base_currencies = raw.get("base_currencies", {}).get(account_name, ())
            if not base_currencies:
                base_currencies = (account.get("currency") or "",)
            for ticker in base_currencies:
                if ticker:
                    cash_keys.add((account_name, str(ticker).lower()))
        net_changes = defaultdict(lambda: Decimal("0"))
        snapshots = {}
        for event in events:
            account = str(event.get("account") or event.get("account_name") or "")
            from_ticker = str(event.get("from_ticker") or "").lower()
            to_ticker = str(event.get("to_ticker") or "").lower()
            from_amount = _finite_decimal(event.get("from_amount") or 0, "from_amount")
            to_amount = _finite_decimal(event.get("to_amount") or 0, "to_amount")
            record_type = event.get("record_type")
            if record_type == "snapshot":
                ticker = to_ticker or from_ticker or str(event.get("currency") or "").lower()
                if ticker:
                    snapshots[(account, ticker)] = _SnapshotBaseline(
                        marker=_event_marker(event), quantity=to_amount,
                    )
            elif record_type == "trade":
                if from_ticker:
                    net_changes[(account, from_ticker)] -= from_amount
                if to_ticker:
                    net_changes[(account, to_ticker)] += to_amount
                commission = _finite_decimal(event.get("commission") or 0, "commission")
                commission_asset = str(event.get("commission_asset") or "").lower()
                if commission and commission_asset:
                    net_changes[(account, commission_asset)] -= commission
            elif record_type == "income":
                # Dividend source tickers identify the income, but the source
                # position itself is not reduced by the cash amount.
                if to_ticker:
                    net_changes[(account, to_ticker)] += to_amount
            elif record_type in {"funding", "expense", "reversal", "subscription", "adjustment"}:
                if from_ticker:
                    net_changes[(account, from_ticker)] -= from_amount
                if to_ticker:
                    net_changes[(account, to_ticker)] += to_amount

        return _PeriodPerformanceContext(
            events=events,
            period_start=period_start,
            period_end=period_end,
            cash_keys=cash_keys,
            net_changes=dict(net_changes),
            snapshots=snapshots,
        )

    @staticmethod
    def _historical_quote_requests(context, accounts):
        current = {
            (account.name, position.ticker.lower()): position
            for account in accounts for position in account.positions
        }
        requests = []
        for key in sorted(set(current) | set(context.net_changes) | set(context.snapshots)):
            account_name, ticker = key
            position = current.get(key)
            q1 = position.shares if position is not None else Decimal("0")
            baseline = context.snapshots.get(key)
            q0 = baseline.quantity if baseline is not None else q1 - context.net_changes.get(key, Decimal("0"))
            if q0 <= 0 or _is_current_cash(current, account_name, ticker, context.cash_keys):
                continue
            requests.append(_HistoricalQuoteRequest(
                key=key,
                ticker=ticker,
                quantity=q0,
                quote_at=baseline.occurred_at if baseline is not None else context.period_start,
            ))
        return tuple(requests)

    def _period_performance(self, accounts, *, context, historical_quotes, deadline: float | None = None):
        events = context.events
        period_start = context.period_start
        period_end = context.period_end
        cash_keys = context.cash_keys
        net_changes = context.net_changes
        current = {
            (account.name, position.ticker.lower()): position
            for account in accounts for position in account.positions
        }

        keys = set(current) | set(net_changes) | set(context.snapshots)
        opening_assets = Decimal("0")
        closing_assets = Decimal("0")
        external_flows = []
        period_positions = {}
        period_baselines = []
        complete = True
        for key in sorted(keys):
            if deadline is not None and self._monotonic() >= deadline:
                complete = False
                break
            account_name, ticker = key
            position = current.get(key)
            q1 = position.shares if position is not None else Decimal("0")
            baseline = context.snapshots.get(key)
            q0 = baseline.quantity if baseline is not None else q1 - net_changes.get(key, Decimal("0"))
            baseline_dto = None if baseline is None else PortfolioPeriodBaselineDTO(
                account=account_name, ticker=ticker, occurred_at=baseline.occurred_at,
            )
            if baseline_dto is not None:
                period_baselines.append(baseline_dto)
            if position is not None and position.market_value is not None:
                closing_assets += position.market_value
            if _is_current_cash(current, account_name, ticker, cash_keys):
                opening_assets += q0
                continue
            if q0 < 0:
                complete = False
                continue
            opening_value = Decimal("0")
            if q0 != 0:
                historical = self._historical_result_value(
                    historical_quotes.result_for(key, deadline), position,
                )
                if historical is None:
                    complete = False
                    continue
                opening_value = historical
            opening_assets += opening_value
            if position is None:
                continue
            trade_flows, income, costs = _instrument_flows(
                events, account_name, ticker, current, cash_keys,
                after=baseline.marker if baseline is not None else None,
            )
            if position.market_value is None:
                complete = False
                continue
            pnl = calculate_instrument_period_pnl(
                opening_market_value=opening_value,
                closing_market_value=position.market_value,
                trade_flows=trade_flows,
                investment_income=income,
                costs=costs,
            )
            rate = calculate_period_return(
                pnl=pnl,
                opening_assets=opening_value,
                flows=tuple(PeriodFlow(flow.occurred_at, flow.capital_change) for flow in trade_flows),
                period_start=baseline.occurred_at if baseline is not None else period_start,
                period_end=period_end,
            )
            period_positions[key] = (pnl, rate, () if baseline_dto is None else (baseline_dto,))

        for event in events:
            if event.get("record_type") != "funding":
                continue
            amount = _finite_decimal(
                event.get("to_amount") if _finite_decimal(event.get("to_amount") or 0, "to_amount") > 0
                else -_finite_decimal(event.get("from_amount") or 0, "from_amount"),
                "external_flow",
            )
            external_flows.append(PeriodFlow(_event_at(event), amount))

        if not complete:
            return period_positions, None, None, tuple(period_baselines)
        total_pnl = calculate_total_period_pnl(
            opening_assets=opening_assets,
            closing_assets=closing_assets,
            external_flows=tuple(flow.amount for flow in external_flows),
        )
        total_rate = None if period_baselines else calculate_period_return(
            pnl=total_pnl, opening_assets=opening_assets, flows=tuple(external_flows),
            period_start=period_start, period_end=period_end,
        )
        return period_positions, total_pnl, total_rate, tuple(period_baselines)

    @staticmethod
    def _historical_result_value(result, position):
        if result is None:
            return None
        if result.status is not QuoteStatus.COMPLETE or result.market_value is None:
            return None
        if position is not None and position.cost_currency and result.quote_currency:
            if position.cost_currency.upper() != result.quote_currency.upper():
                return None
        return result.market_value

    @staticmethod
    def _current_totals(accounts, *, display_currency):
        values = []
        profits = []
        currencies = set()
        for account in accounts:
            for position in account.positions:
                value = position.display_market_value if display_currency else position.market_value
                if value is None:
                    return None, None, None
                values.append(value)
                currencies.add((position.display_currency if display_currency else position.quote_currency) or position.cost_currency)
                if not position.is_cash and position.profit is not None:
                    if display_currency and position.display_market_value is not None and position.market_value:
                        profits.append(position.profit * (position.display_market_value / position.market_value))
                    else:
                        profits.append(position.profit)
        if not values or len(currencies) > 1:
            return None, None, None
        total_market = sum(values, Decimal("0"))
        total_profit = sum(profits, Decimal("0"))
        rate = None if total_market == 0 else total_profit / total_market
        return total_market, total_profit, rate

    def _quote_requests(self, requests, deadline: float | None):
        grouped = defaultdict(list)
        for request_key, ref in requests.items():
            grouped[request_key[0]].append((request_key, ref))
        if not grouped:
            return {}

        results = {}
        completed = Queue()
        pending = list(grouped.values())

        def read_group(grouped_items):
            try:
                batch = self._valuation.quote_many(
                    [ref for _, ref in grouped_items],
                    timeout=None if deadline is None else max(deadline - self._monotonic(), 0),
                )
            except Exception:
                batch = None
            completed.put((grouped_items, batch))

        while pending and (deadline is None or deadline > self._monotonic()):
            grouped_items = pending.pop(0)
            Thread(target=read_group, args=(grouped_items,), daemon=True).start()

        in_flight = len(grouped) - len(pending)
        while in_flight:
            remaining = None if deadline is None else max(deadline - self._monotonic(), 0)
            if remaining is not None and remaining <= 0:
                break
            try:
                grouped_items, batch = completed.get(timeout=remaining)
            except Empty:
                break
            in_flight -= 1
            if batch is None:
                results.update({key: self._provider_error_result(ref) for key, ref in grouped_items})
            else:
                results.update({
                    key: result for (key, _), result in zip(grouped_items, batch.results, strict=True)
                })
        for request_key, ref in requests.items():
            results.setdefault(request_key, self._deadline_result(ref))
        return results

    @staticmethod
    def _deadline_result(ref: AssetRef):
        from ft.domain.valuation import QuoteResult
        return QuoteResult(
            identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
            quantity=ref.quantity, reason="query_deadline_exceeded",
        )

    @staticmethod
    def _provider_error_result(ref: AssetRef):
        from ft.domain.valuation import QuoteResult
        return QuoteResult(
            identity=ref.identity, kind=ref.kind, status=QuoteStatus.PARTIAL,
            quantity=ref.quantity, reason="provider_error",
        )

    def _convert_display(self, market_value, quote_currency, display: str, *, deadline: float | None = None):
        if market_value is None or not quote_currency:
            return None, None, FxStatus.NOT_APPLICABLE.value, "currency_unspecified"
        base = str(quote_currency).upper()
        if base == display:
            return market_value, Decimal("1"), FxStatus.COMPLETE.value, "ok"
        if self._fx_rates is None:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        rate = self._run_until_deadline(
            lambda: self._fx_rates.get_mid(base, display), deadline,
        )
        if rate is None:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        try:
            rate_d = exact_decimal(rate, "fx_rate")
        except ValueError:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        if rate_d <= 0:
            return None, None, FxStatus.PARTIAL.value, "fx_unavailable"
        return market_value * rate_d, rate_d, FxStatus.COMPLETE.value, "ok"

    def _run_until_deadline(self, operation, deadline: float | None):
        if deadline is None:
            try:
                return operation()
            except (AttributeError, ValuationError):
                return None
        remaining = deadline - self._monotonic()
        if remaining <= 0:
            return None
        completed = Queue(maxsize=1)

        def run():
            try:
                completed.put(operation())
            except Exception:
                completed.put(None)

        Thread(target=run, daemon=True).start()
        try:
            return completed.get(timeout=remaining)
        except Empty:
            return None

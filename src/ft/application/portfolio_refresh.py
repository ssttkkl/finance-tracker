"""In-process SSE refresh coordination for the local portfolio Web surface."""
from __future__ import annotations

from dataclasses import dataclass, field, replace
from threading import Condition, Event, Thread
import time

from decimal import Decimal

from ft.domain.investment import PortfolioDTO, PortfolioPositionDTO


@dataclass(frozen=True)
class PortfolioRefreshScope:
    display_currency: str | None
    period: str
    timezone: str | None


@dataclass(frozen=True)
class PortfolioStreamUpdate:
    version: int
    kind: str
    portfolio: PortfolioDTO | None = None


@dataclass
class _RefreshEntry:
    snapshot: PortfolioDTO | None = None
    version: int = 0
    latest: PortfolioStreamUpdate | None = None
    subscribers: int = 0
    requested: bool = True
    manual_requested: bool = False
    request_generation: int = 0
    next_refresh_at: float = 0.0
    failure_count: int = 0


class PortfolioRefreshCoordinator:
    """Continuously refresh active portfolio scopes without blocking SSE clients.

    This is intentionally process-local: the Web runtime is a single local FastAPI
    process. It owns no accounting facts and only retains the last successful view
    model needed to avoid replacing a usable value with a transient provider miss.
    """

    def __init__(
        self, portfolio_service, *, refresh_interval_seconds: float = 5.0,
        heartbeat_seconds: float = 15.0, max_backoff_seconds: float = 60.0,
        monotonic=None,
    ):
        self._portfolio_service = portfolio_service
        self._refresh_interval = max(float(refresh_interval_seconds), 0.1)
        self._heartbeat = max(float(heartbeat_seconds), 0.1)
        self._max_backoff = max(float(max_backoff_seconds), self._refresh_interval)
        self._monotonic = monotonic or time.monotonic
        self._condition = Condition()
        self._entries: dict[PortfolioRefreshScope, _RefreshEntry] = {}
        self._stopping = Event()
        self._worker: Thread | None = None

    def start(self) -> None:
        with self._condition:
            if self._worker is not None and self._worker.is_alive():
                return
            self._stopping.clear()
            self._worker = Thread(target=self._run, name="portfolio-refresh", daemon=True)
            self._worker.start()

    def stop(self) -> None:
        self._stopping.set()
        with self._condition:
            self._condition.notify_all()
            worker = self._worker
        if worker is not None:
            # The worker may still be using the application service (and its
            # database session).  Lifespan teardown must not dispose that
            # dependency underneath an in-flight refresh.
            worker.join()

    def request_refresh(
        self, *, display_currency: str | None = None, period: str = "24h", timezone: str | None = None,
    ) -> None:
        scope = self._scope(display_currency, period, timezone)
        self.start()
        with self._condition:
            entry = self._entries.setdefault(scope, _RefreshEntry())
            entry.requested = True
            entry.manual_requested = True
            entry.request_generation += 1
            self._condition.notify_all()

    def subscribe(
        self, *, display_currency: str | None = None, period: str = "24h", timezone: str | None = None,
        last_version: int | None = None,
    ):
        scope = self._scope(display_currency, period, timezone)
        self.start()
        with self._condition:
            entry = self._entries.setdefault(scope, _RefreshEntry())
            entry.subscribers += 1
            entry.requested = True
            entry.request_generation += 1
            self._condition.notify_all()
        delivered = -1 if last_version is None else last_version
        try:
            while not self._stopping.is_set():
                update = None
                with self._condition:
                    current = self._entries[scope]
                    if current.latest is not None and current.latest.version > delivered:
                        update = current.latest
                    else:
                        self._condition.wait(timeout=self._heartbeat)
                if update is not None:
                    delivered = update.version
                    yield update
                else:
                    yield PortfolioStreamUpdate(version=delivered, kind="heartbeat")
        finally:
            with self._condition:
                entry = self._entries.get(scope)
                if entry is not None:
                    entry.subscribers = max(entry.subscribers - 1, 0)
                self._condition.notify_all()

    def _run(self) -> None:
        while not self._stopping.is_set():
            selected = None
            with self._condition:
                selected = self._next_entry()
                if selected is None:
                    self._condition.wait(timeout=self._next_wait())
                    continue
                scope, entry = selected
                generation = entry.request_generation
            self._refresh(scope, generation)

    def _next_entry(self):
        now = self._monotonic()
        eligible = [
            (scope, entry)
            for scope, entry in self._entries.items()
            if entry.requested or (entry.subscribers and entry.next_refresh_at <= now)
        ]
        if not eligible:
            return None
        return min(eligible, key=lambda item: (
            0 if item[1].manual_requested else 1 if item[1].requested else 2,
            item[1].next_refresh_at,
        ))

    def _next_wait(self) -> float:
        now = self._monotonic()
        upcoming = [entry.next_refresh_at for entry in self._entries.values() if entry.subscribers]
        return self._heartbeat if not upcoming else max(min(upcoming) - now, 0.01)

    def _refresh(self, scope: PortfolioRefreshScope, generation: int) -> None:
        emitted = None

        def publish_progress(snapshot: PortfolioDTO) -> None:
            nonlocal emitted
            emitted = snapshot
            self._publish(scope, snapshot)

        try:
            snapshot = self._portfolio_service.get_portfolio(
                display_currency=scope.display_currency,
                period=scope.period,
                timezone=scope.timezone,
                on_update=publish_progress,
            )
        except Exception:
            self._publish_failure(scope, generation)
            return
        if emitted != snapshot:
            self._publish(scope, snapshot)
        with self._condition:
            entry = self._entries[scope]
            if entry.request_generation == generation:
                entry.requested = False
                entry.manual_requested = False
            entry.failure_count = 0
            entry.next_refresh_at = self._monotonic() + self._refresh_interval
            self._condition.notify_all()

    def _publish(self, scope: PortfolioRefreshScope, incoming: PortfolioDTO) -> None:
        with self._condition:
            entry = self._entries[scope]
            entry.snapshot = _merge_last_success(entry.snapshot, incoming)
            entry.version += 1
            entry.latest = PortfolioStreamUpdate(entry.version, "portfolio", entry.snapshot)
            self._condition.notify_all()

    def _publish_failure(self, scope: PortfolioRefreshScope, generation: int) -> None:
        with self._condition:
            entry = self._entries[scope]
            entry.version += 1
            entry.latest = PortfolioStreamUpdate(entry.version, "refresh_error")
            entry.failure_count += 1
            if entry.request_generation == generation:
                entry.requested = False
                entry.manual_requested = False
            entry.next_refresh_at = self._monotonic() + min(
                self._refresh_interval * (2 ** min(entry.failure_count, 4)), self._max_backoff,
            )
            self._condition.notify_all()

    @staticmethod
    def _scope(display_currency: str | None, period: str, timezone: str | None) -> PortfolioRefreshScope:
        normalized_currency = str(display_currency or "").strip().upper() or None
        normalized_timezone = str(timezone or "").strip() or None
        return PortfolioRefreshScope(normalized_currency, str(period or "24h"), normalized_timezone)


def _merge_last_success(previous: PortfolioDTO | None, incoming: PortfolioDTO) -> PortfolioDTO:
    if previous is None:
        return incoming
    prior_positions = {
        (account.name, account.currency, position.ticker, position.cost_currency): position
        for account in previous.accounts
        for position in account.positions
    }
    accounts = tuple(
        replace(account, positions=tuple(
            _merge_position(
                prior_positions.get((account.name, account.currency, position.ticker, position.cost_currency)),
                position,
            )
            for position in account.positions
        ))
        for account in incoming.accounts
    )
    total_market_value, total_profit, total_profit_rate = _totals(accounts)
    return replace(
        incoming,
        accounts=accounts,
        total_market_value=incoming.total_market_value if incoming.total_market_value is not None else total_market_value if total_market_value is not None else previous.total_market_value,
        total_profit=incoming.total_profit if incoming.total_profit is not None else total_profit if total_profit is not None else previous.total_profit,
        total_profit_rate=incoming.total_profit_rate if incoming.total_profit_rate is not None else total_profit_rate if total_profit_rate is not None else previous.total_profit_rate,
        period_profit=incoming.period_profit if incoming.period_profit is not None else previous.period_profit,
        period_profit_rate=incoming.period_profit_rate if incoming.period_profit_rate is not None else previous.period_profit_rate,
        period_baselines=incoming.period_baselines or previous.period_baselines,
    )


def _merge_position(previous: PortfolioPositionDTO | None, incoming: PortfolioPositionDTO) -> PortfolioPositionDTO:
    if previous is None:
        return incoming
    values = {}
    if incoming.current_price is None or incoming.market_value is None:
        current_price = incoming.current_price if incoming.current_price is not None else previous.current_price
        quote_currency = incoming.quote_currency or previous.quote_currency
        market_value = current_price * incoming.shares if current_price is not None else previous.market_value
        values.update({
            "current_price": current_price,
            "market_value": market_value,
            "quote_status": previous.quote_status,
            "quote_reason": previous.quote_reason,
            "quote_currency": quote_currency,
            "quote_observed_at": incoming.quote_observed_at or previous.quote_observed_at,
            "quote_session": incoming.quote_session or previous.quote_session,
        })
        if quote_currency and incoming.cost_currency.upper() == quote_currency.upper() and market_value is not None:
            values["profit"] = market_value - incoming.total_cost
        elif incoming.profit is None:
            values["profit"] = previous.profit
        display_currency = incoming.display_currency or previous.display_currency
        fx_rate = incoming.fx_rate if incoming.fx_rate is not None else previous.fx_rate
        if display_currency and fx_rate is not None and market_value is not None:
            values["display_currency"] = display_currency
            values["display_market_value"] = market_value * fx_rate
            values["fx_rate"] = fx_rate
            values["fx_status"] = previous.fx_status
            values["fx_reason"] = previous.fx_reason
        elif incoming.display_market_value is None:
            values.update({
                "display_currency": display_currency,
                "display_market_value": previous.display_market_value,
                "fx_rate": fx_rate,
                "fx_status": previous.fx_status,
                "fx_reason": previous.fx_reason,
            })
    if incoming.period_profit is None:
        values["period_profit"] = previous.period_profit
        values["period_profit_rate"] = previous.period_profit_rate
    if not incoming.period_baselines:
        values["period_baselines"] = previous.period_baselines
    return replace(incoming, **values)


def _totals(accounts) -> tuple[Decimal | None, Decimal | None, Decimal | None]:
    positions = [position for account in accounts for position in account.positions]
    use_display_currency = any(position.display_currency is not None for position in positions)
    values = []
    profits = []
    currencies = set()
    for position in positions:
        value = position.display_market_value if use_display_currency else position.market_value
        if value is None:
            return None, None, None
        values.append(value)
        currencies.add((position.display_currency if use_display_currency else position.quote_currency) or position.cost_currency)
        if not position.is_cash and position.profit is not None:
            if use_display_currency and position.display_market_value is not None and position.market_value:
                profits.append(position.profit * (position.display_market_value / position.market_value))
            else:
                profits.append(position.profit)
    if not values or len(currencies) > 1:
        return None, None, None
    market_value = sum(values, Decimal("0"))
    profit = sum(profits, Decimal("0"))
    return market_value, profit, None if market_value == 0 else profit / market_value

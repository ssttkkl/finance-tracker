from __future__ import annotations

from collections import deque
from decimal import Decimal
from threading import Event


def _portfolio(*, price: str | None, shares: str = "1"):
    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO

    market_value = None if price is None else Decimal(price) * Decimal(shares)
    return PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
        PortfolioPositionDTO(
            ticker="AAPL.US", shares=Decimal(shares), total_cost=Decimal("5"),
            cost_currency="USD", is_cash=False, current_price=None if price is None else Decimal(price),
            market_value=market_value, profit=None if market_value is None else market_value - Decimal("5"),
            quote_status="partial" if price is None else "complete",
            quote_reason="provider_error" if price is None else "ok", quote_currency="USD" if price else None,
        ),
    )),))


class _PortfolioService:
    def __init__(self, snapshots):
        self.snapshots = deque(snapshots)
        self.calls = []

    def get_portfolio(self, *, display_currency=None, period="24h", timezone=None, on_update=None):
        self.calls.append((display_currency, period, timezone))
        snapshot = self.snapshots.popleft()
        if on_update is not None:
            on_update(snapshot)
        return snapshot


def _next_update(stream):
    for _ in range(50):
        update = next(stream)
        if update.kind != "heartbeat":
            return update
    raise AssertionError("coordinator did not publish a portfolio update")


def test_refresh_coordinator_streams_updates_and_keeps_last_successful_quote():
    from ft.application.portfolio_refresh import PortfolioRefreshCoordinator

    service = _PortfolioService((_portfolio(price="10"), _portfolio(price=None, shares="2")))
    coordinator = PortfolioRefreshCoordinator(service, refresh_interval_seconds=60, heartbeat_seconds=0.01)
    coordinator.start()
    stream = coordinator.subscribe(period="24h")
    try:
        first = _next_update(stream)
        assert first.kind == "portfolio"
        assert first.portfolio.accounts[0].positions[0].current_price == Decimal("10")

        coordinator.request_refresh(period="24h")
        second = _next_update(stream)

        assert second.kind == "portfolio"
        position = second.portfolio.accounts[0].positions[0]
        assert position.shares == Decimal("2")
        assert position.current_price == Decimal("10")
        assert position.market_value == Decimal("20")
        assert position.profit == Decimal("15")
        assert second.portfolio.total_market_value == Decimal("20")
        assert len(service.calls) == 2
    finally:
        stream.close()
        coordinator.stop()


def test_refresh_coordinator_replays_latest_snapshot_after_reconnect_without_polling():
    from ft.application.portfolio_refresh import PortfolioRefreshCoordinator

    service = _PortfolioService((_portfolio(price="10"),))
    coordinator = PortfolioRefreshCoordinator(service, refresh_interval_seconds=60, heartbeat_seconds=0.01)
    coordinator.start()
    stream = coordinator.subscribe(period="24h")
    try:
        first = _next_update(stream)
        stream.close()

        reconnected = coordinator.subscribe(period="24h", last_version=first.version - 1)
        try:
            replay = _next_update(reconnected)
        finally:
            reconnected.close()

        assert replay.kind == "portfolio"
        assert replay.version == first.version
        assert len(service.calls) == 1
    finally:
        coordinator.stop()


def test_refresh_coordinator_schedules_a_manual_scope_before_active_refreshes():
    from ft.application.portfolio_refresh import PortfolioRefreshCoordinator, PortfolioRefreshScope, _RefreshEntry

    coordinator = PortfolioRefreshCoordinator(_PortfolioService(()), refresh_interval_seconds=60)
    active_scope = PortfolioRefreshScope(None, "24h", "Asia/Shanghai")
    manual_scope = PortfolioRefreshScope("CNY", "30d", "Asia/Shanghai")
    coordinator._entries = {
        active_scope: _RefreshEntry(subscribers=1, next_refresh_at=0),
        manual_scope: _RefreshEntry(subscribers=1, requested=True, manual_requested=True, next_refresh_at=0),
    }

    selected, _entry = coordinator._next_entry()

    assert selected == manual_scope


def test_refresh_coordinator_heartbeats_while_a_slow_provider_runs_and_stops_its_worker():
    from ft.application.portfolio_refresh import PortfolioRefreshCoordinator

    started = Event()
    release = Event()

    class SlowService:
        def get_portfolio(self, **_kwargs):
            started.set()
            release.wait(timeout=1)
            return _portfolio(price="10")

    coordinator = PortfolioRefreshCoordinator(SlowService(), refresh_interval_seconds=60, heartbeat_seconds=0.01)
    coordinator.start()
    worker = coordinator._worker
    assert worker is not None
    stream = coordinator.subscribe(period="24h")
    try:
        assert next(stream).kind == "heartbeat"
        assert started.wait(timeout=0.2)
        assert next(stream).kind == "heartbeat"
        release.set()
        assert _next_update(stream).kind == "portfolio"
    finally:
        stream.close()
        coordinator.stop()

    assert not worker.is_alive()

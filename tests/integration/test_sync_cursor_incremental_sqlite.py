"""SQLite contract for cursor-driven incremental connector sync (T040)."""
from __future__ import annotations

from datetime import datetime, timezone

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.uow import RelationalUnitOfWork, create_schema
from ft.application.sync_service import SyncService
from ft.domain.accounts import AccountDTO
from ft.domain.connector_port import ConnectorResult


class _Connector:
    source_type = "binance_api"

    def __init__(self, events, cursor):
        self._events = events
        self._cursor = cursor
        self.calls = []

    def fetch_trades(self, *, since=None):
        self.calls.append(since)
        return ConnectorResult(events=self._events, next_cursor=self._cursor, raw_count=len(self._events))


def _event(record_id: str) -> dict:
    return {
        "record_type": "trade", "record_subtype": "security", "currency": "USD", "occurred_at": datetime(2026, 7, 26, tzinfo=timezone.utc),
        "from_ticker": "usd", "from_amount": "100", "to_ticker": "btc", "to_amount": "1",
        "commission": "0", "commission_asset": "", "record_id": record_id,
        "source_payload": {"id": record_id},
    }


def test_incremental_cursor_is_saved_used_and_ignored_by_full(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'cursor.db'}")
    try:
        create_schema(engine)
        sessions = create_session_factory(engine)
        ensure_workspace(sessions, "sync-workspace")
        uow = RelationalUnitOfWork(sessions, "sync-workspace")
        with uow as entered:
            entered.accounts.add(AccountDTO("Binance", "crypto"))
            entered.commit()
        service = SyncService(uow)
        first = _Connector([_event("one")], "100")
        assert service.sync(provider="binance", account_name="Binance", connector=first).ok
        second = _Connector([_event("two")], "200")
        assert service.sync(provider="binance", account_name="Binance", connector=second).ok
        assert second.calls == ["100"]
        full = _Connector([], None)
        assert service.sync(
            provider="binance", account_name="Binance", connector=full, full=True,
        ).ok
        assert full.calls == [None]
    finally:
        engine.dispose()

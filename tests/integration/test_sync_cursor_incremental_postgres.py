"""PostgreSQL contract for cursor-driven incremental connector sync (T041)."""
from __future__ import annotations

import os
from datetime import datetime, timezone

import pytest


@pytest.mark.skipif(not os.environ.get("FT_TEST_POSTGRES_URL"), reason="set FT_TEST_POSTGRES_URL")
def test_incremental_cursor_contract_on_postgres():
    """Repeat the SQLite incremental-cursor contract against a dedicated PG DB."""
    from conftest import reset_postgres_schema
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.adapters.relational.uow import RelationalUnitOfWork, create_schema
    from ft.application.sync_service import SyncService
    from ft.domain.accounts import AccountDTO
    from ft.domain.connector_port import ConnectorResult

    url = os.environ["FT_TEST_POSTGRES_URL"]
    reset_postgres_schema(url)
    engine = create_relational_engine(url)
    try:
        create_schema(engine)
        sessions = create_session_factory(engine)
        ensure_workspace(sessions, "sync-workspace")
        uow = RelationalUnitOfWork(sessions, "sync-workspace")
        with uow as entered:
            entered.accounts.add(AccountDTO("Binance", "crypto"))
            entered.commit()

        class Connector:
            source_type = "binance_api"

            def __init__(self, cursor):
                self.calls, self.cursor = [], cursor

            def fetch_trades(self, *, since=None):
                self.calls.append(since)
                return ConnectorResult([{
                    "record_type": "swap", "currency": "USD",
                    "occurred_at": datetime(2026, 7, 26, tzinfo=timezone.utc),
                    "from_ticker": "usd", "from_amount": "100",
                    "to_ticker": "btc", "to_amount": "1",
                    "commission": "0", "commission_asset": "", "record_id": self.cursor,
                    "source_payload": {"id": self.cursor},
                }], self.cursor, 1)

        service = SyncService(uow)
        assert service.sync(provider="binance", account_name="Binance", connector=Connector("100")).ok
        second = Connector("200")
        assert service.sync(provider="binance", account_name="Binance", connector=second).ok
        assert second.calls == ["100"]
    finally:
        engine.dispose()
        reset_postgres_schema(url)

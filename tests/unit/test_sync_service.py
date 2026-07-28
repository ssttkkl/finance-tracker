"""Unit tests for SyncService orchestration (T006, T039)."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from unittest.mock import MagicMock, patch

import pytest

from ft.domain.connector_port import ConnectorAuthError, ConnectorError, ConnectorResult


class FakeConnector:
    """Minimal ConnectorPort implementation for testing."""
    def __init__(self, events=None, next_cursor=None, raw_count=0, error=None):
        self._events = events or []
        self._next_cursor = next_cursor
        self._raw_count = raw_count
        self._error = error
        self.fetch_calls = []

    @property
    def source_type(self):
        return "test_api"

    def fetch_trades(self, *, since=None):
        self.fetch_calls.append(since)
        if self._error:
            raise self._error
        return ConnectorResult(
            events=self._events,
            next_cursor=self._next_cursor,
            raw_count=self._raw_count,
        )


def _make_event(record_id="t1", ticker="eth"):
    return {
        "action": "swap",
        "account": "",
        "currency": "USD",
        "occurred_at": datetime(2024, 1, 1, tzinfo=timezone.utc),
        "from_ticker": "usdt",
        "from_amount": "100",
        "to_ticker": ticker,
        "to_amount": "1",
        "commission": "0",
        "commission_asset": "",
        "note": f"test trade {record_id}",
        "record_id": record_id,
        "source_payload": {"id": record_id},
    }


@pytest.fixture
def sync_runtime(tmp_path):
    """Build a real SQLite-backed sync runtime."""
    from pathlib import Path
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.config import StorageSettings
    from ft.runtime import build_services
    from ft.application.sync_service import SyncService

    root = Path(__file__).parents[2]
    url = f"sqlite+pysqlite:///{tmp_path / 'sync_test.db'}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "test-workspace")

    # Create a crypto account
    from ft.adapters.relational.uow import RelationalUnitOfWork
    from ft.domain.accounts import AccountDTO
    uow = RelationalUnitOfWork(sessions, "test-workspace")
    with uow as u:
        u.accounts.add(AccountDTO(name="TestCrypto", type="crypto"))
        u.commit()

    service = SyncService(uow)
    return service, uow, engine


class TestSyncServiceHappyPath:
    def test_sync_with_events(self, sync_runtime):
        service, uow, engine = sync_runtime
        events = [_make_event("t1"), _make_event("t2", "btc")]
        connector = FakeConnector(events=events, next_cursor="12345", raw_count=2)
        result = service.sync(
            provider="binance",
            account_name="TestCrypto",
            connector=connector,
        )
        assert result.ok
        assert result.count == 2
        assert "2" in result.message

    def test_sync_empty(self, sync_runtime):
        service, uow, engine = sync_runtime
        connector = FakeConnector(events=[], raw_count=0)
        result = service.sync(
            provider="binance",
            account_name="TestCrypto",
            connector=connector,
        )
        assert result.ok
        assert result.count == 0

    def test_idempotent_skip(self, sync_runtime):
        service, uow, engine = sync_runtime
        events = [_make_event("t1")]
        connector = FakeConnector(events=events, next_cursor="100", raw_count=1)
        r1 = service.sync(provider="binance", account_name="TestCrypto", connector=connector)
        assert r1.ok and r1.count == 1

        # Re-sync same events
        connector2 = FakeConnector(events=events, next_cursor="100", raw_count=1)
        r2 = service.sync(provider="binance", account_name="TestCrypto", connector=connector2)
        assert r2.ok and r2.count == 0


class TestSyncServiceErrors:
    def test_unknown_provider(self, sync_runtime):
        service, _, _ = sync_runtime
        result = service.sync(provider="unknown", account_name="x", connector=FakeConnector())
        assert not result.ok
        assert "未知的同步数据源" in result.message

    def test_account_not_found(self, sync_runtime):
        service, _, _ = sync_runtime
        result = service.sync(provider="binance", account_name="NoSuch", connector=FakeConnector())
        assert not result.ok
        assert "找不到账户" in result.message

    def test_wrong_account_type(self, sync_runtime):
        service, uow, _ = sync_runtime
        from ft.domain.accounts import AccountDTO
        with uow as u:
            u.accounts.add(AccountDTO(name="CashAccount", type="cash"))
            u.commit()
        result = service.sync(provider="binance", account_name="CashAccount", connector=FakeConnector())
        assert not result.ok
        assert "crypto" in result.message

    def test_connector_auth_error(self, sync_runtime):
        service, _, _ = sync_runtime
        connector = FakeConnector(error=ConnectorAuthError("bad key"))
        result = service.sync(provider="binance", account_name="TestCrypto", connector=connector)
        assert not result.ok
        assert "认证失败" in result.message

    def test_connector_error(self, sync_runtime):
        service, _, _ = sync_runtime
        connector = FakeConnector(error=ConnectorError("timeout"))
        result = service.sync(provider="binance", account_name="TestCrypto", connector=connector)
        assert not result.ok



def _get_account_id(uow, name):
    """Resolve account name to surrogate PK for test assertions."""
    from sqlalchemy import select
    from ft.adapters.relational.models import AccountModel
    with uow as u:
        aid = u._state().session.scalar(
            select(AccountModel.id).where(
                AccountModel.workspace_id == uow.workspace_id,
                AccountModel.name == name,
            )
        )
        u.rollback()
    return aid

class TestSyncServiceCursor:
    def test_cursor_saved_after_sync(self, sync_runtime):
        service, uow, _ = sync_runtime
        events = [_make_event("t1")]
        connector = FakeConnector(events=events, next_cursor="99999", raw_count=1)
        service.sync(provider="binance", account_name="TestCrypto", connector=connector)

        # Read cursor back
        account_id = _get_account_id(uow, "TestCrypto")
        with uow as u:
            cursor = u.imports.get_sync_cursor(
                account_id=account_id, source_type="binance_api",
            )
            u.rollback()
        assert cursor == "99999"

    def test_cursor_passed_to_connector(self, sync_runtime):
        service, uow, _ = sync_runtime
        # First sync saves cursor
        events = [_make_event("t1")]
        c1 = FakeConnector(events=events, next_cursor="1000", raw_count=1)
        service.sync(provider="binance", account_name="TestCrypto", connector=c1)

        # Second sync should pass cursor
        c2 = FakeConnector(events=[], raw_count=0)
        service.sync(provider="binance", account_name="TestCrypto", connector=c2)
        assert c2.fetch_calls == ["1000"]

    def test_full_ignores_cursor(self, sync_runtime):
        service, uow, _ = sync_runtime
        events = [_make_event("t1")]
        c1 = FakeConnector(events=events, next_cursor="1000", raw_count=1)
        service.sync(provider="binance", account_name="TestCrypto", connector=c1)

        c2 = FakeConnector(events=[], raw_count=0)
        service.sync(provider="binance", account_name="TestCrypto", connector=c2, full=True)
        assert c2.fetch_calls == [None]


def test_later_chunk_failure_rolls_back_events_snapshot_and_cursor(sync_runtime, monkeypatch):
    """A logical processing chunk is never a persistence boundary."""
    service, uow, _ = sync_runtime
    events = [_make_event("first"), _make_event("second", "btc")]
    connector = FakeConnector(events=events, next_cursor="42", raw_count=2)

    from ft.application.sync_service import apply_investment_event

    def fail_second(snapshot, event, **kwargs):
        if event["record_id"] == "second":
            raise ValueError("invalid upstream record")
        return apply_investment_event(snapshot, event, **kwargs)

    monkeypatch.setattr("ft.application.sync_service.apply_investment_event", fail_second)
    result = service.sync(
        provider="binance",
        account_name="TestCrypto",
        connector=connector,
        batch_size=1,
    )

    assert not result.ok
    account_id = _get_account_id(uow, "TestCrypto")
    with uow as entered:
        assert entered.investments.list() == []
        assert entered.snapshot.load()["accounts"]["security"] == {}
        assert entered.imports.get_sync_cursor(
            account_id=account_id, source_type="binance_api",
        ) is None
        entered.rollback()


def test_stale_cursor_retries_once_from_full_history(sync_runtime):
    """A rejected saved cursor falls back once to a full connector fetch."""
    service, _, _ = sync_runtime
    seed = FakeConnector(events=[_make_event("seed")], next_cursor="100", raw_count=1)
    assert service.sync(provider="binance", account_name="TestCrypto", connector=seed).ok

    class StaleCursorConnector:
        source_type = "binance_api"

        def __init__(self):
            self.fetch_calls = []

        def fetch_trades(self, *, since=None):
            self.fetch_calls.append(since)
            if since == "100":
                raise ConnectorError("cursor expired")
            return ConnectorResult(events=[], next_cursor=None, raw_count=0)

    connector = StaleCursorConnector()
    result = service.sync(
        provider="binance", account_name="TestCrypto", connector=connector,
    )
    assert result.ok
    assert connector.fetch_calls == ["100", None]

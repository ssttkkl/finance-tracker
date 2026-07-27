"""Integration test: exchange sync end-to-end on PostgreSQL (T021)."""
from __future__ import annotations

import json
import os
from datetime import datetime, timezone
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import select

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.sync_service import SyncService
from ft.domain.accounts import AccountDTO
from ft.domain.connector_port import ConnectorResult
from ft.adapters.relational.models import AccountModel


FIXTURES = Path(__file__).parents[1] / "fixtures"


class RawCcxtClient:
    def fetch_my_trades(self, symbol=None, since=None, limit=None):
        return [{"id": "trade", "symbol": "XBT/USDT", "side": "buy", "amount": "1", "price": "10", "cost": "10", "timestamp": 1000, "fee": {"cost": "0", "currency": "USDT"}}]

    def fetch_ledger(self, code=None, since=None, limit=None, params=None):
        return [
            {"id": "deposit", "timestamp": 2000, "currency": "USDT", "amount": "100", "info": {"type": "deposit"}},
            {"id": "reward", "timestamp": 3000, "currency": "USDT", "amount": "10", "info": {"type": "reward"}, "fee": {"cost": "1", "currency": "USDT"}},
            {"id": "move", "timestamp": 4000, "currency": "USDT", "amount": "50", "info": {"type": "transfer"}},
        ]


def _raw_ccxt_connector():
    from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
    return CcxtExchangeConnector(provider="kraken", credentials={}, _client=RawCcxtClient())


def _pg_url():
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("FT_TEST_POSTGRES_URL not set")
    return url


class FakeExchangeConnector:
    def __init__(self, trades_file):
        with open(trades_file) as f:
            raw = json.load(f)
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        class FakeClient:
            def fetch_my_trades(self, symbol=None, since=None, limit=None):
                return raw
            def fetch_ledger(self, code=None, since=None, limit=None, params=None):
                return []
        c = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeClient())
        result = c.fetch_trades()
        self._events = result.events
        self._next_cursor = result.next_cursor
        self._raw_count = result.raw_count

    @property
    def source_type(self):
        return "binance_api"

    def fetch_trades(self, *, since=None):
        return ConnectorResult(
            events=self._events,
            next_cursor=self._next_cursor,
            raw_count=self._raw_count,
        )


@pytest.fixture
def pg_sync():
    from conftest import reset_postgres_schema
    url = _pg_url()
    reset_postgres_schema(url)
    root = Path(__file__).parents[2]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ws")
    uow = RelationalUnitOfWork(sessions, "ws")
    with uow as u:
        u.accounts.add(AccountDTO(name="Binance", type="crypto"))
        u.commit()
    service = SyncService(uow)
    yield service, uow
    engine.dispose()
    reset_postgres_schema(url)


class TestExchangeSyncPostgres:
    def test_sync_and_idempotent(self, pg_sync):
        service, uow = pg_sync
        connector = FakeExchangeConnector(FIXTURES / "ccxt_trades.json")
        r1 = service.sync(provider="binance", account_name="Binance", connector=connector)
        assert r1.ok and r1.count == 4
        connector2 = FakeExchangeConnector(FIXTURES / "ccxt_trades.json")
        r2 = service.sync(provider="binance", account_name="Binance", connector=connector2)
        assert r2.ok and r2.count == 0

    def test_ledger_events_are_persisted_idempotently(self, pg_sync):
        service, uow = pg_sync
        at = datetime(2026, 7, 26, tzinfo=timezone.utc)
        common = {"commission": "0", "commission_asset": "", "occurred_at": at, "source_payload": {}}
        events = [
            dict(common, action="deposit", from_ticker="", from_amount="0", to_ticker="usd", to_amount="100", record_id="dep"),
            dict(common, action="dividend", from_ticker="", from_amount="0", to_ticker="usd", to_amount="10", record_id="reward"),
            dict(common, action="fee", from_ticker="usd", from_amount="1", to_ticker="", to_amount="0", record_id="reward:fee"),
            dict(common, action="transfer", from_ticker="usd", from_amount="50", to_ticker="", to_amount="0", record_id="move"),
        ]
        class LedgerConnector:
            source_type = "binance_api"
            def fetch_trades(self, *, since=None):
                return ConnectorResult(events=events, next_cursor="200", raw_count=len(events))
        assert service.sync(provider="binance", account_name="Binance", connector=LedgerConnector()).count == 4
        assert service.sync(provider="binance", account_name="Binance", connector=LedgerConnector()).count == 0
        with uow as u:
            shares = u.snapshot.load()["accounts"]["security"]["Binance"]["positions"]["usd"]["shares"]
            u.rollback()
        assert shares == "109"

    def test_raw_ccxt_trade_and_ledger_are_atomic_and_idempotent(self, pg_sync):
        service, uow = pg_sync
        assert service.sync(provider="kraken", account_name="Binance", connector=_raw_ccxt_connector()).count == 5
        assert service.sync(provider="kraken", account_name="Binance", connector=_raw_ccxt_connector()).count == 0
        with uow as u:
            assert u.snapshot.load()["accounts"]["security"]["Binance"]["positions"]["btc"]["shares"] == "1"
            u.rollback()

    def test_raw_ccxt_late_replay_failure_rolls_back_everything(self, pg_sync, monkeypatch):
        service, uow = pg_sync
        from ft.application.sync_service import apply_investment_event
        def fail_fee(snapshot, event, **kwargs):
            if event["record_id"] == "reward:fee":
                raise ValueError("late replay failure")
            return apply_investment_event(snapshot, event, **kwargs)
        monkeypatch.setattr("ft.application.sync_service.apply_investment_event", fail_fee)
        result = service.sync(provider="kraken", account_name="Binance", connector=_raw_ccxt_connector(), batch_size=1)
        assert not result.ok
        with uow as u:
            assert u.investments.list() == []
            assert u.snapshot.load()["accounts"]["security"] == {}
            account_id = u._state().session.scalar(
                select(AccountModel.id).where(AccountModel.name == "Binance")
            )
            assert u.imports.get_sync_cursor(account_id=account_id, source_type="kraken_api") is None
            u.rollback()

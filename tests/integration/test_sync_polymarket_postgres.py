"""Integration test: Polymarket sync end-to-end on PostgreSQL (T030)."""
from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.application.sync_service import SyncService
from ft.domain.accounts import AccountDTO
from ft.domain.connector_port import ConnectorResult


FIXTURES = Path(__file__).parents[1] / "fixtures"


def _pg_url():
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("FT_TEST_POSTGRES_URL not set")
    return url


def _make_pm_connector():
    with open(FIXTURES / "polymarket_activities.json") as f:
        activities = json.load(f)
    valid = [a for a in activities[:4]]
    from ft.adapters.connectors.polymarket import PolymarketConnector
    def mock_fetch(url):
        return valid
    return PolymarketConnector(
        credentials={"proxy_wallet": "0x" + "a" * 40},
        _fetch_fn=mock_fetch,
    )


def _make_activity_connector(activities):
    from ft.adapters.connectors.polymarket import PolymarketConnector

    return PolymarketConnector(
        credentials={"proxy_wallet": "0x" + "a" * 40},
        _fetch_fn=lambda url: activities,
    )


_FUNDER = "0x" + "a" * 40
_EXTERNAL = "0x" + "b" * 40
_TX = "0x" + "c" * 64


def _topic(address):
    return "0x" + "0" * 24 + address[2:]


def _load_activity_trade():
    with open(FIXTURES / "polymarket_activities.json") as f:
        return json.load(f)[0]


def _chain_connector(*, malformed=False):
    from ft.adapters.connectors.polymarket import PolymarketConnector
    log = {
        "address": "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB",
        "topics": ["0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef", _topic(_EXTERNAL), _topic(_FUNDER)],
        "data": hex(1_234_567), "blockNumber": hex(10), "logIndex": hex(7), "transactionHash": _TX,
    }
    def rpc(method, params):
        if method == "eth_blockNumber": return hex(12)
        if method == "eth_getBlockByNumber": return {"timestamp": hex(1_777_667_210)}
        if method == "eth_call":
            if malformed: return "not-hex"
            return hex(1_234_567)
        raise AssertionError(method)

    return PolymarketConnector(credentials={"proxy_wallet": _FUNDER}, _fetch_fn=lambda _url: [_load_activity_trade()], _rpc_fetch_fn=rpc)


@pytest.fixture
def pg_pm_sync():
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
        u.accounts.add(AccountDTO(name="Polymarket", type="security"))
        u.commit()
    service = SyncService(uow)
    yield service, uow
    engine.dispose()
    reset_postgres_schema(url)


class TestPolymarketSyncPostgres:
    def test_sync_and_idempotent(self, pg_pm_sync):
        service, uow = pg_pm_sync
        c1 = _make_pm_connector()
        r1 = service.sync(provider="polymarket", account_name="Polymarket", connector=c1)
        assert r1.ok and r1.count == 3
        c2 = _make_pm_connector()
        r2 = service.sync(provider="polymarket", account_name="Polymarket", connector=c2)
        assert r2.ok and r2.count == 0

    def test_redeem_and_yield_are_imported_idempotently(self, pg_pm_sync):
        service, uow = pg_pm_sync
        activities = [
            {"type": "REDEEM", "slug": "market", "outcome": "Yes", "size": "2", "usdcSize": "2", "timestamp": 1_700_000_000, "transactionHash": "redeem-1"},
            {"type": "YIELD", "usdcSize": "0.5", "timestamp": 1_700_000_001, "transactionHash": "yield-1"},
            {"type": "DEPOSIT", "timestamp": 1_700_000_002},
        ]
        first = service.sync(provider="polymarket", account_name="Polymarket", connector=_make_activity_connector(activities))
        second = service.sync(provider="polymarket", account_name="Polymarket", connector=_make_activity_connector(activities))
        assert first.ok and first.count == 2
        assert second.ok and second.count == 0
        with uow as u:
            actions = sorted(e["action"] for e in u.investments.list())
            u.rollback()
        assert actions == ["dividend", "swap"]

    def test_activity_and_pusd_checkin_are_atomic_idempotent_and_preserve_market_position(self, pg_pm_sync):
        service, uow = pg_pm_sync
        first = service.sync(provider="polymarket", account_name="Polymarket", connector=_chain_connector())
        assert first.ok and first.count == 2
        with uow as u:
            account_id = service._resolve_account_id(u, "Polymarket")
            events = u.investments.list()
            cursor = u.imports.get_sync_cursor(account_id=account_id, source_type="polymarket_api")
            snapshot = u.snapshot.load()
            u.rollback()
        assert len(events) == 2
        assert "checkin:12" in {event["record_id"] for event in events}
        assert cursor == str(_load_activity_trade()["timestamp"] + 1)
        positions = snapshot["accounts"]["security"]["Polymarket"]["positions"]
        assert positions["usd"]["shares"] == "1.234567"
        assert positions["pm:will-trump-win-2024:yes"]["shares"] == "100"
        assert service.sync(provider="polymarket", account_name="Polymarket", connector=_chain_connector()).count == 0

    def test_pusd_balance_failure_keeps_activity_and_cursor_unwritten(self, pg_pm_sync):
        service, uow = pg_pm_sync
        result = service.sync(provider="polymarket", account_name="Polymarket", connector=_chain_connector(malformed=True))
        assert not result.ok
        with uow as u:
            account_id = service._resolve_account_id(u, "Polymarket")
            assert u.investments.list() == []
            assert u.imports.get_sync_cursor(account_id=account_id, source_type="polymarket_api") is None
            u.rollback()

    def test_successful_empty_chain_scan_persists_compound_cursor(self, pg_pm_sync):
        service, uow = pg_pm_sync

        class EmptyConnector:
            source_type = "polymarket_api"

            def fetch_trades(self, *, since=None):
                return ConnectorResult([], '{"activity_since":1782226943,"pusd_block":90000000}', 0)

        assert service.sync(provider="polymarket", account_name="Polymarket", connector=EmptyConnector()).ok
        with uow as u:
            account_id = service._resolve_account_id(u, "Polymarket")
            assert u.imports.get_sync_cursor(account_id=account_id, source_type="polymarket_api") == '{"activity_since":1782226943,"pusd_block":90000000}'
            u.rollback()

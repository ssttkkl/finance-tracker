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

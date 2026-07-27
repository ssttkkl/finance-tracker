"""Integration test: sync_cursors CRUD on SQLite (T007)."""
from __future__ import annotations

from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from sqlalchemy import select

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.models import AccountModel
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.domain.accounts import AccountDTO


def _account_id(uow, name):
    with uow as u:
        aid = u._state().session.scalar(
            select(AccountModel.id).where(
                AccountModel.workspace_id == uow.workspace_id,
                AccountModel.name == name,
            )
        )
        u.rollback()
    return aid


@pytest.fixture
def sqlite_uow(tmp_path):
    root = Path(__file__).parents[2]
    url = f"sqlite+pysqlite:///{tmp_path / 'cursor_test.db'}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ws")
    uow = RelationalUnitOfWork(sessions, "ws")
    with uow as u:
        u.accounts.add(AccountDTO(name="TestAccount", type="crypto"))
        u.commit()
    yield uow
    engine.dispose()


class TestSyncCursorSQLite:
    def test_no_cursor_returns_none(self, sqlite_uow):
        aid = _account_id(sqlite_uow, "TestAccount")
        with sqlite_uow as u:
            cursor = u.imports.get_sync_cursor(account_id=aid, source_type="binance_api")
            u.rollback()
        assert cursor is None

    def test_upsert_creates_cursor(self, sqlite_uow):
        aid = _account_id(sqlite_uow, "TestAccount")
        with sqlite_uow as u:
            u.imports.upsert_sync_cursor(account_id=aid, source_type="binance_api", cursor_value="12345")
            u.commit()
        with sqlite_uow as u:
            cursor = u.imports.get_sync_cursor(account_id=aid, source_type="binance_api")
            u.rollback()
        assert cursor == "12345"

    def test_upsert_updates_cursor(self, sqlite_uow):
        aid = _account_id(sqlite_uow, "TestAccount")
        with sqlite_uow as u:
            u.imports.upsert_sync_cursor(account_id=aid, source_type="binance_api", cursor_value="100")
            u.commit()
        with sqlite_uow as u:
            u.imports.upsert_sync_cursor(account_id=aid, source_type="binance_api", cursor_value="200")
            u.commit()
        with sqlite_uow as u:
            cursor = u.imports.get_sync_cursor(account_id=aid, source_type="binance_api")
            u.rollback()
        assert cursor == "200"

    def test_different_sources_independent(self, sqlite_uow):
        aid = _account_id(sqlite_uow, "TestAccount")
        with sqlite_uow as u:
            u.imports.upsert_sync_cursor(account_id=aid, source_type="binance_api", cursor_value="AAA")
            u.imports.upsert_sync_cursor(account_id=aid, source_type="polymarket_api", cursor_value="BBB")
            u.commit()
        with sqlite_uow as u:
            c1 = u.imports.get_sync_cursor(account_id=aid, source_type="binance_api")
            c2 = u.imports.get_sync_cursor(account_id=aid, source_type="polymarket_api")
            u.rollback()
        assert c1 == "AAA"
        assert c2 == "BBB"

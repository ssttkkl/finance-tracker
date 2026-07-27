"""Integration test: sync_cursors CRUD on PostgreSQL (T008)."""
from __future__ import annotations

import os
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config

from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.uow import RelationalUnitOfWork
from ft.domain.accounts import AccountDTO

def _get_account_id(uow, name):
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



def _pg_url():
    url = os.environ.get("FT_TEST_POSTGRES_URL")
    if not url:
        pytest.skip("FT_TEST_POSTGRES_URL not set")
    return url


@pytest.fixture
def pg_uow():
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
        u.accounts.add(AccountDTO(name="PGAccount", type="crypto"))
        u.commit()
    yield uow
    engine.dispose()
    reset_postgres_schema(url)


class TestSyncCursorPostgres:
    def test_no_cursor_returns_none(self, pg_uow):
        with pg_uow as u:
            _aid = _get_account_id(pg_uow, "PGAccount")
            cursor = u.imports.get_sync_cursor(
                account_id=_aid, source_type="binance_api",
            )
            u.rollback()
        assert cursor is None

    def test_upsert_creates_and_updates(self, pg_uow):
        with pg_uow as u:
            _aid = _get_account_id(pg_uow, "PGAccount")
            u.imports.upsert_sync_cursor(
                account_id=_aid,
                source_type="binance_api",
                cursor_value="100",
            )
            u.commit()
        with pg_uow as u:
            account = u.accounts.find("PGAccount")
            assert u.imports.get_sync_cursor(
                account_id=_aid, source_type="binance_api",
            ) == "100"
            u.imports.upsert_sync_cursor(
                account_id=_aid,
                source_type="binance_api",
                cursor_value="200",
            )
            u.commit()
        with pg_uow as u:
            account = u.accounts.find("PGAccount")
            assert u.imports.get_sync_cursor(
                account_id=_aid, source_type="binance_api",
            ) == "200"
            u.rollback()

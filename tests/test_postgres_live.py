"""Live PostgreSQL integration test, gated by a dedicated test database URL."""
from decimal import Decimal
import os
from pathlib import Path
from urllib.parse import urlparse

import pytest


DATABASE_URL = os.environ.get("FT_TEST_POSTGRES_URL")
pytestmark = pytest.mark.skipif(
    not DATABASE_URL,
    reason="set FT_TEST_POSTGRES_URL to a dedicated database ending in _test",
)


@pytest.fixture
def postgres_sessions():
    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine

    database_name = urlparse(DATABASE_URL).path.removeprefix("/")
    if not database_name.endswith("_test"):
        pytest.fail("FT_TEST_POSTGRES_URL must target a database ending in _test")

    root = Path(__file__).parents[1]
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", DATABASE_URL)
    command.downgrade(config, "base")
    command.upgrade(config, "head")

    from ft.adapters.postgres import create_session_factory

    engine = create_engine(DATABASE_URL, pool_pre_ping=True)
    try:
        yield create_session_factory(engine)
    finally:
        engine.dispose()
        command.downgrade(config, "base")


def test_live_postgres_workspace_application_and_migration_contracts(
    postgres_sessions, tmp_path,
):
    from ft.adapters.local_migration import LocalMigrationSource
    from ft.adapters.postgres import PostgresUnitOfWork, ensure_workspace
    from ft.adapters.postgres.migration import PostgresMigrationTarget
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService
    from ft.application.migration import MigrationService
    from test_storage_migration import _ledger_fixture

    ensure_workspace(postgres_sessions, "live-workspace-a")
    ensure_workspace(postgres_sessions, "live-workspace-b")
    ensure_workspace(postgres_sessions, "migration-workspace")

    workspace_a = PostgresUnitOfWork(postgres_sessions, "live-workspace-a")
    workspace_b = PostgresUnitOfWork(postgres_sessions, "live-workspace-b")
    assert AccountService(workspace_a).create_account("Cash", "cash", "CNY").ok
    result = CashflowService(workspace_a).add_manual_transaction(
        amount=Decimal("-12.34"),
        counterparty="Coffee",
        account_name="Cash",
        date="2026-07-17 09:00:00",
    )
    assert result.ok
    assert AccountService(workspace_b).list_accounts() == []

    with workspace_a as uow:
        assert uow.cashflows.list()[0]["amount"] == Decimal("-12.34")
        assert Decimal(str(
            uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"]
        )) == Decimal("-12.34")
        uow.commit()
    with workspace_b as uow:
        assert uow.cashflows.list() == []
        uow.commit()

    source = LocalMigrationSource(_ledger_fixture(tmp_path / "ledger"))
    target = PostgresMigrationTarget(postgres_sessions, "migration-workspace")
    migration = MigrationService(source, target)
    first = migration.import_ledger()
    second = migration.import_ledger()
    verification = migration.verify()

    assert first.imported is True
    assert second.imported is False
    assert second.batch_id == first.batch_id
    assert verification.ok is True
    assert all(verification.checks.values())
    assert target.raw_record_count(first.batch_id) == 6

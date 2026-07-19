"""Live PostgreSQL integration tests gated by a dedicated test database URL."""
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

    from ft.adapters.relational import create_session_factory

    engine = create_engine(DATABASE_URL, pool_pre_ping=True)
    try:
        yield create_session_factory(engine)
    finally:
        engine.dispose()
        command.downgrade(config, "base")


def test_live_postgres_runtime_cross_entrypoint_and_empty_home(
    postgres_sessions, tmp_path, monkeypatch, capsys,
):
    from ft import cli
    from ft.adapters.relational import ensure_workspace

    ensure_workspace(postgres_sessions, "live-workspace")
    home = tmp_path / "home"
    home.mkdir()
    monkeypatch.setenv("HOME", str(home))
    monkeypatch.setenv("FT_DATABASE_URL", DATABASE_URL)
    monkeypatch.setenv("FT_WORKSPACE_ID", "live-workspace")

    cli.main(["acct", "add", "Cash", "--type", "cash", "--currency", "CNY"])
    cli.main([
        "add", "--amount", "-12.34", "--counterparty", "Coffee",
        "--account", "Cash", "--currency", "CNY", "--date", "2026-07-17 09:00:00",
    ])
    cli.main(["list", "--account", "Cash"])

    assert "Coffee" in capsys.readouterr().out
    assert not (home / ".ft").exists()


def test_live_postgres_workspace_isolation_and_transaction_rollback(postgres_sessions):
    from ft.adapters.relational import RelationalUnitOfWork, ensure_workspace
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    ensure_workspace(postgres_sessions, "workspace-a")
    ensure_workspace(postgres_sessions, "workspace-b")
    workspace_a = RelationalUnitOfWork(postgres_sessions, "workspace-a")
    workspace_b = RelationalUnitOfWork(postgres_sessions, "workspace-b")
    AccountService(workspace_a).create_account("Cash", "cash", "CNY")
    assert CashflowService(workspace_a).add_manual_transaction(
        amount=Decimal("1.230000000000000001"), counterparty="Exact",
        account_name="Cash", date="2026-07-17 09:00:00",
    ).ok
    assert AccountService(workspace_b).list_accounts() == []

    with pytest.raises(RuntimeError, match="boom"):
        with workspace_a as uow:
            uow.cashflows.add("cash", {
                "date": "2026-07-17 10:00:00", "amount": "2", "currency": "CNY",
                "account_name": "Cash",
            })
            raise RuntimeError("boom")

    with workspace_a as uow:
        rows = uow.cashflows.list()
        assert [row["amount"] for row in rows] == [Decimal("1.230000000000000001")]
        uow.commit()


def test_live_shared_uow_serializes_concurrent_projection_updates(postgres_sessions):
    from concurrent.futures import ThreadPoolExecutor
    from ft.adapters.relational import RelationalUnitOfWork, ensure_workspace
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService

    ensure_workspace(postgres_sessions, "concurrent-workspace")
    shared = RelationalUnitOfWork(postgres_sessions, "concurrent-workspace")
    AccountService(shared).create_account("Cash", "cash", "CNY")

    def add(amount):
        return CashflowService(shared).add_manual_transaction(
            amount=Decimal(amount), counterparty=amount, account_name="Cash",
            currency="CNY", date="2026-07-17 09:00:00",
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        results = list(executor.map(add, ["1", "2"]))

    assert all(result.ok for result in results)
    with shared as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "3"
        assert len(uow.cashflows.list()) == 2
        uow.commit()

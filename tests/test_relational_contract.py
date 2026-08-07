"""End-to-end contracts exercised through the neutral relational boundary."""
from __future__ import annotations

from decimal import Decimal
from dataclasses import dataclass
import os
from pathlib import Path

import pytest


@dataclass
class RelationalRuntime:
    name: str
    services: object
    sessions: object


def _backend_names() -> list[object]:
    from conftest import postgres_test_backend_params

    return postgres_test_backend_params()


@pytest.fixture(params=_backend_names())
def relational_runtime(request, tmp_path):
    """A newly migrated formal runtime for the same scenario on each backend."""
    from conftest import migrate_test_postgres_schema, require_test_postgres_url, reset_postgres_schema
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    if request.param == "sqlite":
        url = _upgrade_sqlite(tmp_path / "contract.db")
    else:
        url = require_test_postgres_url()
        assert url is not None
        migrate_test_postgres_schema(url, root)
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "parity-workspace")
    from ft.application.cash_projections import CashProjectionService
    CashProjectionService(sessions, "parity-workspace").rebuild()
    runtime = RelationalRuntime(
        request.param,
        build_services(StorageSettings(url, "parity-workspace")),
        sessions,
    )
    try:
        yield runtime
    finally:
        engine.dispose()
        if request.param == "postgresql":
            reset_postgres_schema(url)


def _upgrade_sqlite(database: Path) -> str:
    from conftest import upgrade_schema_on_connection
    from ft.adapters.relational import create_relational_engine

    url = f"sqlite+pysqlite:///{database}"
    engine = create_relational_engine(url)
    try:
        with engine.begin() as connection:
            upgrade_schema_on_connection(connection, Path(__file__).parents[1])
    finally:
        engine.dispose()
    return url


def test_relational_contract_runtime_uses_test_postgres_despite_runtime_database_url(monkeypatch, tmp_path):
    from sqlalchemy import inspect

    from conftest import migrate_test_postgres_schema, require_test_postgres_url, reset_postgres_schema
    from ft.adapters.relational import create_relational_engine

    url = require_test_postgres_url()
    if url is None:
        pytest.skip("未设置 FT_TEST_POSTGRES_URL，跳过真实 PostgreSQL 合同夹具回归")
    unrelated = tmp_path / "unrelated-contract-runtime.db"
    monkeypatch.setenv("FT_DATABASE_URL", f"sqlite+pysqlite:///{unrelated}")
    try:
        migrate_test_postgres_schema(url, Path(__file__).parents[1])
        engine = create_relational_engine(url)
        try:
            assert "workspaces" in inspect(engine).get_table_names()
        finally:
            engine.dispose()
        assert not unrelated.exists()
    finally:
        reset_postgres_schema(url)


def test_file_sqlite_runtime_uses_shared_services_after_explicit_migration(tmp_path):
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService
    from ft.config import StorageSettings
    from ft.runtime import build_services

    url = _upgrade_sqlite(tmp_path / "finance.db")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "parity-workspace")
    from ft.application.cash_projections import CashProjectionService
    CashProjectionService(sessions, "parity-workspace").rebuild()
    engine.dispose()

    services = build_services(StorageSettings(url, "parity-workspace"))
    assert isinstance(services.accounts, AccountService)
    assert services.accounts.create_account("Cash", "cash", "CNY").ok
    result = services.cashflow.add_manual_transaction(
        amount=Decimal("-12.50"), counterparty="Coffee", account_name="Cash",
        currency="CNY", date="2026-07-17 09:00:00",
    )
    assert result.ok
    assert services.queries.list_transactions(limit=20).items[0].amount == Decimal("-12.50")


def test_unknown_workspace_fails_without_creating_a_fallback_database(tmp_path):
    from ft.adapters.relational.runtime import StorageError
    from ft.config import StorageSettings
    from ft.runtime import build_services

    database = tmp_path / "selected.db"
    url = _upgrade_sqlite(database)
    with pytest.raises(StorageError, match="storage.workspace"):
        build_services(StorageSettings(url, "missing"))
    assert database.exists()
    assert not (tmp_path / "finance_tracker.db").exists()


def test_required_postgres_mode_rejects_missing_or_non_test_database(monkeypatch):
    from conftest import require_test_postgres_url

    monkeypatch.setenv("FT_REQUIRE_TEST_POSTGRES", "1")
    monkeypatch.delenv("FT_TEST_POSTGRES_URL", raising=False)
    with pytest.raises(pytest.fail.Exception, match="FT_TEST_POSTGRES_URL"):
        require_test_postgres_url()
    monkeypatch.setenv("FT_TEST_POSTGRES_URL", "postgresql+psycopg://localhost/finance")
    with pytest.raises(pytest.fail.Exception, match="_test"):
        require_test_postgres_url()


def test_required_postgres_url_fails_closed_when_the_dedicated_server_is_unreachable(monkeypatch):
    from sqlalchemy.exc import OperationalError

    from ft.adapters.relational.dialect import create_relational_engine

    monkeypatch.setenv("FT_REQUIRE_TEST_POSTGRES", "1")
    url = "postgresql+psycopg://user:secret@127.0.0.1:1/unreachable_test"
    engine = create_relational_engine(url)
    try:
        with pytest.raises(OperationalError):
            engine.connect()
    finally:
        engine.dispose()


def test_shared_runtime_workflow_preserves_account_cash_transfer_and_investment_results(relational_runtime):
    runtime = relational_runtime
    services = runtime.services

    assert services.accounts.create_account("Cash", "cash", "CNY").ok
    assert services.accounts.create_account("Card", "cash", "CNY").ok
    assert services.accounts.create_account("Broker", "security", "USD").ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("-12.50"), counterparty="Coffee", account_name="Cash",
        currency="CNY", date="2026-07-17 09:00:00",
    ).ok
    assert services.transfers.transfer(
        from_name="Cash", to_name="Card", amount=Decimal("2"),
        from_currency="CNY", to_currency="CNY", date="2026-07-17", time_str="10:00:00",
    ).ok
    assert services.investments.deposit(
        amount="100.25", currency="USD", account="Broker", date="2026-07-17 11:00:00",
    ).ok

    rows = services.queries.list_transactions(limit=20).items
    assert sorted(row.amount for row in rows) == [Decimal("-12.50"), Decimal("-2"), Decimal("2")]
    with services.uow as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "-14.50"
        assert uow.snapshot.load()["accounts"]["cash"]["Card"]["CNY"] == "2"
        assert uow.investments.list()[0]["to_amount"] == "100.25"
        uow.commit()


def test_shared_runtime_preserves_exact_decimal_utc_and_month(relational_runtime):
    services = relational_runtime.services
    assert services.accounts.create_account("Cash", "cash", "CNY").ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("1.230000000000000001"), counterparty="Exact", account_name="Cash",
        currency="CNY", date="2026-07-01T00:30:00+00:00",
    ).ok
    rows = services.queries.list_transactions(month="2026-07", limit=10).items
    assert len(rows) == 1
    assert rows[0].amount == Decimal("1.230000000000000001")
    assert rows[0].occurred_at == "2026-07-01T00:30:00+00:00"

    with pytest.raises(ValueError, match="18 decimal places"):
        services.cashflow.add_manual_transaction(
            amount=Decimal("0.1234567890123456789"), counterparty="Scale", account_name="Cash",
            currency="CNY", date="2026-07-01 00:30:00",
        )


def test_shared_runtime_rolls_back_injected_fact_and_projection_failure(relational_runtime):
    from ft.domain.accounts import AccountDTO

    services = relational_runtime.services
    with pytest.raises(RuntimeError, match="inject"):
        with services.uow as uow:
            uow.accounts.add(AccountDTO("Cash", "cash"))
            snapshot = uow.snapshot.load(lock=True)
            uow.snapshot.set_balance(snapshot, "Cash", "cash", "CNY", Decimal("99"))
            uow.snapshot.save(snapshot)
            raise RuntimeError("inject")

    with services.uow as uow:
        assert uow.accounts.list() == []
        assert "Cash" not in uow.snapshot.load()["accounts"]["cash"]
        assert uow.snapshot.load()["updated_at"] == ""
        uow.commit()


def test_shared_runtime_import_identity_lookup(relational_runtime):
    services = relational_runtime.services
    with services.uow as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        fact_id = uow.cashflows.add("cash", {
            "occurred_at": "2026-07-17 09:00:00", "amount": "1", "currency": "CNY",
            "account_name": "Cash",
            "source_type": "alipay",
            "record_id": "provider:1",
            "source_payload": {"amount": "1"},
            "category": "income",
        })
        uow.commit()

    with services.uow as uow:
        found = uow.imports.existing_fact_targets(
            source_type="alipay", record_ids=["provider:1"],
        )
        assert found["provider:1"] == ("Cash", "CNY")
        rows = uow.cashflows.list_detailed()
        assert rows[0]["id"] == fact_id
        assert rows[0]["source_type"] == "alipay"
        uow.commit()

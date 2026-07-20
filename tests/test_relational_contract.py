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


def _backend_names() -> list[str]:
    postgres_url = os.environ.get("FT_TEST_POSTGRES_URL")
    if postgres_url:
        return ["sqlite", "postgresql"]
    if os.environ.get("FT_REQUIRE_TEST_POSTGRES") == "1":
        pytest.fail("FT_REQUIRE_TEST_POSTGRES=1 requires FT_TEST_POSTGRES_URL")
    return ["sqlite"]


@pytest.fixture(params=_backend_names())
def relational_runtime(request, tmp_path):
    """A newly migrated formal runtime for the same scenario on each backend."""
    from alembic import command
    from alembic.config import Config
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.config import StorageSettings
    from ft.runtime import build_services

    root = Path(__file__).parents[1]
    if request.param == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'contract.db'}"
    else:
        url = os.environ["FT_TEST_POSTGRES_URL"]
        assert url.rsplit("/", 1)[-1].endswith("_test")
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    if request.param == "postgresql":
        command.downgrade(config, "base")
    command.upgrade(config, "head")
    engine = create_relational_engine(url)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "parity-workspace")
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
            command.downgrade(config, "base")


def _upgrade_sqlite(database: Path) -> str:
    from alembic import command
    from alembic.config import Config

    root = Path(__file__).parents[1]
    url = f"sqlite+pysqlite:///{database}"
    config = Config(str(root / "alembic.ini"))
    config.set_main_option("script_location", str(root / "migrations"))
    config.set_main_option("sqlalchemy.url", url)
    command.upgrade(config, "head")
    return url


def test_file_sqlite_runtime_uses_shared_services_after_explicit_migration(tmp_path):
    from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
    from ft.application.accounts import AccountService
    from ft.application.cashflow import CashflowService
    from ft.config import StorageSettings
    from ft.runtime import build_services

    url = _upgrade_sqlite(tmp_path / "finance.db")
    engine = create_relational_engine(url)
    ensure_workspace(create_session_factory(engine), "parity-workspace")
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


def test_shared_runtime_preserves_exact_decimal_utc_and_shanghai_month(relational_runtime):
    services = relational_runtime.services
    assert services.accounts.create_account("Cash", "cash", "CNY").ok
    assert services.cashflow.add_manual_transaction(
        amount=Decimal("1.230000000000000001"), counterparty="Exact", account_name="Cash",
        currency="CNY", date="2026-07-01T00:30:00+08:00",
    ).ok
    rows = services.queries.list_transactions(month="2026-07", limit=10).items
    assert len(rows) == 1
    assert rows[0].amount == Decimal("1.230000000000000001")
    assert rows[0].date == "2026-07-01 00:30:00"

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


def test_shared_runtime_import_idempotency_and_audit_relationships(relational_runtime):
    services = relational_runtime.services
    with services.uow as uow:
        uow.accounts.add_raw({"name": "Cash", "type": "cash", "currency": "CNY"})
        batch = uow.imports.start_batch(
            source_kind="alipay", source_digest="sha256:contract", source_ref="statement.csv",
            target_account_name="Cash", target_account_currency="CNY",
        )
        same_batch = uow.imports.start_batch(
            source_kind="alipay", source_digest="sha256:contract", source_ref="other.csv",
            target_account_name="Cash", target_account_currency="CNY",
        )
        raw_id = uow.imports.add_raw_records(
            batch_id=batch, raw_file_id=None, source_type="cash",
            records=[{"source_identity": "provider:1", "source_line": 1, "payload": {"amount": "1"}}],
        )[0]
        fact_id = uow.cashflows.add("cash", {
            "date": "2026-07-17 09:00:00", "amount": "1", "currency": "CNY",
            "account_name": "Cash", "raw_record_id": raw_id,
        })
        revision = uow.imports.append_revision(
            cash_transaction_id=fact_id, before={"category": ""}, after={"category": "income"},
            actor_type="statement_import", reason="contract",
        )
        uow.imports.complete_batch(batch)
        uow.commit()

    assert batch == same_batch
    with services.uow as uow:
        assert uow.imports.get_batch(batch)["status"] == "completed"
        assert uow.imports.list_raw_records(batch)[0]["id"] == raw_id
        assert uow.imports.list_revisions(cash_transaction_id=fact_id)[0]["id"] == revision
        uow.commit()

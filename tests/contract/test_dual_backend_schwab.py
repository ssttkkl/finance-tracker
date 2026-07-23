import os
from decimal import Decimal
from pathlib import Path

import pytest

from ft.adapters.relational import create_relational_engine
from ft.adapters.relational.uow import (
    RelationalUnitOfWork,
    create_schema,
    create_session_factory,
    ensure_workspace,
)
from ft.application.investment_import import InvestmentImportService
from ft.domain.accounts import AccountDTO


FIXTURE = Path("tests/fixtures/schwab/transaction_history_sample.csv")


def _backend_uow(tmp_path, backend):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'schwab-parity.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip(
                "FT_TEST_POSTGRES_URL is unset; PostgreSQL Schwab parity is not evidenced "
                "(risk: backend-specific persistence divergence remains unverified)"
            )
        if not url.rsplit("/", 1)[-1].endswith("_test"):
            pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "schwab-parity")
    return engine, RelationalUnitOfWork(sessions, "schwab-parity")


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_schwab_import_backend_contract(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("嘉信", "security", active=True))
            session.commit()
        result = InvestmentImportService(uow).import_statement("schwab", FIXTURE, "嘉信")
        assert result.ok
        assert result.count == 37
        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()
        assert len(events) == 37
        positions = snapshot["accounts"]["security"]["嘉信"]["positions"]
        assert Decimal(positions["usd"]["shares"]) == Decimal("2865.36")
        assert Decimal(positions["avgo"]["shares"]) == Decimal("7")
        assert Decimal(positions["msft"]["shares"]) == Decimal("5")
    finally:
        engine.dispose()

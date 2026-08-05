import os
from decimal import Decimal
from pathlib import Path

import pytest

from ft.adapters.relational import create_relational_engine
from ft.adapters.relational.uow import RelationalUnitOfWork, create_schema, create_session_factory, ensure_workspace
from ft.application.investment_import import InvestmentImportService
from ft.domain.accounts import AccountDTO


FIXTURE = Path("tests/fixtures/usmart_hk/monthly_sample.txt")


def _backend_uow(tmp_path, backend):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'usmart-hk-parity.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip("FT_TEST_POSTGRES_URL is unset; PostgreSQL uSmart HK parity is not evidenced")
        if not url.rsplit("/", 1)[-1].endswith("_test"):
            pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
        from conftest import reset_postgres_schema
        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "usmart-hk-parity")
    return engine, RelationalUnitOfWork(sessions, "usmart-hk-parity")


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_usmart_hk_import_backend_contract(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("盈立证券", "security", active=True))
            session.commit()
        result = InvestmentImportService(uow).import_statement("usmart-hk", FIXTURE, "盈立证券")
        assert result.ok, result.message
        assert result.count == 46
        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()
        assert all(
            not any(str(key).startswith("_") for key in (event.get("source_payload") or {}))
            for event in events
        )
        positions = snapshot["accounts"]["security"]["盈立证券"]["positions"]
        assert Decimal(positions["usd"]["shares"]) == Decimal("4750.17")
        assert Decimal(positions["hkd"]["shares"]) == Decimal("2021.09")
        assert Decimal(positions["00700.hk"]["shares"]) == Decimal("100")
    finally:
        engine.dispose()

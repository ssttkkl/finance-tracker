"""Dual-backend contract tests for DFZQ import."""
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


FIXTURE = Path("tests/fixtures/dfzq/sample_statement.txt")


def _backend_uow(tmp_path, backend):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'dfzq-parity.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip(
                "FT_TEST_POSTGRES_URL is unset; PostgreSQL DFZQ parity is not evidenced "
                "(risk: backend-specific persistence divergence remains unverified)"
            )
        if not url.rsplit("/", 1)[-1].endswith("_test"):
            pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "dfzq-parity")
    return engine, RelationalUnitOfWork(sessions, "dfzq-parity")


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_dfzq_import_backend_contract(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()
        result = InvestmentImportService(uow).import_statement("dfzq", FIXTURE, "东方证券")
        assert result.ok
        assert result.count == 6
        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()
        assert len(events) == 6
        funding = next(event for event in events if event["record_type"] == "funding")
        assert funding["source_payload"] == {
            "原始文本单元": [
                "20260610", "银行转证券", "CNY", "1.0000", "10000.00", "0.00", "10000.00",
            ],
        }
        assert all(
            not ({"action", "action_raw", "ticker", "amount", "record_type"} & set(event["source_payload"]))
            for event in events
        )
        positions = snapshot["accounts"]["security"]["东方证券"]["positions"]
        assert Decimal(positions["cny"]["shares"]) == Decimal("9447.30")
        assert Decimal(positions["600000.sh"]["shares"]) == Decimal("60")
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_dfzq_idempotency_dual_backend(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        first = service.import_statement("dfzq", FIXTURE, "东方证券")
        second = service.import_statement("dfzq", FIXTURE, "东方证券")
        assert first.ok
        assert second.ok
        assert second.count == 0
        assert second.details["duplicate"] is True
        assert second.details["batch_id"] == first.details["batch_id"]
    finally:
        engine.dispose()

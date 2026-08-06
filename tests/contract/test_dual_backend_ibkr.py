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


FIXTURE = Path("tests/fixtures/ibkr/transactions_1y_sample.csv")


def _backend_uow(tmp_path, backend):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / 'ibkr-parity.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip(
                "FT_TEST_POSTGRES_URL is unset; PostgreSQL IBKR parity is not evidenced "
                "(risk: backend-specific persistence divergence remains unverified)"
            )
        if not url.rsplit("/", 1)[-1].endswith("_test"):
            pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ibkr-parity")
    return engine, RelationalUnitOfWork(sessions, "ibkr-parity")


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_ibkr_import_backend_contract(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("IBKR", "security", active=True))
            session.commit()
        result = InvestmentImportService(uow).import_statement("ibkr", FIXTURE, "IBKR")
        assert result.ok
        assert result.count == 39
        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()
        assert len(events) == 39
        funding = next(
            event
            for event in events
            if event["record_type"] == "funding" and event["to_amount"] == "4757"
        )
        assert funding["source_payload"] == {
            "原始文本单元": [
                "Transaction History", "Data", "2026-07-08", "U***67228", "电子资金转账",
                "存款", "-", "-", "-", "-", "4757.0", "-", "4757.0",
            ],
        }
        assert all(
            not ({"action", "ticker", "amount", "record_type", "record_subtype"} & set(event["source_payload"]))
            for event in events
        )
        assert Decimal(snapshot["accounts"]["security"]["IBKR"]["positions"]["usd"]["shares"]) == Decimal("5044.938780328453")
    finally:
        engine.dispose()

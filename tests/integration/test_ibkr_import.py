from decimal import Decimal
from pathlib import Path

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


def _sqlite_uow(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'ibkr.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "ibkr-test")
    return engine, RelationalUnitOfWork(sessions, "ibkr-test")


def test_ibkr_import_sqlite_creates_auditable_events_and_is_idempotent(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("IBKR", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        first = service.import_statement("ibkr", FIXTURE, "IBKR")
        second = service.import_statement("ibkr", FIXTURE, "IBKR")

        assert first.ok
        assert first.count == 39
        assert second.ok
        assert second.count == 0
        assert second.details["duplicate"] is True

        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()

        assert len(events) == 39
        cash = snapshot["accounts"]["security"]["IBKR"]["positions"]["usd"]
        assert Decimal(cash["shares"]) == Decimal("5044.938780328453")
    finally:
        engine.dispose()

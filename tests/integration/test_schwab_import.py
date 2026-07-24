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


FIXTURE = Path("tests/fixtures/schwab/transaction_history_sample.csv")


def _sqlite_uow(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'schwab.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "schwab-test")
    return engine, RelationalUnitOfWork(sessions, "schwab-test")


def test_schwab_import_sqlite_creates_auditable_events_and_is_idempotent(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("嘉信", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        first = service.import_statement("schwab", FIXTURE, "嘉信")
        second = service.import_statement("schwab", FIXTURE, "嘉信")

        assert first.ok
        assert first.count == 37
        assert second.ok
        assert second.count == 0
        assert second.details["duplicate"] is True

        with uow as session:
            events = session.investments.list()
            raw_records = session.imports.list_raw_records(first.details["batch_id"])
            snapshot = session.snapshot.load()
            session.rollback()

        assert len(events) == 37
        assert len(raw_records) == 37
        assert all(record["source_identity"].startswith("schwab:") for record in raw_records)
        cash = snapshot["accounts"]["security"]["嘉信"]["positions"]["usd"]
        assert Decimal(cash["shares"]) == Decimal("2865.36")
        positions = snapshot["accounts"]["security"]["嘉信"]["positions"]
        assert Decimal(positions["avgo.us"]["shares"]) == Decimal("7")
        assert Decimal(positions["msft.us"]["shares"]) == Decimal("5")
    finally:
        engine.dispose()

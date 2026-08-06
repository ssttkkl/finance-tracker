import csv
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
            snapshot = session.snapshot.load()
            session.rollback()

        assert len(events) == 37
        with FIXTURE.open(encoding="utf-8-sig", newline="") as handle:
            source_rows = list(csv.DictReader(handle))
        newest_source_row = source_rows[0]
        funding_source_row = next(
            row for row in source_rows if (row.get(" 类型") or "").strip() == "WIN"
        )
        funding = next(event for event in events if event["record_type"] == "funding")
        assert funding["source_payload"] == funding_source_row
        cash_snapshot = next(
            event
            for event in events
            if (event["record_type"], event["record_subtype"]) == ("snapshot", "cash")
        )
        assert cash_snapshot["source_payload"] == newest_source_row
        prohibited = {"date", "type", "ticker", "amount", "record_type", "record_subtype"}
        assert all(not (prohibited & set(event["source_payload"])) for event in events)
        cash = snapshot["accounts"]["security"]["嘉信"]["positions"]["usd"]
        assert Decimal(cash["shares"]) == Decimal("2865.36")
        positions = snapshot["accounts"]["security"]["嘉信"]["positions"]
        assert Decimal(positions["avgo.us"]["shares"]) == Decimal("7")
        assert Decimal(positions["msft.us"]["shares"]) == Decimal("5")
    finally:
        engine.dispose()

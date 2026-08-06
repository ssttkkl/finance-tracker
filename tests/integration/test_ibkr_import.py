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
        cash_snapshot = next(
            event
            for event in events
            if (event["record_type"], event["record_subtype"]) == ("snapshot", "cash")
        )
        assert cash_snapshot["source_payload"] == {
            "原始文本单元": ["总结", "Data", "期末现金", "5044.938780328453"],
        }
        prohibited = {"action", "ticker", "amount", "record_type", "record_subtype"}
        assert all(not (prohibited & set(event["source_payload"])) for event in events)
        cash = snapshot["accounts"]["security"]["IBKR"]["positions"]["usd"]
        assert Decimal(cash["shares"]) == Decimal("5044.938780328453")
    finally:
        engine.dispose()

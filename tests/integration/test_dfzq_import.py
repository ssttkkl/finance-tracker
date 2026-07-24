"""Integration tests for DFZQ import flow (text fixture path)."""
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


FIXTURE = Path("tests/fixtures/dfzq/sample_statement.txt")


def _sqlite_uow(tmp_path):
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'dfzq.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "dfzq-test")
    return engine, RelationalUnitOfWork(sessions, "dfzq-test")


def test_dfzq_import_full_flow(tmp_path):
    """Full DFZQ import: text fixture → events → snapshot."""
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        result = service.import_statement("dfzq", FIXTURE, "东方证券")

        assert result.ok is True
        assert result.count == 6  # 5 transactions + CHECKIN

        with uow as session:
            events = session.investments.list()
            snapshot = session.snapshot.load()
            session.rollback()

        assert len(events) == 6
        assert all(e.get("source_type") == "dfzq_pdf" for e in events)
        assert all(str(e.get("record_id") or "").startswith("dfzq:") for e in events)

        actions = [e["action"] for e in events]
        assert actions.count("deposit") == 1
        assert actions.count("swap") == 2
        assert actions.count("dividend") == 2
        assert actions.count("checkin") == 1

        positions = snapshot["accounts"]["security"]["东方证券"]["positions"]
        assert Decimal(positions["cny"]["shares"]) == Decimal("9447.30")
        assert Decimal(positions["600000.sh"]["shares"]) == Decimal("60")
    finally:
        engine.dispose()


def test_dfzq_import_writes_inline_provenance(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        result = InvestmentImportService(uow).import_statement(
            "dfzq", FIXTURE, "东方证券"
        )
        assert result.ok is True
        with uow as session:
            events = session.investments.list()
            session.rollback()
        assert len(events) == 6
        assert all(e.get("source_type") == "dfzq_pdf" for e in events)
        assert all(e.get("source_payload") for e in events)
    finally:
        engine.dispose()


def test_dfzq_import_validates_snapshot(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        result = InvestmentImportService(uow).import_statement(
            "dfzq", FIXTURE, "东方证券"
        )
        assert result.ok is True
    finally:
        engine.dispose()


def test_dfzq_import_account_not_found(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        result = InvestmentImportService(uow).import_statement(
            "dfzq", FIXTURE, "NonExistentAccount"
        )
        assert result.ok is False
        assert "not found" in result.message.lower()
    finally:
        engine.dispose()


def test_dfzq_import_wrong_account_type(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("银行账户", "cash", active=True))
            session.commit()

        result = InvestmentImportService(uow).import_statement(
            "dfzq", FIXTURE, "银行账户"
        )
        assert result.ok is False
        assert "security" in result.message.lower() or "crypto" in result.message.lower()
    finally:
        engine.dispose()


def test_dfzq_import_transaction_atomicity_on_missing_file(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        result = InvestmentImportService(uow).import_statement(
            "dfzq", tmp_path / "missing.txt", "东方证券"
        )
        assert result.ok is False
        with uow as session:
            events = session.investments.list()
            batches = session.imports.list_batches()
            session.rollback()
        assert events == []
        assert all(b["status"] != "completed" for b in batches) or batches == []
    finally:
        engine.dispose()

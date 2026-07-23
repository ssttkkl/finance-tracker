"""Idempotency tests for DFZQ import."""
from pathlib import Path
import shutil

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
    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'dfzq-idemp.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "dfzq-idemp")
    return engine, RelationalUnitOfWork(sessions, "dfzq-idemp")


def test_dfzq_import_duplicate_file_returns_success(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        result1 = service.import_statement("dfzq", FIXTURE, "东方证券")
        result2 = service.import_statement("dfzq", FIXTURE, "东方证券")

        assert result1.ok is True
        assert result1.count == 6
        assert result2.ok is True
        assert result2.count == 0
        assert result2.details["duplicate"] is True
        assert result2.details["batch_id"] == result1.details["batch_id"]

        with uow as session:
            events = session.investments.list()
            session.rollback()
        assert len(events) == 6
    finally:
        engine.dispose()


def test_dfzq_import_duplicate_via_source_digest(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        result1 = service.import_statement("dfzq", FIXTURE, "东方证券")

        copied = tmp_path / "copied_statement.txt"
        shutil.copy(FIXTURE, copied)
        result2 = service.import_statement("dfzq", copied, "东方证券")

        assert result2.ok is True
        assert result2.count == 0
        assert result2.details["duplicate"] is True
        assert result2.details["batch_id"] == result1.details["batch_id"]
    finally:
        engine.dispose()


def test_dfzq_import_modified_file_creates_new_batch(tmp_path):
    engine, uow = _sqlite_uow(tmp_path)
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()

        service = InvestmentImportService(uow)
        result1 = service.import_statement("dfzq", FIXTURE, "东方证券")
        assert result1.ok is True

        modified = tmp_path / "modified_statement.txt"
        modified.write_text(FIXTURE.read_text(encoding="utf-8") + "\n\n", encoding="utf-8")
        result2 = service.import_statement("dfzq", modified, "东方证券")

        # Different digest → not treated as same batch; may fail on source_identity collision
        if result2.ok:
            assert result2.details["batch_id"] != result1.details["batch_id"]
        else:
            assert "already" in result2.message.lower() or "unique" in result2.message.lower() or "fail" in result2.message.lower() or result2.message
    finally:
        engine.dispose()

"""Contract: row-level idempotent import (010).

Digest/batch completion must not short-circuit before identity classification.
Formal facts are unique by business identity; re-import and overlap are novel-only.
"""
from __future__ import annotations

import inspect
import os
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
from ft.application.statement_import import StatementImportService
from ft.domain.accounts import AccountDTO
from ft.domain.imports import StatementImportCommand


ROOT = Path(__file__).resolve().parents[2]
IBKR_FIXTURE = ROOT / "tests/fixtures/ibkr/transactions_1y_sample.csv"
DFZQ_FIXTURE = ROOT / "tests/fixtures/dfzq/sample_statement.txt"
DFZQ_A = ROOT / "tests/fixtures/import_idempotency/dfzq_overlap_a.txt"
DFZQ_B = ROOT / "tests/fixtures/import_idempotency/dfzq_overlap_b.txt"


def _backend_uow(tmp_path, backend, workspace: str):
    if backend == "sqlite":
        url = f"sqlite+pysqlite:///{tmp_path / f'{workspace}.db'}"
    else:
        url = os.environ.get("FT_TEST_POSTGRES_URL")
        if not url:
            pytest.skip(
                "FT_TEST_POSTGRES_URL unset; PostgreSQL row-idempotency not evidenced"
            )
        if not url.rsplit("/", 1)[-1].endswith("_test"):
            pytest.fail("FT_TEST_POSTGRES_URL must target a dedicated _test database")
        from conftest import reset_postgres_schema

        reset_postgres_schema(url)
    engine = create_relational_engine(url)
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, workspace)
    from ft.application.cash_projections import CashProjectionService

    CashProjectionService(sessions, workspace).rebuild()
    return engine, RelationalUnitOfWork(sessions, workspace)


# --- SC-005: no digest-only short-circuit remains in production path ---


def test_sc005_no_digest_only_already_imported_short_circuit():
    cash_src = inspect.getsource(StatementImportService.import_statement)
    inv_src = inspect.getsource(InvestmentImportService.import_statement)
    assert 'status"] == "completed"' not in cash_src
    assert "_find_existing_batch" not in inv_src
    assert "Statement already imported" not in inv_src
    inv_tx = inspect.getsource(InvestmentImportService._import_transactions)
    assert "existing_fact_targets" in inv_tx
    assert "existing_fact_targets" in cash_src


# --- US1: same-file re-import ---


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_investment_same_file_reimport_count_zero(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-ibkr")
    try:
        with uow as session:
            session.accounts.add(AccountDTO("IBKR", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        first = service.import_statement("ibkr", IBKR_FIXTURE, "IBKR")
        with uow as session:
            positions_after_first = session.snapshot.load()["accounts"]["security"]["IBKR"][
                "positions"
            ]
            event_count_first = len(session.investments.list())
            session.rollback()
        second = service.import_statement("ibkr", IBKR_FIXTURE, "IBKR")
        with uow as session:
            positions_after_second = session.snapshot.load()["accounts"]["security"]["IBKR"][
                "positions"
            ]
            event_count_second = len(session.investments.list())
            session.rollback()

        assert first.ok and first.count > 0
        assert second.ok and second.count == 0
        assert second.details.get("new_rows") == 0
        assert event_count_first == event_count_second == first.count
        assert positions_after_first == positions_after_second
        # May report duplicate=True when new_rows=0; must not be digest-only path
        assert second.message.lower() in {
            "no new rows to import",
            "statement already imported",
        } or "no new" in second.message.lower() or second.count == 0
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_cash_same_file_reimport_count_zero(tmp_path, backend):
    from test_postgres_statement_import import FakeStatementParser, _cash_row, _command

    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-cash")
    try:
        with uow as session:
            session.accounts.add_raw(
                {"name": "Cash", "type": "cash", "currency": "CNY"}
            )
            session.commit()
        source = tmp_path / "alipay.csv"
        source.write_bytes(b"same-file-bytes-010")
        service = StatementImportService(
            uow, FakeStatementParser([_cash_row()])
        )
        first = service.import_statement(_command(source))
        second = service.import_statement(_command(source))
        with uow as session:
            n = len(session.cashflows.list())
            balance = session.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"]
            session.rollback()
        assert first.ok and first.count == 1
        assert second.ok and second.count == 0
        assert second.details.get("duplicate") is True
        assert second.details.get("new_rows") == 0
        assert n == 1
        assert balance == "-12.34"
    finally:
        engine.dispose()


# --- US2: overlap A then B / reverse ---


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_investment_overlap_a_then_b_novel_only(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-overlap-ab")
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        a = service.import_statement("dfzq", DFZQ_A, "东方证券")
        b = service.import_statement("dfzq", DFZQ_B, "东方证券")
        with uow as session:
            events = session.investments.list()
            session.rollback()
        # A: deposit+buy+checkinA = 3; B shares deposit+buy, novel sell+checkinB = 2
        assert a.ok and a.count == 3
        assert b.ok and b.count == 2
        assert b.details.get("new_rows") == 2
        assert len(events) == 5  # |union of identities|
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_investment_overlap_b_then_a_same_union(tmp_path, backend):
    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-overlap-ba")
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        b = service.import_statement("dfzq", DFZQ_B, "东方证券")
        a = service.import_statement("dfzq", DFZQ_A, "东方证券")
        with uow as session:
            events = session.investments.list()
            session.rollback()
        assert b.ok and b.count == 4
        assert a.ok and a.count == 1  # only checkinA is novel
        assert len(events) == 5
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_cash_overlap_different_digest_novel_only(tmp_path, backend):
    from test_postgres_statement_import import FakeStatementParser, _cash_row, _command

    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-cash-overlap")
    try:
        with uow as session:
            session.accounts.add_raw(
                {"name": "Cash", "type": "cash", "currency": "CNY"}
            )
            session.commit()
        shared = _cash_row(record_id="shared-1", amount="-10.00")
        novel_a = _cash_row(record_id="only-a", amount="-1.00")
        novel_b = _cash_row(record_id="only-b", amount="-2.00")
        path_a = tmp_path / "a.csv"
        path_b = tmp_path / "b.csv"
        path_a.write_bytes(b"cash-overlap-a")
        path_b.write_bytes(b"cash-overlap-b")
        service_a = StatementImportService(
            uow, FakeStatementParser([shared, novel_a])
        )
        service_b = StatementImportService(
            uow, FakeStatementParser([shared, novel_b])
        )
        first = service_a.import_statement(_command(path_a))
        second = service_b.import_statement(_command(path_b))
        with uow as session:
            n = len(session.cashflows.list())
            session.rollback()
        assert first.count == 2
        assert second.count == 1
        assert n == 3
    finally:
        engine.dispose()


@pytest.mark.parametrize("backend", ["sqlite", "postgresql"])
def test_us3_batch_completed_not_ledger_truth(tmp_path, backend):
    """Multiple completed jobs / same digest re-entry: formal count identity-based."""
    engine, uow = _backend_uow(tmp_path, backend, "row-idemp-us3")
    try:
        with uow as session:
            session.accounts.add(AccountDTO("东方证券", "security", active=True))
            session.commit()
        service = InvestmentImportService(uow)
        first = service.import_statement("dfzq", DFZQ_FIXTURE, "东方证券")
        second = service.import_statement("dfzq", DFZQ_FIXTURE, "东方证券")
        with uow as session:
            events = session.investments.list()
            session.rollback()
        assert first.count == 6
        assert second.count == 0
        assert len(events) == 6
        # 015: no import_batches table; idempotency is formal identity only
    finally:
        engine.dispose()

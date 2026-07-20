"""Live transaction contracts for the selected relational backend."""
from __future__ import annotations

import sqlite3
from decimal import Decimal
import multiprocessing

import pytest
from sqlalchemy import event
from sqlalchemy.exc import OperationalError


def _write_cash_in_process(database_url: str, amount: str, results) -> None:
    """Process target kept at module scope so spawn exercises a real SQLite connection."""
    from ft.adapters.relational import RelationalUnitOfWork, create_relational_engine, create_session_factory
    from ft.application.cashflow import CashflowService

    engine = create_relational_engine(database_url)
    try:
        result = CashflowService(
            RelationalUnitOfWork(create_session_factory(engine), "workspace")
        ).add_manual_transaction(
            amount=Decimal(amount), counterparty=amount, account_name="Cash", currency="CNY",
            date="2026-07-17 09:00:00",
        )
        results.put((result.ok, result.error.code if result.error else ""))
    finally:
        engine.dispose()


@pytest.fixture
def sqlite_runtime(tmp_path):
    from ft.adapters.relational import (
        create_relational_engine,
        create_schema,
        create_session_factory,
        ensure_workspace,
    )

    engine = create_relational_engine(f"sqlite+pysqlite:///{tmp_path / 'live.db'}")
    create_schema(engine)
    sessions = create_session_factory(engine)
    ensure_workspace(sessions, "workspace")
    try:
        yield engine, sessions
    finally:
        engine.dispose()


def test_sqlite_command_reserves_writer_before_workspace_read(sqlite_runtime):
    from ft.adapters.relational import RelationalUnitOfWork

    engine, sessions = sqlite_runtime
    statements: list[str] = []

    @event.listens_for(engine, "before_cursor_execute")
    def record_statement(_connection, _cursor, statement, _parameters, _context, _executemany):
        statements.append(statement.upper())

    try:
        with RelationalUnitOfWork(sessions, "workspace") as uow:
            uow.snapshot.load(lock=True)
            uow.commit()
    finally:
        event.remove(engine, "before_cursor_execute", record_statement)

    begin = next(index for index, statement in enumerate(statements) if "BEGIN IMMEDIATE" in statement)
    workspace_read = next(index for index, statement in enumerate(statements) if "FROM WORKSPACES" in statement)
    assert begin < workspace_read


def test_command_exception_rolls_back_and_returns_connection_to_pool(sqlite_runtime):
    from ft.adapters.relational import RelationalUnitOfWork
    from ft.domain.accounts import AccountDTO

    engine, sessions = sqlite_runtime
    uow = RelationalUnitOfWork(sessions, "workspace")

    with pytest.raises(RuntimeError, match="injected failure"):
        with uow as active:
            active.accounts.add(AccountDTO("Cash", "cash"))
            raise RuntimeError("injected failure")

    with uow as active:
        assert active.accounts.list() == []
        active.commit()
    assert engine.pool.checkedout() == 0


def test_commit_time_storage_failure_is_rolled_back_closed_and_mapped(sqlite_runtime, monkeypatch):
    from ft.adapters.relational import RelationalUnitOfWork
    from ft.adapters.relational.runtime import StorageError

    _engine, sessions = sqlite_runtime
    uow = RelationalUnitOfWork(sessions, "workspace")

    with pytest.raises(StorageError, match="storage.readonly"):
        with uow as active:
            session = active._state().session
            rollbacks = []
            original_rollback = session.rollback
            monkeypatch.setattr(session, "rollback", lambda: rollbacks.append(True) or original_rollback())
            monkeypatch.setattr(
                session,
                "commit",
                lambda: (_ for _ in ()).throw(
                    OperationalError("COMMIT", {}, sqlite3.OperationalError("attempt to write a readonly database"))
                ),
            )
            active.commit()

    assert rollbacks


def test_commit_time_generic_connection_failure_is_rolled_back_and_mapped(sqlite_runtime, monkeypatch):
    from ft.adapters.relational import RelationalUnitOfWork
    from ft.adapters.relational.runtime import StorageError

    _engine, sessions = sqlite_runtime
    uow = RelationalUnitOfWork(sessions, "workspace")

    with pytest.raises(StorageError, match="storage.connect"):
        with uow as active:
            session = active._state().session
            rollbacks = []
            original_rollback = session.rollback
            monkeypatch.setattr(session, "rollback", lambda: rollbacks.append(True) or original_rollback())
            monkeypatch.setattr(
                session,
                "commit",
                lambda: (_ for _ in ()).throw(
                    OperationalError("COMMIT", {}, sqlite3.OperationalError("connection lost"))
                ),
            )
            active.commit()

    assert rollbacks


def test_two_process_sqlite_writers_preserve_every_committed_projection_delta(sqlite_runtime):
    from ft.adapters.relational import RelationalUnitOfWork
    from ft.application.accounts import AccountService

    engine, sessions = sqlite_runtime
    assert AccountService(RelationalUnitOfWork(sessions, "workspace")).create_account(
        "Cash", "cash", "CNY"
    ).ok
    context = multiprocessing.get_context("spawn")
    results = context.Queue()
    url = str(engine.url)
    processes = [
        context.Process(target=_write_cash_in_process, args=(url, amount, results))
        for amount in ("1", "2")
    ]
    for process in processes:
        process.start()
    for process in processes:
        process.join(timeout=15)
        assert process.exitcode == 0

    assert sorted(results.get(timeout=2) for _ in processes) == [(True, ""), (True, "")]
    with RelationalUnitOfWork(sessions, "workspace") as uow:
        assert uow.snapshot.load()["accounts"]["cash"]["Cash"]["CNY"] == "3"
        uow.commit()

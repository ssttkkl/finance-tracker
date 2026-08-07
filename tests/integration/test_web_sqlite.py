from __future__ import annotations

from fastapi.testclient import TestClient
from sqlalchemy import event, select
from sqlalchemy.exc import OperationalError


def test_file_sqlite_cash_web_contract(cash_web_runtime):
    from ft.application.web_queries import CashLedgerQueryService
    from tests.contract.web_response_matrix import assert_cash_response_contract
    from ft.web.app import create_app

    client = TestClient(create_app(CashLedgerQueryService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id
    )))
    assert_cash_response_contract(client, cash_web_runtime, create_app)


def test_file_sqlite_busy_error_is_stable_and_never_falls_back(cash_web_runtime):
    from ft.adapters.relational.runtime import storage_error

    error = storage_error(RuntimeError("database is locked"), cash_web_runtime.database_url)

    assert error.code == "storage.busy"
    assert "file SQLite" in str(error)
    assert "cash-web.db" not in str(error)


def test_file_sqlite_read_endpoints_map_runtime_database_errors(cash_web_runtime):
    import sqlite3

    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    runtime = cash_web_runtime
    engine = runtime.sessions.kw["bind"]

    def fail_read(_connection, _cursor, _statement, _parameters, _context, _executemany):
        raise OperationalError(
            "SELECT private_ledger WHERE token = :token",
            {"token": "secret-token"},
            sqlite3.OperationalError("database is locked"),
        )

    event.listen(engine, "before_cursor_execute", fail_read)
    try:
        client = TestClient(create_app(CashLedgerQueryService(runtime.sessions, runtime.workspace_id)))
        responses = [
            client.get("/api/v1/accounts?view=cash"),
            client.get("/api/v1/cash-projections"),
            client.get("/api/v1/evidence/cash-projections/cash:1003"),
        ]
    finally:
        event.remove(engine, "before_cursor_execute", fail_read)

    for response in responses:
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "storage.busy"
        assert "private_ledger" not in response.text
        assert "secret-token" not in response.text


def test_file_sqlite_evidence_read_uses_one_projection_snapshot(cash_web_runtime):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    runtime = cash_web_runtime
    with runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            id=1004,
            workspace_id=runtime.workspace_id,
            account_id=101,
            occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("UTC")),
            amount=Decimal("2.50"),
            currency="CNY",
            counterparty="咖啡店",
            category="退款",
            source_type="fixture",
            record_id="cash-004",
        ))
        session.add(TransactionRelationModel(
            workspace_id=runtime.workspace_id,
            kind="refund_offset",
            subtype="",
            primary_fact_id=1003,
            secondary_fact_id=1004,
            primary_fact_type="cash",
            secondary_fact_type="cash",
            ordered_fact_a=1003,
            ordered_fact_b=1004,
            anchor_fact_id=1004,
            status="accepted",
            rule_id="refund.fixture.v1",
        ))
    baseline = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()

    rebuilt = False
    engine = runtime.sessions.kw["bind"]
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
        connection.exec_driver_sql("PRAGMA journal_mode=WAL")

    def rebuild_after_active_state(_connection, _cursor, statement, _parameters, _context, _executemany):
        nonlocal rebuilt
        if rebuilt or "cash_projection_states" not in statement:
            return
        rebuilt = True
        with runtime.sessions.begin() as session:
            root = session.get(CashTransactionModel, 1003)
            root.counterparty = "并发商户"
            accepted = session.scalar(select(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == runtime.workspace_id,
                TransactionRelationModel.status == "accepted",
            ))
            accepted.rule_id = "refund.fixture.v2"
            session.add(TransactionRelationModel(
                workspace_id=runtime.workspace_id,
                kind="transfer_pair",
                subtype="",
                primary_fact_id=1003,
                secondary_fact_id=1002,
                primary_fact_type="cash",
                secondary_fact_type="cash",
                ordered_fact_a=1002,
                ordered_fact_b=1003,
                anchor_fact_id=1003,
                status="pending_review",
            ))
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()

    event.listen(engine, "after_cursor_execute", rebuild_after_active_state)
    try:
        evidence = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).get_projection_evidence("cash:1003")
    finally:
        event.remove(engine, "after_cursor_execute", rebuild_after_active_state)

    assert rebuilt is True
    assert evidence["projection_version"] == baseline["projection_version"]
    assert evidence["projection"].counterparty == "咖啡店"
    assert evidence["root_record"]["counterparty"] == "咖啡店"
    assert next(member for member in evidence["members"] if member["id"] == "1003")["counterparty"] == "咖啡店"
    assert len(evidence["accepted_relations"]) == 1
    assert evidence["accepted_relations"][0]["kind"] == "refund_offset"
    assert evidence["accepted_relations"][0]["rule_id"] == "refund.fixture.v1"
    assert evidence["inactive_relation_hints"] == []

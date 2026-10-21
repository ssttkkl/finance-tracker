from __future__ import annotations

from tests.cash_relation_fixtures import cash_relation_fixture

from fastapi.testclient import TestClient
from sqlalchemy import event, select
from sqlalchemy.exc import OperationalError


def test_postgres_cash_web_contract_uses_same_response_matrix(postgres_cash_web_runtime):
    from ft.application.web_queries import CashLedgerQueryService
    from tests.contract.web_response_matrix import assert_cash_response_contract
    from ft.web.app import create_app

    client = TestClient(create_app(CashLedgerQueryService(
        postgres_cash_web_runtime.sessions, postgres_cash_web_runtime.workspace_id
    )))
    assert_cash_response_contract(client, postgres_cash_web_runtime, create_app)


def test_postgres_cash_contract_rejects_untyped_investment_endpoints(postgres_cash_web_runtime):
    from datetime import datetime
    from zoneinfo import ZoneInfo

    import pytest
    from ft.adapters.relational.models import InvestmentEventModel
    from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
    from ft.domain.cash_relation_policy import CashRelationError
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    with postgres_cash_web_runtime.sessions.begin() as session:
        session.add(InvestmentEventModel(
            id=1003, workspace_id=postgres_cash_web_runtime.workspace_id, account_id=103,
            occurred_at=datetime(2026, 7, 3, 9, tzinfo=ZoneInfo("UTC")),
            record_type="trade", record_subtype="security", currency="CNY", payload={}, record_id="investment-1003",
        ))

        repo = CurrentCashRelationRepository(session, postgres_cash_web_runtime.workspace_id)
        with pytest.raises(CashRelationError, match="relation_data_invalid"):
            repo.add({"kind": "transfer_pair", "primary_transaction_id": 1003,
                "secondary_transaction_id": 1002, "primary_fact_type": "investment",
                "secondary_fact_type": "investment", "state": "confirmed", "anchor_transaction_id": 1003})

    CashProjectionService(
        postgres_cash_web_runtime.sessions,
        postgres_cash_web_runtime.workspace_id,
    ).rebuild()

    client = TestClient(create_app(CashLedgerQueryService(
        postgres_cash_web_runtime.sessions, postgres_cash_web_runtime.workspace_id
    )))
    page = client.get("/api/v1/cash-projections?limit=3")
    evidence = client.get("/api/v1/evidence/cash-projections/cash:1003")

    assert all(item["accepted_relation_summary"] == [] for item in page.json()["items"])
    assert evidence.json()["accepted_relations"] == []


def test_postgres_read_endpoints_map_runtime_database_errors(postgres_cash_web_runtime):
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    runtime = postgres_cash_web_runtime
    engine = runtime.sessions.kw["bind"]

    def fail_read(_connection, _cursor, _statement, _parameters, _context, _executemany):
        raise OperationalError(
            "SELECT private_ledger WHERE token = :token",
            {"token": "secret-token"},
            RuntimeError("connection refused"),
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
        assert response.json()["error"]["code"] == "storage.connect"
        assert "private_ledger" not in response.text
        assert "secret-token" not in response.text


def test_postgres_evidence_read_uses_one_projection_snapshot(postgres_cash_web_runtime):
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import (
        CashTransactionComponentModel,
        CashTransactionModel,
        TransactionRelationModel,
    )
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    runtime = postgres_cash_web_runtime
    with runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            id=1004,
            workspace_id=runtime.workspace_id,
            account_id=101,
            occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("UTC")),
            amount=Decimal("2.50"),
            currency="CNY",
            counterparty="咖啡店",
            category_id="category-food",
            source_type="fixture",
            record_id="cash-004",
        ))
        session.flush()
        session.add(CashTransactionComponentModel(
            id=1004,
            workspace_id=runtime.workspace_id,
            cash_transaction_id=1004,
            account_id=101,
            amount=Decimal("2.50"),
            currency="CNY",
            ordinal=0,
        ))
        session.add(cash_relation_fixture(session,
            workspace_id=runtime.workspace_id,
            kind="refund_offset",
            subtype="",
            primary_component_id=1003,
            secondary_component_id=1004,
            anchor_component_id=1004,
            state="confirmed",
            rule_id="refund.fixture.v1",
        ))
    baseline = CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()

    rebuilt = False
    engine = runtime.sessions.kw["bind"]

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
                (TransactionRelationModel.state == "confirmed") & TransactionRelationModel.active.is_(True),
            ))
            accepted.rule_id = "refund.fixture.v2"
            session.add(cash_relation_fixture(session,
                workspace_id=runtime.workspace_id,
                kind="transfer_pair",
                subtype="",
                primary_component_id=1003,
                secondary_component_id=1002,
                anchor_component_id=1003,
                state="draft",
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

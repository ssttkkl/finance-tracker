from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest

def _service(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    CashProjectionService(runtime.sessions,runtime.workspace_id).rebuild()
    return CashLedgerQueryService(runtime.sessions,runtime.workspace_id)

def test_projection_page_filters_and_stable_cursor(cash_web_runtime):
    service=_service(cash_web_runtime); first=service.list_cash_projections(limit=2); second=service.list_cash_projections(limit=2,cursor=first.next_cursor)
    assert [x.projection_id for x in first.items+second.items]==["cash:1003","cash:1002","cash:1001"]
    assert [x.projection_id for x in service.list_cash_projections(category="餐饮").items]==["cash:1003"]
    assert [x.projection_id for x in service.list_cash_projections(economic_type="income").items]==["cash:1001"]
    with pytest.raises(ValueError,match="invalid_cursor"):service.list_cash_projections(cursor=first.next_cursor,category="餐饮")

def test_query_fails_closed_before_first_build(cash_web_runtime):
    from ft.application.web_queries import CashLedgerQueryService, ProjectionUnavailableError
    with pytest.raises(ProjectionUnavailableError):CashLedgerQueryService(cash_web_runtime.sessions,cash_web_runtime.workspace_id).list_cash_projections()


@pytest.mark.parametrize("payload", [b"[]", b'"cursor"', b"0", b"true", b"null"])
def test_non_object_cursor_is_invalid(cash_web_runtime, payload):
    import base64

    service = _service(cash_web_runtime)
    cursor = base64.urlsafe_b64encode(payload).decode().rstrip("=")

    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_cash_projections(cursor=cursor)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("v", True),
        ("version", True),
        ("workspace", 1),
        ("filters", []),
        ("occurred_at", 1),
        ("occurred_at", "2026-07-03"),
        ("projection_id", 1),
        ("projection_id", True),
        ("projection_id", None),
        ("projection_id", []),
        ("projection_id", {}),
    ),
)
def test_cursor_with_invalid_contract_field_is_invalid(cash_web_runtime, field, value):
    import base64
    import json

    service = _service(cash_web_runtime)
    cursor = service.list_cash_projections(limit=1).next_cursor
    payload = json.loads(base64.urlsafe_b64decode(cursor + "=" * (-len(cursor) % 4)))
    payload[field] = value
    cursor = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()
    ).decode().rstrip("=")

    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_cash_projections(cursor=cursor)
def test_old_version_cursor_requires_refresh(cash_web_runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import ProjectionUpdatedError
    service=_service(cash_web_runtime); cursor=service.list_cash_projections(limit=1).next_cursor; CashProjectionService(cash_web_runtime.sessions,cash_web_runtime.workspace_id).rebuild()
    with pytest.raises(ProjectionUpdatedError):service.list_cash_projections(limit=1,cursor=cursor)


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_projection_page_keeps_version_and_dataset_in_one_read_snapshot(request, runtime_name):
    from sqlalchemy import event
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService, ProjectionUpdatedError

    runtime = request.getfixturevalue(runtime_name)
    baseline = _service(runtime)
    baseline_version = CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"]
    rebuilt = False
    state_statements = []
    engine = runtime.sessions.kw["bind"]

    if engine.dialect.name == "sqlite":
        with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
            connection.exec_driver_sql("PRAGMA journal_mode=WAL")

    def rebuild_after_state_query():
        nonlocal rebuilt
        rebuilt = True
        with runtime.sessions.begin() as session:
            session.add(CashTransactionModel(
                id=1004,
                workspace_id=runtime.workspace_id,
                account_id=101,
                occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")),
                amount=Decimal("3"),
                currency="CNY",
                counterparty="新流水",
                category="餐饮",
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
            ))
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()

    def rebuild_between_state_and_page(_connection, _cursor, statement, _parameters, _context, _executemany):
        if "cash_projection_states" not in statement:
            return
        if not rebuilt:
            state_statements.append(statement)
            rebuild_after_state_query()

    event.listen(engine, "after_cursor_execute", rebuild_between_state_and_page)
    try:
        page = CashLedgerQueryService(runtime.sessions, runtime.workspace_id).list_cash_projections(limit=2)
    finally:
        event.remove(engine, "after_cursor_execute", rebuild_between_state_and_page)

    assert rebuilt is True
    assert page.projection_version == baseline_version
    assert [
        (item.projection_id, item.amount, item.composition, item.accepted_relation_summary)
        for item in page.items
    ] == [
        ("cash:1003", "-12.5", (), ()),
        ("cash:1002", "-100", (), ()),
    ]
    assert len(state_statements) == 1
    assert "WITH active_state AS" in state_statements[0]
    assert "cash_projection_relations" in state_statements[0]
    assert CashProjectionService(runtime.sessions, runtime.workspace_id).status()["projection_version"] == baseline_version + 1
    with pytest.raises(ProjectionUpdatedError):
        baseline.list_cash_projections(limit=2, cursor=page.next_cursor)

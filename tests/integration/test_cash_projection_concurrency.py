"""收支投影在正式关系型后端上的并发合同。"""
from __future__ import annotations

import pytest


def test_postgresql_rebuild_locks_workspace_before_projection_state(postgres_cash_web_runtime):
    from sqlalchemy import event
    from ft.application.cash_projections import CashProjectionService

    statements: list[str] = []

    def capture(_connection, _cursor, statement, _parameters, _context, _executemany):
        statements.append(statement.lower())

    engine = postgres_cash_web_runtime.sessions.kw["bind"]
    event.listen(engine, "before_cursor_execute", capture)
    try:
        CashProjectionService(
            postgres_cash_web_runtime.sessions,
            postgres_cash_web_runtime.workspace_id,
        ).rebuild()
    finally:
        event.remove(engine, "before_cursor_execute", capture)

    workspace_lock = next(
        index for index, statement in enumerate(statements)
        if "workspaces" in statement and "for update" in statement
    )
    state_lock = next(
        index for index, statement in enumerate(statements)
        if "cash_projection_states" in statement and "for update" in statement
    )
    assert workspace_lock < state_lock


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("counterparty", "并发交易对方"),
        ("category", "并发分类"),
        ("note", "并发备注"),
        ("source_type", "concurrent-source"),
        ("source_payload", {"merchant": "并发商户"}),
    ),
)
def test_postgresql_rebuild_rejects_source_display_change_before_publish(
    postgres_cash_web_runtime,
    monkeypatch,
    field,
    value,
):
    from ft.adapters.relational.models import CashTransactionModel
    from ft.application import cash_projections
    from ft.application.cash_projections import CashProjectionService

    original = cash_projections.build_cash_projections
    changed = False

    def build_after_concurrent_source_change(facts, relations):
        nonlocal changed
        if not changed:
            changed = True
            with postgres_cash_web_runtime.sessions.begin() as session:
                fact = session.get(CashTransactionModel, 1003)
                setattr(fact, field, value)
        return original(facts, relations)

    monkeypatch.setattr(cash_projections, "build_cash_projections", build_after_concurrent_source_change)

    with pytest.raises(RuntimeError, match="projection.concurrent_update"):
        CashProjectionService(
            postgres_cash_web_runtime.sessions,
            postgres_cash_web_runtime.workspace_id,
        ).rebuild()

    status = CashProjectionService(
        postgres_cash_web_runtime.sessions,
        postgres_cash_web_runtime.workspace_id,
    ).status()
    assert status["availability"] == "uninitialized"
    assert status["active_dataset_id"] is None



def test_postgresql_uninitialized_write_locks_workspace_before_skipping_projection_maintenance(
    postgres_cash_web_runtime,
):
    from sqlalchemy import event
    from ft.application.cash_projections import CashProjectionService

    statements: list[str] = []

    def capture(_connection, _cursor, statement, _parameters, _context, _executemany):
        statements.append(statement.lower())

    runtime = postgres_cash_web_runtime
    engine = runtime.sessions.kw["bind"]
    event.listen(engine, "before_cursor_execute", capture)
    try:
        with runtime.sessions.begin() as session:
            assert CashProjectionService.maintain_if_ready_in_session(
                session, runtime.workspace_id, set(),
            ) is None
    finally:
        event.remove(engine, "before_cursor_execute", capture)

    assert any("workspaces" in statement and "for update" in statement for statement in statements)
    assert any("cash_projection_states" in statement and "for update" in statement for statement in statements)


def test_postgresql_source_digest_covers_accepted_relation_evidence(postgres_cash_web_runtime):
    from ft.adapters.relational.models import TransactionRelationModel
    from ft.adapters.relational.projections import RelationalCashProjectionRepository

    with postgres_cash_web_runtime.sessions.begin() as session:
        repository = RelationalCashProjectionRepository(session, postgres_cash_web_runtime.workspace_id)
        before = repository.source_digest()
        session.add(TransactionRelationModel(
            workspace_id=postgres_cash_web_runtime.workspace_id,
            kind="refund_offset",
            subtype="",
            primary_fact_id=1003,
            secondary_fact_id=1002,
            primary_fact_type="cash",
            secondary_fact_type="cash",
            ordered_fact_a=1002,
            ordered_fact_b=1003,
            anchor_fact_id=1002,
            status="accepted",
            rule_id="refund.fixture.v1",
        ))
        session.flush()
        assert repository.source_digest() != before


def test_sqlite_rebuild_reports_busy_while_another_writer_holds_lock(cash_web_runtime):
    import pytest
    from ft.adapters.relational.runtime import StorageError
    from ft.application.cash_projections import CashProjectionService

    engine = cash_web_runtime.sessions.kw["bind"]
    connection = engine.connect()
    connection.exec_driver_sql("BEGIN IMMEDIATE")
    try:
        with pytest.raises(StorageError) as raised:
            CashProjectionService(
                cash_web_runtime.sessions,
                cash_web_runtime.workspace_id,
            ).rebuild()
    finally:
        connection.rollback()
        connection.close()

    assert raised.value.code == "storage.busy"

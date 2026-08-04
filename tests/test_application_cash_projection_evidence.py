from __future__ import annotations


def _service(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return CashLedgerQueryService(runtime.sessions, runtime.workspace_id)


def test_evidence_returns_root_members_and_explicit_absence(cash_web_runtime):
    evidence = _service(cash_web_runtime).get_projection_evidence("cash:1003")

    assert evidence["projection"].projection_id == "cash:1003"
    assert evidence["root_record"]["record_id"] == "cash-003"
    assert evidence["root_record"]["account"]["name"] == "日常账户"
    assert evidence["root_record"]["source_snapshot"] == {"merchant": "咖啡店"}
    assert [member["id"] for member in evidence["members"]] == ["1003"]
    assert evidence["members"][0]["roles"] == ["root"]
    assert evidence["accepted_relations"] == []
    assert evidence["inactive_relation_hints"] == []
    assert evidence["refund_timeline"] == []


def test_evidence_reads_members_and_relations_in_fixed_batch_queries(cash_web_runtime):
    from decimal import Decimal

    from sqlalchemy import event
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    with cash_web_runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1002).amount = Decimal("-12.50")
        session.add(TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="payment_mirror", subtype="",
            primary_fact_id=1003, secondary_fact_id=1002, primary_fact_type="cash", secondary_fact_type="cash",
            ordered_fact_a=1002, ordered_fact_b=1003, anchor_fact_id=1003, status="accepted",
            rule_id="mirror.fixture.v1",
        ))
    CashProjectionService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).rebuild()
    statements = []
    engine = cash_web_runtime.sessions.kw["bind"]

    def record(_connection, _cursor, statement, _parameters, _context, _many):
        statements.append(statement)

    event.listen(engine, "before_cursor_execute", record)
    try:
        evidence = CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).get_projection_evidence("cash:1003")
    finally:
        event.remove(engine, "before_cursor_execute", record)

    assert [member["id"] for member in evidence["members"]] == ["1003", "1002"]
    relation = evidence["accepted_relations"]
    assert len(relation) == 1
    assert relation[0]["kind"] == "payment_mirror"
    assert relation[0]["rule_id"] == "mirror.fixture.v1"
    assert relation[0]["primary_record"]["id"] == "1003"
    assert relation[0]["secondary_record"]["id"] == "1002"
    assert len([statement for statement in statements if statement.lstrip().upper().startswith("SELECT")]) <= 8

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

def test_projection_page_returns_monthly_summaries_for_all_filtered_rows(cash_web_runtime):
    from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel
    from sqlalchemy import select

    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions.begin() as session:
        state = session.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == cash_web_runtime.workspace_id))
        session.add_all((
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:month-june-income",
                root_cash_transaction_id=1001, economic_type="income", transfer_subtype=None, net_amount=Decimal("10"), currency="USD",
                occurred_at=datetime(2026, 6, 30, 9, tzinfo=ZoneInfo("Asia/Shanghai")), account_id=101, counterparty="六月收入",
                category="收入", note="", source_type="fixture", record_id="month-june-income", visible=True,
                hidden_reason=None, member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:month-june-expense",
                root_cash_transaction_id=1001, economic_type="expense", transfer_subtype=None, net_amount=Decimal("-3"), currency="USD",
                occurred_at=datetime(2026, 6, 29, 9, tzinfo=ZoneInfo("Asia/Shanghai")), account_id=101, counterparty="六月支出",
                category="日用", note="", source_type="fixture", record_id="month-june-expense", visible=True,
                hidden_reason=None, member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
        ))

    page = service.list_cash_projections(limit=1)

    assert [
        (summary.month, [(currency.currency, currency.income, currency.expense) for currency in summary.currencies])
        for summary in page.monthly_summaries
    ] == [
        ("2026-07", [("CNY", "2000", "-112.5")]),
        ("2026-06", [("USD", "10", "-3")]),
    ]

def test_projection_filter_matches_counterparty_or_note(cash_web_runtime):
    from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel
    from sqlalchemy import select

    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions.begin() as session:
        state = session.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == cash_web_runtime.workspace_id))
        session.add_all((
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:counterparty-match",
                root_cash_transaction_id=1001, economic_type="expense", transfer_subtype=None, net_amount=Decimal("-2"), currency="CNY",
                occurred_at=datetime(2026, 7, 5, 9, tzinfo=ZoneInfo("Asia/Shanghai")), account_id=101, counterparty="星巴克",
                category="餐饮", note="下午消费", source_type="fixture", record_id="counterparty-match", visible=True,
                hidden_reason=None, member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:note-match",
                root_cash_transaction_id=1001, economic_type="expense", transfer_subtype=None, net_amount=Decimal("-3"), currency="CNY",
                occurred_at=datetime(2026, 7, 4, 9, tzinfo=ZoneInfo("Asia/Shanghai")), account_id=101, counterparty="便利店",
                category="日用", note="星巴克豆采购", source_type="fixture", record_id="note-match", visible=True,
                hidden_reason=None, member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
        ))

    assert [item.projection_id for item in service.list_cash_projections(counterparty="星巴克").items] == ["cash:counterparty-match", "cash:note-match"]

def test_projection_page_returns_global_filter_options_independent_of_current_filters(cash_web_runtime):
    service = _service(cash_web_runtime)

    page = service.list_cash_projections(limit=1)
    filtered = service.list_cash_projections(
        account_id="101", counterparty="咖啡", category="餐饮", currency="CNY",
        date_from="2026-07-03", date_to="2026-07-03", amount_min="-20", amount_max="-1",
        economic_type="expense", composition="single",
    )

    assert page.filter_options.categories == tuple(sorted(("餐饮", "日用", "收入")))
    assert page.filter_options.currencies == ("CNY",)
    assert filtered.filter_options == page.filter_options

def test_projection_filter_options_exclude_hidden_and_blank_values(cash_web_runtime):
    from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel
    from sqlalchemy import select

    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions.begin() as session:
        state = session.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == cash_web_runtime.workspace_id))
        session.add_all((
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:hidden-option",
                root_cash_transaction_id=1001, economic_type="internal_transfer", transfer_subtype="ordinary_transfer",
                net_amount=Decimal("0"), currency="JPY", occurred_at=datetime(2026, 7, 1, 9, tzinfo=ZoneInfo("Asia/Shanghai")),
                account_id=101, counterparty="", category="隐藏分类", note="", source_type=None, record_id="hidden-option",
                visible=False, hidden_reason="internal_transfer", member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
            CashProjectionModel(
                workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id, projection_id="cash:blank-option",
                root_cash_transaction_id=1001, economic_type="expense", transfer_subtype=None,
                net_amount=Decimal("-1"), currency="USD", occurred_at=datetime(2026, 7, 1, 10, tzinfo=ZoneInfo("Asia/Shanghai")),
                account_id=101, counterparty="", category="", note="", source_type=None, record_id="blank-option",
                visible=True, hidden_reason=None, member_count=1, accepted_relation_count=0, built_projection_version=1,
            ),
        ))

    options = service.list_cash_projections(limit=1).filter_options

    assert "隐藏分类" not in options.categories
    assert options.categories == tuple(sorted(("餐饮", "日用", "收入")))
    assert options.currencies == ("CNY", "USD")


def test_projection_page_includes_visible_internal_transfer_and_filter_option(cash_web_runtime):
    from ft.adapters.relational.models import CashProjectionModel, CashProjectionStateModel
    from sqlalchemy import select

    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions.begin() as session:
        state = session.scalar(select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == cash_web_runtime.workspace_id))
        session.add(CashProjectionModel(
            workspace_id=cash_web_runtime.workspace_id, dataset_id=state.active_dataset_id,
            projection_id="cash:visible-transfer", root_cash_transaction_id=1001,
            economic_type="internal_transfer", transfer_subtype="ordinary_transfer", net_amount=Decimal("0"),
            currency="HKD", occurred_at=datetime(2026, 7, 4, 9, tzinfo=ZoneInfo("Asia/Shanghai")),
            account_id=101, counterparty="个人账户", category="转账", note="账户间转移",
            source_type="fixture", record_id="visible-transfer", visible=True, hidden_reason=None,
            member_count=2, accepted_relation_count=1, built_projection_version=1,
        ))

    page = service.list_cash_projections()
    transfer = next(item for item in page.items if item.projection_id == "cash:visible-transfer")

    assert transfer.economic_type == "internal_transfer"
    assert transfer.amount == "0"
    assert page.filter_options.categories == tuple(sorted(("餐饮", "日用", "收入", "转账")))
    assert page.filter_options.currencies == ("CNY", "HKD")
    assert [item.projection_id for item in service.list_cash_projections(economic_type="internal_transfer").items] == ["cash:visible-transfer"]

def test_projection_filter_options_are_empty_when_no_visible_projection(cash_web_runtime):
    from ft.adapters.relational.models import CashProjectionModel
    from sqlalchemy import update

    service = _service(cash_web_runtime)
    with cash_web_runtime.sessions.begin() as session:
        session.execute(update(CashProjectionModel).where(CashProjectionModel.workspace_id == cash_web_runtime.workspace_id).values(visible=False))

    page = service.list_cash_projections()

    assert page.items == ()
    assert page.filter_options.categories == ()
    assert page.filter_options.currencies == ()

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

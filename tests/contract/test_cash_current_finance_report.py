"""生产报表组合入口复用当前正式投影，排除草稿及删除来源。"""
from contextvars import ContextVar
from datetime import datetime, timezone
from decimal import Decimal
from types import SimpleNamespace

import pytest

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository
from ft.adapters.relational.models import CashTransactionComponentModel, CashTransactionModel
from ft.application.cash_projections import CashProjectionService
from tests.contract.test_cash_draft_storage import draft_runtime
from tests.contract.test_cash_manual_classification import _mirror


def _report_fixture(runtime):
    _mirror(runtime)
    with runtime.sessions.begin() as session:
        session.get(CashTransactionModel, 1001).record_type = "income"
        for identifier, values in (
            (2001, dict(amount="5", record_type="refund", occurred_at=datetime(2026, 8, 1, tzinfo=timezone.utc))),
            (2002, dict(amount="-99", record_type="consumption")),
            (2003, dict(amount="999", record_type="income", state="draft")),
            (2004, dict(amount="-999", record_type="transfer_out", state="draft", note="草稿流向")),
            (2005, dict(amount="-888", record_type="transfer_out", note="已删除流向")),
            (2006, dict(amount="-10", record_type="transfer_out", note="投资")),
            (2008, dict(amount="10", record_type="transfer_in")),
        ):
            values = {"occurred_at": datetime(2026, 7, 5, tzinfo=timezone.utc), **values}
            values["amount"] = Decimal(values["amount"])
            values["record_subtype"] = "ordinary_transfer" if values["record_type"] in {"transfer_out", "transfer_in"} else "not_applicable"
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id,
                account_id=101, currency="CNY", **values))
            session.flush()
            session.add(CashTransactionComponentModel(id=identifier, workspace_id=runtime.workspace_id,
                cash_transaction_id=identifier, account_id=101, amount=values["amount"], currency="CNY", ordinal=0))
        session.add(CashTransactionModel(id=2007, workspace_id=runtime.workspace_id, state="draft",
            amount=None, currency=None, occurred_at=None, record_type=None, record_subtype=None))
        session.flush()
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="refund_offset", state="confirmed",
            primary_transaction_id=1003, secondary_transaction_id=2001, primary_component_id=1003, secondary_component_id=2001,
            anchor_transaction_id=1003, anchor_component_id=1003, primary_role="expense", secondary_role="refund", applied_amount="5"))
        CurrentCashRelationRepository(session, runtime.workspace_id).add(dict(kind="transfer_pair", subtype="ordinary_transfer", state="confirmed",
            primary_transaction_id=2006, secondary_transaction_id=2008, primary_component_id=2006, secondary_component_id=2008,
            anchor_transaction_id=2006, anchor_component_id=2006, primary_role="outgoing", secondary_role="incoming"))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    # 查询必须继续执行删除边界，不能依赖派生缓存恰好已维护。
    with runtime.sessions.begin() as session:
        for identifier in (2002, 2005):
            session.get(CashTransactionModel, identifier).deleted_at = datetime(2026, 8, 5, tzinfo=timezone.utc)


@pytest.mark.parametrize("composition", ["runtime", "web"])
@pytest.mark.parametrize("month", [None, "2026-07", "2026-08"])
def test_production_reports_use_current_posted_projection_totals(draft_runtime, composition, month):
    runtime = draft_runtime
    _report_fixture(runtime)
    if composition == "runtime":
        from ft.adapters.relational.runtime import build_relational_services
        bundle = build_relational_services(SimpleNamespace(database_url=runtime.database_url, workspace_id=runtime.workspace_id))
        service = bundle.queries
    else:
        from ft.web.app import WorkspaceServices
        workspace = ContextVar("report-workspace", default=runtime.workspace_id)
        service = WorkspaceServices(runtime.sessions, workspace).legacy_queries()
    try:
        result = service.report(month=month)
        assert result.expenses == ({} if month == "2026-08" else {"CNY": Decimal("7.5")})
        assert result.income == ({} if month == "2026-08" else {"CNY": Decimal("2000")})
        assert [(flow.note, flow.currency, flow.amount) for flow in result.flows] == [("投资", "CNY", Decimal("10"))]
    finally:
        if composition == "runtime":
            bundle.uow._session_factory.kw["bind"].dispose()


def test_report_http_uninitialized_state_is_safe_and_never_uses_raw_fallback(draft_runtime):
    from fastapi.testclient import TestClient
    from ft.web.app import WorkspaceServices, create_app

    runtime = draft_runtime
    workspace = ContextVar("report-http-workspace", default=None)
    class Access:
        def require_context(self, _token, _roles):
            return runtime.workspace_id, "viewer", "fixture-user"
    services = WorkspaceServices(runtime.sessions, workspace)
    with TestClient(create_app(object(), access_service=Access(), workspace_context=workspace,
        operations_service=services)) as client:
        response = client.get("/api/v1/reports/finance")
        assert response.status_code == 503
        assert response.json()["error"]["code"] == "projection.unavailable"
        assert "SQL" not in response.text and runtime.database_url not in response.text
        CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
        assert client.get("/api/v1/reports/finance?month=invalid").status_code == 400
        assert client.get("/api/v1/reports/finance?month=2026-07").status_code == 200


def test_current_report_totals_preserve_supported_precision(draft_runtime):
    from sqlalchemy import delete
    from ft.adapters.relational.web_queries import RelationalCashLedgerQueryRepository

    runtime = draft_runtime
    with runtime.sessions.begin() as session:
        session.execute(delete(CashTransactionComponentModel).where(CashTransactionComponentModel.workspace_id == runtime.workspace_id))
        session.execute(delete(CashTransactionModel).where(CashTransactionModel.workspace_id == runtime.workspace_id))
        for identifier, amount in ((1001, "12345678901234567890.123456789012345678"), (1002, "0.000000000000000001")):
            session.add(CashTransactionModel(id=identifier, workspace_id=runtime.workspace_id,
                account_id=101, occurred_at=datetime(2026, 7, 5, tzinfo=timezone.utc), amount=Decimal(amount),
                currency="CNY", record_type="income", record_subtype="not_applicable"))
            session.flush()
            session.add(CashTransactionComponentModel(id=identifier, workspace_id=runtime.workspace_id,
                cash_transaction_id=identifier, account_id=101, amount=Decimal(amount), currency="CNY", ordinal=0))
    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    report = RelationalCashLedgerQueryRepository(runtime.sessions, runtime.workspace_id).read_finance_report(month="2026-07")
    assert report["income"] == {"CNY": Decimal("12345678901234567890.123456789012345679")}

from __future__ import annotations

from datetime import datetime

import pytest


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_evidence_whitelists_snapshot_and_keeps_hidden_projection_readable(request, runtime_name):
    from decimal import Decimal
    from zoneinfo import ZoneInfo
    from ft.adapters.relational.models import CashTransactionModel, TransactionRelationModel
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    cash_web_runtime = request.getfixturevalue(runtime_name)
    with cash_web_runtime.sessions.begin() as session:
        session.add(CashTransactionModel(
            id=1004, workspace_id=cash_web_runtime.workspace_id, account_id=101,
            occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("Asia/Shanghai")), amount=Decimal("12.50"),
            currency="CNY", counterparty="咖啡店", category="退款", source_type="fixture", record_id="cash-004",
        ))
        session.add(TransactionRelationModel(
            workspace_id=cash_web_runtime.workspace_id, kind="refund_offset", subtype="",
            primary_fact_id=1003, secondary_fact_id=1004, primary_fact_type="cash", secondary_fact_type="cash",
            ordered_fact_a=1003, ordered_fact_b=1004, anchor_fact_id=1004, status="accepted",
        ))
    CashProjectionService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).rebuild()

    evidence = CashLedgerQueryService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).get_projection_evidence("cash:1003")

    assert evidence["projection"].visible is False
    assert evidence["projection"].hidden_reason == "full_refund"
    assert evidence["root_record"]["source_snapshot"] == {"merchant": "咖啡店"}
    assert "name" not in evidence["root_record"]["source_snapshot"]
    assert [member["id"] for member in evidence["members"]] == ["1003", "1004"]
    assert evidence["refund_timeline"] == [{
        "record_id": "cash-004", "occurred_at": "2026-07-03T16:00:00+00:00", "amount": "12.5", "currency": "CNY", "source_type": "fixture",
    }]


def test_evidence_only_exposes_cash_inactive_relation_hints(cash_web_runtime):
    from ft.adapters.relational.models import TransactionRelationModel

    with cash_web_runtime.sessions.begin() as session:
        session.add_all((
            TransactionRelationModel(
                workspace_id=cash_web_runtime.workspace_id, kind="transfer_pair", subtype="",
                primary_fact_id=1003, secondary_fact_id=1002, primary_fact_type="cash", secondary_fact_type="cash",
                ordered_fact_a=1002, ordered_fact_b=1003, anchor_fact_id=1003, status="pending_review",
            ),
            TransactionRelationModel(
                workspace_id=cash_web_runtime.workspace_id, kind="transfer_pair", subtype="",
                primary_fact_id=1003, secondary_fact_id=1, primary_fact_type="cash", secondary_fact_type="investment",
                ordered_fact_a=1, ordered_fact_b=1003, anchor_fact_id=1003, status="pending_review",
            ),
        ))
    evidence = _query_evidence(cash_web_runtime)

    assert len(evidence["inactive_relation_hints"]) == 1
    hint = evidence["inactive_relation_hints"][0]
    assert hint["status"] == "pending_review"
    assert hint["primary_record"]["id"] == "1003"
    assert hint["secondary_record"]["id"] == "1002"


def _query_evidence(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return CashLedgerQueryService(runtime.sessions, runtime.workspace_id).get_projection_evidence("cash:1003")

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


def test_evidence_exposes_only_whitelisted_funding_relation_fields(cash_web_runtime):
    import json
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel, InvestmentEventModel
    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    with cash_web_runtime.sessions.begin() as session:
        cash = session.get(CashTransactionModel, 1003)
        cash.record_type = "investment_out"
        cash.record_subtype = "not_applicable"
        session.add(InvestmentEventModel(
            workspace_id=cash_web_runtime.workspace_id,
            account_id=103,
            occurred_at=datetime(2026, 7, 3, 10, tzinfo=ZoneInfo("Asia/Shanghai")),
            record_type="funding",
            record_subtype="external",
            currency="CNY",
            note="原始备注不应出现在关系证据中",
            from_ticker="",
            from_amount=None,
            to_ticker="cny",
            to_amount=Decimal("12.50"),
            commission=None,
            commission_asset="",
            payload={},
            source_type="broker-statement",
            record_id="investment-funding-evidence",
            source_payload={"account": "sensitive-account", "memo": "sensitive-memo"},
        ))

    relation = CashInvestmentFundingRelationService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    ).scan()[0]
    assert relation["status"] == "accepted"
    CashProjectionService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).rebuild()

    evidence = CashLedgerQueryService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    ).get_projection_evidence("cash:1003")

    assert evidence["projection"].transfer_subtype == "bank_security_transfer"
    assert evidence["funding_relation"] == {
        "id": str(relation["id"]),
        "investment_event_id": str(relation["investment_event_id"]),
        "direction": "cash_to_investment",
        "status": "accepted",
        "rule_id": "cash-investment-funding-v1",
        "evidence": {
            "business_day_window": 0,
            "candidate_count": 1,
            "cash_record_type": "investment_out",
            "match_keys": ["amount", "currency", "direction", "business_day"],
        },
    }
    payload = json.dumps(evidence, default=lambda value: value.__dict__, ensure_ascii=True)
    assert "sensitive-account" not in payload
    assert "sensitive-memo" not in payload


def _query_evidence(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return CashLedgerQueryService(runtime.sessions, runtime.workspace_id).get_projection_evidence("cash:1003")

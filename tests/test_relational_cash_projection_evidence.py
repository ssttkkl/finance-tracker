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
            occurred_at=datetime(2026, 7, 4, tzinfo=ZoneInfo("UTC")), amount=Decimal("12.50"),
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
    assert evidence["root_record"]["record_type"] == "other"
    assert evidence["root_record"]["record_subtype"] == "not_applicable"
    assert evidence["root_record"]["account_name"] == "日常账户"
    assert "name" not in evidence["root_record"]["source_snapshot"]
    assert [member["id"] for member in evidence["members"]] == ["1003", "1004"]
    assert evidence["refund_timeline"] == [{
        "record_id": "cash-004", "occurred_at": "2026-07-04T00:00:00+00:00", "amount": "12.5", "currency": "CNY", "source_type": "fixture",
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


@pytest.mark.parametrize(
    (
        "cash_amount", "cash_record_type", "from_ticker", "from_amount", "to_ticker",
        "to_amount", "expected_direction", "expected_from", "expected_to",
    ),
    [
        (
            "-10000", "transfer_out", "", None, "usd", "1275.50",
            "cash_to_investment", ("日常账户", "-10000", "HKD"), ("投资账户", "1275.5", "USD"),
        ),
        (
            "10000", "transfer_in", "usd", "1275.50", "", None,
            "investment_to_cash", ("投资账户", "1275.5", "USD"), ("日常账户", "10000", "HKD"),
        ),
    ],
)
@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_evidence_exposes_only_whitelisted_funding_relation_fields(
    request, runtime_name, cash_amount, cash_record_type, from_ticker, from_amount,
    to_ticker, to_amount, expected_direction, expected_from, expected_to,
):
    import json
    from datetime import datetime
    from decimal import Decimal
    from zoneinfo import ZoneInfo

    from ft.adapters.relational.models import CashTransactionModel, InvestmentEventModel
    from ft.application.cash_investment_funding_relations import CashInvestmentFundingRelationService
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app
    from fastapi.testclient import TestClient

    cash_web_runtime = request.getfixturevalue(runtime_name)
    with cash_web_runtime.sessions.begin() as session:
        cash = session.get(CashTransactionModel, 1003)
        cash.amount = Decimal(cash_amount)
        cash.currency = "HKD"
        cash.counterparty = "Interactive Brokers LLC"
        cash.record_type = cash_record_type
        cash.record_subtype = "ordinary_transfer"
        session.add(InvestmentEventModel(
            workspace_id=cash_web_runtime.workspace_id,
            account_id=103,
            occurred_at=datetime(2026, 7, 3, 10, tzinfo=ZoneInfo("UTC")),
            record_type="funding",
            record_subtype="external",
            currency="USD",
            note="原始备注不应出现在关系证据中",
            from_ticker=from_ticker,
            from_amount=Decimal(from_amount) if from_amount is not None else None,
            to_ticker=to_ticker,
            to_amount=Decimal(to_amount) if to_amount is not None else None,
            commission=None,
            commission_asset="",
            payload={},
            source_type="ibkr_csv",
            record_id="investment-funding-evidence",
            source_payload={"account": "sensitive-account", "memo": "sensitive-memo"},
        ))

    relation = CashInvestmentFundingRelationService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    ).scan()[0]
    assert relation["status"] == "accepted"
    CashProjectionService(cash_web_runtime.sessions, cash_web_runtime.workspace_id).rebuild()

    service = CashLedgerQueryService(
        cash_web_runtime.sessions, cash_web_runtime.workspace_id,
    )
    evidence = service.get_projection_evidence("cash:1003")

    assert evidence["projection"].transfer_subtype == "bank_security_transfer"
    assert evidence["projection"].transfer is not None
    assert (
        evidence["projection"].transfer.from_account.name,
        evidence["projection"].transfer.from_amount,
        evidence["projection"].transfer.from_currency,
    ) == expected_from
    assert (
        evidence["projection"].transfer.to_account.name,
        evidence["projection"].transfer.to_amount,
        evidence["projection"].transfer.to_currency,
    ) == expected_to
    assert evidence["funding_relation"] == {
        "id": str(relation["id"]),
        "investment_event_id": str(relation["investment_event_id"]),
        "direction": expected_direction,
        "status": "accepted",
        "rule_id": "cash-investment-funding-v1",
        "evidence": {
            "business_day_window": 0,
            "candidate_count": 1,
            "cash_record_type": cash_record_type,
            "match_keys": ["institution_name", "direction", "business_day"],
        },
    }
    page = service.list_cash_projections(economic_type="bank_security_transfer")
    assert [item.projection_id for item in page.items] == ["cash:1003"]
    assert page.items[0].transfer == evidence["projection"].transfer

    response = TestClient(create_app(service)).get(
        "/api/v1/cash-projections", params={"economic_type": "bank_security_transfer"},
    )
    assert response.status_code == 200
    def account_payload(name):
        return {
            "id": 101 if name == "日常账户" else 103,
            "name": name,
            "type": "cash" if name == "日常账户" else "security",
            "active": True,
        }
    assert response.json()["items"][0]["transfer"] == {
        "from_account": account_payload(expected_from[0]),
        "from_amount": expected_from[1], "from_currency": expected_from[2],
        "to_account": account_payload(expected_to[0]),
        "to_amount": expected_to[1], "to_currency": expected_to[2],
    }
    payload = json.dumps(evidence, default=lambda value: value.__dict__, ensure_ascii=True)
    assert "sensitive-account" not in payload
    assert "sensitive-memo" not in payload


def _query_evidence(runtime):
    from ft.application.cash_projections import CashProjectionService
    from ft.application.web_queries import CashLedgerQueryService

    CashProjectionService(runtime.sessions, runtime.workspace_id).rebuild()
    return CashLedgerQueryService(runtime.sessions, runtime.workspace_id).get_projection_evidence("cash:1003")

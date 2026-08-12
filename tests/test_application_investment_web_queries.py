from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from zoneinfo import ZoneInfo

import pytest


def _service(runtime):
    from ft.application.investment_web_queries import InvestmentLedgerQueryService

    return InvestmentLedgerQueryService(runtime.sessions, runtime.workspace_id)


def _add_investment_events(runtime):
    from ft.adapters.relational.models import (
        AccountModel,
        CashInvestmentFundingRelationModel,
        InvestmentEventModel,
        LedgerSnapshotModel,
    )

    with runtime.sessions.begin() as session:
        session.add(LedgerSnapshotModel(
            workspace_id=runtime.workspace_id,
            payload={"accounts": {}},
            version=1,
        ))
        session.add_all((
            InvestmentEventModel(
                id=2003,
                workspace_id=runtime.workspace_id,
                account_id=103,
                source_type="fixture",
                record_id="investment-003",
                source_payload={"action": "BUY", "token": "must not leak"},
                occurred_at=datetime(2026, 7, 3, 9, tzinfo=ZoneInfo("UTC")),
                record_type="trade",
                record_subtype="security",
                currency="USD",
                note="买入订单",
                from_ticker="USD",
                from_amount=Decimal("1011.530000000000000000"),
                to_ticker="AAPL.US",
                to_amount=Decimal("10.000000000000000001"),
                commission=Decimal("11.530000000000000000"),
                commission_asset="USD",
                payload={"position": "fixture"},
            ),
            InvestmentEventModel(
                id=2002,
                workspace_id=runtime.workspace_id,
                account_id=103,
                source_type="fixture",
                record_id="investment-002",
                source_payload={"action": "DIVIDEND", "description": "现金股息"},
                occurred_at=datetime(2026, 7, 2, 9, tzinfo=ZoneInfo("UTC")),
                record_type="income",
                record_subtype="dividend_cash",
                currency="USD",
                note="现金股息",
                from_ticker="",
                from_amount=Decimal("0"),
                to_ticker="USD",
                to_amount=Decimal("4.200000000000000000"),
                commission=Decimal("0"),
                commission_asset="",
                payload={},
            ),
            InvestmentEventModel(
                id=2001,
                workspace_id=runtime.workspace_id,
                account_id=103,
                source_type="fixture",
                record_id="investment-001",
                source_payload={"action": "DEPOSIT"},
                occurred_at=datetime(2026, 7, 1, 9, tzinfo=ZoneInfo("UTC")),
                record_type="funding",
                record_subtype="external",
                currency="USD",
                note="银行转证券",
                from_ticker="",
                from_amount=Decimal("0"),
                to_ticker="USD",
                to_amount=Decimal("5000.00"),
                commission=Decimal("0"),
                commission_asset="",
                payload={},
            ),
        ))
        session.flush()
        session.add(CashInvestmentFundingRelationModel(
            id=3001,
            workspace_id=runtime.workspace_id,
            cash_transaction_id=1001,
            investment_event_id=2001,
            direction="cash_to_investment",
            status="accepted",
            rule_id="cash-investment-funding-v1",
            evidence={"business_day_window": 0, "candidate_count": 1, "cash_record_type": "transfer_out", "match_keys": ["amount", "currency", "direction", "business_day"]},
            active_slot="active",
        ))


def test_investment_events_filter_and_cursor_preserve_decimal_values(cash_web_runtime):
    _add_investment_events(cash_web_runtime)
    service = _service(cash_web_runtime)

    first = service.list_events(limit=2, record_type="trade")

    assert [item.event_id for item in first.items] == ["fixture:investment-003"]
    assert first.items[0].from_asset.amount == "1011.530000000000000000"
    assert first.items[0].to_asset.amount == "10.000000000000000001"
    assert first.items[0].commission.amount == "11.530000000000000000"
    assert first.items[0].occurred_at.endswith("+00:00")
    assert first.items[0].relations == ()

    all_first = service.list_events(limit=2)
    second = service.list_events(limit=2, cursor=all_first.next_cursor)
    assert [item.record_id for item in all_first.items + second.items] == [
        "investment-003", "investment-002", "investment-001",
    ]
    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_events(cursor=all_first.next_cursor, ticker="AAPL.US")

    from ft.adapters.relational.models import LedgerSnapshotModel
    from ft.application.investment_web_queries import InvestmentCursorUpdatedError

    with cash_web_runtime.sessions.begin() as session:
        snapshot = session.get(LedgerSnapshotModel, cash_web_runtime.workspace_id)
        assert snapshot is not None
        snapshot.version = 2
    with pytest.raises(InvestmentCursorUpdatedError):
        service.list_events(cursor=all_first.next_cursor)


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_investment_events_filter_ticker_by_case_insensitive_literal_fragment(request, runtime_name):
    from ft.adapters.relational.models import InvestmentEventModel

    runtime = request.getfixturevalue(runtime_name)
    _add_investment_events(runtime)
    with runtime.sessions.begin() as session:
        session.add(InvestmentEventModel(
            id=2004,
            workspace_id=runtime.workspace_id,
            account_id=103,
            source_type="fixture",
            record_id="investment-004",
            source_payload={"action": "BUY"},
            occurred_at=datetime(2026, 7, 4, 9, tzinfo=ZoneInfo("UTC")),
            record_type="trade",
            record_subtype="security",
            currency="USD",
            note="反斜杠标的",
            from_ticker="USD",
            from_amount=Decimal("1"),
            to_ticker=r"AAPL\US",
            to_amount=Decimal("1"),
            commission=Decimal("0"),
            commission_asset="",
            payload={},
        ))
    service = _service(runtime)

    matched = service.list_events(ticker="Pl.Us")

    assert [item.record_id for item in matched.items] == ["investment-003"]
    assert service.list_events(ticker="AAPL%").items == ()
    assert service.list_events(ticker="AAPL_").items == ()
    assert [item.record_id for item in service.list_events(ticker=r"pl\u").items] == ["investment-004"]

    first = service.list_events(ticker="USD", limit=1)
    assert first.next_cursor is not None
    with pytest.raises(ValueError, match="invalid_cursor"):
        service.list_events(ticker="sd", cursor=first.next_cursor)


def test_investment_events_include_batch_funding_relation_summary(cash_web_runtime):
    _add_investment_events(cash_web_runtime)

    item = _service(cash_web_runtime).list_events().items[-1]

    assert item.record_id == "investment-001"
    assert item.relations[0].kind == "cash_investment_funding"
    assert item.relations[0].status == "accepted"
    assert item.relations[0].cash_account.name == "日常账户"
    assert item.relations[0].cash_amount == "2000"


def test_investment_event_evidence_is_workspace_bound_and_redacted(cash_web_runtime):
    _add_investment_events(cash_web_runtime)
    service = _service(cash_web_runtime)

    evidence = service.get_event_evidence("fixture:investment-003")

    assert evidence.event.event_id == "fixture:investment-003"
    assert evidence.source_snapshot == {"action": "BUY"}
    assert evidence.relations == ()
    with pytest.raises(LookupError):
        service.get_event_evidence("fixture:missing")

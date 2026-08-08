from __future__ import annotations

from fastapi.testclient import TestClient
import pytest


def _add_events(runtime):
    from tests.test_application_investment_web_queries import _add_investment_events

    _add_investment_events(runtime)


def _client(runtime, portfolio_service=None):
    from ft.application.investment_web_queries import InvestmentLedgerQueryService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    return TestClient(create_app(
        CashLedgerQueryService(runtime.sessions, runtime.workspace_id),
        investment_service=InvestmentLedgerQueryService(runtime.sessions, runtime.workspace_id),
        portfolio_service=portfolio_service,
    ))


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_investment_api_returns_events_evidence_and_investment_accounts(request, runtime_name):
    runtime = request.getfixturevalue(runtime_name)
    _add_events(runtime)
    client = _client(runtime)

    page = client.get("/api/v1/investment-events", params={"limit": 2})
    accounts = client.get("/api/v1/accounts", params={"view": "investment"})
    evidence = client.get("/api/v1/evidence/investment-events/fixture%3Ainvestment-003")

    assert page.status_code == 200
    assert [item["record_id"] for item in page.json()["items"]] == ["investment-003", "investment-002"]
    assert page.json()["items"][0]["from_asset"]["amount"] == "1011.530000000000000000"
    assert accounts.status_code == 200
    assert [item["name"] for item in accounts.json()["items"]] == ["投资账户"]
    assert evidence.status_code == 200
    assert evidence.json()["source_snapshot"] == {"action": "BUY"}


def test_investment_api_does_not_leak_cross_workspace_event(cash_web_runtime):
    _add_events(cash_web_runtime)
    client = _client(cash_web_runtime)

    response = client.get("/api/v1/evidence/investment-events/fixture%3Aother-event")

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "not_found"
    assert "investment-003" not in response.text


def test_investment_api_rejects_cursor_from_an_older_data_version(cash_web_runtime):
    from ft.adapters.relational.models import LedgerSnapshotModel

    _add_events(cash_web_runtime)
    client = _client(cash_web_runtime)
    first = client.get("/api/v1/investment-events", params={"limit": 2})
    cursor = first.json()["next_cursor"]

    with cash_web_runtime.sessions.begin() as session:
        snapshot = session.get(LedgerSnapshotModel, cash_web_runtime.workspace_id)
        assert snapshot is not None
        snapshot.version = 2

    response = client.get("/api/v1/investment-events", params={"cursor": cursor})

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "investment.updated"


def test_investment_portfolio_api_keeps_decimal_values_and_partial_status(cash_web_runtime):
    from decimal import Decimal

    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO

    class PortfolioStub:
        def get_portfolio(self, *, display_currency=None, period="24h", timezone=None):
            assert display_currency == "CNY"
            return PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
                PortfolioPositionDTO(
                    ticker="BTC", shares=Decimal("10.000000000000000001"), total_cost=Decimal("1000.00"),
                    cost_currency="USD", is_cash=False, quote_status="partial",
                    quote_reason="query_deadline_exceeded",
                ),
            )),))

    client = _client(cash_web_runtime, PortfolioStub())

    response = client.get("/api/v1/investment-portfolio", params={"display_currency": "CNY"})

    assert response.status_code == 200
    position = response.json()["accounts"][0]["positions"][0]
    assert position["shares"] == "10.000000000000000001"
    assert position["quote_status"] == "partial"
    assert position["current_price"] is None
    assert position["market_value"] is None


def test_investment_portfolio_api_forwards_selected_period(cash_web_runtime):
    from ft.domain.investment import PortfolioDTO

    class PortfolioStub:
        def get_portfolio(self, *, display_currency=None, period="24h", timezone=None):
            assert display_currency == "USD"
            assert period == "30d"
            assert timezone == "Asia/Shanghai"
            return PortfolioDTO(())

    client = _client(cash_web_runtime, PortfolioStub())

    response = client.get("/api/v1/investment-portfolio", params={"display_currency": "USD", "period": "30d", "timezone": "Asia/Shanghai"})

    assert response.status_code == 200

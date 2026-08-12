from __future__ import annotations

from fastapi.testclient import TestClient
import pytest


def _add_events(runtime):
    from tests.test_application_investment_web_queries import _add_investment_events

    _add_investment_events(runtime)


def _client(runtime, portfolio_service=None, portfolio_refresh=None):
    from ft.application.investment_web_queries import InvestmentLedgerQueryService
    from ft.application.web_queries import CashLedgerQueryService
    from ft.web.app import create_app

    return TestClient(create_app(
        CashLedgerQueryService(runtime.sessions, runtime.workspace_id),
        investment_service=InvestmentLedgerQueryService(runtime.sessions, runtime.workspace_id),
        portfolio_service=portfolio_service,
        portfolio_refresh=portfolio_refresh,
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


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_investment_api_filters_ticker_by_case_insensitive_literal_fragment(request, runtime_name):
    runtime = request.getfixturevalue(runtime_name)
    _add_events(runtime)
    client = _client(runtime)

    response = client.get("/api/v1/investment-events", params={"ticker": "Pl.Us"})

    assert response.status_code == 200
    assert [item["record_id"] for item in response.json()["items"]] == ["investment-003"]


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
    from datetime import datetime, timezone
    from decimal import Decimal

    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPeriodBaselineDTO, PortfolioPositionDTO

    baseline = PortfolioPeriodBaselineDTO(
        account="投资账户", ticker="BTC", occurred_at=datetime(2026, 8, 12, 9, 30, tzinfo=timezone.utc),
    )

    class PortfolioStub:
        def get_portfolio(self, *, display_currency=None, period="24h", timezone=None):
            assert display_currency == "CNY"
            return PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
                PortfolioPositionDTO(
                    ticker="BTC", shares=Decimal("10.000000000000000001"), total_cost=Decimal("1000.00"),
                    cost_currency="USD", is_cash=False, quote_status="partial",
                    quote_reason="query_deadline_exceeded",
                    period_baselines=(baseline,),
                ),
            )),), period_baselines=(baseline,))

    client = _client(cash_web_runtime, PortfolioStub())

    response = client.get("/api/v1/investment-portfolio", params={"display_currency": "CNY"})

    assert response.status_code == 200
    position = response.json()["accounts"][0]["positions"][0]
    assert position["shares"] == "10.000000000000000001"
    assert position["quote_status"] == "partial"
    assert position["current_price"] is None
    assert position["market_value"] is None
    assert position["period_baselines"] == [{
        "account": "投资账户", "ticker": "BTC", "occurred_at": "2026-08-12T09:30:00+00:00",
    }]
    assert response.json()["period_baselines"] == position["period_baselines"]


def test_investment_portfolio_api_serializes_quote_time_and_session(cash_web_runtime):
    from datetime import datetime, timezone
    from decimal import Decimal
    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO

    class PortfolioStub:
        def get_portfolio(self, **_kwargs):
            return PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
                PortfolioPositionDTO(
                    ticker="AAPL.US", shares=Decimal("1"), total_cost=Decimal("10"),
                    cost_currency="USD", is_cash=False, current_price=Decimal("11"),
                    market_value=Decimal("11"), quote_currency="USD",
                    usd_market_value=Decimal("11"),
                    quote_observed_at=datetime(2026, 8, 12, 13, 0, tzinfo=timezone.utc),
                    quote_session="post_market",
                    display_name="Apple Inc.",
                ),
            )),))

    response = _client(cash_web_runtime, PortfolioStub()).get("/api/v1/investment-portfolio")

    assert response.status_code == 200
    position = response.json()["accounts"][0]["positions"][0]
    assert position["quote_observed_at"] == "2026-08-12T13:00:00+00:00"
    assert position["quote_session"] == "post_market"
    assert position["usd_market_value"] == "11"
    assert position["display_name"] == "Apple Inc."


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


def test_investment_portfolio_holdings_phase_skips_valuation_service(cash_web_runtime):
    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO
    from decimal import Decimal

    class PortfolioStub:
        def get_holdings(self):
            return PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
                PortfolioPositionDTO(
                    ticker="AAPL.US", shares=Decimal("10"), total_cost=Decimal("1000"),
                    cost_currency="USD", is_cash=False,
                ),
            )),))

        def get_portfolio(self, **_kwargs):
            raise AssertionError("holdings phase must not request a valuation")

    client = _client(cash_web_runtime, PortfolioStub())

    response = client.get("/api/v1/investment-portfolio", params={"phase": "holdings"})

    assert response.status_code == 200
    position = response.json()["accounts"][0]["positions"][0]
    assert position["ticker"] == "AAPL.US"
    assert position["shares"] == "10"
    assert position["current_price"] is None


@pytest.mark.parametrize("runtime_name", ["cash_web_runtime", "postgres_cash_web_runtime"])
def test_investment_portfolio_refresh_api_queues_the_selected_scope_and_sse_frames_decimal_portfolio(request, runtime_name):
    from decimal import Decimal

    from ft.application.portfolio_refresh import PortfolioStreamUpdate
    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO
    from ft.web.routes import portfolio_sse_frame

    class PortfolioRefreshStub:
        def __init__(self):
            self.requested = None

        def request_refresh(self, **kwargs):
            self.requested = kwargs

    refresh = PortfolioRefreshStub()
    client = _client(request.getfixturevalue(runtime_name), portfolio_refresh=refresh)

    response = client.post("/api/v1/investment-portfolio/refresh", params={
        "display_currency": "cny", "period": "30d", "timezone": "Asia/Shanghai",
    })
    snapshot = PortfolioDTO((PortfolioAccountDTO("投资账户", "USD", (
        PortfolioPositionDTO(
            ticker="AAPL.US", shares=Decimal("2"), total_cost=Decimal("10"), cost_currency="USD",
            is_cash=False, current_price=Decimal("11"), market_value=Decimal("22"), profit=Decimal("12"),
        ),
    )),), total_market_value=Decimal("22"))
    frame = portfolio_sse_frame(PortfolioStreamUpdate(7, "portfolio", snapshot))

    assert response.status_code == 202
    assert response.json() == {"accepted": True}
    assert refresh.requested == {
        "display_currency": "cny", "period": "30d", "timezone": "Asia/Shanghai",
    }
    assert frame.startswith("id: 7\nevent: portfolio\ndata: ")
    assert '"market_value":"22"' in frame
    assert frame.endswith("\n\n")

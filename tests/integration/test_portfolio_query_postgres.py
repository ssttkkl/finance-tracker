"""PostgreSQL contract for bounded, read-only portfolio quote queries."""
from __future__ import annotations

import os
import time

import pytest

from conftest import reset_postgres_schema
from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
from ft.adapters.relational.queries import RelationalPortfolioRepository
from ft.adapters.relational.uow import RelationalUnitOfWork, create_schema
from ft.application.accounts import AccountService
from ft.application.investment import PortfolioQueryService
from ft.application.valuation import ValuationService
from ft.domain.investment_projection import apply_investment_event


class _BlockingProvider:
    def raw_quote(self, identity, kind):
        time.sleep(1)
        return None


@pytest.mark.skipif(not os.environ.get("FT_TEST_POSTGRES_URL"), reason="set FT_TEST_POSTGRES_URL")
def test_bounded_portfolio_query_preserves_postgres_holdings_without_writes():
    url = os.environ["FT_TEST_POSTGRES_URL"]
    reset_postgres_schema(url)
    engine = create_relational_engine(url)
    try:
        create_schema(engine)
        sessions = create_session_factory(engine)
        ensure_workspace(sessions, "portfolio-workspace")
        uow = RelationalUnitOfWork(sessions, "portfolio-workspace")
        assert AccountService(uow).create_account("Polymarket", "security", "USD").ok
        with uow as entered:
            snapshot = entered.snapshot.load()
            for index in range(16):
                apply_investment_event(snapshot, {
                    "date": "2026-07-27", "action": "checkin", "account_name": "Polymarket",
                    "currency": "USD", "to_ticker": f"pm:market-{index}:yes", "to_amount": "1",
                    "price": "1",
                }, default_currency="USD")
            entered.snapshot.save(snapshot)
            entered.commit()
        before = RelationalPortfolioRepository(sessions, "portfolio-workspace").load_portfolio()
        started = time.monotonic()
        result = PortfolioQueryService(
            RelationalPortfolioRepository(sessions, "portfolio-workspace"),
            ValuationService(_BlockingProvider()), query_deadline_seconds=0.05,
        ).get_portfolio()
        assert time.monotonic() - started < 0.25
        positions = result.accounts[0].positions
        assert {position.ticker for position in positions} == {
            f"pm:market-{index}:yes" for index in range(16)
        }
        assert {position.quote_status for position in positions} == {"partial"}
        assert RelationalPortfolioRepository(sessions, "portfolio-workspace").load_portfolio() == before
    finally:
        engine.dispose()
        reset_postgres_schema(url)

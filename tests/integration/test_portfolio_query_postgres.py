"""PostgreSQL contract for bounded, read-only portfolio quote queries."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
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
from ft.domain.valuation import ProviderTick


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
                    "date": "2026-07-27", "record_type": "snapshot", "record_subtype": "position", "account_name": "Polymarket",
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


@pytest.mark.skipif(not os.environ.get("FT_TEST_POSTGRES_URL"), reason="set FT_TEST_POSTGRES_URL")
def test_postgres_portfolio_quote_contract_deduplicates_and_preserves_display_values():
    class BatchProvider:
        def __init__(self):
            self.calls = []

        def raw_quote_many(self, refs, *, timeout=None):
            self.calls.append(tuple(ref.identity for ref in refs))
            return {
                ref.identity: (
                    ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")
                    if ref.identity.lower() == "aapl.us" else None
                )
                for ref in refs
            }

    class Fx:
        def get_mid(self, base, quote, *, day=None):
            return Decimal("7") if (base, quote) == ("USD", "CNY") else None

    url = os.environ["FT_TEST_POSTGRES_URL"]
    reset_postgres_schema(url)
    engine = create_relational_engine(url)
    try:
        create_schema(engine)
        sessions = create_session_factory(engine)
        ensure_workspace(sessions, "portfolio-contract")
        uow = RelationalUnitOfWork(sessions, "portfolio-contract")
        assert AccountService(uow).create_account("One", "security", "USD").ok
        assert AccountService(uow).create_account("Two", "security", "USD").ok
        with uow as entered:
            snapshot = entered.snapshot.load()
            for account_name, ticker, quantity in (
                ("One", "AAPL.US", "2"), ("One", "missing.us", "1"), ("Two", "aapl.us", "1"),
            ):
                apply_investment_event(snapshot, {
                    "date": "2026-07-27", "record_type": "snapshot", "record_subtype": "position", "account_name": account_name,
                    "currency": "USD", "to_ticker": ticker, "to_amount": quantity, "price": "1",
                }, default_currency="USD")
            entered.snapshot.save(snapshot)
            entered.commit()
        provider = BatchProvider()
        portfolio = PortfolioQueryService(
            RelationalPortfolioRepository(sessions, "portfolio-contract"),
            ValuationService(provider, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
            fx_rates=Fx(),
        ).get_portfolio(display_currency="CNY")

        positions = {
            (account.name, position.ticker): position
            for account in portfolio.accounts for position in account.positions
        }
        assert len(provider.calls) == 1
        assert {identity.lower() for identity in provider.calls[0]} == {"aapl.us", "missing.us"}
        assert positions[("One", "aapl.us")].market_value == Decimal("10")
        assert positions[("One", "aapl.us")].quote_currency == "USD"
        assert positions[("One", "aapl.us")].display_market_value == Decimal("70")
        assert positions[("Two", "aapl.us")].market_value == Decimal("5")
        assert positions[("One", "missing.us")].quote_status == "partial"
        assert positions[("One", "missing.us")].display_market_value is None
    finally:
        engine.dispose()
        reset_postgres_schema(url)

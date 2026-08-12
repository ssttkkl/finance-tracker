"""Portfolio valuation P0: native + display currency."""
from datetime import datetime, timezone
from decimal import Decimal
import os
from pathlib import Path
import subprocess
import sys
import textwrap
import time
from threading import Event

import pytest

from ft.application.investment import PortfolioQueryService
from ft.application.valuation import UnsupportedQuote, ValuationService
from ft.domain.valuation import AssetKind, ProviderTick, QuoteStatus, ValuationError


def _run_deadline_subprocess(provider_setup: str, ticker: str) -> float:
    script = f"""from ft.application.investment import PortfolioQueryService
from ft.application.valuation import ValuationService
import time

class Repository:
    def load_portfolio(self):
        return {{
            "accounts": {{"Broker": {{"currency": "USD", "positions": {{
                "{ticker}": {{"shares": "1", "total_cost": "1", "cost_currency": "USD"}},
            }}}}}},
            "base_currencies": {{"Broker": ("USD",)}},
            "configured_currencies": ("USD",),
        }}

{provider_setup}

started = time.monotonic()
portfolio = PortfolioQueryService(
    Repository(), ValuationService(provider), query_deadline_seconds=0.05,
).get_portfolio()
assert portfolio.accounts[0].positions[0].quote_status == "partial"
print(time.monotonic() - started)
"""
    environment = os.environ.copy()
    source_root = str(Path(__file__).resolve().parents[3] / "src")
    environment["PYTHONPATH"] = source_root + os.pathsep + environment.get("PYTHONPATH", "")
    started = time.monotonic()
    completed = subprocess.run(
        [sys.executable, "-c", script], capture_output=True, text=True,
        timeout=2, env=environment, check=True,
    )
    assert float(completed.stdout.strip()) < 0.2
    return time.monotonic() - started


class FakePortfolioRepo:
    def load_portfolio(self):
        return {
            "accounts": {
                "Multi": {
                    "currency": "USD",
                    "positions": {
                        "usd": {"shares": 10, "total_cost": 10, "cost_currency": "USD"},
                        "aapl.us": {"shares": 2, "total_cost": 6, "cost_currency": "USD"},
                        "0700.hk": {"shares": 1, "total_cost": 100, "cost_currency": "HKD"},
                        "unknown.xyz": {"shares": 1, "total_cost": 1, "cost_currency": "USD"},
                    },
                }
            },
            "base_currencies": {"Multi": ("USD",)},
            "configured_currencies": ("USD",),
        }


class FakeProvider:
    def raw_quote(self, identity, kind):
        if identity == "aapl.us":
            return ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")
        if identity == "0700.hk":
            return ProviderTick(Decimal("300"), "HKD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")
        raise UnsupportedQuote(identity)


class FakeFx:
    def __init__(self, rates=None):
        self.rates = dict(rates or {})

    def get_mid(self, base, quote, *, day=None):
        if base.upper() == quote.upper():
            return Decimal("1")
        return self.rates.get((base.upper(), quote.upper()))


def test_native_portfolio_multi_currency_and_status():
    service = PortfolioQueryService(
        FakePortfolioRepo(),
        ValuationService(FakeProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    )
    result = service.get_portfolio()
    by = {p.ticker: p for p in result.accounts[0].positions}
    assert by["usd"].is_cash and by["usd"].current_price == Decimal("1")
    assert by["usd"].quote_status == QuoteStatus.COMPLETE.value
    assert by["aapl.us"].market_value == Decimal("10")
    assert by["aapl.us"].quote_currency == "USD"
    assert by["0700.hk"].market_value == Decimal("300")
    assert by["0700.hk"].quote_currency == "HKD"
    assert by["0700.hk"].usd_market_value is None
    assert by["unknown.xyz"].market_value is None
    assert by["unknown.xyz"].quote_status == QuoteStatus.UNSUPPORTED.value
    assert by["aapl.us"].display_market_value is None


def test_native_portfolio_exposes_usd_market_value_for_weighting():
    valuation = ValuationService(
        FakeProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    fx = FakeFx({("HKD", "USD"): Decimal("0.125")})
    service = PortfolioQueryService(FakePortfolioRepo(), valuation, fx_rates=fx)
    result = service.get_portfolio()
    by = {p.ticker: p for p in result.accounts[0].positions}
    assert by["usd"].usd_market_value == Decimal("10")
    assert by["aapl.us"].usd_market_value == Decimal("10")
    assert by["0700.hk"].usd_market_value == Decimal("37.5")
    assert by["unknown.xyz"].usd_market_value is None


def test_portfolio_position_exposes_quote_timestamp_and_session_from_provider():
    class SessionProvider(FakeProvider):
        def raw_quote(self, identity, kind):
            if identity == "aapl.us":
                return ProviderTick(
                    Decimal("5"), "USD", datetime(2026, 7, 25, 13, 0, tzinfo=timezone.utc),
                    "fake", "post_market",
                )
            return super().raw_quote(identity, kind)

    result = PortfolioQueryService(
        FakePortfolioRepo(),
        ValuationService(SessionProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    ).get_portfolio()

    position = next(item for item in result.accounts[0].positions if item.ticker == "aapl.us")
    assert position.quote_observed_at == datetime(2026, 7, 25, 13, 0, tzinfo=timezone.utc)
    assert position.quote_session == "post_market"


def test_display_currency_fx_and_fail_closed():
    valuation = ValuationService(
        FakeProvider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)
    )
    fx = FakeFx({("USD", "CNY"): Decimal("7"), ("HKD", "CNY"): Decimal("0.9")})
    service = PortfolioQueryService(FakePortfolioRepo(), valuation, fx_rates=fx)
    result = service.get_portfolio(display_currency="cny")
    by = {p.ticker: p for p in result.accounts[0].positions}
    assert by["aapl.us"].display_currency == "CNY"
    assert by["aapl.us"].display_market_value == Decimal("70")
    assert by["aapl.us"].fx_rate == Decimal("7")
    assert by["0700.hk"].display_market_value == Decimal("270")
    assert by["unknown.xyz"].display_market_value is None

    # FX missing must not use 1:1
    service2 = PortfolioQueryService(FakePortfolioRepo(), valuation, fx_rates=FakeFx({}))
    by2 = {p.ticker: p for p in service2.get_portfolio(display_currency="CNY").accounts[0].positions}
    assert by2["aapl.us"].market_value == Decimal("10")
    assert by2["aapl.us"].display_market_value is None
    assert by2["aapl.us"].fx_status == "partial"

    with pytest.raises(ValuationError):
        service.get_portfolio(display_currency="US")


def test_portfolio_quote_deadline_keeps_every_nonzero_position_and_marks_partial():
    class ManyPositions:
        def load_portfolio(self):
            return {
                "accounts": {"Polymarket": {"currency": "USD", "positions": {
                    f"pm:market-{index}:yes": {"shares": "1", "total_cost": "1", "cost_currency": "USD"}
                    for index in range(16)
                }}},
                "base_currencies": {"Polymarket": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class BlockingProvider:
        def raw_quote(self, identity, kind):
            print("third-party diagnostic")
            time.sleep(1)
            raise RuntimeError("network timeout")

    started = time.monotonic()
    portfolio = PortfolioQueryService(
        ManyPositions(), ValuationService(BlockingProvider()), query_deadline_seconds=0.05,
    ).get_portfolio()
    elapsed = time.monotonic() - started
    positions = portfolio.accounts[0].positions
    assert elapsed < 0.25
    assert len(positions) == 16
    assert all(position.market_value is None for position in positions)
    assert all(position.quote_status == QuoteStatus.PARTIAL.value for position in positions)


def test_portfolio_holdings_phase_skips_blocking_quote_provider_and_returns_base_positions():
    class BlockingProvider:
        def __init__(self):
            self.calls = []

        def raw_quote(self, identity, kind):
            self.calls.append((identity, kind))
            time.sleep(5)
            raise RuntimeError("network timeout")

    provider = BlockingProvider()
    started = time.monotonic()
    result = PortfolioQueryService(
        FakePortfolioRepo(), ValuationService(provider),
    ).get_holdings()

    assert time.monotonic() - started < 1
    assert provider.calls == []
    by_ticker = {position.ticker: position for position in result.accounts[0].positions}
    assert by_ticker["aapl.us"].shares == Decimal("2")
    assert by_ticker["aapl.us"].current_price is None
    assert by_ticker["aapl.us"].market_value is None
    assert result.total_market_value is None
    assert result.period_profit is None


def test_portfolio_default_quote_budget_finishes_within_two_seconds():
    service = PortfolioQueryService(
        FakePortfolioRepo(), ValuationService(FakeProvider()),
    )
    assert service._query_deadline_seconds == 2.0


def test_portfolio_historical_quotes_share_the_full_query_deadline():
    class Repo:
        def load_portfolio(self):
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "aapl.us": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class CurrentFastHistorySlow:
        def __init__(self):
            self.history_calls = 0

        def raw_quote_many(self, refs, *, timeout=None):
            return {
                ref.identity: ProviderTick(
                    Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake",
                )
                for ref in refs
            }

        def raw_quote_at(self, identity, kind, *, at):
            self.history_calls += 1
            time.sleep(1)
            return ProviderTick(Decimal("4"), "USD", at, "fake")

    provider = CurrentFastHistorySlow()
    started = time.monotonic()
    portfolio = PortfolioQueryService(
        Repo(),
        ValuationService(provider, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
        query_deadline_seconds=0.05,
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
    ).get_portfolio()

    assert time.monotonic() - started < 0.25
    assert provider.history_calls == 1
    assert portfolio.accounts[0].positions[0].market_value == Decimal("5")
    assert portfolio.period_profit is None
    assert portfolio.period_profit_rate is None


def test_portfolio_fetches_opening_quote_alongside_current_quote():
    class Repo:
        def load_portfolio(self):
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "aapl.us": {"shares": "10", "total_cost": "1000", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class CurrentWaitsForHistorical:
        def __init__(self):
            self.historical_started = Event()

        def raw_quote_many(self, refs, *, timeout=None):
            self.historical_started.wait(0.1)
            time.sleep(0.04)
            return {
                ref.identity: ProviderTick(
                    Decimal("120"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake",
                )
                for ref in refs
            }

        def raw_quote_at(self, identity, kind, *, at):
            self.historical_started.set()
            time.sleep(0.04)
            return ProviderTick(Decimal("100"), "USD", at, "fake")

    portfolio = PortfolioQueryService(
        Repo(),
        ValuationService(
            CurrentWaitsForHistorical(),
            clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
        ),
        query_deadline_seconds=0.12,
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
    ).get_portfolio()

    position = portfolio.accounts[0].positions[0]
    assert position.market_value == Decimal("1200")
    assert position.period_profit == Decimal("200")
    assert portfolio.period_profit == Decimal("200")


def test_portfolio_progressive_snapshot_fetches_current_quotes_before_history():
    class Repo:
        def load_portfolio(self):
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "aapl.us": {"shares": "10", "total_cost": "1000", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class CurrentMustWin:
        def __init__(self):
            self.history_started = Event()

        def raw_quote_many(self, refs, *, timeout=None):
            assert not self.history_started.wait(0.05)
            return {
                ref.identity: ProviderTick(
                    Decimal("120"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake",
                )
                for ref in refs
            }

        def raw_quote_at(self, identity, kind, *, at):
            self.history_started.set()
            return ProviderTick(Decimal("100"), "USD", at, "fake")

    snapshots = []
    portfolio = PortfolioQueryService(
        Repo(),
        ValuationService(CurrentMustWin(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
        clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
    ).get_portfolio(on_update=snapshots.append)

    assert snapshots[0].accounts[0].positions[0].market_value == Decimal("1200")
    assert snapshots[0].period_profit is None
    assert portfolio.period_profit == Decimal("200")


def test_portfolio_budget_begins_before_the_local_snapshot_read():
    class SlowRepo:
        def load_portfolio(self):
            time.sleep(0.06)
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "aapl.us": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class BlockingProvider:
        def __init__(self):
            self.calls = 0

        def raw_quote_many(self, refs, *, timeout=None):
            self.calls += 1
            time.sleep(1)
            return {}

    provider = BlockingProvider()
    started = time.monotonic()
    portfolio = PortfolioQueryService(
        SlowRepo(), ValuationService(provider), query_deadline_seconds=0.05,
    ).get_portfolio()

    assert time.monotonic() - started < 0.1
    assert provider.calls == 0
    assert portfolio.accounts[0].positions[0].quote_status == QuoteStatus.PARTIAL.value


def test_portfolio_deduplicates_normalized_assets_and_skips_cash_and_zero_positions():
    class DuplicatePortfolioRepo:
        def load_portfolio(self):
            position = {"shares": "2", "total_cost": "6", "cost_currency": "USD"}
            return {
                "accounts": {
                    "One": {"currency": "USD", "positions": {
                        "AAPL.US": position,
                        "usd": {"shares": "5", "total_cost": "5", "cost_currency": "USD"},
                        "zero.us": {"shares": "0", "total_cost": "0", "cost_currency": "USD"},
                    }},
                    "Two": {"currency": "USD", "positions": {"aapl.us": position}},
                },
                "base_currencies": {"One": ("USD",), "Two": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class CountingProvider:
        def __init__(self):
            self.calls = []

        def raw_quote(self, identity, kind):
            self.calls.append((identity, kind))
            return ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")

    provider = CountingProvider()
    portfolio = PortfolioQueryService(
        DuplicatePortfolioRepo(),
        ValuationService(provider, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    ).get_portfolio()

    assert provider.calls == [("AAPL.US", AssetKind.SECURITY)]
    assert len(portfolio.accounts[0].positions) == 2
    assert portfolio.accounts[1].positions[0].market_value == Decimal("10")


def test_portfolio_runs_independent_sources_together_and_stops_unstarted_work_at_deadline():
    class MixedPortfolioRepo:
        def load_portfolio(self):
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "slow-one.us": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                    "slow-two.us": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                    "btc": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class SlowSecurityFastCrypto:
        def __init__(self):
            self.calls = []

        def raw_quote(self, identity, kind):
            self.calls.append(identity)
            if kind is AssetKind.SECURITY:
                time.sleep(0.12)
                return ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "slow")
            return ProviderTick(Decimal("100"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fast")

    provider = SlowSecurityFastCrypto()
    started = time.monotonic()
    portfolio = PortfolioQueryService(
        MixedPortfolioRepo(),
        ValuationService(provider, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
        query_deadline_seconds=0.05,
    ).get_portfolio()
    elapsed = time.monotonic() - started
    by_ticker = {position.ticker: position for position in portfolio.accounts[0].positions}

    assert elapsed < 0.1
    assert by_ticker["btc"].quote_status == QuoteStatus.COMPLETE.value
    assert by_ticker["slow-one.us"].quote_status == QuoteStatus.PARTIAL.value
    time.sleep(0.15)
    assert provider.calls.count("slow-two.us") == 0


def test_portfolio_deadline_does_not_keep_process_alive_for_ignoring_provider():
    elapsed = _run_deadline_subprocess(textwrap.dedent("""
        class Provider:
            def raw_quote(self, identity, kind):
                time.sleep(1)
        provider = Provider()
    """), "slow.us")

    assert elapsed < 0.4


def test_portfolio_deadline_does_not_keep_process_alive_for_prediction_market_batch():
    elapsed = _run_deadline_subprocess(textwrap.dedent("""
        from ft.adapters.market_data import PredictionMarketQuoteProvider

        def fetch_json(url, *, timeout):
            time.sleep(1)
            return []

        provider = PredictionMarketQuoteProvider(fetch_json=fetch_json)
    """), "pm:slow-market:yes")

    assert elapsed < 0.4


def test_portfolio_30_positions_across_three_sources_finish_with_determinate_statuses():
    class LargePortfolioRepo:
        def load_portfolio(self):
            def position():
                return {"shares": "1", "total_cost": "1", "cost_currency": "USD"}

            return {
                "accounts": {
                    "Securities": {"currency": "USD", "positions": {
                        f"security-{index}.us": position() for index in range(10)
                    }},
                    "Crypto": {"currency": "USD", "positions": {
                        ticker: position()
                        for ticker in ("btc", "eth", "usdt", "usdc", "sol", "bnb", "xrp", "doge", "ada")
                    }},
                    "Prediction": {"currency": "USD", "positions": {
                        f"pm:market-{index}:yes": position() for index in range(10)
                    }},
                    "Duplicate": {"currency": "USD", "positions": {"SECURITY-0.US": position()}},
                },
                "base_currencies": {
                    "Securities": ("USD",), "Crypto": ("USD",),
                    "Prediction": ("USD",), "Duplicate": ("USD",),
                },
                "configured_currencies": ("USD",),
            }

    class BatchProvider:
        def __init__(self):
            self.calls = []

        def raw_quote_many(self, refs, *, timeout=None):
            self.calls.append((refs[0].kind, tuple(ref.identity for ref in refs)))
            return {
                ref.identity: ProviderTick(
                    Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake",
                )
                for ref in refs
            }

    provider = BatchProvider()
    started = time.monotonic()
    portfolio = PortfolioQueryService(
        LargePortfolioRepo(),
        ValuationService(provider, clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    ).get_portfolio()
    elapsed = time.monotonic() - started
    positions = [position for account in portfolio.accounts for position in account.positions]
    requested = [identity.lower() for _, identities in provider.calls for identity in identities]

    assert elapsed < 4
    assert len(positions) == 30
    assert {kind for kind, _ in provider.calls} == {
        AssetKind.SECURITY, AssetKind.CRYPTO, AssetKind.PREDICTION_MARKET,
    }
    assert requested.count("security-0.us") == 1
    assert {position.quote_status for position in positions} == {QuoteStatus.COMPLETE.value}

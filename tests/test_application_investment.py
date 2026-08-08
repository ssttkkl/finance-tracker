from decimal import Decimal
import logging
from pathlib import Path
from threading import Thread
import time

import pytest


class FakeInvestmentRepository:
    def __init__(self):
        self.commands = []
        self.appended = None

    def execute(self, command):
        from ft.domain.application import OperationResult
        self.commands.append(command)
        return OperationResult(ok=True, message=command.record_type)

    def append_investments(self, rows):
        self.appended = [dict(row) for row in rows]
        return len(rows)


def _investment_service(repository=None):
    from ft.application.investment import InvestmentService

    return InvestmentService(repository=repository or FakeInvestmentRepository())


def test_all_investment_commands_build_decimal_dtos():
    repository = FakeInvestmentRepository()
    service = _investment_service(repository=repository)

    service.buy("AAPL.US", "1.25", "100.50", "0.25", "USD", "IBKR", "n", "2026-06-01")
    service.sell("AAPL.US", "0.25", "110", "0", "USD", "IBKR", "", None)
    service.swap("Kraken", "BTC", "0.1", "ETH", "2", "USD", "", None)
    service.deposit("10", "USD", "IBKR", "", None)
    service.withdraw("2", "USD", "IBKR", "", None)
    service.dividend("AAPL.US", "1.50", "USD", "IBKR", "", None)
    service.checkin_ticker("AAPL.US", "3.5", "90", "USD", "IBKR", "", None)
    service.checkin_cash("8.75", "USD", "IBKR", "", None)

    assert [command.record_type for command in repository.commands] == [
        "buy", "sell", "swap", "deposit", "withdraw", "dividend",
        "checkin_ticker", "checkin_cash",
    ]
    assert repository.commands[0].quantity == Decimal("1.25")
    assert repository.commands[0].price == Decimal("100.50")
    assert repository.commands[2].to_quantity == Decimal("2")
    assert repository.commands[-1].amount == Decimal("8.75")


def test_investment_service_rejects_non_finite_numbers_before_repository():
    repository = FakeInvestmentRepository()
    service = _investment_service(repository=repository)

    with pytest.raises(ValueError, match="finite"):
        service.deposit("NaN", "USD", "IBKR", "", None)

    assert repository.commands == []


def test_investment_projection_deducts_commission_from_cash_settlement_and_rejects_cost_currency_mix():
    from ft.domain.investment_projection import apply_investment_event

    snapshot = {"accounts": {"security": {"IBKR": {
        "currency": "USD", "positions": {
            "aapl": {"shares": "1", "total_cost": "10", "cost_currency": "USD"},
            "usd": {"shares": "600", "total_cost": "600", "cost_currency": "USD"},
        },
    }}}}
    apply_investment_event(snapshot, {
        "date": "2026-07-17", "record_type": "trade", "record_subtype": "security", "currency": "USD", "account_name": "IBKR",
        "from_ticker": "aapl", "to_ticker": "usd", "from_amount": "1", "to_amount": "120",
        "commission": "2", "commission_asset": "usd",
    }, default_currency="USD")
    assert snapshot["accounts"]["security"]["IBKR"]["positions"]["usd"]["shares"] == "718"

    conflict_snapshot = {"accounts": {"security": {"IBKR": {
        "currency": "USD", "positions": {
            "aapl": {"shares": "1", "total_cost": "10", "cost_currency": "USD"},
        },
    }}}}
    with pytest.raises(ValueError, match="cost currency"):
        apply_investment_event(conflict_snapshot, {
            "date": "2026-07-17", "record_type": "trade", "record_subtype": "security", "currency": "CNY", "account_name": "IBKR",
            "from_ticker": "cny", "to_ticker": "aapl", "from_amount": "1", "to_amount": "1",
            "commission": "0", "commission_asset": "",
        }, default_currency="USD")


def test_investment_projection_soft_start_oversell_and_numeric_overflow():
    """Soft-start oversell is intentional for partial statement history (009/import).

    Selling more equity than held must not abort; CHECKIN realigns later.
    NUMERIC(38,18) overflow still fail-closed.
    """
    from ft.domain.investment_projection import apply_investment_event

    snapshot = {"accounts": {"security": {"IBKR": {
        "currency": "USD", "positions": {
            "aapl": {"shares": "1", "total_cost": "10", "cost_currency": "USD"},
        },
    }}}}
    apply_investment_event(snapshot, {
        "date": "2026-07-17", "record_type": "trade", "record_subtype": "security", "currency": "USD", "account_name": "IBKR",
        "from_ticker": "aapl", "to_ticker": "usd", "from_amount": "2", "to_amount": "40",
        "commission": "0", "commission_asset": "",
    }, default_currency="USD")
    pos = snapshot["accounts"]["security"]["IBKR"]["positions"]
    assert Decimal(pos["aapl"]["shares"]) == Decimal("-1")
    assert Decimal(pos["usd"]["shares"]) == Decimal("40")

    with pytest.raises(ValueError, match=r"NUMERIC\(38,18\)"):
        apply_investment_event({"accounts": {}}, {
            "date": "2026-07-17", "record_type": "funding", "record_subtype": "external", "currency": "USD", "account_name": "IBKR",
            "from_ticker": "", "to_ticker": "usd", "from_amount": "0", "to_amount": "1e100",
            "commission": "0", "commission_asset": "",
        }, default_currency="USD")


class FakePortfolioRepository:
    def load_portfolio(self):
        return {
            "accounts": {
                "IBKR": {
                    "currency": "USD",
                    "positions": {
                        "usd": {"shares": 10, "total_cost": 10, "cost_currency": "USD"},
                        "aapl.us": {"shares": 2, "total_cost": 6, "cost_currency": "USD"},
                        "usdt": {"shares": 3, "total_cost": 3, "cost_currency": "USDT"},
                    },
                }
            },
            "base_currencies": {"IBKR": ("USD",)},
            "configured_currencies": ("USD", "USDT"),
        }


class FakeQuoteProvider:
    def __init__(self):
        self.calls = []

    def raw_quote(self, identity, kind):
        from datetime import datetime, timezone
        from ft.application.valuation import UnsupportedQuote
        from ft.domain.valuation import ProviderTick

        self.calls.append((identity, kind))
        if identity == "aapl.us":
            return ProviderTick(
                Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake"
            )
        raise UnsupportedQuote(identity)


def test_portfolio_query_uses_valuation_and_never_prices_configured_currency():
    from datetime import datetime, timezone

    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService

    provider = FakeQuoteProvider()
    result = PortfolioQueryService(
        FakePortfolioRepository(),
        ValuationService(
            provider,
            clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc),
        ),
    ).get_portfolio()

    account = result.accounts[0]
    by_ticker = {position.ticker: position for position in account.positions}
    assert ("aapl.us", __import__("ft.domain.valuation", fromlist=["AssetKind"]).AssetKind.SECURITY) in provider.calls
    assert by_ticker["usd"].is_cash is True
    assert by_ticker["aapl.us"].market_value == Decimal("10")
    assert by_ticker["aapl.us"].quote_status == "complete"
    assert by_ticker["usdt"].market_value is None
    assert by_ticker["usdt"].quote_status == "unsupported"


def test_portfolio_query_total_profit_rate_uses_cash_in_the_denominator():
    from datetime import datetime, timezone
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import ProviderTick

    class Repository:
        def load_portfolio(self):
            return {
                "accounts": {"IBKR": {"currency": "USD", "positions": {
                    "usd": {"shares": "10", "total_cost": "10", "cost_currency": "USD"},
                    "aapl.us": {"shares": "2", "total_cost": "6", "cost_currency": "USD"},
                }}},
                "base_currencies": {"IBKR": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class Provider:
        def raw_quote(self, identity, kind):
            return ProviderTick(Decimal("5"), "USD", datetime(2026, 7, 25, tzinfo=timezone.utc), "fake")

    result = PortfolioQueryService(
        Repository(), ValuationService(Provider(), clock=lambda: datetime(2026, 7, 25, tzinfo=timezone.utc)),
    ).get_portfolio()

    assert result.total_market_value == Decimal("20")
    assert result.total_profit == Decimal("4")
    assert result.total_profit_rate == Decimal("0.2")


def test_portfolio_query_includes_realized_and_floating_period_pnl():
    from datetime import datetime, timedelta, timezone
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import AssetKind, AssetRef, ProviderTick

    start = datetime(2026, 8, 1, tzinfo=timezone.utc)
    now = start + timedelta(hours=24)

    class Repository(FakePortfolioRepository):
        def load_portfolio(self):
            result = super().load_portfolio()
            result["accounts"]["IBKR"]["positions"] = {
                "usd": {"shares": "400", "total_cost": "400", "cost_currency": "USD"},
                "aapl.us": {"shares": "11", "total_cost": "1118.333333333333333333", "cost_currency": "USD"},
            }
            result["investment_events"] = (
                {"occurred_at": start + timedelta(hours=4), "account": "IBKR", "record_type": "trade", "currency": "USD", "from_ticker": "usd", "from_amount": "220", "to_ticker": "aapl.us", "to_amount": "2", "commission": "0", "commission_asset": "usd"},
                {"occurred_at": start + timedelta(hours=16), "account": "IBKR", "record_type": "trade", "currency": "USD", "from_ticker": "aapl.us", "from_amount": "1", "to_ticker": "usd", "to_amount": "120", "commission": "0", "commission_asset": "usd"},
            )
            return result

    class Provider:
        def raw_quote(self, identity, kind):
            return ProviderTick(Decimal("120"), "USD", now, "current")

        def raw_quote_at(self, identity, kind, *, at):
            return ProviderTick(Decimal("100"), "USD", at, "history")

    result = PortfolioQueryService(
        Repository(), ValuationService(Provider(), clock=lambda: now), clock=lambda: now,
    ).get_portfolio(period="24h")

    position = next(item for item in result.accounts[0].positions if item.ticker == "aapl.us")
    assert position.period_profit == Decimal("220")
    assert result.period_profit == Decimal("220")
    expected_capital = Decimal("1000") + Decimal("220") * Decimal("20") / Decimal("24") - Decimal("120") * Decimal("8") / Decimal("24")
    assert position.period_profit_rate == Decimal("220") / expected_capital


def test_portfolio_query_excludes_external_funding_and_counts_income_in_period_pnl():
    from datetime import datetime, timedelta, timezone
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import ProviderTick

    start = datetime(2026, 8, 1, tzinfo=timezone.utc)
    now = start + timedelta(hours=24)

    class Repository(FakePortfolioRepository):
        def load_portfolio(self):
            result = super().load_portfolio()
            result["accounts"]["IBKR"]["positions"] = {
                "usd": {"shares": "1600", "total_cost": "1600", "cost_currency": "USD"},
                "aapl.us": {"shares": "10", "total_cost": "1000", "cost_currency": "USD"},
            }
            result["investment_events"] = (
                {"occurred_at": start + timedelta(hours=8), "account": "IBKR", "record_type": "funding", "record_subtype": "external", "currency": "USD", "from_ticker": "", "from_amount": "0", "to_ticker": "usd", "to_amount": "500", "commission": "0", "commission_asset": ""},
            )
            return result

    class Provider:
        def raw_quote(self, identity, kind):
            return ProviderTick(Decimal("100"), "USD", now, "current")

        def raw_quote_at(self, identity, kind, *, at):
            return ProviderTick(Decimal("100"), "USD", at, "history")

    result = PortfolioQueryService(
        Repository(), ValuationService(Provider(), clock=lambda: now), clock=lambda: now,
    ).get_portfolio(period="24h")

    assert result.period_profit == Decimal("0")


def test_portfolio_query_counts_profit_from_a_position_sold_during_period():
    from datetime import datetime, timedelta, timezone
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService
    from ft.domain.valuation import ProviderTick

    start = datetime(2026, 8, 1, tzinfo=timezone.utc)
    now = start + timedelta(hours=24)

    class Repository(FakePortfolioRepository):
        def load_portfolio(self):
            result = super().load_portfolio()
            result["accounts"]["IBKR"]["positions"] = {
                "usd": {"shares": "1120", "total_cost": "1120", "cost_currency": "USD"},
            }
            result["investment_events"] = (
                {"occurred_at": start + timedelta(hours=4), "account": "IBKR", "record_type": "trade", "currency": "USD", "from_ticker": "usd", "from_amount": "100", "to_ticker": "aapl.us", "to_amount": "1", "commission": "0", "commission_asset": ""},
                {"occurred_at": start + timedelta(hours=16), "account": "IBKR", "record_type": "trade", "currency": "USD", "from_ticker": "aapl.us", "from_amount": "1", "to_ticker": "usd", "to_amount": "120", "commission": "0", "commission_asset": ""},
            )
            return result

    class Provider:
        def raw_quote(self, identity, kind):
            return ProviderTick(Decimal("1"), "USD", now, "current")

        def raw_quote_at(self, identity, kind, *, at):
            return ProviderTick(Decimal("1"), "USD", at, "history")

    result = PortfolioQueryService(
        Repository(), ValuationService(Provider(), clock=lambda: now), clock=lambda: now,
    ).get_portfolio(period="24h")

    assert result.period_profit == Decimal("20")
    assert all(position.ticker != "aapl.us" for position in result.accounts[0].positions)


def test_investment_application_imports_do_not_touch_home(monkeypatch):
    def fail_home():
        raise AssertionError("investment application import touched home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.application.investment
    import ft.domain.investment

    assert ft.application.investment.InvestmentService


def test_cli_stock_leaves_enter_investment_services(monkeypatch, capsys):
    from ft import cli
    from ft.domain.application import OperationResult
    from ft.domain.investment import PortfolioDTO

    calls = []

    class Investments:
        def buy(self, *args):
            calls.append(("buy", args))
            return OperationResult(ok=True, message="bought")

    class Portfolio:
        def get_portfolio(self, *, display_currency=None):
            calls.append(("list",))
            return PortfolioDTO(())

    bundle = type("Bundle", (), {"investments": Investments(), "portfolio": Portfolio()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)
    cli.main(["stock", "buy", "--ticker", "AAPL.US", "--shares", "1", "--price", "2", "--account", "IBKR"])
    cli.main(["stock", "list"])

    assert [call[0] for call in calls] == ["buy", "list"]
    assert "bought" in capsys.readouterr().out


def test_cli_stock_list_contains_provider_diagnostics(monkeypatch, capsys):
    from ft import cli
    from ft.domain.investment import PortfolioAccountDTO, PortfolioDTO, PortfolioPositionDTO

    class Portfolio:
        def get_portfolio(self, *, display_currency=None):
            print("third-party diagnostic")
            return PortfolioDTO((PortfolioAccountDTO("Broker", "USD", (
                PortfolioPositionDTO(
                    ticker="pm:slow:yes", shares=Decimal("1"), total_cost=Decimal("1"),
                    cost_currency="USD", is_cash=False, current_price=None, market_value=None,
                    profit=None, quote_status="partial", quote_reason="query_deadline_exceeded",
                    quote_currency=None,
                ),
            )),))

    bundle = type("Bundle", (), {"portfolio": Portfolio(), "investments": object()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)
    cli.main(["stock", "list"])
    output = capsys.readouterr()
    assert "pm:slow:yes" in output.out
    assert "N/A" in output.out
    assert "third-party diagnostic" not in output.out + output.err


def test_cli_stock_list_contains_late_yfinance_diagnostics_after_quote_deadline(
    monkeypatch, capsys, caplog,
):
    from ft import cli
    from ft.application.investment import PortfolioQueryService
    from ft.application.valuation import ValuationService

    class Repository:
        def load_portfolio(self):
            return {
                "accounts": {"Broker": {"currency": "USD", "positions": {
                    "aapl.us": {"shares": "1", "total_cost": "1", "cost_currency": "USD"},
                }}},
                "base_currencies": {"Broker": ("USD",)},
                "configured_currencies": ("USD",),
            }

    class DelayedYfinanceProvider:
        def raw_quote(self, identity, kind):
            def emit_late_diagnostic():
                time.sleep(0.05)
                logging.getLogger("yfinance").warning("late yfinance diagnostic")
            Thread(target=emit_late_diagnostic, daemon=True).start()
            time.sleep(1)

    portfolio = PortfolioQueryService(
        Repository(), ValuationService(DelayedYfinanceProvider()), query_deadline_seconds=0.01,
    )
    bundle = type("Bundle", (), {"portfolio": portfolio, "investments": object()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)
    logger = logging.getLogger("yfinance")
    monkeypatch.setattr(logger, "disabled", False)
    caplog.set_level(logging.WARNING, logger="yfinance")

    cli.main(["stock", "list"])
    time.sleep(0.1)
    assert "aapl.us" in capsys.readouterr().out
    assert "late yfinance diagnostic" not in caplog.messages


def test_cli_stock_service_rejection_exits_nonzero(monkeypatch, capsys):
    from ft import cli
    from ft.domain.application import OperationResult

    class Investments:
        def buy(self, *args):
            return OperationResult(ok=False, message="account not found: Missing")

    bundle = type("Bundle", (), {"investments": Investments()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)

    with pytest.raises(SystemExit) as exc:
        cli.main([
            "stock", "buy", "--ticker", "AAPL.US", "--shares", "1",
            "--price", "2", "--account", "Missing",
        ])

    assert exc.value.code == 1
    assert "account not found: Missing" in capsys.readouterr().out


def test_created_investment_account_currency_is_valued_as_cash():
    from test_postgres_adapter import _database
    from ft.adapters.relational.investments import RelationalInvestmentCommandRepository
    from ft.adapters.relational.queries import RelationalPortfolioRepository
    from ft.application.accounts import AccountService
    from ft.application.investment import InvestmentService, PortfolioQueryService

    sessions, unit_of_work = _database()
    assert AccountService(unit_of_work(sessions, "workspace-a")).create_account(
        "Broker", "security", "USD"
    ).ok
    service = InvestmentService(repository=RelationalInvestmentCommandRepository(
        unit_of_work(sessions, "workspace-a")
    ))
    assert service.deposit("100", "USD", "Broker").ok

    class EmptyProvider:
        def raw_quote(self, identity, kind):
            from ft.application.valuation import UnsupportedQuote
            raise UnsupportedQuote(identity)

    from ft.application.valuation import ValuationService

    result = PortfolioQueryService(
        RelationalPortfolioRepository(sessions, "workspace-a"),
        ValuationService(EmptyProvider()),
    ).get_portfolio()

    position = result.accounts[0].positions[0]
    assert position.ticker == "usd"
    assert position.is_cash is True
    assert position.market_value == Decimal("100")
    assert position.quote_status == "complete"


def test_postgres_investment_commands_write_events_and_projection_atomically():
    from test_postgres_adapter import _database
    from ft.adapters.relational.investments import RelationalInvestmentCommandRepository
    from ft.application.investment import InvestmentService

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({
            "name": "IBKR", "type": "security", "currency": "USD",
            "base_currencies": ["USD"],
        })
        uow.commit()

    service = InvestmentService(repository=RelationalInvestmentCommandRepository(
        unit_of_work(sessions, "workspace-a")
    ))
    assert service.deposit("100", "USD", "IBKR", "seed", "2026-07-17").ok
    assert service.buy("AAPL.US", "2", "10", "1", "USD", "IBKR", "buy", "2026-07-17").ok
    assert service.sell("AAPL.US", "1", "12", "1", "USD", "IBKR", "sell", "2026-07-17").ok
    assert service.dividend("AAPL.US", "2", "USD", "IBKR", "dividend", "2026-07-17").ok
    assert service.checkin_ticker("AAPL.US", "3", "8", "USD", "IBKR", "check", "2026-07-17").ok
    assert service.swap("IBKR", "AAPL.US", "1", "MSFT.US", "2", "USD", "swap", "2026-07-17").ok
    assert service.withdraw("2", "USD", "IBKR", "withdraw", "2026-07-17").ok
    assert service.checkin_cash("8.75", "USD", "IBKR", "cash", "2026-07-17").ok

    with unit_of_work(sessions, "workspace-a") as uow:
        assert len(uow.investments.list()) == 8
        snapshot = uow.snapshot.load()
        positions = snapshot["accounts"]["security"]["IBKR"]["positions"]
        assert positions["usd"]["shares"] == "8.75"
        assert positions["aapl.us"]["shares"] == "2"
        assert Decimal(positions["aapl.us"]["total_cost"]) == Decimal("16")
        assert positions["aapl.us"]["cost_currency"] == "USD"
        assert positions["msft.us"]["shares"] == "2"
        assert Decimal(positions["msft.us"]["total_cost"]) == Decimal("8")
        assert positions["msft.us"]["cost_currency"] == "USD"
        uow.commit()


@pytest.mark.parametrize("action", ["sell", "swap"])
def test_computed_investment_projection_released_cost_quantized_to_18dp(action):
    from ft.adapters.relational.investments import RelationalInvestmentCommandRepository
    from ft.application.investment import InvestmentService
    from test_postgres_adapter import _database

    sessions, unit_of_work = _database()
    with unit_of_work(sessions, "workspace-a") as uow:
        uow.accounts.add_raw({
            "name": "IBKR", "type": "security", "currency": "USD",
            "base_currencies": ["USD"],
        })
        uow.snapshot.save({
            "accounts": {"security": {"IBKR": {
                "currency": "USD",
                "positions": {
                    "aapl.us": {
                        "shares": "3", "total_cost": "1", "cost_currency": "USD",
                    },
                },
            }}},
        })
        uow.commit()

    service = InvestmentService(
        repository=RelationalInvestmentCommandRepository(unit_of_work(sessions, "workspace-a"))
    )
    # Released cost uses _div quantized to 18dp (soft import path); must not raise
    # and must not leave unquantized scale > 18.
    if action == "sell":
        assert service.sell("AAPL.US", "1", "1", "0", "USD", "IBKR").ok
    else:
        assert service.swap("IBKR", "AAPL.US", "1", "MSFT.US", "1", "USD").ok

    with unit_of_work(sessions, "workspace-a") as uow:
        assert len(uow.investments.list()) == 1
        positions = uow.snapshot.load()["accounts"]["security"]["IBKR"]["positions"]
        remaining = Decimal(positions["aapl.us"]["total_cost"])
        # 1 - 1/3 = 2/3 → at most 18 fractional digits after quantize
        assert abs(remaining.as_tuple().exponent) <= 18 or remaining.as_tuple().exponent >= 0
        uow.commit()

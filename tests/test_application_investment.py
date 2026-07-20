from decimal import Decimal
from pathlib import Path

import pytest


class FakeInvestmentRepository:
    def __init__(self):
        self.commands = []
        self.appended = None

    def execute(self, command):
        from ft.domain.application import OperationResult
        self.commands.append(command)
        return OperationResult(ok=True, message=command.action)

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

    assert [command.action for command in repository.commands] == [
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
        "date": "2026-07-17", "action": "swap", "currency": "USD", "account_name": "IBKR",
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
            "date": "2026-07-17", "action": "swap", "currency": "CNY", "account_name": "IBKR",
            "from_ticker": "cny", "to_ticker": "aapl", "from_amount": "1", "to_amount": "1",
            "commission": "0", "commission_asset": "",
        }, default_currency="USD")


def test_investment_projection_rejects_short_positions_and_numeric_overflow():
    from ft.domain.investment_projection import apply_investment_event

    snapshot = {"accounts": {"security": {"IBKR": {
        "currency": "USD", "positions": {
            "aapl": {"shares": "1", "total_cost": "10", "cost_currency": "USD"},
        },
    }}}}
    with pytest.raises(ValueError, match="insufficient aapl position"):
        apply_investment_event(snapshot, {
            "date": "2026-07-17", "action": "swap", "currency": "USD", "account_name": "IBKR",
            "from_ticker": "aapl", "to_ticker": "usd", "from_amount": "2", "to_amount": "40",
            "commission": "0", "commission_asset": "",
        }, default_currency="USD")

    with pytest.raises(ValueError, match=r"NUMERIC\(38,18\)"):
        apply_investment_event({"accounts": {}}, {
            "date": "2026-07-17", "action": "deposit", "currency": "USD", "account_name": "IBKR",
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


class FakeMarketData:
    def __init__(self):
        self.calls = []

    def get_prices(self, tickers, *, quote_currency):
        self.calls.append((tuple(tickers), quote_currency))
        return {"aapl.us": Decimal("5")}


def test_portfolio_query_uses_market_port_and_never_prices_configured_currency():
    from ft.application.investment import PortfolioQueryService

    market = FakeMarketData()
    result = PortfolioQueryService(FakePortfolioRepository(), market).get_portfolio()

    account = result.accounts[0]
    by_ticker = {position.ticker: position for position in account.positions}
    assert market.calls == [(('aapl.us',), 'USD')]
    assert by_ticker["usd"].is_cash is True
    assert by_ticker["aapl.us"].market_value == Decimal("10")
    assert by_ticker["usdt"].market_value is None


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
        def get_portfolio(self):
            calls.append(("list",))
            return PortfolioDTO(())

    bundle = type("Bundle", (), {"investments": Investments(), "portfolio": Portfolio()})()
    monkeypatch.setattr("ft.config.StorageSettings.load", lambda: object())
    monkeypatch.setattr("ft.cli.build_services", lambda _settings: bundle)
    cli.main(["stock", "buy", "--ticker", "AAPL.US", "--shares", "1", "--price", "2", "--account", "IBKR"])
    cli.main(["stock", "list"])

    assert [call[0] for call in calls] == ["buy", "list"]
    assert "bought" in capsys.readouterr().out


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

    class MarketData:
        def __init__(self):
            self.calls = []

        def get_prices(self, tickers, *, quote_currency):
            self.calls.append((tuple(tickers), quote_currency))
            return {}

    market = MarketData()
    result = PortfolioQueryService(
        RelationalPortfolioRepository(sessions, "workspace-a"), market
    ).get_portfolio()

    position = result.accounts[0].positions[0]
    assert position.ticker == "usd"
    assert position.is_cash is True
    assert position.market_value == Decimal("100")
    assert market.calls == []


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
        assert positions["aapl.us"] == {
            "shares": "2", "total_cost": "16", "cost_currency": "USD",
        }
        assert positions["msft.us"] == {
            "shares": "2", "total_cost": "8", "cost_currency": "USD",
        }
        uow.commit()


@pytest.mark.parametrize("action", ["sell", "swap"])
def test_computed_investment_projection_scale_over_18_rolls_back_atomically(action):
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
    with pytest.raises(ValueError, match="at most 18 decimal places"):
        if action == "sell":
            service.sell("AAPL.US", "1", "1", "0", "USD", "IBKR")
        else:
            service.swap("IBKR", "AAPL.US", "1", "MSFT.US", "1", "USD")

    with unit_of_work(sessions, "workspace-a") as uow:
        assert uow.investments.list() == []
        positions = uow.snapshot.load()["accounts"]["security"]["IBKR"]["positions"]
        assert positions == {
            "aapl.us": {"shares": "3", "total_cost": "1", "cost_currency": "USD"},
        }
        uow.commit()

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


class FakeInvestmentImporter:
    def __init__(self, converted=(), incoming=()):
        self.converted = [dict(row) for row in converted]
        self.incoming = [dict(row) for row in incoming]

    def convert(self, command):
        return [dict(row) for row in self.converted]

    def read_converted(self, source):
        return [dict(row) for row in self.incoming]


class FakeChanges:
    def __init__(self):
        self.staged = 0

    def stage(self):
        self.staged += 1


def _investment_service(repository=None, importer=None, changes=None):
    from ft.application.investment import InvestmentService

    return InvestmentService(
        repository=repository or FakeInvestmentRepository(),
        importer=importer or FakeInvestmentImporter(),
        change_sets=changes or FakeChanges(),
    )


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


def test_investment_convert_is_read_only_and_append_stages_once():
    from ft.domain.investment import InvestmentConvertCommand

    row = {"date": "2026-06-01", "action": "deposit", "to_amount": "1"}
    repository = FakeInvestmentRepository()
    importer = FakeInvestmentImporter(converted=[row], incoming=[row])
    changes = FakeChanges()
    service = _investment_service(repository, importer, changes)

    converted = service.convert(InvestmentConvertCommand("statement.pdf", "dfzq"))
    appended = service.append("converted.csv")

    assert converted.export.rows == (row,)
    assert converted.count == 1
    assert repository.appended == [row]
    assert appended.count == 1
    assert changes.staged == 1


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


def test_cli_stock_leaves_enter_investment_services(monkeypatch, tmp_path, capsys):
    from ft import cli
    from ft.domain.application import ExportPayload, OperationResult
    from ft.domain.investment import PortfolioDTO

    calls = []

    class Investments:
        def buy(self, *args):
            calls.append(("buy", args))
            return OperationResult(ok=True, message="bought")

        def convert(self, command):
            calls.append(("convert", command))
            return OperationResult(ok=True, count=1, export=ExportPayload(({"action": "deposit"},), fieldnames=("action",)))

        def append(self, source):
            calls.append(("append", source))
            return OperationResult(ok=True, count=1)

    class Portfolio:
        def get_portfolio(self):
            calls.append(("list",))
            return PortfolioDTO(())

    bundle = type("Bundle", (), {"investments": Investments(), "portfolio": Portfolio()})()
    monkeypatch.setattr("ft.cli.build_local_services", lambda _root: bundle)
    monkeypatch.setattr("ft.cli.write_csv_export", lambda payload, output: calls.append(("write", output)))

    cli.main(["stock", "buy", "--ticker", "AAPL.US", "--shares", "1", "--price", "2", "--account", "IBKR"])
    cli.main(["stock", "convert", "statement.pdf", "-s", "dfzq", "-o", str(tmp_path / "out.csv")])
    cli.main(["stock", "append", "rows.csv"])
    cli.main(["stock", "list"])

    assert [call[0] for call in calls] == ["buy", "convert", "write", "append", "list"]
    assert "bought" in capsys.readouterr().out

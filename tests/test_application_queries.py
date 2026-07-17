from decimal import Decimal
from pathlib import Path


class FakeAccounts:
    def __init__(self, accounts):
        self.accounts = list(accounts)

    def list_accounts(self):
        return list(self.accounts)


class FakeTransactions:
    def __init__(self, rows):
        self.rows = [dict(row) for row in rows]

    def list_transactions(self, *, month=None, account=None, category=None):
        rows = self.rows
        if month:
            rows = [row for row in rows if row["date"].startswith(month)]
        if account:
            rows = [row for row in rows if row.get("account_name") == account]
        if category:
            rows = [row for row in rows if row.get("category") == category]
        return [dict(row) for row in rows]


class FakeSnapshot:
    def __init__(self, snapshot):
        self.snapshot = snapshot

    def load_snapshot(self):
        return self.snapshot


class FakeMarketData:
    def __init__(self, prices):
        self.prices = prices
        self.calls = []

    def get_prices(self, tickers, *, quote_currency):
        self.calls.append((tuple(tickers), quote_currency))
        return {ticker: self.prices[ticker] for ticker in tickers if ticker in self.prices}


def _service(rows=()):
    from ft.application.queries import FinanceQueryService
    from ft.domain.accounts import AccountDTO

    accounts = [
        AccountDTO("Cash", "cash", "CNY"),
        AccountDTO("Broker", "security", "USD"),
    ]
    snapshot = {
        "accounts": {
            "cash": {"Cash": {"CNY": 12.34}},
            "security": {
                "Broker": {
                    "currency": "USD",
                    "positions": {
                        "usd": {"shares": 10, "total_cost": 10},
                        "aapl.us": {"shares": 2, "total_cost": 6},
                        "missing.us": {"shares": 3, "total_cost": 9},
                    },
                }
            },
        }
    }
    market = FakeMarketData({"aapl.us": Decimal("5")})
    return FinanceQueryService(
        accounts=FakeAccounts(accounts),
        transactions=FakeTransactions(rows),
        snapshots=FakeSnapshot(snapshot),
        market_data=market,
    ), market


def test_list_accounts_values_cash_and_investment_with_cost_fallback():
    service, market = _service()

    result = service.list_accounts()

    assert [(item.name, item.balance) for item in result.accounts] == [
        ("Cash", Decimal("12.34")),
        ("Broker", Decimal("29")),
    ]
    assert market.calls == [(('aapl.us', 'missing.us'), 'USD')]


def test_report_returns_structured_totals_and_ignores_pre_checkin_expense():
    rows = [
        {"date": "2026-06-01 09:00:00", "account_name": "Cash", "currency": "CNY", "category": "expense", "amount": "-100", "description": "old"},
        {"date": "2026-06-02 09:00:00", "account_name": "Cash", "currency": "CNY", "category": "checkin", "amount": "0", "description": "余额校准1000"},
        {"date": "2026-06-03 09:00:00", "account_name": "Cash", "currency": "CNY", "category": "expense", "amount": "-12.50", "description": "meal"},
        {"date": "2026-06-04 09:00:00", "account_name": "Cash", "currency": "CNY", "category": "income", "amount": "20", "description": "gift"},
        {"date": "2026-05-01 09:00:00", "account_name": "Cash", "currency": "CNY", "category": "transfer_out", "amount": "-5", "transfer_account": "Broker"},
    ]
    service, _ = _service(rows)

    result = service.report(month="2026-06")

    assert result.expenses == {"CNY": Decimal("12.50")}
    assert result.income == {"CNY": Decimal("20")}
    assert result.flows[0].description == "Broker"
    assert result.flows[0].amount == Decimal("5")
    assert result.accounts.accounts[0].balance == Decimal("12.34")


def test_list_transactions_filters_sorts_and_limits_as_dtos():
    rows = [
        {"date": "2026-06-01", "account_name": "Cash", "currency": "CNY", "category": "expense", "amount": "-1", "description": "one"},
        {"date": "2026-06-03", "account_name": "Cash", "currency": "CNY", "category": "expense", "amount": "-3", "description": "three"},
        {"date": "2026-06-02", "account_name": "Other", "currency": "CNY", "category": "expense", "amount": "-2", "description": "two"},
    ]
    service, _ = _service(rows)

    result = service.list_transactions(month="2026-06", account="Cash", limit=1)

    assert len(result.items) == 1
    assert result.items[0].description == "three"
    assert result.items[0].amount == Decimal("-3")


def test_query_modules_import_without_home(monkeypatch):
    def fail_home():
        raise AssertionError("query import touched home")

    monkeypatch.setattr(Path, "home", fail_home)
    import ft.application.queries
    import ft.domain.queries

    assert ft.application.queries.FinanceQueryService
    assert ft.domain.queries.FinanceReportDTO


def test_cli_report_and_list_enter_query_service(monkeypatch, capsys):
    from ft import cli
    from ft.domain.queries import (
        AccountListDTO,
        FinanceReportDTO,
        TransactionDTO,
        TransactionPageDTO,
    )

    calls = []

    class FakeService:
        def report(self, *, month=None):
            calls.append(("report", month))
            return FinanceReportDTO(accounts=AccountListDTO(()))

        def list_transactions(self, **kwargs):
            calls.append(("list", kwargs))
            return TransactionPageDTO((TransactionDTO(
                date="2026-06-03", account_name="Cash", currency="CNY",
                category="expense", amount=Decimal("-3"), description="meal",
            ),))

    bundle = type("Bundle", (), {"queries": FakeService()})()
    monkeypatch.setattr("ft.cli.build_local_services", lambda _root: bundle)

    cli.main(["report", "--month", "2026-06"])
    cli.main(["list", "--month", "2026-06", "--limit", "1"])

    assert calls == [
        ("report", "2026-06"),
        ("list", {"month": "2026-06", "account": None, "category": None, "limit": 1}),
    ]
    assert "meal" in capsys.readouterr().out

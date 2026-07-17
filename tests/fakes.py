"""Reusable in-memory test doubles for application-service tests."""


class FakeTransactionQueryRepository:
    def __init__(self, rows=()):
        self.rows = [dict(row) for row in rows]

    def list_transactions(self, *, month=None, account=None, category=None):
        rows = self.rows
        if month:
            rows = [row for row in rows if row.get("date", "").startswith(month)]
        if account:
            rows = [row for row in rows if row.get("account_name") == account]
        if category:
            rows = [row for row in rows if row.get("category") == category]
        return [dict(row) for row in rows]


class FakeMarketDataProvider:
    def __init__(self, prices=None):
        self.prices = dict(prices or {})
        self.calls = []

    def get_prices(self, tickers, *, quote_currency):
        self.calls.append((tuple(tickers), quote_currency))
        return {ticker: self.prices[ticker] for ticker in tickers if ticker in self.prices}


class FakeChangeSetRepository:
    def __init__(self, changed_files=()):
        self.changed_files = tuple(changed_files)
        self.commits = []
        self.reset_calls = 0

    def status(self):
        return self.changed_files

    def commit(self, message=None):
        self.commits.append(message)
        return bool(self.changed_files)

    def reset(self):
        self.reset_calls += 1
        return self.changed_files

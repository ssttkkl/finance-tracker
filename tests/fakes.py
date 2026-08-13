"""Reusable in-memory test doubles for application-service tests."""


class FakeTransactionQueryRepository:
    def __init__(self, rows=()):
        self.rows = [dict(row) for row in rows]

    def list_transactions(self, *, month=None, account=None, category_id=None, limit=None):
        rows = self.rows
        if month:
            rows = [row for row in rows if row.get("date", "").startswith(month)]
        if account:
            rows = [row for row in rows if row.get("account_name") == account]
        if category_id:
            rows = [row for row in rows if row.get("category_id") == category_id]
        rows = sorted(rows, key=lambda row: row.get("date", ""), reverse=True)
        return [dict(row) for row in (rows[:limit] if limit is not None else rows)]


class FakeMarketDataProvider:
    def __init__(self, prices=None):
        self.prices = dict(prices or {})
        self.calls = []

    def get_prices(self, tickers, *, quote_currency):
        self.calls.append((tuple(tickers), quote_currency))
        return {ticker: self.prices[ticker] for ticker in tickers if ticker in self.prices}


class FakeValuationService:
    """Minimal stand-in exposing quote/quote_many for portfolio tests."""

    def __init__(self, prices=None):
        from datetime import datetime, timezone
        from decimal import Decimal
        from ft.domain.valuation import QuoteResult, QuoteStatus, AssetKind

        self.prices = {k: Decimal(str(v)) for k, v in dict(prices or {}).items()}
        self._QuoteResult = QuoteResult
        self._QuoteStatus = QuoteStatus
        self._AssetKind = AssetKind
        self._now = datetime(2026, 7, 25, tzinfo=timezone.utc)

    def quote(self, ref=None, *, identity="", kind=None, quantity=None):
        from decimal import Decimal
        from ft.domain.valuation import AssetRef, make_asset_ref

        asset = ref if ref is not None else make_asset_ref(identity, kind, quantity)
        price = self.prices.get(asset.identity)
        if price is None:
            return self._QuoteResult(
                identity=asset.identity,
                kind=asset.kind,
                status=self._QuoteStatus.UNSUPPORTED,
                reason="unsupported_identity",
                quantity=asset.quantity,
            )
        mv = price * asset.quantity if asset.quantity is not None else None
        return self._QuoteResult(
            identity=asset.identity,
            kind=asset.kind,
            status=self._QuoteStatus.COMPLETE,
            unit_price=price,
            quote_currency="USD",
            observed_at=self._now,
            market_value=mv,
            quantity=asset.quantity,
            reason="ok",
            provider="fake",
        )

"""Local compatibility adapter for current market-price providers."""


class LegacyMarketDataProvider:
    def get_prices(self, tickers, *, quote_currency):
        if not tickers:
            return {}
        from ft.stock import _fetch_prices

        return _fetch_prices(list(tickers))

"""Unit tests for CcxtExchangeConnector (T016-T019)."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import MagicMock

import pytest

from ft.domain.connector_port import ConnectorAuthError, ConnectorDataError, ConnectorError
from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector


FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"


def _load_trades():
    with open(FIXTURES_DIR / "ccxt_trades.json") as f:
        return json.load(f)


class FakeCcxtClient:
    """Mock ccxt exchange client."""
    def __init__(self, trades=None, pages=None, ledger=None, ledger_pages=None, error=None):
        self._trades = trades or []
        self._pages = pages  # list of lists for multi-page
        self._ledger = ledger or []
        self._ledger_pages = ledger_pages
        self._error = error
        self._call_count = 0

    def fetch_my_trades(self, symbol=None, since=None, limit=None):
        self._call_count += 1
        if self._error:
            raise self._error
        if self._pages:
            idx = self._call_count - 1
            if idx < len(self._pages):
                return self._pages[idx]
            return []
        return self._trades

    def fetch_ledger(self, code=None, since=None, limit=None, params=None):
        if self._ledger_pages is not None:
            offset = (params or {}).get("ofs", 0)
            return self._ledger_pages.get(offset, [])
        return self._ledger


class TestTradeMapping:
    """T016: ccxt trade → investment event mapping."""

    def test_buy_base_quote(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trades = [_load_trades()[0]]  # ETH/USDT BUY
        client = FakeCcxtClient(trades=trades)
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 1
        event = result.events[0]
        assert event["action"] == "swap"
        assert event["from_ticker"] == "usdt"
        assert event["to_ticker"] == "eth"
        assert event["to_amount"] == "1.5"
        assert event["from_amount"] == "4500.75"
        assert event["commission"] == "0.001"
        assert event["commission_asset"] == "eth"
        assert event["record_id"] == "t1"

    def test_sell_base_quote(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trades = [_load_trades()[1]]  # BTC/USDT SELL
        client = FakeCcxtClient(trades=trades)
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        result = connector.fetch_trades()
        event = result.events[0]
        assert event["from_ticker"] == "btc"
        assert event["to_ticker"] == "usdt"
        assert event["from_amount"] == "0.1"
        assert event["to_amount"] == "6000"
        assert event["commission"] == "6"
        assert event["commission_asset"] == "usdt"

    def test_crypto_to_crypto(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trades = [_load_trades()[2]]  # ETH/BTC BUY
        client = FakeCcxtClient(trades=trades)
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        result = connector.fetch_trades()
        event = result.events[0]
        assert event["from_ticker"] == "btc"
        assert event["to_ticker"] == "eth"
        assert event["from_amount"] == "0.5"
        assert event["to_amount"] == "10"

    def test_null_fee(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trades = [_load_trades()[3]]  # SOL/USDT with null fee
        client = FakeCcxtClient(trades=trades)
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        result = connector.fetch_trades()
        event = result.events[0]
        assert event["commission"] == "0"


class TestPagination:
    """T017: fetch_my_trades pagination."""

    def test_multi_page(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        page1 = [{"id": "1", "symbol": "ETH/USDT", "side": "buy", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}]
        page2 = [{"id": "2", "symbol": "ETH/USDT", "side": "buy", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 2000}]
        client = FakeCcxtClient(pages=[page1, page2, []])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client, page_limit=1,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 2
        assert result.next_cursor == "2001"

    def test_empty_response(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        client = FakeCcxtClient(trades=[])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 0
        assert result.next_cursor is None

    def test_repeated_full_trade_page_fails_closed(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trade = {"id": "dup1", "symbol": "ETH/USDT", "side": "buy", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}
        client = FakeCcxtClient(pages=[[trade], [trade], []])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client, page_limit=1,
        )
        with pytest.raises(ConnectorDataError, match="pagination made no progress"):
            connector.fetch_trades()


class TestRetry:
    """T018: retry logic."""

    def test_auth_error_no_retry(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        error = Exception("401 Unauthorized")
        client = FakeCcxtClient(error=error)
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        with pytest.raises(ConnectorAuthError):
            connector.fetch_trades()
        assert client._call_count == 1  # no retry


class TestDataValidation:
    """T019: data validation → ConnectorDataError."""

    def test_missing_id(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trade = {"symbol": "ETH/USDT", "side": "buy", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}
        client = FakeCcxtClient(trades=[trade])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        with pytest.raises(ConnectorDataError, match="id"):
            connector.fetch_trades()

    def test_bad_symbol(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trade = {"id": "t1", "symbol": "NOSLASH", "side": "buy", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}
        client = FakeCcxtClient(trades=[trade])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        with pytest.raises(ConnectorDataError, match="symbol"):
            connector.fetch_trades()

    def test_bad_side(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trade = {"id": "t1", "symbol": "ETH/USDT", "side": "unknown", "price": 3000, "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}
        client = FakeCcxtClient(trades=[trade])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        with pytest.raises(ConnectorDataError, match="side"):
            connector.fetch_trades()

    def test_non_finite_amount(self):
        from ft.adapters.connectors.ccxt_exchange import CcxtExchangeConnector
        trade = {"id": "t1", "symbol": "ETH/USDT", "side": "buy", "price": float("inf"), "amount": 1, "cost": 3000, "fee": None, "timestamp": 1000}
        client = FakeCcxtClient(trades=[trade])
        connector = CcxtExchangeConnector(
            provider="binance", credentials={}, _client=client,
        )
        with pytest.raises(ConnectorDataError, match="non-finite"):
            connector.fetch_trades()

    def test_malformed_trade_fee_fails_closed(self):
        trade = {"id": "t1", "symbol": "ETH/USDT", "side": "buy", "price": 1, "amount": 1, "cost": 1, "fee": {"cost": "invalid", "currency": "usdt"}, "timestamp": 1000}
        connector = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeCcxtClient(trades=[trade]))
        with pytest.raises(ConnectorDataError, match="fee.cost"):
            connector.fetch_trades()

    def test_crypto_alias_and_nonzero_fee_without_currency_fail_closed(self):
        trade = {"id": "xbt", "symbol": "XBT/USDT", "side": "buy", "price": 10, "amount": 1, "cost": 10, "fee": {"cost": "0.1", "currency": "XBT"}, "timestamp": 1000}
        connector = CcxtExchangeConnector(provider="kraken", credentials={}, _client=FakeCcxtClient(trades=[trade]))
        event = connector.fetch_trades().events[0]
        assert (event["to_ticker"], event["commission_asset"]) == ("btc", "btc")
        missing_currency = dict(trade, fee={"cost": "0.1"})
        connector = CcxtExchangeConnector(provider="kraken", credentials={}, _client=FakeCcxtClient(trades=[missing_currency]))
        with pytest.raises(ConnectorDataError, match="non-zero fee missing currency"):
            connector.fetch_trades()


class TestLedgerMapping:
    def test_kraken_uses_offset_until_final_short_page(self):
        first = [{"id": f"first-{i}", "timestamp": i, "currency": "USD", "amount": "1", "info": {"type": "deposit"}} for i in range(50)]
        second = [{"id": "last", "timestamp": 100, "currency": "USD", "amount": "1", "info": {"type": "deposit"}}]
        connector = CcxtExchangeConnector(provider="kraken", credentials={}, _client=FakeCcxtClient(ledger_pages={0: first, 50: second}))
        result = connector.fetch_trades()
        assert len(result.events) == 51
        assert result.next_cursor == "101"

    @pytest.mark.parametrize(("kind", "action"), [
        ("deposit", "deposit"), ("withdrawal", "withdraw"),
        ("staking", "dividend"), ("reward", "dividend"),
        ("credit", "dividend"), ("rollover", "dividend"),
        ("transfer", "transfer"),
        ("derivativescrossexchangetransfer", "transfer"),
    ])
    def test_supported_ledger_types_map_to_events(self, kind, action):
        entry = {"id": kind, "timestamp": 1000, "currency": "USD", "amount": "2", "info": {"type": kind}}
        connector = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeCcxtClient(ledger=[entry]))
        event = connector.fetch_trades().events[0]
        assert event["action"] == action
        assert event["record_id"] == kind

    def test_ledger_fee_becomes_child_event(self):
        entry = {"id": "reward", "timestamp": 1000, "currency": "ETH", "amount": "2", "info": {"type": "reward"}, "fee": {"cost": "0.1", "currency": "ETH"}}
        connector = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeCcxtClient(ledger=[entry]))
        assert [(event["action"], event["record_id"]) for event in connector.fetch_trades().events] == [("dividend", "reward"), ("fee", "reward:fee")]

    def test_unknown_type_and_bad_fee_fail_closed(self):
        unknown = {"id": "bad", "timestamp": 1000, "currency": "USD", "amount": "1", "info": {"type": "unknown"}}
        connector = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeCcxtClient(ledger=[unknown]))
        with pytest.raises(ConnectorDataError, match="unsupported ledger type"):
            connector.fetch_trades()
        bad_fee = {"id": "fee", "timestamp": 1000, "currency": "USD", "amount": "1", "info": {"type": "deposit"}, "fee": {"cost": "bad", "currency": "USD"}}
        connector = CcxtExchangeConnector(provider="binance", credentials={}, _client=FakeCcxtClient(ledger=[bad_fee]))
        with pytest.raises(ConnectorDataError, match="ledger fee.cost"):
            connector.fetch_trades()

    def test_ledger_alias_and_nonzero_fee_without_currency_fail_closed(self):
        alias = {"id": "xbt-deposit", "timestamp": 1, "currency": "XBT", "amount": "1", "info": {"type": "deposit"}}
        connector = CcxtExchangeConnector(provider="kraken", credentials={}, _client=FakeCcxtClient(ledger=[alias]))
        assert connector.fetch_trades().events[0]["to_ticker"] == "btc"
        missing_currency = {"id": "fee", "timestamp": 1, "currency": "USD", "amount": "1", "info": {"type": "deposit"}, "fee": {"cost": "0.1"}}
        connector = CcxtExchangeConnector(provider="kraken", credentials={}, _client=FakeCcxtClient(ledger=[missing_currency]))
        with pytest.raises(ConnectorDataError, match="non-zero ledger fee missing currency"):
            connector.fetch_trades()

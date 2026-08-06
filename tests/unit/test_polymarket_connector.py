"""Unit tests for PolymarketConnector (T025-T028)."""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from ft.domain.connector_port import ConnectorDataError, ConnectorError


FIXTURES_DIR = Path(__file__).parents[1] / "fixtures"


def _load_activities():
    with open(FIXTURES_DIR / "polymarket_activities.json") as f:
        return json.load(f)


def _make_fetch_fn(responses):
    """Create a mock fetch function that returns responses in order."""
    call_count = [0]
    def fetch(url):
        idx = call_count[0]
        call_count[0] += 1
        if idx < len(responses):
            return responses[idx]
        return []
    return fetch


_FUNDER = "0x" + "a" * 40
_EXTERNAL = "0x" + "b" * 40
_TX = "0x" + "c" * 64


def _topic(address: str) -> str:
    return "0x" + "0" * 24 + address[2:].lower()


def _transfer_log(*, sender=_EXTERNAL, recipient=_FUNDER, value=1_234_567,
                  block=10, index=7, tx_hash=_TX):
    return {
        "address": "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB",
        "topics": [
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef",
            _topic(sender), _topic(recipient),
        ],
        "data": hex(value), "blockNumber": hex(block), "logIndex": hex(index),
        "transactionHash": tx_hash,
    }


def _rpc_for_logs(logs, *, latest=12, timestamps=None):
    """Controlled public-RPC boundary; connector mapping remains real."""
    timestamps = timestamps or {}

    def rpc(method, params):
        if method == "eth_blockNumber":
            return hex(latest)
        if method == "eth_getLogs":
            return logs
        if method == "eth_getBlockByNumber":
            block = int(params[0], 16)
            return {"timestamp": hex(timestamps.get(block, 1_777_667_200 + block))}
        raise AssertionError(f"unexpected RPC method: {method}")

    return rpc


def test_default_polygon_rpc_prefers_no_registration_onfinality_endpoint():
    """The default historical-import path must work without user signup."""
    from ft.adapters.connectors.polymarket import POLYGON_RPC_ENDPOINTS

    assert POLYGON_RPC_ENDPOINTS[0] == "https://polygon.api.onfinality.io/public"


class TestActivityMapping:
    """T025: Polymarket activity → investment event mapping."""

    def test_buy_trade(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activities = [_load_activities()[0]]  # BUY will-trump-win-2024 Yes
        fetch = _make_fetch_fn([activities])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 1
        event = result.events[0]
        assert (event["record_type"], event["record_subtype"]) == ("trade", "security")
        assert event["from_ticker"] == "usd"
        assert event["to_ticker"] == "pm:will-trump-win-2024:yes"
        assert event["from_amount"] == "65"
        assert event["to_amount"] == "100"
        assert event["commission"] == "0"
        assert event["note"] == ""

    def test_sell_trade(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activities = [_load_activities()[1]]  # SELL
        fetch = _make_fetch_fn([activities])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        event = result.events[0]
        assert event["from_ticker"] == "pm:will-trump-win-2024:yes"
        assert event["to_ticker"] == "usd"
        assert event["from_amount"] == "50"
        assert event["to_amount"] == "37.5"

    def test_non_trade_skipped(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activities = _load_activities()[:3]  # BUY, SELL, DEPOSIT
        fetch = _make_fetch_fn([activities])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 2  # DEPOSIT skipped
        assert result.raw_count == 3

    def test_redeem_maps_outcome_tokens_to_usd(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activity = {
            "type": "REDEEM", "slug": "market", "outcome": "Yes",
            "size": "25", "usdcSize": "25", "timestamp": 1_700_000_000,
            "transactionHash": "redeem-hash",
        }
        result = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=_make_fetch_fn([[activity]]),
        ).fetch_trades()
        assert (result.events[0]["record_type"], result.events[0]["record_subtype"]) == ("trade", "security")
        assert result.events[0]["from_ticker"] == "pm:market:yes"
        assert result.events[0]["to_ticker"] == "usd"
        assert result.events[0]["record_id"] == "redeem-hash"
        assert result.events[0]["note"] == ""

    def test_yield_maps_to_usd_interest_income(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activity = {
            "type": "YIELD", "usdcSize": "1.25", "timestamp": 1_700_000_000,
            "transactionHash": "yield-hash",
        }
        result = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=_make_fetch_fn([[activity]]),
        ).fetch_trades()
        assert (result.events[0]["record_type"], result.events[0]["record_subtype"]) == ("income", "interest")
        assert result.events[0]["to_ticker"] == "usd"
        assert result.events[0]["to_amount"] == "1.25"
        assert result.events[0]["record_id"] == "yield-hash"
        assert result.events[0]["note"] == ""


class TestPagination:
    """T026: pagination."""

    def test_multi_page(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        page1 = [_load_activities()[0]]
        page2 = [_load_activities()[1]]
        fetch = _make_fetch_fn([page1, page2, []])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            page_limit=1,
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 2

    def test_empty(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        fetch = _make_fetch_fn([[]])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        assert len(result.events) == 0


class TestDataValidation:
    """T027: data validation → ConnectorDataError."""

    def test_missing_outcome(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activities = [_load_activities()[4]]  # missing outcome
        fetch = _make_fetch_fn([activities])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        with pytest.raises(ConnectorDataError, match="outcome"):
            connector.fetch_trades()

    def test_missing_slug(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        activities = [_load_activities()[5]]  # empty slug
        fetch = _make_fetch_fn([activities])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=fetch,
        )
        with pytest.raises(ConnectorDataError, match="slug"):
            connector.fetch_trades()

    @pytest.mark.parametrize("kind, activity", [
        ("REDEEM", {"type": "REDEEM", "slug": "market", "outcome": "Yes", "size": "1", "usdcSize": "1", "timestamp": 1}),
        ("YIELD", {"type": "YIELD", "usdcSize": "1", "timestamp": 1}),
    ])
    def test_redeem_and_yield_require_transaction_hash(self, kind, activity):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0x" + "a" * 40},
            _fetch_fn=_make_fetch_fn([[activity]]),
        )
        with pytest.raises(ConnectorDataError, match="transactionHash"):
            connector.fetch_trades()


class TestProxyWalletResolution:
    """T028: proxy wallet resolution."""

    def test_proxy_wallet_direct(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        fetch = _make_fetch_fn([[]])
        connector = PolymarketConnector(
            credentials={"proxy_wallet": "0xAbCd" + "0" * 36},
            _fetch_fn=fetch,
        )
        result = connector.fetch_trades()
        assert result.raw_count == 0

    def test_extract_proxy_from_html(self):
        from ft.adapters.connectors.polymarket import extract_proxy_wallet
        html = '{"proxyAddress":"0x1234567890abcdef1234567890abcdef12345678"}'
        assert extract_proxy_wallet(html) == "0x1234567890abcdef1234567890abcdef12345678"

    def test_extract_proxy_missing(self):
        from ft.adapters.connectors.polymarket import extract_proxy_wallet
        with pytest.raises(ConnectorDataError):
            extract_proxy_wallet("<html>no proxy here</html>")


class TestPusdBalanceCheckin:
    """T066: current pUSD balance is a cash observation, not transfer history."""

    def test_current_balance_maps_to_exact_checkin_without_log_scan(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        calls = []

        def rpc(method, params):
            calls.append(method)
            if method == "eth_blockNumber": return hex(12)
            if method == "eth_getBlockByNumber": return {"timestamp": hex(1_777_667_210)}
            if method == "eth_call": return hex(1_234_567)
            raise AssertionError(method)

        result = PolymarketConnector(credentials={"proxy_wallet": _FUNDER}, _fetch_fn=_make_fetch_fn([[]]), _rpc_fetch_fn=rpc).fetch_trades()
        assert calls == ["eth_blockNumber", "eth_getBlockByNumber", "eth_call"]
        assert (result.events[0]["record_type"], result.events[0]["record_subtype"]) == ("snapshot", "cash")
        assert result.events[0]["to_ticker"] == "usd"
        assert result.events[0]["to_amount"] == "1.234567"
        assert result.events[0]["record_id"] == "checkin:12"
        assert result.events[0]["note"] == ""
        assert result.events[0]["source_payload"] == {"token": "0xc011a7e12a19f7b1f670d46f03b03f3342e82dfb", "wallet": _FUNDER, "balance_base_units": "1234567", "block_number": 12, "block_timestamp": 1_777_667_210}

    def test_balance_rpc_failure_returns_no_partial_result(self):
        from ft.adapters.connectors.polymarket import PolymarketConnector
        def rpc(method, params):
            if method == "eth_blockNumber": return hex(12)
            if method == "eth_getBlockByNumber": return {"timestamp": hex(1)}
            raise OSError("balance unavailable")
        with pytest.raises(ConnectorError, match="eth_call"):
            PolymarketConnector(credentials={"proxy_wallet": _FUNDER}, _fetch_fn=_make_fetch_fn([[_load_activities()[0]]]), _rpc_fetch_fn=rpc).fetch_trades()

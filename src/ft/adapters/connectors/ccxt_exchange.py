"""CcxtExchangeConnector — ccxt-based exchange trade fetcher.

Maps ccxt ``fetch_my_trades`` results to standardized investment event dicts
for the SyncService batch-import pipeline.

Ticker convention: lowercase (``btc``, ``eth``, ``usdt``).
交易事件统一写为 ``trade(security)``，一项资产换取另一项资产。
"""
from __future__ import annotations

import time
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

from ft.domain.connector_port import (
    ConnectorAuthError,
    ConnectorDataError,
    ConnectorError,
    ConnectorResult,
)
from ft.importers.ticker_normalize import normalize_crypto_ticker


# Stablecoins / fiat quotes treated as cash equivalents
_CASH_QUOTES = frozenset({"usdt", "usdc", "usd", "busd", "tusd", "dai"})

# Retry config
_MAX_RETRIES = 3
_RETRY_BASE_DELAY = 1.0  # seconds


def _exact_decimal(value, name: str = "value") -> Decimal:
    """Convert to finite Decimal; raise ConnectorDataError on failure."""
    if value is None:
        raise ConnectorDataError(f"{name} is None")
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError, TypeError) as exc:
        raise ConnectorDataError(f"invalid {name}: {value!r}") from exc
    if not d.is_finite():
        raise ConnectorDataError(f"non-finite {name}: {value!r}")
    return d


def _format_decimal(d: Decimal) -> str:
    """Normalize and format as plain decimal string."""
    text = format(d.normalize(), "f")
    return "0" if text == "-0" else text


class CcxtExchangeConnector:
    """Connector adapter for ccxt-supported exchanges.

    Implements the ConnectorPort protocol.
    """

    def __init__(
        self,
        *,
        provider: str,
        credentials: dict[str, str],
        page_limit: int = 500,
        _client: Any = None,
        _ledger_fetch_fn: Any = None,
    ):
        self._provider = provider
        self._credentials = credentials
        self._page_limit = page_limit
        self._client = _client
        self._ledger_fetch_fn = _ledger_fetch_fn

    @property
    def source_type(self) -> str:
        return f"{self._provider}_api"

    def fetch_trades(self, *, since: str | None = None) -> ConnectorResult:
        """Fetch all trades via ccxt, paginating with ``since`` timestamp."""
        client = self._get_client()
        since_ms = int(since) if since else None

        all_trades = self._fetch_all_trades(client, since_ms)
        all_ledger = self._fetch_all_ledger(client, since_ms)

        # Map trades to investment events
        events = []
        for trade in all_trades:
            event = self._map_trade(trade)
            events.append(event)
        for entry in all_ledger:
            events.extend(self._map_ledger_entry(entry))

        # Sort by timestamp
        events.sort(key=lambda e: e.get("occurred_at", ""))

        # Next cursor = last trade timestamp + 1
        next_cursor = None
        timestamps = [
            int(row["timestamp"])
            for row in [*all_trades, *all_ledger]
            if row.get("timestamp") is not None
        ]
        if timestamps:
            next_cursor = str(max(timestamps) + 1)

        return ConnectorResult(
            events=events,
            next_cursor=next_cursor,
            raw_count=len(all_trades) + len(all_ledger),
        )

    def _fetch_all_trades(self, client, since_ms: int | None) -> list[dict]:
        rows: list[dict] = []
        seen_ids: set[str] = set()
        cursor = since_ms
        while True:
            batch = self._fetch_with_retry(client, cursor)
            if not batch:
                return rows
            fresh = 0
            for trade in batch:
                trade_id = str(trade.get("id", "")).strip()
                if not trade_id:
                    raise ConnectorDataError("trade missing 'id' during pagination")
                if trade_id not in seen_ids:
                    seen_ids.add(trade_id)
                    rows.append(trade)
                    fresh += 1
            if len(batch) < self._page_limit:
                return rows
            last_ts = batch[-1].get("timestamp")
            if last_ts is None or fresh == 0:
                raise ConnectorDataError("trade pagination made no progress")
            cursor = int(last_ts) + 1

    def _fetch_all_ledger(self, client, since_ms: int | None) -> list[dict]:
        if self._ledger_fetch_fn is None and not hasattr(client, "fetch_ledger"):
            raise ConnectorError(f"{self._provider} does not support fetch_ledger")
        rows: list[dict] = []
        seen_ids: set[str] = set()
        cursor = since_ms
        offset = 0
        while True:
            batch = self._fetch_ledger_with_retry(client, cursor, offset=offset)
            if not batch:
                return rows
            fresh = 0
            for entry in batch:
                entry_id = str(entry.get("id", "")).strip()
                if not entry_id:
                    raise ConnectorDataError("ledger entry missing 'id' during pagination")
                if entry_id not in seen_ids:
                    seen_ids.add(entry_id)
                    rows.append(entry)
                    fresh += 1
            # Kraken's Ledgers endpoint caps a page at 50 regardless of the
            # requested limit and exposes an `ofs` offset.  Without this
            # branch, a 50-row first page would look like a complete history.
            if self._provider == "kraken":
                if len(batch) < 50:
                    return rows
                if fresh == 0:
                    raise ConnectorDataError("ledger pagination made no progress")
                offset += len(batch)
                continue
            if len(batch) < self._page_limit:
                return rows
            last_ts = batch[-1].get("timestamp")
            if last_ts is None or fresh == 0:
                raise ConnectorDataError("ledger pagination made no progress")
            cursor = int(last_ts) + 1

    def _get_client(self):
        """Lazily build the ccxt client."""
        if self._client is not None:
            return self._client

        try:
            import ccxt
        except ImportError as exc:
            raise ConnectorError(
                "ccxt library is required: pip install ccxt"
            ) from exc

        if not hasattr(ccxt, self._provider):
            raise ConnectorError(
                f"ccxt does not support exchange '{self._provider}'"
            )

        params: dict[str, str] = {
            "apiKey": self._credentials["api_key"],
            "secret": self._credentials["api_secret"],
        }
        if self._credentials.get("password"):
            params["password"] = self._credentials["password"]

        self._client = getattr(ccxt, self._provider)(params)
        return self._client

    def _fetch_with_retry(self, client, since_ms: int | None) -> list[dict]:
        """Call fetch_my_trades with exponential backoff retry."""
        last_exc = None
        for attempt in range(1, _MAX_RETRIES + 1):
            try:
                return client.fetch_my_trades(
                    symbol=None,
                    since=since_ms,
                    limit=self._page_limit,
                )
            except Exception as exc:
                last_exc = exc
                exc_str = str(exc).lower()
                exc_type = type(exc).__name__.lower()

                # Non-retryable errors
                if "auth" in exc_type or "401" in exc_str or "403" in exc_str:
                    raise ConnectorAuthError(
                        f"{self._provider} authentication failed"
                    ) from exc
                if "400" in exc_str or "invalid" in exc_str:
                    raise ConnectorError(
                        f"{self._provider} request error: {exc}"
                    ) from exc

                # Retryable errors (429, 5xx, network)
                if attempt < _MAX_RETRIES:
                    delay = _RETRY_BASE_DELAY * (2 ** (attempt - 1))
                    time.sleep(delay)

        raise ConnectorError(
            f"{self._provider} failed after {_MAX_RETRIES} retries: {last_exc}"
        ) from last_exc

    def _fetch_ledger_with_retry(self, client, since_ms: int | None, *, offset: int = 0) -> list[dict]:
        fetch = self._ledger_fetch_fn or client.fetch_ledger
        last_exc = None
        for attempt in range(1, _MAX_RETRIES + 1):
            try:
                params = {"ofs": offset} if self._provider == "kraken" else {}
                return fetch(code=None, since=since_ms, limit=self._page_limit, params=params)
            except Exception as exc:
                last_exc = exc
                exc_str = str(exc).lower()
                exc_type = type(exc).__name__.lower()
                if "auth" in exc_type or "401" in exc_str or "403" in exc_str:
                    raise ConnectorAuthError(
                        f"{self._provider} authentication failed"
                    ) from exc
                if "400" in exc_str or "invalid" in exc_str:
                    raise ConnectorError(
                        f"{self._provider} ledger request error: {exc}"
                    ) from exc
                if attempt < _MAX_RETRIES:
                    time.sleep(_RETRY_BASE_DELAY * (2 ** (attempt - 1)))
        raise ConnectorError(
            f"{self._provider} ledger failed after {_MAX_RETRIES} retries: {last_exc}"
        ) from last_exc

    def _map_trade(self, trade: dict) -> dict:
        """Map one ccxt trade dict to a standardized investment event dict.

        Raises ConnectorDataError on invalid/ambiguous data.
        """
        # Validate required fields
        tid = trade.get("id")
        if tid is None or str(tid).strip() == "":
            raise ConnectorDataError(f"trade missing 'id': {trade!r}")
        tid = str(tid)

        symbol = str(trade.get("symbol", ""))
        if "/" not in symbol:
            raise ConnectorDataError(
                f"cannot split trade symbol '{symbol}' (expected BASE/QUOTE)"
            )
        parts = symbol.split("/", 1)
        base = normalize_crypto_ticker(parts[0])
        quote = normalize_crypto_ticker(parts[1])
        if not base or not quote:
            raise ConnectorDataError(f"empty base or quote in symbol '{symbol}'")

        side = str(trade.get("side", "")).lower()
        if side not in {"buy", "sell"}:
            raise ConnectorDataError(f"unknown trade side: {trade.get('side')!r}")

        # Parse amounts
        amount = _exact_decimal(trade.get("amount"), "amount")
        price = _exact_decimal(trade.get("price"), "price")
        cost = trade.get("cost")
        if cost is None:
            cost = price * amount
        else:
            cost = _exact_decimal(cost, "cost")

        # Parse fee
        fee = trade.get("fee")
        if fee is None:
            fee = {}
        if not isinstance(fee, dict):
            raise ConnectorDataError("trade fee must be an object")
        fee_cost = Decimal("0")
        fee_currency = ""
        if fee.get("cost") is not None:
            fee_cost = _exact_decimal(fee["cost"], "fee.cost")
            if fee_cost < 0:
                raise ConnectorDataError("fee.cost must be non-negative")
            fee_currency = normalize_crypto_ticker(str(fee.get("currency", "")))
            if fee_cost and not fee_currency:
                raise ConnectorDataError("non-zero fee missing currency")

        # Parse timestamp
        ts_ms = trade.get("timestamp")
        if ts_ms is None:
            raise ConnectorDataError(f"trade missing 'timestamp': {trade!r}")
        try:
            occurred_at = datetime.fromtimestamp(
                int(ts_ms) / 1000, tz=timezone.utc,
            )
        except (TypeError, ValueError, OSError) as exc:
            raise ConnectorDataError(
                f"invalid trade timestamp: {ts_ms!r}"
            ) from exc

        # Map to investment event
        # BUY: pay quote, receive base → from=quote, to=base
        # SELL: pay base, receive quote → from=base, to=quote
        if side == "buy":
            from_ticker = quote
            from_amount = cost
            to_ticker = base
            to_amount = amount
        else:
            from_ticker = base
            from_amount = amount
            to_ticker = quote
            to_amount = cost

        return {
            "record_type": "trade",
            "record_subtype": "security",
            "account": "",  # filled by SyncService
            "currency": "USD",
            "occurred_at": occurred_at,
            "from_ticker": from_ticker,
            "from_amount": _format_decimal(from_amount),
            "to_ticker": to_ticker,
            "to_amount": _format_decimal(to_amount),
            "commission": _format_decimal(fee_cost),
            "commission_asset": fee_currency,
            "note": f"{self._provider} trade {tid}",
            "record_id": tid,
            "source_payload": trade,
        }

    def _map_ledger_entry(self, entry: dict) -> list[dict]:
        """Map one non-trade ccxt ledger entry and its optional fee child."""
        raw_type = (entry.get("info") or {}).get("type") or entry.get("type")
        entry_type = str(raw_type or "").strip().lower()
        if entry_type == "trade":
            # Trade endpoint is the canonical two-legged representation.
            return []
        record_type_by_type = {
            "deposit": ("funding", "external", True),
            "withdrawal": ("funding", "external", False),
            "staking": ("income", "reward", True),
            "reward": ("income", "reward", True),
            "credit": ("income", "reward", True),
            "rollover": ("income", "reward", True),
            "transfer": ("funding", "subaccount", False),
            "derivativescrossexchangetransfer": ("funding", "subaccount", False),
        }
        semantics = record_type_by_type.get(entry_type)
        if semantics is None:
            raise ConnectorDataError(f"unsupported ledger type: {entry_type or '<missing>'}")
        record_type, record_subtype, incoming = semantics
        entry_id = str(entry.get("id", "")).strip()
        ticker = normalize_crypto_ticker(str(entry.get("currency", "")))
        timestamp = entry.get("timestamp")
        if not entry_id or not ticker or timestamp is None:
            raise ConnectorDataError("ledger entry missing id, currency, or timestamp")
        amount = _exact_decimal(entry.get("amount"), "ledger amount")
        if amount < 0:
            raise ConnectorDataError("ledger amount must be non-negative")
        try:
            occurred_at = datetime.fromtimestamp(int(timestamp) / 1000, tz=timezone.utc)
        except (TypeError, ValueError, OSError) as exc:
            raise ConnectorDataError(f"invalid ledger timestamp: {timestamp!r}") from exc
        event = {
            "record_type": record_type,
            "record_subtype": record_subtype,
            "account": "",
            "currency": "USD",
            "occurred_at": occurred_at,
            "from_ticker": "" if incoming else ticker,
            "from_amount": "0" if incoming else _format_decimal(amount),
            "to_ticker": ticker if incoming else "",
            "to_amount": _format_decimal(amount) if incoming else "0",
            "commission": "0",
            "commission_asset": "",
            "note": f"{self._provider} ledger {entry_type} {entry_id}",
            "record_id": entry_id,
            "source_payload": entry,
        }
        fee = entry.get("fee")
        if fee is None:
            return [event]
        if not isinstance(fee, dict):
            raise ConnectorDataError("ledger fee must be an object")
        fee_cost = _exact_decimal(fee.get("cost"), "ledger fee.cost")
        if fee_cost < 0:
            raise ConnectorDataError("ledger fee.cost must be non-negative")
        if fee_cost == 0:
            return [event]
        fee_ticker = normalize_crypto_ticker(str(fee.get("currency", "")))
        if not fee_ticker:
            raise ConnectorDataError("non-zero ledger fee missing currency")
        fee_event = dict(event)
        fee_event.update({
            "record_type": "expense", "record_subtype": "commission", "from_ticker": fee_ticker,
            "from_amount": _format_decimal(fee_cost), "to_ticker": "",
            "to_amount": "0", "record_id": f"{entry_id}:fee",
            "note": f"{self._provider} ledger fee {entry_id}",
        })
        return [event, fee_event]

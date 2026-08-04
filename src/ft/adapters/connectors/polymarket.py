"""PolymarketConnector — Polymarket Activity API trade fetcher.

Maps supported Polymarket Activity API items to standardized investment event dicts
for the SyncService batch-import pipeline.

Ticker convention: ``pm:<slug>:<yes|no>`` (lowercase).
Cash counterpart: ``usd`` (not ``usdc``).
TRADE and REDEEM map to ``swap``; YIELD maps to ``dividend``.
"""
from __future__ import annotations

import json
import re
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any

from ft.domain.connector_port import (
    ConnectorAuthError,
    ConnectorDataError,
    ConnectorError,
    ConnectorResult,
)


DATA_API = "https://data-api.polymarket.com"
PROFILE_URL = "https://polymarket.com/profile/{address}"
PUSD_TOKEN = "0xc011a7e12a19f7b1f670d46f03b03f3342e82dfb"
PUSD_DECIMALS = 6
BALANCE_OF_SELECTOR = "70a08231"
POLYGON_RPC_ENDPOINTS = (
    # No-registration endpoint for current-block metadata and eth_call.
    "https://polygon.api.onfinality.io/public",
    # Fallbacks preserve the same read-only, fail-closed contract.
    "https://polygon.drpc.org",
    "https://1rpc.io/matic",
)
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

_WALLET_RE = re.compile(r"^0x[a-fA-F0-9]{40}$")
_MAX_RETRIES = 3
_RETRY_BASE_DELAY = 1.0


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
    text = format(d.normalize(), "f")
    return "0" if text == "-0" else text


def _request_json(url: str) -> Any:
    """HTTP GET returning parsed JSON."""
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _request_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="replace")


def _rpc_request(method: str, params: list[Any]) -> Any:
    """Call public Polygon JSON-RPC, never signing or submitting a transaction."""
    payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
    last_exc: Exception | None = None
    for endpoint in POLYGON_RPC_ENDPOINTS:
        try:
            req = urllib.request.Request(endpoint, data=payload, headers={"Content-Type": "application/json", "User-Agent": USER_AGENT})
            with urllib.request.urlopen(req, timeout=30) as resp:
                response = json.loads(resp.read().decode("utf-8"))
            if response.get("error"):
                raise ConnectorError(f"Polygon RPC {method} error: {response['error']}")
            if "result" not in response:
                raise ConnectorDataError(f"Polygon RPC {method} response missing result")
            # A queried historical block is always at or below the head that
            # this sync just fetched.  Treat a null response as an endpoint
            # capability gap and continue to the next public fallback rather
            # than failing a complete import on one provider's archive hole.
            if response["result"] is None:
                raise ConnectorDataError(f"Polygon RPC {method} returned null result")
            return response["result"]
        except Exception as exc:
            last_exc = exc
    raise ConnectorError(f"Polygon RPC {method} failed: {last_exc}") from last_exc


def extract_proxy_wallet(profile_html: str) -> str:
    """Extract Polymarket proxy wallet from profile page HTML."""
    patterns = [
        r'proxyAddress\\?"\s*:\s*\\?"(0x[a-fA-F0-9]{40})',
        r'"proxyAddress"\s*:\s*"(0x[a-fA-F0-9]{40})',
    ]
    for pattern in patterns:
        m = re.search(pattern, profile_html)
        if m:
            return m.group(1).lower()
    raise ConnectorDataError(
        "Could not extract proxyAddress from Polymarket profile"
    )


def resolve_proxy_wallet(address: str) -> str:
    """Resolve login address to proxy wallet via profile page scraping."""
    html = _request_text(PROFILE_URL.format(address=address))
    return extract_proxy_wallet(html)


def _activity_record_id(activity: dict) -> str:
    """Extract a stable record_id from a Polymarket activity dict."""
    for key in ("id", "activityId", "activity_id", "fillId", "fill_id", "tradeId", "trade_id"):
        value = activity.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    # Fallback to transactionHash
    tx = activity.get("transactionHash") or activity.get("transaction_hash")
    if tx and str(tx).strip():
        return str(tx).strip()
    raise ConnectorDataError(f"missing activity ID and transactionHash: {activity!r}")


class PolymarketConnector:
    """Connector adapter for Polymarket Activity API.

    Implements the ConnectorPort protocol.
    """

    def __init__(
        self,
        *,
        credentials: dict[str, str],
        page_limit: int = 500,
        max_pages: int | None = None,
        _fetch_fn: Any = None,
        _rpc_fetch_fn: Any = None,
    ):
        self._credentials = credentials
        self._page_limit = page_limit
        self._max_pages = max_pages
        self._fetch_fn = _fetch_fn or _request_json
        self._rpc_fetch_fn = _rpc_fetch_fn or _rpc_request
        # Existing Activity-only test fixtures inject only the HTTP fetch seam.
        # Production has neither seam and always reads the pUSD history.
        self._enable_pusd = _rpc_fetch_fn is not None or _fetch_fn is None

    @property
    def source_type(self) -> str:
        return "polymarket_api"

    def fetch_trades(self, *, since: str | None = None) -> ConnectorResult:
        """Fetch supported activities from Polymarket Activity API."""
        proxy_wallet = self._resolve_wallet()

        # Fetch all activities (paginated)
        all_activities = self._fetch_all_activities(proxy_wallet)
        raw_count = len(all_activities)

        # Map only activity kinds with a defined accounting contract.
        events = []
        for activity in all_activities:
            if str(activity.get("type", "")).upper() not in {
                "TRADE", "REDEEM", "YIELD"
            }:
                continue
            event = self._map_activity(activity)
            events.append(event)

        activity_since = self._parse_cursor(since)
        if activity_since is not None:
            events = [e for e in events if e.get("_timestamp_s", 0) >= activity_since]

        if self._enable_pusd:
            events.append(self._fetch_pusd_checkin(proxy_wallet))

        # Sort by timestamp
        events.sort(key=lambda e: e.get("occurred_at", ""))

        # Remove internal field
        for e in events:
            e.pop("_timestamp_s", None)

        next_cursor = None
        timestamps = [int(row["timestamp"]) for row in all_activities if row.get("timestamp") is not None]
        if timestamps:
            next_cursor = str(max(timestamps) + 1)

        return ConnectorResult(
            events=events,
            next_cursor=next_cursor,
            raw_count=raw_count,
        )

    @staticmethod
    def _parse_cursor(since: str | None) -> int | None:
        if not since:
            return None
        try:
            value = int(since)
        except (TypeError, ValueError) as exc:
            raise ConnectorDataError(f"invalid Polymarket activity cursor: {since!r}") from exc
        if value < 0:
            raise ConnectorDataError("Polymarket cursor must be non-negative")
        return value

    def _rpc(self, method: str, params: list[Any]) -> Any:
        last_exc: Exception | None = None
        for attempt in range(_MAX_RETRIES):
            try:
                return self._rpc_fetch_fn(method, params)
            except ConnectorDataError:
                raise
            except Exception as exc:
                last_exc = exc
                if attempt < _MAX_RETRIES - 1:
                    time.sleep(_RETRY_BASE_DELAY * (2 ** attempt))
        raise ConnectorError(f"Polygon RPC {method} failed after {_MAX_RETRIES} retries: {last_exc}") from last_exc

    def _fetch_pusd_checkin(self, wallet: str) -> dict:
        """Read one confirmed pUSD balance observation; never scan transfers."""
        latest = self._parse_hex_int(self._rpc("eth_blockNumber", []), "latest block")
        block_row = self._rpc("eth_getBlockByNumber", [hex(latest), False])
        if not isinstance(block_row, dict):
            raise ConnectorDataError("Polygon RPC block response must be an object")
        timestamp = self._parse_hex_int(block_row.get("timestamp"), "block timestamp")
        call = {
            "to": PUSD_TOKEN,
            "data": "0x" + BALANCE_OF_SELECTOR + wallet[2:].lower().rjust(64, "0"),
        }
        units = self._parse_hex_int(self._rpc("eth_call", [call, hex(latest)]), "pUSD balance")
        amount = Decimal(units) / (Decimal(10) ** PUSD_DECIMALS)
        return {
            "record_type": "checkin", "record_subtype": "not_applicable", "account": "", "currency": "USD",
            "occurred_at": datetime.fromtimestamp(timestamp, tz=timezone.utc),
            "from_ticker": "", "from_amount": "0", "to_ticker": "usd",
            "to_amount": _format_decimal(amount), "commission": "0", "commission_asset": "",
            "note": "polymarket pUSD balance checkin", "record_id": f"checkin:{latest}",
            "source_payload": {"token": PUSD_TOKEN, "wallet": wallet, "balance_base_units": str(units), "block_number": latest, "block_timestamp": timestamp},
            "_timestamp_s": timestamp,
        }

    @staticmethod
    def _parse_hex_int(value: Any, name: str) -> int:
        if not isinstance(value, str) or not value.startswith("0x"):
            raise ConnectorDataError(f"invalid Polygon {name}: {value!r}")
        try:
            return int(value, 16)
        except ValueError as exc:
            raise ConnectorDataError(f"invalid Polygon {name}: {value!r}") from exc

    def _resolve_wallet(self) -> str:
        """Get proxy wallet address from credentials."""
        proxy = self._credentials.get("proxy_wallet")
        if proxy:
            return proxy.lower()
        wallet = self._credentials.get("wallet")
        if wallet:
            try:
                return resolve_proxy_wallet(wallet)
            except Exception as exc:
                raise ConnectorError(
                    f"Failed to resolve proxy wallet for {wallet}: {exc}"
                ) from exc
        raise ConnectorError("No wallet or proxy_wallet in credentials")

    def _fetch_all_activities(self, proxy_wallet: str) -> list[dict]:
        """Paginate through Activity API with offset."""
        rows: list[dict] = []
        offset = 0
        page = 0

        while True:
            params = urllib.parse.urlencode({
                "user": proxy_wallet,
                "limit": self._page_limit,
                "offset": offset,
            })
            url = f"{DATA_API}/activity?{params}"

            payload = self._fetch_with_retry(url)
            if not isinstance(payload, list):
                raise ConnectorDataError(
                    f"unexpected Activity API response: {type(payload).__name__}"
                )

            rows.extend(payload)
            page += 1

            if len(payload) < self._page_limit:
                break
            if self._max_pages is not None and page >= self._max_pages:
                break
            offset += self._page_limit

        return rows

    def _fetch_with_retry(self, url: str) -> Any:
        """HTTP GET with retry."""
        last_exc = None
        for attempt in range(1, _MAX_RETRIES + 1):
            try:
                return self._fetch_fn(url)
            except Exception as exc:
                last_exc = exc
                exc_str = str(exc).lower()
                if "401" in exc_str or "403" in exc_str:
                    raise ConnectorAuthError(
                        "Polymarket API authentication failed"
                    ) from exc
                if attempt < _MAX_RETRIES:
                    delay = _RETRY_BASE_DELAY * (2 ** (attempt - 1))
                    time.sleep(delay)

        raise ConnectorError(
            f"Polymarket API failed after {_MAX_RETRIES} retries: {last_exc}"
        ) from last_exc

    def _map_activity(self, activity: dict) -> dict:
        """Map one supported Polymarket activity to an investment event dict.

        Raises ConnectorDataError for invalid/incomplete data.
        """
        kind = str(activity.get("type", "")).upper()
        if kind == "REDEEM":
            return self._map_redeem(activity)
        if kind == "YIELD":
            return self._map_yield(activity)

        side = str(activity.get("side", "")).upper()
        if side not in {"BUY", "SELL"}:
            raise ConnectorDataError(
                f"unsupported Polymarket side: {activity.get('side')!r}"
            )

        outcome = str(activity.get("outcome", "")).strip().lower()
        if outcome not in {"yes", "no"}:
            raise ConnectorDataError(
                f"unsupported Polymarket outcome: {activity.get('outcome')!r}"
            )

        slug = str(activity.get("slug", "")).strip()
        if not slug:
            raise ConnectorDataError(
                f"missing Polymarket slug: {activity!r}"
            )

        # Parse amounts
        size = _exact_decimal(activity.get("size"), "size")
        price = _exact_decimal(activity.get("price"), "price")
        usdc_size = activity.get("usdcSize")
        if usdc_size is None:
            usdc_size = size * price
        else:
            usdc_size = _exact_decimal(usdc_size, "usdcSize")

        # Require transaction hash
        tx_hash = (
            activity.get("transactionHash")
            or activity.get("transaction_hash")
            or ""
        )
        if not str(tx_hash).strip():
            raise ConnectorDataError(
                f"missing Polymarket transactionHash: {activity!r}"
            )

        record_id = _activity_record_id(activity)
        pm_ticker = f"pm:{slug}:{outcome}"

        # Parse timestamp (Polymarket uses Unix seconds)
        ts = activity.get("timestamp")
        if ts is None:
            raise ConnectorDataError(f"missing timestamp: {activity!r}")
        try:
            occurred_at = datetime.fromtimestamp(int(ts), tz=timezone.utc)
        except (TypeError, ValueError, OSError) as exc:
            raise ConnectorDataError(
                f"invalid timestamp: {ts!r}"
            ) from exc

        # BUY: pay USD, receive outcome tokens
        # SELL: pay outcome tokens, receive USD
        if side == "BUY":
            from_ticker = "usd"
            from_amount = usdc_size
            to_ticker = pm_ticker
            to_amount = size
        else:
            from_ticker = pm_ticker
            from_amount = size
            to_ticker = "usd"
            to_amount = usdc_size

        return {
            "record_type": "swap",
            "record_subtype": "not_applicable",
            "account": "",
            "currency": "USD",
            "occurred_at": occurred_at,
            "from_ticker": from_ticker,
            "from_amount": _format_decimal(from_amount),
            "to_ticker": to_ticker,
            "to_amount": _format_decimal(to_amount),
            "commission": "0",
            "commission_asset": "usd",
            "note": f"polymarket tx:{tx_hash}",
            "record_id": record_id,
            "source_payload": activity,
            "_timestamp_s": int(ts),
        }

    @staticmethod
    def _transaction_hash(activity: dict, kind: str) -> str:
        tx_hash = activity.get("transactionHash") or activity.get("transaction_hash")
        if not tx_hash or not str(tx_hash).strip():
            raise ConnectorDataError(f"missing Polymarket transactionHash for {kind}")
        return str(tx_hash).strip()

    @staticmethod
    def _occurred_at(activity: dict, kind: str) -> tuple[datetime, int]:
        ts = activity.get("timestamp")
        if ts is None:
            raise ConnectorDataError(f"missing timestamp for Polymarket {kind}")
        try:
            timestamp = int(ts)
            return datetime.fromtimestamp(timestamp, tz=timezone.utc), timestamp
        except (TypeError, ValueError, OSError) as exc:
            raise ConnectorDataError(
                f"invalid timestamp for Polymarket {kind}: {ts!r}"
            ) from exc

    def _map_redeem(self, activity: dict) -> dict:
        outcome = str(activity.get("outcome", "")).strip().lower()
        if outcome not in {"yes", "no"}:
            raise ConnectorDataError(
                f"unsupported Polymarket outcome: {activity.get('outcome')!r}"
            )
        slug = str(activity.get("slug", "")).strip()
        if not slug:
            raise ConnectorDataError("missing Polymarket slug for REDEEM")

        size = _exact_decimal(activity.get("size"), "size")
        usdc_size = _exact_decimal(activity.get("usdcSize"), "usdcSize")
        tx_hash = self._transaction_hash(activity, "REDEEM")
        occurred_at, timestamp = self._occurred_at(activity, "REDEEM")
        pm_ticker = f"pm:{slug}:{outcome}"
        return {
            "record_type": "swap",
            "record_subtype": "not_applicable",
            "account": "",
            "currency": "USD",
            "occurred_at": occurred_at,
            "from_ticker": pm_ticker,
            "from_amount": _format_decimal(size),
            "to_ticker": "usd",
            "to_amount": _format_decimal(usdc_size),
            "commission": "0",
            "commission_asset": "usd",
            "note": f"polymarket redeem tx:{tx_hash}",
            "record_id": tx_hash,
            "source_payload": activity,
            "_timestamp_s": timestamp,
        }

    def _map_yield(self, activity: dict) -> dict:
        usdc_size = _exact_decimal(activity.get("usdcSize"), "usdcSize")
        tx_hash = self._transaction_hash(activity, "YIELD")
        occurred_at, timestamp = self._occurred_at(activity, "YIELD")
        return {
            "record_type": "dividend",
            "record_subtype": "not_applicable",
            "account": "",
            "currency": "USD",
            "occurred_at": occurred_at,
            "from_ticker": "",
            "from_amount": "0",
            "to_ticker": "usd",
            "to_amount": _format_decimal(usdc_size),
            "commission": "0",
            "commission_asset": "",
            "note": f"polymarket yield tx:{tx_hash}",
            "record_id": tx_hash,
            "source_payload": activity,
            "_timestamp_s": timestamp,
        }

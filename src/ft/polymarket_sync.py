"""Polymarket public Activity API → ft security records sync."""
from __future__ import annotations

import csv
import json
import re
import tempfile
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterable

from . import models
from .credentials import ensure_credentials_gitignored, load_polymarket_credentials
from .stock import CSV_FIELDS, _validate_security_csv_header, do_append
from .sync_common import row_identity as _shared_row_identity, write_stock_csv


def validate_security_account(account_name: str, currency: str = "USD") -> None:
    """Validate that the sync target is an existing security account."""
    from .accounts import find_account

    account = find_account(account_name, currency=currency)
    if account is None:
        raise ValueError(f"未知账户 '{account_name}' ({currency})，请先 ft acct add")
    if account.get("type") != "security":
        raise ValueError(f"账户 '{account_name}' ({currency}) 不是 security 类型，不能同步 Polymarket 交易")

DATA_API = "https://data-api.polymarket.com"
PROFILE_URL = "https://polymarket.com/profile/{address}"
USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)
UTC_PLUS_8 = timezone(timedelta(hours=8))


def _today_iso() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def _request_json(url: str):
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _request_text(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read().decode("utf-8", errors="replace")


def extract_proxy_wallet(profile_html: str) -> str:
    """Extract Polymarket proxy wallet address from profile HTML payload."""
    patterns = [
        r'proxyAddress\\?"\s*:\s*\\?"(0x[a-fA-F0-9]{40})',
        r'"proxyAddress"\s*:\s*"(0x[a-fA-F0-9]{40})',
    ]
    for pattern in patterns:
        m = re.search(pattern, profile_html)
        if m:
            return m.group(1).lower()
    raise ValueError("未能从 Polymarket profile 页面解析 proxyAddress")


def resolve_proxy_wallet(address: str) -> str:
    """Resolve a public Polymarket profile/login address to its proxy wallet."""
    html = _request_text(PROFILE_URL.format(address=address))
    return extract_proxy_wallet(html)


def _decimal_text(value) -> str:
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid numeric value: {value!r}") from exc
    if not d.is_finite():
        raise ValueError(f"invalid numeric value: {value!r}")
    text = format(d.normalize(), "f")
    if text == "-0":
        return "0"
    return text


def _format_activity_timestamp(value) -> str:
    try:
        ts = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid Polymarket timestamp: {value!r}") from exc
    return datetime.fromtimestamp(ts, tz=timezone.utc).astimezone(UTC_PLUS_8).strftime("%Y-%m-%d %H:%M:%S")


def activity_to_stock_row(activity: dict, account_name: str = "Polymarket") -> dict | None:
    """Convert one Polymarket activity item to a ft security CSV row (unified swap).

    BUY  → swap(USD,  pm:<slug>:<outcome>, usdc_size, size)
    SELL → swap(pm:<slug>:<outcome), USD, size, usdc_size)

    Returns None for non-TRADE activity. Unexpected TRADE shapes raise ValueError
    so the importer never silently drops ambiguous money/position data.
    """
    if not isinstance(activity, dict):
        raise ValueError(f"activity item must be object: {type(activity).__name__}")
    if str(activity.get("type", "")).upper() != "TRADE":
        return None

    side = str(activity.get("side", "")).upper()
    if side not in {"BUY", "SELL"}:
        raise ValueError(f"unsupported Polymarket side: {activity.get('side')!r}")

    outcome = str(activity.get("outcome", "")).strip().lower()
    if outcome not in {"yes", "no"}:
        raise ValueError(f"unsupported Polymarket outcome: {activity.get('outcome')!r}")

    slug = str(activity.get("slug", "")).strip()
    if not slug:
        raise ValueError(f"missing Polymarket slug: {activity!r}")

    size = _decimal_text(activity.get("size"))
    price = _decimal_text(activity.get("price"))
    usdc_size = activity.get("usdcSize")
    if usdc_size is None:
        usdc_size = Decimal(size) * Decimal(price)
    else:
        usdc_size = Decimal(_decimal_text(usdc_size))

    tx_hash = activity.get("transactionHash") or activity.get("transaction_hash") or ""
    if not tx_hash:
        raise ValueError(f"missing Polymarket transactionHash: {activity!r}")

    pm_ticker = f"pm:{slug}:{outcome}"
    usdc_size_text = _decimal_text(usdc_size)

    if side == "BUY":
        # Pay USDC, receive outcome tokens
        from_ticker, to_ticker = "USD", pm_ticker
        from_amount, to_amount = usdc_size_text, size
    else:
        # Sell outcome tokens, receive USDC
        from_ticker, to_ticker = pm_ticker, "USD"
        from_amount, to_amount = size, usdc_size_text

    row_id = _activity_row_id(activity)
    note = f"polymarket tx:{tx_hash}"
    if row_id:
        note = f"polymarket id:{row_id} tx:{tx_hash}"

    return {
        "date": _format_activity_timestamp(activity.get("timestamp")),
        "action": "swap",
        "from_ticker": from_ticker,
        "to_ticker": to_ticker,
        "from_amount": from_amount,
        "to_amount": to_amount,
        "price": price,
        "commission": "0",
        "commission_asset": "USD",
        "currency": "USD",
        "account_name": account_name,
        "note": note,
    }


def fetch_activity(proxy_wallet: str, limit: int = 500, max_pages: int | None = None) -> list[dict]:
    """Fetch all public Polymarket activity rows for a proxy wallet."""
    if limit <= 0:
        raise ValueError("limit must be positive")
    if max_pages is not None and max_pages <= 0:
        raise ValueError("max_pages must be positive")
    rows: list[dict] = []
    offset = 0
    page = 0
    while True:
        params = urllib.parse.urlencode({
            "user": proxy_wallet,
            "limit": limit,
            "offset": offset,
        })
        payload = _request_json(f"{DATA_API}/activity?{params}")
        if not isinstance(payload, list):
            raise ValueError(f"unexpected Polymarket activity payload: {type(payload).__name__}")
        rows.extend(payload)
        page += 1
        if len(payload) < limit:
            break
        if max_pages is not None and page >= max_pages:
            break
        offset += limit
    return rows


def activities_to_stock_rows(activities: Iterable[dict], account_name: str = "Polymarket") -> list[dict]:
    rows: list[dict] = []
    for activity in activities:
        row = activity_to_stock_row(activity, account_name=account_name)
        if row is not None:
            rows.append(row)
    rows.sort(key=lambda r: r["date"])
    return rows


def _row_id_from_note(note: str) -> str | None:
    m = re.search(r"\bid:(\S+)", note or "")
    return m.group(1) if m else None


def _activity_row_id(activity: dict) -> str | None:
    for key in ("id", "activityId", "activity_id", "fillId", "fill_id", "tradeId", "trade_id"):
        value = activity.get(key)
        if value is not None and str(value).strip():
            return str(value).strip()
    return None


def _existing_polymarket_identities(
    records_dir: Path | None = None,
    account_name: str | None = None,
) -> tuple[set[str], set[tuple[str, ...]]]:
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    row_ids: set[str] = set()
    exact_rows: set[tuple[str, ...]] = set()
    if not security_dir.exists():
        return row_ids, exact_rows

    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            _validate_security_csv_header(reader.fieldnames, path)
            for row in reader:
                if "action" not in row:
                    continue
                from_ticker = row.get("from_ticker") or ""
                row_account_name = row.get("account_name") or ""
                if account_name is not None:
                    if row_account_name != account_name:
                        continue
                elif row_account_name != "Polymarket" and not from_ticker.startswith("pm:"):
                    continue
                row_id = _row_id_from_note(row.get("note", ""))
                if row_id:
                    row_ids.add(row_id)
                exact_rows.add(_row_identity(row))
    return row_ids, exact_rows


def _row_identity(row: dict) -> tuple[str, ...]:
    return _shared_row_identity(row)


def filter_new_rows(
    rows: Iterable[dict],
    records_dir: Path | None = None,
    account_name: str | None = None,
) -> list[dict]:
    """Drop Polymarket rows already present in security CSV records."""
    row_ids, exact_rows = _existing_polymarket_identities(records_dir, account_name=account_name)
    new_rows = []
    seen_ids: set[str] = set()
    seen_exact: set[tuple[str, ...]] = set()
    for row in rows:
        ident = _row_identity(row)
        row_id = _row_id_from_note(row.get("note", ""))
        if row_id and (row_id in row_ids or row_id in seen_ids):
            continue
        if ident in exact_rows or ident in seen_exact:
            continue
        new_rows.append(row)
        if row_id:
            seen_ids.add(row_id)
        seen_exact.add(ident)
    return new_rows


def _coerce_json_list(value) -> list:
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        text = value.strip()
        if not text:
            return []
        try:
            parsed = json.loads(text)
        except Exception:
            return [value]
        if isinstance(parsed, list):
            return parsed
        return [parsed]
    return []


def _parse_pm_ticker(ticker: str) -> tuple[str, str] | None:
    text = str(ticker).strip().lower()
    if not text.startswith("pm:"):
        return None
    parts = text.split(":")
    if len(parts) < 3:
        return None
    side = parts[-1]
    if side not in {"yes", "no"}:
        return None
    slug = ":".join(parts[1:-1]).strip()
    if not slug:
        return None
    return slug, side


def _truthy(value) -> bool:
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        return value.strip().lower() in {"true", "1", "yes"}
    return False


def _market_has_resolved_metadata(market: dict) -> bool:
    if not _truthy(market.get("closed")):
        return False
    if _truthy(market.get("resolved")):
        return True
    for key in ("umaResolutionStatus", "resolutionStatus", "marketStatus", "status"):
        value = market.get(key)
        if isinstance(value, str) and value.strip().lower() in {"resolved", "finalized"}:
            return True
    return False


def _find_gamma_market(slug: str) -> dict | None:
    from urllib.parse import quote

    payload = _request_json(f"https://gamma-api.polymarket.com/markets?slug={quote(slug)}")
    markets = payload
    if isinstance(markets, dict):
        markets = markets.get("data") or markets.get("markets") or [markets]
    if isinstance(markets, list):
        market = next((m for m in markets if isinstance(m, dict) and m.get("slug") == slug), None)
        if market:
            return market

    search_q = slug.replace("-", " ")
    search_payload = _request_json(f"https://gamma-api.polymarket.com/public-search?q={quote(search_q)}")
    events = []
    if isinstance(search_payload, dict):
        events = search_payload.get("events") or search_payload.get("data") or []
    elif isinstance(search_payload, list):
        events = search_payload
    for event in events if isinstance(events, list) else []:
        if not isinstance(event, dict):
            continue
        for candidate in event.get("markets", []) or []:
            if isinstance(candidate, dict) and candidate.get("slug") == slug:
                return candidate
    return None


def _fetch_polymarket_resolution_prices(tickers: list[str]) -> dict[str, Decimal]:
    """Return Decimal settlement prices only for explicitly resolved/closed markets."""
    resolved_prices: dict[str, Decimal] = {}
    grouped: dict[str, list[tuple[str, str]]] = {}
    for ticker in tickers:
        parsed = _parse_pm_ticker(ticker)
        if not parsed:
            continue
        slug, side = parsed
        grouped.setdefault(slug, []).append((str(ticker).lower(), side))

    for slug, items in grouped.items():
        try:
            market = _find_gamma_market(slug)
        except Exception:
            continue
        if not isinstance(market, dict) or not _market_has_resolved_metadata(market):
            continue
        outcomes = [str(x).strip().lower() for x in _coerce_json_list(market.get("outcomes"))]
        outcome_prices = _coerce_json_list(market.get("outcomePrices"))
        for ticker, side in items:
            idx = None
            if side in outcomes:
                idx = outcomes.index(side)
            elif side == "yes" and len(outcome_prices) >= 1:
                idx = 0
            elif side == "no" and len(outcome_prices) >= 2:
                idx = 1
            if idx is None or idx >= len(outcome_prices):
                continue
            try:
                price = Decimal(str(outcome_prices[idx]))
            except (InvalidOperation, ValueError):
                continue
            if price.is_finite() and price in {Decimal("0"), Decimal("1")}:
                resolved_prices[ticker] = price
    return resolved_prices


def _snapshot_pm_positions(account_name: str) -> dict[str, Decimal]:
    from .snapshot import load_snapshot

    snap = load_snapshot()
    account = snap.get("accounts", {}).get("security", {}).get(account_name, {})
    positions = account.get("positions", {}) if isinstance(account, dict) else {}
    pm_positions: dict[str, Decimal] = {}
    for ticker, pos in positions.items():
        ticker_text = str(ticker).lower()
        if not ticker_text.startswith("pm:"):
            continue
        try:
            shares = Decimal(str(pos.get("shares", 0) or 0))
        except (InvalidOperation, ValueError):
            continue
        pm_positions[ticker_text] = shares
    return pm_positions


def _project_pm_positions(account_name: str, rows: Iterable[dict]) -> dict[str, Decimal]:
    positions = _snapshot_pm_positions(account_name)
    for row in sorted(rows, key=lambda r: r.get("date", "")):
        if row.get("account_name") != account_name or row.get("action") != "swap":
            continue
        from_ticker = str(row.get("from_ticker") or "").lower()
        to_ticker = str(row.get("to_ticker") or "").lower()
        try:
            from_amount = Decimal(str(row.get("from_amount") or "0"))
            to_amount = Decimal(str(row.get("to_amount") or "0"))
        except (InvalidOperation, ValueError):
            continue
        if from_ticker.startswith("pm:"):
            positions[from_ticker] = positions.get(from_ticker, Decimal("0")) - from_amount
        if to_ticker.startswith("pm:"):
            positions[to_ticker] = positions.get(to_ticker, Decimal("0")) + to_amount
    return positions


def _existing_settlement_tokens(account_name: str, records_dir: Path | None = None) -> set[str]:
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    tokens: set[str] = set()
    if not security_dir.exists():
        return tokens
    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            _validate_security_csv_header(reader.fieldnames, path)
            for row in reader:
                if row.get("account_name") != account_name:
                    continue
                note = row.get("note", "")
                from_ticker = (row.get("from_ticker") or "").lower()
                to_ticker = (row.get("to_ticker") or "").lower()
                if (
                    "polymarket settlement" in note
                    and from_ticker.startswith("pm:")
                    and to_ticker == "usd"
                ):
                    tokens.add(from_ticker)
    return tokens


def _settlement_rows_for_open_positions(
    account_name: str = "Polymarket",
    positions: dict[str, Decimal] | None = None,
    settled_tokens: set[str] | None = None,
) -> list[dict]:
    """Create SELL rows for projected positive positions in resolved Polymarket markets."""
    if positions is None:
        positions = _snapshot_pm_positions(account_name)

    if settled_tokens is None:
        settled_tokens = _existing_settlement_tokens(account_name)
    tickers = [
        ticker for ticker, shares in positions.items()
        if str(ticker).startswith("pm:") and shares > 0 and ticker not in settled_tokens
    ]
    if not tickers:
        return []

    prices = _fetch_polymarket_resolution_prices(tickers)
    rows: list[dict] = []
    for ticker in sorted(tickers):
        if ticker not in prices:
            continue
        shares = positions[ticker]
        price_dec = prices[ticker]
        settlement_value = shares * price_dec
        rows.append({
            "date": _today_iso(),
            "action": "swap",
            "from_ticker": ticker,
            "to_ticker": "USD",
            "from_amount": _decimal_text(shares),
            "to_amount": _decimal_text(settlement_value),
            "price": _decimal_text(price_dec),
            "commission": "0",
            "commission_asset": "USD",
            "currency": "USD",
            "account_name": account_name,
            "note": f"polymarket settlement token:{ticker} price:{_decimal_text(price_dec)}",
        })
    return rows


def sync_polymarket(
    wallet: str | None = None,
    proxy_wallet: str | None = None,
    account_name: str = "Polymarket",
    dry_run: bool = False,
    output: str | None = None,
    limit: int = 500,
    max_pages: int | None = None,
) -> list[dict]:
    """Fetch public Polymarket Activity trades, dedupe, and append to ft."""
    validate_security_account(account_name, currency="USD")

    if not proxy_wallet and not wallet:
        ensure_credentials_gitignored()
        creds = load_polymarket_credentials()
        proxy_wallet = creds.get("proxy_wallet")
        wallet = creds.get("wallet")

    if not proxy_wallet:
        if not wallet:
            raise ValueError("必须指定 wallet 或 proxy_wallet，或在 credentials.yaml 的 polymarket 段配置")
        proxy_wallet = resolve_proxy_wallet(wallet)
    else:
        proxy_wallet = proxy_wallet.lower()

    activities = fetch_activity(proxy_wallet, limit=limit, max_pages=max_pages)
    activity_rows = activities_to_stock_rows(activities, account_name=account_name)
    new_activity_rows = filter_new_rows(activity_rows, account_name=account_name)
    projected_positions = _project_pm_positions(account_name, new_activity_rows)
    settlement_rows = _settlement_rows_for_open_positions(
        account_name=account_name,
        positions=projected_positions,
    )
    candidate_rows = new_activity_rows + settlement_rows
    candidate_rows.sort(key=lambda r: r["date"])
    new_rows = candidate_rows

    print(f"Polymarket proxy wallet: {proxy_wallet}")
    print(f"Activity rows: {len(activities)}; trade rows: {len(activity_rows) + len(settlement_rows)}; new rows: {len(new_rows)}")

    if output:
        write_stock_csv(new_rows, output)
        print(f"✅ 已写出待导入 CSV: {output}")

    if dry_run or not new_rows:
        if dry_run:
            print("DRY-RUN: 未写入 ft records")
        elif not new_rows:
            print("✅ 没有新增 Polymarket 交易")
        return new_rows

    with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8", suffix=".csv", delete=False) as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(new_rows)
        tmp_path = f.name
    try:
        appended = do_append(tmp_path)
        if not appended:
            raise ValueError("Polymarket records append failed")
    finally:
        Path(tmp_path).unlink(missing_ok=True)
    return new_rows

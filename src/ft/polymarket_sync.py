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
from .stock import CSV_FIELDS, do_append
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
    """Convert one Polymarket activity item to a ft stock CSV row.

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

    amount = Decimal(str(usdc_size))
    if side == "BUY":
        amount = -amount

    return {
        "date": _format_activity_timestamp(activity.get("timestamp")),
        "action": side,
        "ticker": f"pm:{slug}:{outcome}",
        "shares": size,
        "price": price,
        "amount": _decimal_text(amount),
        "commission": "0",
        "currency": "USD",
        "account_name": account_name,
        "note": f"polymarket tx:{tx_hash}",
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


def _tx_hash_from_note(note: str) -> str | None:
    m = re.search(r"tx:(0x[a-fA-F0-9]+)", note or "")
    return m.group(1).lower() if m else None


def _existing_polymarket_identities(
    records_dir: Path | None = None,
    account_name: str | None = None,
) -> tuple[set[str], set[tuple[str, ...]]]:
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    security_dir = Path(records_dir) / "security"
    tx_hashes: set[str] = set()
    exact_rows: set[tuple[str, ...]] = set()
    if not security_dir.exists():
        return tx_hashes, exact_rows

    for path in sorted(security_dir.glob("*.csv")):
        with path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                if "action" not in row or "ticker" not in row:
                    continue
                ticker = row.get("ticker") or ""
                row_account_name = row.get("account_name") or ""
                if account_name is not None:
                    if row_account_name != account_name:
                        continue
                elif row_account_name != "Polymarket" and not ticker.startswith("pm:"):
                    continue
                tx = _tx_hash_from_note(row.get("note", ""))
                if tx:
                    tx_hashes.add(tx)
                exact_rows.add(_row_identity(row))
    return tx_hashes, exact_rows


def _row_identity(row: dict) -> tuple[str, ...]:
    return _shared_row_identity(row)


def filter_new_rows(
    rows: Iterable[dict],
    records_dir: Path | None = None,
    account_name: str | None = None,
) -> list[dict]:
    """Drop Polymarket rows already present in security CSV records."""
    tx_hashes, exact_rows = _existing_polymarket_identities(records_dir, account_name=account_name)
    new_rows = []
    seen_exact: set[tuple[str, ...]] = set()
    for row in rows:
        tx = _tx_hash_from_note(row.get("note", ""))
        ident = _row_identity(row)
        if tx and tx in tx_hashes:
            continue
        if ident in exact_rows or ident in seen_exact:
            continue
        new_rows.append(row)
        seen_exact.add(ident)
    return new_rows


def _settlement_rows_for_open_positions(account_name: str = "Polymarket") -> list[dict]:
    """Create SELL rows for open Polymarket positions whose market has resolved to 0/1."""
    from .snapshot import load_snapshot
    from .stock import _fetch_polymarket_prices

    snap = load_snapshot()
    account = snap.get("accounts", {}).get("security", {}).get(account_name, {})
    positions = account.get("positions", {}) if isinstance(account, dict) else {}
    tickers = [
        ticker for ticker, pos in positions.items()
        if str(ticker).startswith("pm:") and Decimal(str(pos.get("shares", 0) or 0)) > 0
    ]
    if not tickers:
        return []

    prices = _fetch_polymarket_prices(tickers)
    rows: list[dict] = []
    for ticker in sorted(tickers):
        if ticker not in prices:
            continue
        price = float(prices[ticker])
        # Resolved Gamma markets expose exact outcomePrices [0, 1]. Only auto-close
        # when the held token is at a settlement endpoint, not for live market quotes.
        if price not in (0.0, 1.0):
            continue
        shares = Decimal(str(positions[ticker].get("shares", 0)))
        price_dec = Decimal(str(int(price)))
        amount = shares * price_dec
        rows.append({
            "date": _today_iso(),
            "action": "SELL",
            "ticker": ticker,
            "shares": _decimal_text(shares),
            "price": _decimal_text(price_dec),
            "amount": _decimal_text(amount),
            "commission": "0",
            "currency": "USD",
            "account_name": account_name,
            "note": "polymarket settlement",
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
    rows = activities_to_stock_rows(activities, account_name=account_name)
    rows.extend(_settlement_rows_for_open_positions(account_name=account_name))
    rows.sort(key=lambda r: r["date"])
    new_rows = filter_new_rows(rows, account_name=account_name)

    print(f"Polymarket proxy wallet: {proxy_wallet}")
    print(f"Activity rows: {len(activities)}; trade rows: {len(rows)}; new rows: {len(new_rows)}")

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

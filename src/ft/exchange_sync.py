"""Generic ccxt exchange private-trades sync → ft crypto records."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

import csv
import tempfile
from pathlib import Path

from .accounts import find_account
from .stock import CSV_FIELDS
from . import sync_common
from .stock import do_append
from .credentials import load_credentials, ensure_credentials_gitignored

CASH_QUOTES = {"usdt", "usd"}
UTC_PLUS_8 = timezone(timedelta(hours=8))


def _num(value) -> str:
    """Format a number as a normalized plain-decimal string ('3000' not '3000.0')."""
    try:
        d = Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid numeric value: {value!r}") from exc
    if not d.is_finite():
        raise ValueError(f"invalid numeric value: {value!r}")
    text = format(d.normalize(), "f")
    return "0" if text == "-0" else text


def _format_trade_timestamp(ms) -> str:
    try:
        seconds = int(ms) / 1000
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid trade timestamp: {ms!r}") from exc
    return (datetime.fromtimestamp(seconds, tz=timezone.utc)
            .astimezone(UTC_PLUS_8).strftime("%Y-%m-%d %H:%M:%S"))


def _blank_row(account_name: str) -> dict:
    row = {field: "" for field in CSV_FIELDS}
    row["currency"] = "USD"
    row["account_name"] = account_name
    return row


def trade_to_rows(trade: dict, account_name: str, provider: str) -> list[dict]:
    """Map one ccxt trade to 1-3 ft stock CSV rows. Raises on ambiguous shapes."""
    tid = trade.get("id")
    if tid is None or str(tid) == "":
        raise ValueError(f"trade 缺少 id: {trade!r}")
    tid = str(tid)

    symbol = str(trade.get("symbol", ""))
    if "/" not in symbol:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'（应形如 BASE/QUOTE）")
    base, quote = (part.strip().lower() for part in symbol.split("/", 1))
    if not base or not quote:
        raise ValueError(f"无法拆分 trade symbol '{symbol}'")

    side = str(trade.get("side", "")).lower()
    if side not in {"buy", "sell"}:
        raise ValueError(f"未知 trade side: {trade.get('side')!r}")

    date = _format_trade_timestamp(trade.get("timestamp"))
    price = trade.get("price")
    amount = trade.get("amount")
    cost = trade.get("cost")
    if cost is None:
        cost = Decimal(str(price)) * Decimal(str(amount))

    fee = trade.get("fee") or {}
    fee_cost = fee.get("cost")
    fee_ccy = str(fee.get("currency", "")).lower()
    has_fee = fee_cost is not None and Decimal(str(fee_cost)) != 0

    note = f"{provider} tid:{tid}"
    # Include quote currency so replay can track per-currency cash
    cash_note = f"{note} quote:{quote}"
    rows: list[dict] = []

    if quote in CASH_QUOTES:
        cash_fee = has_fee and fee_ccy in CASH_QUOTES
        main = _blank_row(account_name)
        main["date"] = date
        main["action"] = "BUY" if side == "buy" else "SELL"
        main["ticker"] = base
        main["shares"] = _num(amount)
        main["price"] = _num(price)
        main["amount"] = _num(-Decimal(str(cost)) if side == "buy" else Decimal(str(cost)))
        main["commission"] = _num(fee_cost) if cash_fee else "0"
        main["note"] = cash_note
        rows.append(main)
        if has_fee and not cash_fee:
            rows.append(_fee_row(account_name, date, fee_ccy, fee_cost, tid, provider))
    else:
        swap_note = f"{provider} tid:{tid} swap:{tid}"
        if side == "buy":
            out_ticker, out_shares, in_ticker, in_shares = quote, cost, base, amount
        else:
            out_ticker, out_shares, in_ticker, in_shares = base, amount, quote, cost
        for action, ticker, shares in (
            ("SWAP_OUT", out_ticker, out_shares),
            ("SWAP_IN", in_ticker, in_shares),
        ):
            r = _blank_row(account_name)
            r["date"] = date
            r["action"] = action
            r["ticker"] = ticker
            r["shares"] = _num(shares)
            r["note"] = swap_note
            rows.append(r)
        if has_fee:
            rows.append(_fee_row(account_name, date, fee_ccy, fee_cost, tid, provider))

    return rows


def _fee_row(account_name, date, fee_ccy, fee_cost, tid, provider) -> dict:
    r = _blank_row(account_name)
    r["date"] = date
    r["action"] = "FEE"
    r["ticker"] = fee_ccy
    r["shares"] = _num(fee_cost)
    r["note"] = f"{provider} tid:{tid} fee"
    return r


def validate_crypto_account(account_name: str, currency: str = "USD") -> None:
    account = find_account(account_name, currency=currency)
    if account is None:
        raise ValueError(f"未知账户 '{account_name}' ({currency})，请先 ft acct add")
    if account.get("type") != "crypto":
        raise ValueError(
            f"账户 '{account_name}' ({currency}) 不是 crypto 类型，不能同步交易所成交"
        )


def build_client(provider: str, creds: dict):
    import ccxt
    if not hasattr(ccxt, provider):
        raise ValueError(f"ccxt 不支持交易所 '{provider}'")
    params = {"apiKey": creds["api_key"], "secret": creds["api_secret"]}
    if creds.get("password"):
        params["password"] = creds["password"]
    return getattr(ccxt, provider)(params)


def fetch_trades(client, since=None, symbols=None, limit=1000) -> list[dict]:
    """Paginate client.fetch_my_trades over symbols; merge & dedupe by trade id."""
    targets = list(symbols) if symbols else [None]
    seen: set[str] = set()
    out: list[dict] = []
    for symbol in targets:
        cursor = since
        while True:
            batch = client.fetch_my_trades(symbol, cursor, limit)
            if not batch:
                break
            fresh = 0
            for tr in batch:
                tid = str(tr.get("id"))
                if tid in seen:
                    continue
                seen.add(tid)
                out.append(tr)
                fresh += 1
            if len(batch) < limit:
                break
            last_ts = batch[-1].get("timestamp")
            if last_ts is None or fresh == 0:
                break
            cursor = int(last_ts) + 1
    return out


def _since_to_ms(since: str | None) -> int | None:
    if not since:
        return None
    dt = datetime.strptime(since, "%Y-%m-%d").replace(tzinfo=UTC_PLUS_8)
    return int(dt.timestamp() * 1000)


def filter_new_rows(rows, records_dir=None, account_name=None) -> list[dict]:
    return sync_common.filter_new_rows(
        rows, records_dir=records_dir, account_name=account_name, prefix="tid"
    )


def sync_exchange(provider, account_name, since=None, dry_run=False,
                  output=None, symbols=None, _client=None) -> list[dict]:
    """Fetch private trades via ccxt, map, dedupe, and (unless dry-run) append."""
    validate_crypto_account(account_name)

    client = _client
    if client is None:
        creds = load_credentials(provider)
        ensure_credentials_gitignored()
        client = build_client(provider, creds)

    trades = fetch_trades(client, since=_since_to_ms(since), symbols=symbols)
    rows: list[dict] = []
    for trade in trades:
        rows.extend(trade_to_rows(trade, account_name, provider))
    # 稳定排序：同 tid 的 SWAP_OUT→SWAP_IN→FEE 同 timestamp，靠稳定性保序。
    rows.sort(key=lambda r: r["date"])
    new_rows = filter_new_rows(rows, account_name=account_name)

    print(f"交易所: {provider}; 账户: {account_name}")
    print(f"成交: {len(trades)}; 映射行: {len(rows)}; 新增行: {len(new_rows)}")

    if output:
        sync_common.write_stock_csv(new_rows, output)
        print(f"✅ 已写出待导入 CSV: {output}")
        return new_rows

    if dry_run or not new_rows:
        if dry_run:
            print("DRY-RUN: 未写入 ft records")
        elif not new_rows:
            print("✅ 没有新增成交")
        return new_rows

    with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8",
                                     suffix=".csv", delete=False) as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(new_rows)
        tmp_path = f.name
    try:
        if not do_append(tmp_path):
            raise ValueError("交易所成交 append 失败")
    finally:
        Path(tmp_path).unlink(missing_ok=True)
    return new_rows

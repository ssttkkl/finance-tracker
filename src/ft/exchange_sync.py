"""Generic ccxt exchange private-trades sync → ft crypto records."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

import csv
import tempfile
from pathlib import Path

from .accounts import find_account, load_accounts
from .stock import CSV_FIELDS
from . import sync_common
from .stock import do_append
from .credentials import load_credentials, ensure_credentials_gitignored

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


def _blank_row(account_name: str, currency: str = "USD") -> dict:
    row = {field: "" for field in CSV_FIELDS}
    row["currency"] = currency
    row["account_name"] = account_name
    return row


def _load_base_currencies(account_name: str) -> str:
    """Return the base currency for *account_name* from accounts.yaml."""
    accounts = load_accounts()
    for acct in accounts:
        if acct.get("name") == account_name:
            return acct.get("currency", "USD")
    return "USD"


def trade_to_rows(trade: dict, account_name: str, provider: str) -> list[dict]:
    """Map one ccxt trade to exactly one ft swap CSV row. Raises on bad shapes."""
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

    # Determine swap direction based on side
    # buy: pay quote → receive base
    # sell: pay base → receive quote
    if side == "buy":
        from_ticker, from_amount = quote, cost
        to_ticker, to_amount = base, amount
    else:  # sell
        from_ticker, from_amount = base, amount
        to_ticker, to_amount = quote, cost

    currency = _load_base_currencies(account_name)
    r = _blank_row(account_name, currency)
    r["date"] = date
    r["action"] = "swap"
    r["from_ticker"] = from_ticker
    r["to_ticker"] = to_ticker
    r["from_amount"] = _num(from_amount)
    r["to_amount"] = _num(to_amount)
    r["price"] = _num(price) if price is not None else ""
    r["commission"] = _num(fee_cost) if has_fee else "0"
    r["commission_asset"] = fee_ccy if has_fee else ""
    r["note"] = note

    return [r]


_TRANSFER_LEDGER_TYPES = {
    "transaction",
    "transfer",
    "derivativescrossexchangetransfer",
}
_INCOME_LEDGER_TYPES = {"reward", "staking"}


def _ledger_context(provider: str, lid: str, entry: dict) -> str:
    return (
        f"provider={provider} id={lid} type={entry.get('type')!r} "
        f"direction={entry.get('direction')!r} currency={entry.get('currency')!r} "
        f"amount={entry.get('amount')!r}"
    )


def _ledger_abs_decimal(entry: dict, lid: str, field: str) -> Decimal:
    try:
        amount = abs(Decimal(str(entry.get(field))))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid ledger {field} for {lid}: {entry.get(field)!r}") from exc
    if not amount.is_finite():
        raise ValueError(f"invalid ledger {field} for {lid}: {entry.get(field)!r}")
    return amount


def ledger_to_rows(entry: dict, account_name: str, provider: str) -> list[dict]:
    """Map one ccxt ledger entry to ft rows.

    CCXT ledger amount is treated as the net credited/debited amount. Fees are
    kept in commission fields for audit, but replay must not charge them again.
    """
    lid = entry.get("id")
    if lid is None or str(lid) == "":
        raise ValueError(f"ledger 缺少 id: {entry!r}")
    lid = str(lid)

    typ = str(entry.get("type", "")).lower()
    if typ == "trade":
        return []

    direction = str(entry.get("direction", "")).lower()
    currency = str(entry.get("currency", "")).lower()
    if not currency:
        raise ValueError(f"ledger {lid} 缺少 currency: {_ledger_context(provider, lid, entry)}")

    if typ not in _TRANSFER_LEDGER_TYPES and typ not in _INCOME_LEDGER_TYPES:
        raise ValueError(
            f"unsupported balance-affecting ledger entry: "
            f"{_ledger_context(provider, lid, entry)}"
        )
    if typ in _TRANSFER_LEDGER_TYPES and direction not in {"in", "out"}:
        raise ValueError(
            f"unsupported transfer ledger direction: {_ledger_context(provider, lid, entry)}"
        )
    if typ in _INCOME_LEDGER_TYPES and direction != "in":
        raise ValueError(
            f"unsupported income ledger direction: {_ledger_context(provider, lid, entry)}"
        )

    amount = _ledger_abs_decimal(entry, lid, "amount")
    if typ in _INCOME_LEDGER_TYPES and amount <= 0:
        raise ValueError(f"invalid income ledger amount: {_ledger_context(provider, lid, entry)}")

    fee = entry.get("fee") or {}
    fee_cost = fee.get("cost")
    fee_amount = Decimal("0")
    if fee_cost is not None:
        try:
            fee_amount = abs(Decimal(str(fee_cost)))
        except (InvalidOperation, ValueError) as exc:
            raise ValueError(f"invalid ledger fee for {lid}: {fee_cost!r}") from exc
        if not fee_amount.is_finite():
            raise ValueError(f"invalid ledger fee for {lid}: {fee_cost!r}")
    fee_ccy = str(fee.get("currency", "")).lower()

    base_currency = _load_base_currencies(account_name)
    r = _blank_row(account_name, base_currency)
    r["date"] = _format_trade_timestamp(entry.get("timestamp"))
    r["price"] = "1"
    r["commission"] = _num(fee_amount) if fee_amount else "0"
    r["commission_asset"] = fee_ccy if fee_amount else ""
    r["note"] = f"{provider} lid:{lid} type:{typ}"

    if typ in _INCOME_LEDGER_TYPES:
        r["action"] = "dividend"
        r["from_ticker"] = ""
        r["to_ticker"] = currency
        r["from_amount"] = "0"
        r["to_amount"] = _num(amount)
    elif direction == "in":
        r["action"] = "deposit"
        r["from_ticker"] = ""
        r["to_ticker"] = currency
        r["from_amount"] = "0"
        r["to_amount"] = _num(amount)
    else:
        r["action"] = "withdraw"
        r["from_ticker"] = currency
        r["to_ticker"] = ""
        r["from_amount"] = _num(amount)
        r["to_amount"] = "0"

    return [r]


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


def fetch_ledger(client, since=None, limit=1000) -> list[dict]:
    """Paginate client.fetch_ledger and dedupe by ledger id."""
    if not hasattr(client, "fetch_ledger"):
        return []
    seen: set[str] = set()
    out: list[dict] = []
    cursor = since
    while True:
        batch = client.fetch_ledger(None, cursor, limit)
        if not batch:
            break
        fresh = 0
        for entry in batch:
            lid = str(entry.get("id"))
            if lid in seen:
                continue
            seen.add(lid)
            out.append(entry)
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


def _filter_new_rows_by_prefix(rows, prefix, records_dir=None, account_name=None) -> list[dict]:
    return sync_common.filter_new_rows(
        rows, records_dir=records_dir, account_name=account_name, prefix=prefix
    )


def sync_exchange(provider, account_name, since=None, dry_run=False,
                  output=None, symbols=None, _client=None) -> list[dict]:
    """Fetch private trades/ledger via ccxt, map, dedupe, and append unless dry-run."""
    validate_crypto_account(account_name)

    client = _client
    if client is None:
        creds = load_credentials(provider)
        client = build_client(provider, creds)

    since_ms = _since_to_ms(since)
    trades = fetch_trades(client, since=since_ms, symbols=symbols)
    trade_rows: list[dict] = []
    for trade in trades:
        trade_rows.extend(trade_to_rows(trade, account_name, provider))

    ledger_entries = fetch_ledger(client, since=since_ms) if provider == "kraken" else []
    ledger_rows: list[dict] = []
    for entry in ledger_entries:
        ledger_rows.extend(ledger_to_rows(entry, account_name, provider))

    trade_rows.sort(key=lambda r: r["date"])
    ledger_rows.sort(key=lambda r: r["date"])
    new_trade_rows = _filter_new_rows_by_prefix(
        trade_rows, "tid", account_name=account_name
    )
    new_ledger_rows = _filter_new_rows_by_prefix(
        ledger_rows, "lid", account_name=account_name
    )
    new_rows = sorted(new_trade_rows + new_ledger_rows, key=lambda r: r["date"])
    rows = trade_rows + ledger_rows

    print(f"交易所: {provider}; 账户: {account_name}")
    print(
        f"成交: {len(trades)}; ledger: {len(ledger_entries)}; "
        f"映射行: {len(rows)}; 新增行: {len(new_rows)}"
    )

    if output:
        sync_common.write_stock_csv(new_rows, output)
        print(f"✅ 已写出待导入 CSV: {output}")
        return new_rows

    if dry_run or not new_rows:
        if dry_run:
            print("DRY-RUN: 未写入 ft records")
        elif not new_rows:
            print("✅ 没有新增成交或资金流水")
        return new_rows

    with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8",
                                     suffix=".csv", delete=False) as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(new_rows)
        tmp_path = f.name
    try:
        ensure_credentials_gitignored()
        if not do_append(tmp_path):
            raise ValueError("交易所同步 append 失败")
    finally:
        Path(tmp_path).unlink(missing_ok=True)
    return new_rows

"""Generic ccxt exchange private-trades sync → ft crypto records."""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation

from .accounts import find_account
from .stock import CSV_FIELDS

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
        main["note"] = note
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

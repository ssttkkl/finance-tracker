"""Stock trading — snapshot management + CSV recording + all stock operations"""
import csv
import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

from . import models
from .snapshot import load_snapshot, save_snapshot

# ── CSV fields for security trades ──────────────────────────────────────
CSV_FIELDS = [
    "date", "action", "ticker", "shares", "price", "amount",
    "commission", "currency", "account_name", "note",
]


# ── Snapshot helpers ────────────────────────────────────────────────────



# ── CSV trade recording ─────────────────────────────────────────────────


def _ensure_account(snap: dict, account_name: str, currency: str) -> dict:
    """Get-or-create an account dict inside the snapshot."""
    accounts = snap.setdefault("accounts", {})
    if account_name not in accounts:
        accounts[account_name] = {
            "currency": currency,
            "cash": 0.0,
            "positions": {},
        }
    return accounts[account_name]


def record_trade(
    date: str,
    action: str,
    ticker: str,
    shares: float,
    price: float,
    amount: float,
    commission: float,
    currency: str,
    account_name: str,
    note: str = "",
) -> dict:
    """Write a trade row to records/security/{date[:10]}.csv.

    Returns the row dict that was written.
    """
    records_dir = models.RECORDS_DIR
    date_key = date[:10]  # "2026-06-12 10:00:00" → "2026-06-12"
    security_dir = records_dir / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / f"{date_key}.csv"

    # Read existing rows
    existing_rows = []
    if day_path.exists():
        with day_path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            existing_rows = list(reader)

    # Build new row
    new_row = {
        "date": date,
        "action": action,
        "ticker": ticker,
        "shares": str(shares),
        "price": str(price),
        "amount": str(amount),
        "commission": str(commission),
        "currency": currency,
        "account_name": account_name,
        "note": note,
    }

    # Merge, sort, write
    all_rows = existing_rows + [new_row]
    all_rows.sort(key=lambda r: r["date"])

    with day_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(all_rows)

    return new_row


# ── Position helpers ────────────────────────────────────────────────────


def _position_cost(pos: dict) -> float:
    """Total cost basis for a position."""
    return pos["shares"] * pos["avg_cost"]


# ── Stock operations ────────────────────────────────────────────────────


def _now() -> str:
    """Return current datetime string."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def do_buy(
    ticker: str,
    shares: float,
    price: float,
    commission: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Buy shares — updates snapshot & records trade."""
    if date is None:
        date = _now()

    date_key = date[:10]
    amount = -shares * price

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    pos = acct["positions"].setdefault(ticker, {"shares": 0, "avg_cost": 0.0})

    total_cost = _position_cost(pos) + shares * price
    pos["shares"] += shares
    pos["avg_cost"] = total_cost / pos["shares"] if pos["shares"] > 0 else 0.0
    acct["cash"] += amount - commission
    acct["cash"] = round(acct["cash"], 10)  # avoid floating point noise
    snap["updated_at"] = date_key
    save_snapshot(snap)

    # Record trade
    record_trade(
        date=date, action="BUY", ticker=ticker,
        shares=shares, price=price, amount=amount,
        commission=commission, currency=currency,
        account_name=account_name, note=note,
    )
    print(f"✅ 买入 {int(shares)} 股 {ticker} @ ${price} ({account_name})")
    return True


def do_sell(
    ticker: str,
    shares: float,
    price: float,
    commission: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Sell shares — updates snapshot & records trade.

    avg_cost is unchanged.  If all shares are sold the position is removed.
    """
    if date is None:
        date = _now()

    date_key = date[:10]
    amount = shares * price

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    pos = acct["positions"].get(ticker)

    if pos is None:
        print(f"⚠️  No position for {ticker} in {account_name}", file=sys.stderr)
        return

    # avg_cost stays the same
    pos["shares"] -= shares
    acct["cash"] += amount - commission
    acct["cash"] = round(acct["cash"], 10)

    if pos["shares"] <= 0:
        del acct["positions"][ticker]

    snap["updated_at"] = date_key
    save_snapshot(snap)

    # Record trade
    record_trade(
        date=date, action="SELL", ticker=ticker,
        shares=shares, price=price, amount=amount,
        commission=commission, currency=currency,
        account_name=account_name, note=note,
    )


def do_deposit(
    amount: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Deposit cash into account."""
    if date is None:
        date = _now()

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] += amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    save_snapshot(snap)

    record_trade(
        date=date, action="DEPOSIT", ticker="",
        shares=0, price=0, amount=amount,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


def do_withdraw(
    amount: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Withdraw cash from account."""
    if date is None:
        date = _now()

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] -= amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    save_snapshot(snap)

    record_trade(
        date=date, action="WITHDRAW", ticker="",
        shares=0, price=0, amount=-amount,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


def do_dividend(
    ticker: str,
    amount: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Receive dividend — cash in, no position change."""
    if date is None:
        date = _now()

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] += amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    save_snapshot(snap)

    record_trade(
        date=date, action="DIVIDEND", ticker=ticker,
        shares=0, price=0, amount=amount,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


def do_checkin_ticker(
    ticker: str,
    shares: float,
    avg_cost: float,
    currency: str,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Directly overwrite a position in the snapshot.

    Records a CHECKIN row.
    """
    if date is None:
        date = _now()

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["positions"][ticker] = {
        "shares": shares,
        "avg_cost": avg_cost,
    }
    snap["updated_at"] = date_key
    save_snapshot(snap)

    record_trade(
        date=date, action="CHECKIN", ticker=ticker,
        shares=shares, price=avg_cost, amount=0,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


def do_checkin_cash(
    cash: float,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Directly overwrite cash in the snapshot.

    Records a CHECKIN row.
    """
    if date is None:
        date = _now()

    date_key = date[:10]
    snap = load_snapshot()
    # Need currency for the account — use the existing one or default
    acct = snap.setdefault("accounts", {}).get(account_name, {})
    currency = acct.get("currency", "USD")
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] = cash
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    save_snapshot(snap)

    record_trade(
        date=date, action="CHECKIN", ticker="",
        shares=0, price=0, amount=cash,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


# ── Portfolio listing ───────────────────────────────────────────────────


def _fetch_prices(tickers: list[str]) -> dict[str, float]:
    """Fetch current prices from yfinance.

    Returns {} on failure (yfinance not installed or network error).
    """
    try:
        import yfinance as yf  # type: ignore[import-untyped]
    except ImportError:
        return {}

    if not tickers:
        return {}

    try:
        data = yf.download(
            tickers, period="1d", progress=False,
            auto_adjust=False,
        )
        # yfinance returns a multi-index DataFrame
        # Structure depends on version, handle flexibly
        prices = {}
        for ticker in tickers:
            try:
                # Try Close column first
                if "Close" in data.columns:
                    close = data["Close"].get(ticker)
                else:
                    close = data.xs("Close", axis=1, level=0).get(ticker)
                if close is not None and not close.empty:
                    val = close.iloc[-1]
                    if val is not None and not (hasattr(val, "isna") and val.isna()):
                        prices[ticker] = float(val)
                        continue
                # Fallback: Adj Close
                if "Adj Close" in data.columns:
                    adj = data["Adj Close"].get(ticker)
                else:
                    adj = data.xs("Adj Close", axis=1, level=0).get(ticker)
                if adj is not None and not adj.empty:
                    val = adj.iloc[-1]
                    if val is not None and not (hasattr(val, "isna") and val.isna()):
                        prices[ticker] = float(val)
            except (KeyError, IndexError, TypeError, ValueError):
                continue
        return prices
    except Exception:
        return {}


def _fmt(value: float, symbol: str) -> str:
    """Format a number with currency symbol."""
    if value >= 0:
        return f" {symbol}{value:>,.2f}"
    return f"{symbol}{value:>,.2f}"


def do_list():
    """Read snapshot, fetch prices, display portfolio."""
    snap = load_snapshot()
    accounts = snap.get("accounts", {})
    if not accounts:
        print("📭 无持仓")
        return

    # Collect all tickers for price fetching
    all_tickers = set()
    for acct_data in accounts.values():
        all_tickers.update(acct_data.get("positions", {}).keys())
    prices = _fetch_prices(list(all_tickers))

    for acct_name, acct_data in accounts.items():
        currency = acct_data.get("currency", "USD")
        symbol = models.CURRENCY_SYMBOLS.get(currency, "$")
        positions = acct_data.get("positions", {})
        cash = acct_data.get("cash", 0.0)

        print(f"\n  📊 持仓 [{currency}]  {acct_name}")
        print(
            f"  {'代码':<16} {'股数':>8} {'均价':>12} {'成本':>14} "
            f"{'市值':>14} {'盈亏':>14} {'涨幅':>8}"
        )
        print("  " + "-" * 90)

        total_cost = 0.0
        total_value = 0.0

        for ticker in sorted(positions.keys()):
            pos = positions[ticker]
            shares = pos["shares"]
            avg_cost = pos["avg_cost"]
            cost = shares * avg_cost
            current_price = prices.get(ticker)
            if current_price is not None:
                value = shares * current_price
                pl = value - cost
                pct = (current_price - avg_cost) / avg_cost * 100
                pl_str = f"+{symbol}{pl:>,.2f}" if pl >= 0 else f"{symbol}{pl:>,.2f}"
                pct_str = f"+{pct:.1f}%" if pct >= 0 else f"{pct:.1f}%"
            else:
                value = 0.0
                pl = 0.0
                pl_str = "   N/A"
                pct_str = "  N/A"

            print(
                f"  {ticker:<16} {shares:>8.0f} {symbol}{avg_cost:>10,.2f} "
                f"{symbol}{cost:>12,.2f} {symbol}{value:>12,.2f} "
                f"{pl_str:>14} {pct_str:>8}"
            )

            total_cost += cost
            total_value += value

        print("  " + "─" * 90)
        print(f"  {'持仓市值':<16} {'':>8} {'':>12} {'':>14} "
              f"{symbol}{total_value:>12,.2f}")
        print(f"  {'现金':<16} {'':>8} {'':>12} {'':>14} "
              f"{symbol}{cash:>12,.2f}")
        total_combined = total_value + cash
        print(f"  {'合计':<16} {'':>8} {'':>12} {'':>14} "
              f"{symbol}{total_combined:>12,.2f}")


# ── Verification ────────────────────────────────────────────────────────


def _replay_security_csv(records_dir=None):
    """Replay security CSV records. Returns (positions, cash) dicts."""
    from pathlib import Path
    from collections import defaultdict
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    records_dir = Path(str(records_dir))
    security_dir = records_dir / "security"

    positions = defaultdict(lambda: {"shares": 0, "total_cost": 0.0})
    cash = defaultdict(float)

    if not security_dir.exists():
        return positions, cash

    for csv_file in sorted(security_dir.glob("*.csv")):
        with open(csv_file, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                a = row["account_name"]
                act = row["action"]
                t = row.get("ticker", "") or ""
                try:
                    s = int(float(row["shares"] or 0))
                    p = float(row["price"] or 0)
                    amt = float(row["amount"] or 0)
                    com = float(row["commission"] or 0)
                except (ValueError, KeyError):
                    continue

                if act == "INIT":
                    positions[(a, t)]["shares"] = s
                    positions[(a, t)]["total_cost"] = round(s * p, 2)
                elif act == "BUY":
                    h = positions[(a, t)]
                    old_s = h["shares"]
                    old_c = h["total_cost"]
                    new_s = old_s + s
                    if old_s > 0:
                        avg = round((old_c + s * p) / new_s, 2)
                        h["total_cost"] = round(avg * new_s, 2)
                    else:
                        h["total_cost"] = round(s * p, 2)
                    h["shares"] = new_s
                    cash[a] = round(cash[a] + amt - com, 2)
                elif act == "SELL":
                    h = positions[(a, t)]
                    sold = abs(s)
                    if h["shares"] > 0:
                        avg = round(h["total_cost"] / h["shares"], 2)
                        h["shares"] -= sold
                        if h["shares"] > 0:
                            h["total_cost"] = round(avg * h["shares"], 2)
                        else:
                            h["total_cost"] = 0.0
                    cash[a] = round(cash[a] + amt - com, 2)
                elif act == "CHECKIN":
                    if t:
                        positions[(a, t)]["shares"] = s
                        positions[(a, t)]["total_cost"] = round(s * p, 2)
                    else:
                        cash[a] = amt
                elif act in ("DEPOSIT", "DIVIDEND"):
                    cash[a] = round(cash[a] + amt, 2)
                elif act == "WITHDRAW":
                    cash[a] = round(cash[a] + amt, 2)

    return positions, cash


def verify_security(records_dir=None):
    """Replay security CSV and compare against snapshot.
    Returns (ok: bool, report_lines: list[str])."""
    snap = load_snapshot()
    positions, cash = _replay_security_csv(records_dir)
    lines = []
    ok = True

    if not positions and not cash:
        lines.append("📭 无 security CSV 记录")
        return True, lines

    # Compare with snapshot — security accounts live under accounts.security
    sec_accounts = snap.get("accounts", {}).get("security", {})
    for acct_name, acct_data in sec_accounts.items():
        for ticker, sp in acct_data.get("positions", {}).items():
            csv_p = positions.get((acct_name, ticker))
            if csv_p is None:
                lines.append(f"  ❌ {acct_name}/{ticker}: snapshot有但CSV无")
                ok = False
            elif csv_p["shares"] != sp["shares"]:
                lines.append(f"  ❌ {acct_name}/{ticker}: CSV股数={csv_p['shares']} vs 快照={sp['shares']}")
                ok = False

        sc = acct_data.get("cash", 0.0)
        cc = cash.get(acct_name, 0.0)
        if abs(sc - cc) > 0.02:
            lines.append(f"  ❌ {acct_name} 现金: CSV={cc:.2f} vs 快照={sc:.2f}")
            ok = False

    # Check CSV-only positions not in snapshot
    for (acct, ticker) in positions:
        if ticker and not sec_accounts.get(acct, {}).get("positions", {}).get(ticker):
            p = positions[(acct, ticker)]
            if p["shares"] > 0:
                lines.append(f"  ❌ {acct}/{ticker}: CSV有但快照无")
                ok = False

    if ok:
        lines.append("  ✅ Security CSV ↔ Snapshot 完全对齐")
    else:
        lines.append("  ❌ 存在差异")
    return ok, lines


def repair_security(records_dir=None):
    """Replay security CSV and write into unified snapshot accounts.security."""
    from datetime import datetime
    positions, cash = _replay_security_csv(records_dir)

    accounts = {}
    for (acct_name, ticker), p in positions.items():
        if ticker:
            if acct_name not in accounts:
                accounts[acct_name] = {"currency": "", "cash": 0.0, "positions": {}}
            accounts[acct_name]["positions"][ticker] = {
                "shares": p["shares"],
                "avg_cost": round(p["total_cost"] / p["shares"], 2) if p["shares"] > 0 else 0.0,
            }

    for acct_name, c in cash.items():
        if acct_name not in accounts:
            accounts[acct_name] = {"currency": "", "cash": 0.0, "positions": {}}
        accounts[acct_name]["cash"] = round(c, 2)

    snap = load_snapshot()
    snap.setdefault("accounts", {})["security"] = accounts
    snap["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_snapshot(snap)
    print(f"✅ 已从 CSV 重建快照: {len(accounts)} 个账户, {sum(len(a.get('positions',{})) for a in accounts.values())} 个标的")

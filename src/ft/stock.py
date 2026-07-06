"""Stock trading — snapshot management + CSV recording + all stock operations"""
import csv
import math
import os
import subprocess
import sys
import tempfile
from collections import Counter
from datetime import datetime
from pathlib import Path
from typing import Optional

from . import models
from .snapshot import git_stage, load_snapshot, save_snapshot

# ── CSV fields for security trades ──────────────────────────────────────
CSV_FIELDS = [
    "date", "action", "ticker", "shares", "price", "amount",
    "commission", "currency", "account_name", "note",
]

VALID_ACTIONS = {"BUY", "SELL", "DEPOSIT", "WITHDRAW", "DIVIDEND", "CHECKIN"}


def _clean_csv_row(row: dict) -> dict:
    """Drop csv.DictReader's None key for malformed over-wide rows."""
    return {k: v for k, v in row.items() if k is not None}


def _security_fieldnames(rows: list[dict]) -> list[str]:
    """Security files may mix stock rows and transfer audit rows; preserve both schemas."""
    fieldnames = list(CSV_FIELDS)
    for row in rows:
        for field in row.keys():
            if field is not None and field not in fieldnames:
                fieldnames.append(field)
    return fieldnames


def _write_security_csv(path: Path, rows: list[dict]) -> None:
    """Write security rows while preserving transfer-style audit columns if present."""
    clean_rows = [_clean_csv_row(row) for row in rows]
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = None
    try:
        with tempfile.NamedTemporaryFile("w", newline="", encoding="utf-8", dir=path.parent, delete=False) as f:
            tmp_path = Path(f.name)
            writer = csv.DictWriter(
                f,
                fieldnames=_security_fieldnames(clean_rows),
                extrasaction="ignore",
            )
            writer.writeheader()
            writer.writerows(clean_rows)
        tmp_path.replace(path)
    finally:
        if tmp_path is not None and tmp_path.exists():
            tmp_path.unlink(missing_ok=True)


def _ensure_finite_values(**values: float) -> None:
    for name, value in values.items():
        try:
            numeric = float(value)
        except (TypeError, ValueError) as exc:
            raise ValueError(f"{name} must be numeric: {value!r}") from exc
        if math.isnan(numeric) or math.isinf(numeric):
            raise ValueError(f"{name} is not finite: {value!r}")


def _snapshot_file_backup():
    """Return the live snapshot path and its current bytes for rollback."""
    from . import snapshot as snapshot_mod
    path = snapshot_mod.SNAPSHOT_PATH
    return path, path.read_bytes() if path.exists() else None


def _restore_snapshot_file(path: Path, backup: bytes | None) -> None:
    if backup is None:
        path.unlink(missing_ok=True)
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(backup)


def _save_snapshot_and_record_trade(snap: dict, **trade_kwargs) -> dict:
    """Save snapshot and write audit row; restore snapshot/CSV if either write fails."""
    _validate_security_snapshot_finite(snap)
    snapshot_path, snapshot_backup = _snapshot_file_backup()
    date_key = str(trade_kwargs.get("date", ""))[:10]
    day_path = models.RECORDS_DIR / "security" / f"{date_key}.csv"
    day_backup = day_path.read_bytes() if day_path.exists() else None
    try:
        save_snapshot(snap)
        return record_trade(**trade_kwargs)
    except Exception:
        _restore_snapshot_file(snapshot_path, snapshot_backup)
        _restore_snapshot_file(day_path, day_backup)
        try:
            git_stage(snapshot_path.parent)
        except Exception:
            pass
        raise


def _validate_security_snapshot_finite(snap: dict) -> None:
    """Reject non-finite numeric values in proposed security snapshot state."""
    security = snap.get("accounts", {}).get("security", {})
    for account_name, account in security.items():
        _ensure_finite_values(**{f"{account_name}.cash": account.get("cash", 0)})
        for ticker, pos in account.get("positions", {}).items():
            _ensure_finite_values(
                **{
                    f"{account_name}.{ticker}.shares": pos.get("shares", 0),
                    f"{account_name}.{ticker}.avg_cost": pos.get("avg_cost", 0),
                }
            )


# ── CSV trade recording ─────────────────────────────────────────────────


def _ensure_account(snap: dict, account_name: str, currency: str) -> dict:
    """Get-or-create an account dict inside snap.accounts.security."""
    top = snap.setdefault("accounts", {})
    sec = top.setdefault("security", {})
    if account_name not in sec:
        sec[account_name] = {
            "currency": currency,
            "cash": 0.0,
            "positions": {},
        }
    return sec[account_name]


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
    _ensure_finite_values(shares=shares, price=price, amount=amount, commission=commission)
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
    all_rows.sort(key=lambda r: r.get("date", ""))

    _write_security_csv(day_path, all_rows)

    return new_row


# ── PDF → stock CSV ────────────────────────────────────────────────────


def do_convert(path, source, output, password=None, account="东方证券", currency="CNY"):
    """将 PDF 对账单转换为 10 列 stock CSV。

    当前仅支持 source="dfzq"（东方证券）。
    """
    if source != "dfzq":
        print(f"❌ 不支持的券商类型: {source}，仅支持 dfzq")
        return

    # 1. Decrypt PDF if password provided
    tmp_pdf = None
    if password:
        tmp_pdf = tempfile.NamedTemporaryFile(suffix=".pdf", delete=False)
        try:
            subprocess.run(
                ["qpdf", f"--password={password}", "--decrypt", path, tmp_pdf.name],
                check=True, timeout=30,
            )
            pdf_path = tmp_pdf.name
        except Exception as e:
            print(f"❌ PDF 解密失败: {e}")
            os.unlink(tmp_pdf.name)
            return
    else:
        pdf_path = path

    # 2. Extract text with mutool
    try:
        result = subprocess.run(
            ["mutool", "draw", "-F", "text", pdf_path],
            capture_output=True, check=True, timeout=60,
        )
        text = result.stdout.decode("utf-8", errors="replace")
        lines = text.split("\n")
    except Exception as e:
        print(f"❌ 文本提取失败: {e}")
        if tmp_pdf:
            os.unlink(pdf_path)
        return

    # Clean up temp PDF
    if tmp_pdf:
        os.unlink(pdf_path)

    # 3. Parse with dfzq importer
    from .importers.dfzq import parse_dfzq_text
    records = parse_dfzq_text(lines)

    if not records:
        print("❌ 未解析到任何交易记录")
        return

    # 4. Map parser output to stock CSV 10-column format
    mapped = []
    for rec in records:
        mapped.append({
            "date": rec["date"],
            "action": rec["action"],
            "ticker": rec["ticker"],
            "shares": str(rec["shares"]),
            "price": str(rec["price"]),
            "amount": str(rec["amount"]),
            "commission": str(rec["fee"]),
            "currency": currency,
            "account_name": account,
            "note": rec["note"],
        })

    # 5. Write CSV
    with open(output, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(mapped)

    # 6. Print statistics
    actions = Counter(r["action"] for r in mapped)
    print(f"✅ 已转换 {len(mapped)} 条记录 → {output}")
    for act in sorted(actions):
        print(f"   {act}: {actions[act]}")


# ── Stock CSV batch import ──────────────────────────────────────────────


def do_append(file_path):
    """将 stock CSV 批量导入 records/security/。

    校验、按日写入、重建快照、git commit。
    """
    # 1. Read & validate CSV
    with open(file_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    if not rows:
        print("❌ CSV 为空")
        return False

    # Validate 10 columns
    actual_fields = reader.fieldnames or list(rows[0].keys())
    if set(actual_fields) != set(CSV_FIELDS):
        missing = set(CSV_FIELDS) - set(actual_fields)
        extra = set(actual_fields) - set(CSV_FIELDS)
        msg = []
        if missing:
            msg.append(f"缺少字段: {', '.join(sorted(missing))}")
        if extra:
            msg.append(f"多余字段: {', '.join(sorted(extra))}")
        print(f"❌ CSV 字段不匹配: {'; '.join(msg)}")
        return False

    # Validate actions
    for i, row in enumerate(rows, 1):
        if row["action"] not in VALID_ACTIONS:
            print(f"❌ 第 {i} 行: 无效 action '{row['action']}'，"
                  f"允许值: {', '.join(sorted(VALID_ACTIONS))}")
            return False

    # Load accounts for validation
    from .accounts import load_accounts
    accounts = load_accounts()
    accounts_by_key = {(a.get("name"), a.get("currency")): a for a in accounts}

    # Validate account names, currencies, and types
    for i, row in enumerate(rows, 1):
        account = accounts_by_key.get((row["account_name"], row["currency"]))
        if account is None:
            print(f"❌ 第 {i} 行: 未知账户 '{row['account_name']}' ({row['currency']})，请先 ft acct add")
            return False
        if account.get("type") != "security":
            print(f"❌ 第 {i} 行: 账户 '{row['account_name']}' ({row['currency']}) 不是 security 类型，不能导入股票记录")
            return False

    # Validate numeric fields and replay-derived finite values
    num_fields = ["shares", "price", "amount", "commission"]
    for i, row in enumerate(rows, 1):
        parsed = {}
        for field in num_fields:
            try:
                value = float(row[field])
            except (ValueError, TypeError):
                print(f"❌ 第 {i} 行: 字段 '{field}' 值 '{row[field]}' 不是有效数字")
                return False
            if not math.isfinite(value):
                print(f"❌ 第 {i} 行: 字段 '{field}' 值 '{row[field]}' 不是有限数字")
                return False
            parsed[field] = value
        try:
            if row["action"] in {"BUY", "SELL", "CHECKIN"}:
                _ensure_finite_values(position_value=parsed["shares"] * parsed["price"])
            _ensure_finite_values(cash_delta=parsed["amount"] - parsed["commission"])
        except ValueError as exc:
            print(f"❌ 第 {i} 行: 派生数值不是有限数字: {exc}")
            return False

    # 2. Sort by date
    rows.sort(key=lambda r: r["date"])

    # 3. Group by date and write per-day files
    from collections import defaultdict
    by_date = defaultdict(list)
    for row in rows:
        day = row["date"][:10]
        by_date[day].append(row)

    records_dir = models.RECORDS_DIR
    security_dir = records_dir / "security"
    security_dir.mkdir(parents=True, exist_ok=True)

    total_written = 0
    original_files: dict[Path, bytes | None] = {}
    snapshot_path = None
    snapshot_backup = None
    try:
        from . import snapshot as snapshot_mod
        snapshot_path = snapshot_mod.SNAPSHOT_PATH
        snapshot_backup = snapshot_path.read_bytes() if snapshot_path.exists() else None
    except Exception:
        snapshot_path = None
        snapshot_backup = None

    def _restore_touched_files():
        for path, content in original_files.items():
            if content is None:
                path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)

    try:
        for day, day_rows in sorted(by_date.items()):
            day_path = security_dir / f"{day}.csv"
            if day_path not in original_files:
                original_files[day_path] = day_path.read_bytes() if day_path.exists() else None

            # Read existing rows
            existing_rows = []
            if day_path.exists():
                with day_path.open(encoding="utf-8") as f:
                    existing_rows = list(csv.DictReader(f))

            # Merge, sort, write
            all_rows = existing_rows + day_rows
            all_rows.sort(key=lambda r: r.get("date", ""))

            _write_security_csv(day_path, all_rows)
            total_written += len(day_rows)
    except Exception:
        _restore_touched_files()
        raise

    # 4. Rebuild snapshot
    try:
        repair_security()
    except Exception as exc:
        _restore_touched_files()
        if snapshot_path is not None:
            if snapshot_backup is None:
                snapshot_path.unlink(missing_ok=True)
            else:
                snapshot_path.parent.mkdir(parents=True, exist_ok=True)
                snapshot_path.write_bytes(snapshot_backup)
        if isinstance(exc, ValueError):
            print(f"❌ security 重放失败: {exc}")
            return False
        raise

    # 5. Git stage
    try:
        from .snapshot import git_stage
        git_stage()
    except Exception:
        pass

    # 6. Print statistics
    action_counts = Counter(r["action"] for r in rows)
    print(f"✅ 已导入 {total_written} 条记录到 security 记录")
    for act in sorted(action_counts):
        print(f"   {act}: {action_counts[act]}")
    return True


# ── Position helpers ────────────────────────────────────────────────────


def _position_cost(pos: dict) -> float:
    """Total cost basis for a position."""
    return pos["shares"] * pos["avg_cost"]


# ── Stock operations ────────────────────────────────────────────────────


def _fmt_shares(shares: float) -> str:
    """Format share counts without dropping fractional Polymarket holdings."""
    if float(shares).is_integer():
        return f"{shares:.0f}"
    return f"{shares:.4f}".rstrip("0").rstrip(".")


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
    _ensure_finite_values(shares=shares, price=price, commission=commission)

    date_key = date[:10]
    amount = -shares * price
    _ensure_finite_values(amount=amount, cash_delta=amount - commission, total_cost=shares * price)

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    pos = acct["positions"].setdefault(ticker, {"shares": 0, "avg_cost": 0.0})

    total_cost = _position_cost(pos) + shares * price
    pos["shares"] += shares
    pos["shares"] = round(pos["shares"], 10)
    pos["avg_cost"] = round(total_cost / pos["shares"], 2) if pos["shares"] != 0 else 0.0
    if pos["shares"] == 0:
        del acct["positions"][ticker]
    acct["cash"] += amount - commission
    acct["cash"] = round(acct["cash"], 10)  # avoid floating point noise
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="BUY", ticker=ticker,
        shares=shares, price=price, amount=amount,
        commission=commission, currency=currency,
        account_name=account_name, note=note,
    )
    print(f"✅ 买入 {_fmt_shares(shares)} 股 {ticker} @ ${price} ({account_name})")
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

    Supports regular sell (sell from existing position) and
    short sell (sell when no position, creating negative shares).
    avg_cost for shorts = sale price (positive).
    """
    if date is None:
        date = _now()
    _ensure_finite_values(shares=shares, price=price, commission=commission)

    date_key = date[:10]
    amount = shares * price
    _ensure_finite_values(amount=amount, cash_delta=amount - commission)

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    pos = acct["positions"].get(ticker)

    if pos is None:
        # Short sell — create negative position
        acct["positions"][ticker] = {"shares": 0, "avg_cost": 0.0}
        pos = acct["positions"][ticker]

    old_shares = pos["shares"]

    if old_shares >= 0:
        # Regular sell or short sell from flat
        old_cost = round(pos["avg_cost"] * old_shares, 2)  # 0 if old_shares == 0
        pos["shares"] -= shares
        pos["shares"] = round(pos["shares"], 10)
        pos_new_shares = pos["shares"]

        if pos_new_shares >= 0:
            # Still long or flat
            new_cost = round(old_cost - amount + commission, 2)
            if pos_new_shares > 0:
                pos["avg_cost"] = round(new_cost / pos_new_shares, 2)
            else:
                # Flat, clear position
                if pos_new_shares == 0:
                    del acct["positions"][ticker]
        else:
            # Flipped into short: excess shares are shorted at current price
            excess = -pos_new_shares
            # The old long position's P&L is already realized
            # The short portion starts fresh
            pos["avg_cost"] = price  # short avg cost = short entry price

        acct["cash"] += amount - commission
        acct["cash"] = round(acct["cash"], 10)
    else:
        # Already short — short more
        old_total = pos["avg_cost"] * old_shares  # negative
        pos["shares"] -= shares  # more negative
        pos["shares"] = round(pos["shares"], 10)
        new_total = round(old_total - amount + commission, 2)
        pos["avg_cost"] = round(new_total / pos["shares"], 2) if pos["shares"] < 0 else 0.0
        acct["cash"] += amount - commission
        acct["cash"] = round(acct["cash"], 10)

    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
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
    _ensure_finite_values(amount=amount)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] += amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
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
    _ensure_finite_values(amount=amount)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] -= amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
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
    _ensure_finite_values(amount=amount)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] += amount
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
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
    _ensure_finite_values(shares=shares, avg_cost=avg_cost, position_value=shares * avg_cost)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, currency)
    acct["positions"][ticker] = {
        "shares": shares,
        "avg_cost": avg_cost,
    }
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
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
    _ensure_finite_values(cash=cash)

    date_key = date[:10]
    snap = load_snapshot()
    # Need currency for the account — use the existing one or default
    acct = snap.setdefault("accounts", {}).get(account_name, {})
    currency = acct.get("currency", "USD")
    acct = _ensure_account(snap, account_name, currency)
    acct["cash"] = cash
    acct["cash"] = round(acct["cash"], 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="CHECKIN", ticker="",
        shares=0, price=0, amount=cash,
        commission=0, currency=currency,
        account_name=account_name, note=note,
    )


# ── Portfolio listing ───────────────────────────────────────────────────


def _normalize_ticker(t: str) -> str:
    """Normalize ticker to yfinance / Polymarket lookup format.

    ft stores tickers like 'avgo.us', '00700.hk', '159330.sz'.
    yfinance needs uppercase, and HK stocks need '0700.HK' format.
    Polymarket tickers keep the pm: prefix and normalize to lowercase.
    """
    t = t.strip()
    if t.lower().startswith("pm:"):
        return t.lower()
    t = t.upper()
    # ft stores hk stocks as 00700.hk → yfinance needs 0700.HK
    if t.endswith(".HK"):
        # 00700.HK → 0700.HK  (yfinance expects 4 digits for HK)
        code = t.replace(".HK", "")
        if len(code) <= 5 and code.isdigit():
            return f"{int(code):04d}.HK"
        return t
    # .US suffix → strip it (yfinance doesn't use .US)
    if t.endswith(".US"):
        return t.replace(".US", "")
    # .SZ / .SS already correct for China A-shares
    return t


def _extract_last_close(data, ticker: str):
    """Extract the most recent close from a yfinance download result.

    yfinance returns different shapes depending on the number of tickers:
    - one ticker: Close is usually a Series
    - multiple tickers: Close is usually a DataFrame
    - some responses use MultiIndex columns requiring xs(...)
    """
    if data is None or getattr(data, "empty", False):
        return None

    try:
        close = data["Close"]
    except Exception:
        try:
            close = data.xs("Close", axis=1, level=0)
        except Exception:
            return None

    # Single ticker download usually yields a Series here.
    if hasattr(close, "iloc") and not hasattr(close, "columns"):
        if getattr(close, "empty", False):
            return None
        val = close.iloc[-1]
        return None if val is None or (hasattr(val, "isna") and val.isna()) else float(val)

    # Multi-ticker download yields a DataFrame.
    if hasattr(close, "columns"):
        if ticker in close.columns:
            series = close[ticker]
        elif len(close.columns) == 1:
            series = close.iloc[:, 0]
        else:
            return None
        if getattr(series, "empty", False):
            return None
        val = series.iloc[-1]
        return None if val is None or (hasattr(val, "isna") and val.isna()) else float(val)

    try:
        return float(close)
    except (TypeError, ValueError):
        return None


def _parse_polymarket_ticker(t: str):
    """Parse a Polymarket pseudo-ticker.

    Format: pm:<slug>:yes|no
    Returns (slug, side) or None.
    """
    t = t.strip().lower()
    if not t.startswith("pm:"):
        return None
    parts = t.split(":")
    if len(parts) < 3:
        return None
    side = parts[-1]
    if side not in ("yes", "no"):
        return None
    slug = ":".join(parts[1:-1]).strip()
    if not slug:
        return None
    return slug, side


def _fetch_polymarket_prices(tickers: list[str]) -> dict[str, float]:
    """Fetch current token prices from Polymarket gamma API."""
    if not tickers:
        return {}

    from collections import defaultdict
    from urllib.parse import quote
    import json
    import urllib.request

    grouped = defaultdict(list)
    for t in tickers:
        parsed = _parse_polymarket_ticker(t)
        if not parsed:
            continue
        slug, side = parsed
        grouped[slug].append((t, side))

    if not grouped:
        return {}

    prices = {}
    headers = {
        "User-Agent": "Mozilla/5.0",
        "Accept": "application/json",
    }
    for slug, items in grouped.items():
        url = f"https://gamma-api.polymarket.com/markets?slug={quote(slug)}"
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=15) as resp:
                markets = json.load(resp)
        except Exception:
            continue

        if isinstance(markets, dict):
            markets = markets.get("data") or markets.get("markets") or [markets]
        if not isinstance(markets, list):
            continue

        market = next((m for m in markets if m.get("slug") == slug), None)
        if not market:
            continue

        def _coerce_json_list(value):
            if isinstance(value, list):
                return value
            if isinstance(value, str):
                text = value.strip()
                if text:
                    try:
                        parsed = json.loads(text)
                    except Exception:
                        return [value]
                    if isinstance(parsed, list):
                        return parsed
                    return [parsed]
            return []

        outcomes = [str(x).strip().lower() for x in _coerce_json_list(market.get("outcomes"))]
        outcome_prices = _coerce_json_list(market.get("outcomePrices"))
        last_trade = market.get("lastTradePrice")
        best_bid = market.get("bestBid")
        best_ask = market.get("bestAsk")
        fallback = None
        for candidate in (last_trade, best_bid, best_ask):
            try:
                if candidate is not None:
                    fallback = float(candidate)
                    break
            except (TypeError, ValueError):
                continue
        if fallback is None and len(outcome_prices) == 2:
            try:
                fallback = (float(outcome_prices[0]) + float(outcome_prices[1])) / 2
            except (TypeError, ValueError):
                fallback = None

        for ticker, side in items:
            idx = None
            if side in outcomes:
                idx = outcomes.index(side)
            elif side == "yes" and len(outcome_prices) >= 1:
                idx = 0
                if "yes" in outcomes:
                    idx = outcomes.index("yes")
            elif side == "no" and len(outcome_prices) >= 2:
                idx = 1
                if "no" in outcomes:
                    idx = outcomes.index("no")

            if idx is not None and idx < len(outcome_prices):
                try:
                    prices[ticker] = float(outcome_prices[idx])
                    continue
                except (TypeError, ValueError):
                    pass
            if fallback is not None:
                prices[ticker] = fallback

    return prices


def _http_get_json(url: str, timeout: int = 15) -> dict:
    """GET JSON with browser-style UA and HTTP(S)_PROXY support. Raises on failure."""
    import json
    import os
    import urllib.request

    headers = {"User-Agent": "Mozilla/5.0", "Accept": "application/json"}
    proxy = os.environ.get("HTTP_PROXY") or os.environ.get("HTTPS_PROXY")
    if proxy:
        opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({"http": proxy, "https": proxy})
        )
    else:
        opener = urllib.request.build_opener()
    req = urllib.request.Request(url, headers=headers)
    with opener.open(req, timeout=timeout) as resp:
        return json.load(resp)


def _fetch_crypto_prices(tickers: list[str]) -> dict[str, float]:
    """Fetch USD prices for crypto tickers via CoinGecko simple/price.

    Input tickers are ft's stored symbols (e.g. ['btc','eth']).
    Returns {original_ticker: usd_price}; {} on failure.
    """
    if not tickers:
        return {}
    from urllib.parse import quote

    id_to_ticker = {}
    for t in tickers:
        cid = models.CRYPTO_IDS.get(str(t).strip().lower())
        if cid:
            id_to_ticker[cid] = t
    if not id_to_ticker:
        return {}

    ids = ",".join(sorted(id_to_ticker))
    url = (
        "https://api.coingecko.com/api/v3/simple/price"
        f"?ids={quote(ids)}&vs_currencies=usd"
    )
    try:
        data = _http_get_json(url)
    except Exception:
        return {}

    prices = {}
    if not isinstance(data, dict):
        return {}
    for cid, ticker in id_to_ticker.items():
        entry = data.get(cid)
        if isinstance(entry, dict) and "usd" in entry:
            try:
                prices[ticker] = float(entry["usd"])
            except (TypeError, ValueError):
                continue
    return prices


def _fetch_prices(tickers: list[str]) -> dict[str, float]:
    """Fetch current prices from yfinance and Polymarket.

    - Normalizes ticker formats (avgo.us→AVGO, 00700.hk→0700.HK)
    - Supports Polymarket pseudo-tickers (pm:<slug>:yes|no)
    - Respects HTTP_PROXY / HTTPS_PROXY env vars for users behind a proxy
      (e.g. in China where Yahoo Finance is blocked).

    Returns {} on failure (yfinance not installed or network error).
    """
    if not tickers:
        return {}

    # Build mapping: normalized → original
    ticker_map = {}
    normalized = []
    for t in tickers:
        nt = _normalize_ticker(t)
        ticker_map[nt] = t
        normalized.append(nt)

    pm_tickers = [nt for nt in normalized if nt.startswith("pm:")]
    regular_tickers = [nt for nt in normalized if not nt.startswith("pm:")]

    import os
    proxy = os.environ.get("HTTP_PROXY") or os.environ.get("HTTPS_PROXY")
    if proxy:
        os.environ.setdefault("HTTP_PROXY", proxy)
        os.environ.setdefault("HTTPS_PROXY", proxy)

    prices = _fetch_polymarket_prices(pm_tickers)

    if not regular_tickers:
        return prices

    try:
        import yfinance as yf  # type: ignore[import-untyped]
    except ImportError:
        return prices

    try:
        # Split by market because yfinance can return NaN when US, A-shares,
        # and HK tickers are mixed in the same download call.
        us_tickers = [nt for nt in regular_tickers if nt.endswith(".US")]
        sz_tickers = [nt for nt in regular_tickers if nt.endswith(".SZ")]
        ss_tickers = [nt for nt in regular_tickers if nt.endswith(".SS")]
        hk_tickers = [nt for nt in regular_tickers if nt.endswith(".HK")]
        other_tickers = [
            nt for nt in regular_tickers
            if not nt.endswith((".US", ".SZ", ".SS", ".HK"))
        ]

        groups = [
            us_tickers,
            sz_tickers,
            ss_tickers,
            other_tickers,
        ] + [[t] for t in hk_tickers]

        import math
        import time

        def _is_bad_price(val):
            return val is None or (
                isinstance(val, float) and math.isnan(val)
            )

        for i, group in enumerate(groups):
            if not group:
                continue
            if i > 0:
                time.sleep(2)
            try:
                data = yf.download(
                    group, period="1d", progress=False,
                    auto_adjust=False,
                )
                # Single-ticker results often come back as a Series under Close.
                # Multi-ticker results come back as a DataFrame.
                if len(group) == 1:
                    nt = group[0]
                    val = _extract_last_close(data, nt)
                    if val is not None:
                        prices[ticker_map[nt]] = val
                    continue
                for nt in group:
                    try:
                        val = _extract_last_close(data, nt)
                        if val is not None:
                            prices[ticker_map[nt]] = val
                    except (KeyError, IndexError, TypeError, ValueError):
                        continue
            except Exception:
                pass

            # Fallback: retry any missing / NaN tickers one by one.
            for nt in group:
                original = ticker_map[nt]
                if not _is_bad_price(prices.get(original)):
                    continue
                try:
                    single = yf.download(nt, period="1d", progress=False, auto_adjust=False)
                    val = _extract_last_close(single, nt)
                    if val is not None and not (isinstance(val, float) and math.isnan(val)):
                        prices[original] = val
                except Exception:
                    continue
    except Exception:
        pass
    return prices


def _fmt(value: float, symbol: str) -> str:
    """Format a number with currency symbol."""
    if value >= 0:
        return f" {symbol}{value:>,.2f}"
    return f"{symbol}{value:>,.2f}"


def do_list():
    """Read snapshot, fetch prices, display portfolio."""
    snap = load_snapshot()
    accounts = snap.get("accounts", {})

    # Collect all security accounts from both old and new snapshot structure
    # Old: accounts.IBKR.positions  New: accounts.security.东方证券.positions
    all_accts = {}
    for name, data in accounts.items():
        if isinstance(data, dict) and "positions" in data:
            all_accts[name] = data
    for name, data in accounts.get("security", {}).items():
        if name not in all_accts:
            all_accts[name] = data

    if not all_accts:
        print("📭 无持仓")
        return

    # Collect all tickers for price fetching
    all_tickers = set()
    for acct_data in all_accts.values():
        all_tickers.update(acct_data.get("positions", {}).keys())
    prices = _fetch_prices(list(all_tickers))

    for acct_name, acct_data in all_accts.items():
        currency = acct_data.get("currency", "CNY") or "CNY"
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
            if shares == 0:
                continue
            avg_cost = pos["avg_cost"]
            cost = shares * avg_cost
            current_price = prices.get(ticker)
            if current_price is not None and shares > 0:
                value = shares * current_price
                pl = value - cost
                pct = (current_price - avg_cost) / avg_cost * 100 if avg_cost > 0 else 0.0
                pl_str = f"+{symbol}{pl:>,.2f}" if pl >= 0 else f"{symbol}{pl:>,.2f}"
                pct_str = f"+{pct:.1f}%" if pct >= 0 else f"{pct:.1f}%"
            else:
                value = 0.0
                pl = 0.0
                pl_str = "   N/A"
                pct_str = "  N/A"

            print(
                f"  {ticker:<16} {_fmt_shares(shares):>8} {symbol}{avg_cost:>10,.2f} "
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
    """Replay security CSV into positions and cash.

    Uses average cost method; supports short positions (negative shares).
    """
    if records_dir is None:
        records_dir = models.RECORDS_DIR
    records_dir = Path(str(records_dir))
    security_dir = records_dir / "security"

    if not security_dir.exists():
        from collections import defaultdict
        return defaultdict(lambda: {"shares": 0.0, "total_cost": 0.0}), defaultdict(float)

    rows = []
    for csv_file in sorted(security_dir.glob("*.csv")):
        with open(csv_file, encoding="utf-8") as f:
            rows.extend(csv.DictReader(f))
    return _replay_security_rows(rows)


def _replay_security_rows(rows):
    """Replay in-memory security rows, raising ValueError on non-finite state."""
    from collections import defaultdict

    positions = defaultdict(lambda: {"shares": 0.0, "total_cost": 0.0})
    cash = defaultdict(float)

    def _normalize_position(h):
        """Snap tiny floating-point residue to zero so closed positions disappear."""
        if abs(h["shares"]) < 1e-9:
            h["shares"] = 0.0
            h["total_cost"] = 0.0
        elif abs(h["total_cost"]) < 1e-9:
            h["total_cost"] = 0.0

    def _validate_position(account: str, ticker: str) -> None:
        h = positions[(account, ticker)]
        _ensure_finite_values(
            **{
                f"{account}.{ticker}.shares": h["shares"],
                f"{account}.{ticker}.total_cost": h["total_cost"],
            }
        )

    def _validate_cash(account: str) -> None:
        _ensure_finite_values(**{f"{account}.cash": cash[account]})

    for row in rows:
        # Security records are mixed with some transfer-style audit rows
        # that don't carry stock-trade fields. Skip anything that isn't a
        # real security action row.
        if row.get("action") not in VALID_ACTIONS or not row.get("account_name"):
            continue
        a = row["account_name"]
        act = row["action"]
        t = row.get("ticker", "") or ""
        try:
            s = float(row["shares"] or 0)
            p = float(row["price"] or 0)
            amt = float(row["amount"] or 0)
            com = float(row["commission"] or 0)
        except (ValueError, KeyError):
            continue
        _ensure_finite_values(shares=s, price=p, amount=amt, commission=com)

        if act == "CHECKIN":
            if t:
                positions[(a, t)]["shares"] = s
                positions[(a, t)]["total_cost"] = round(s * p, 2)
                _normalize_position(positions[(a, t)])
                _validate_position(a, t)
            else:
                cash[a] = amt
                _validate_cash(a)
        elif act == "BUY":
            h = positions[(a, t)]
            old_s = h["shares"]
            old_c = h["total_cost"]
            new_s = old_s + s
            _ensure_finite_values(new_shares=new_s)
            if old_s >= 0:
                if old_s > 0:
                    h["total_cost"] = round(old_c + s * p, 2)
                else:
                    h["total_cost"] = round(s * p, 2)
            else:
                # Covering short — keep cumulative cost
                h["total_cost"] = round(old_c + s * p, 2)
            h["shares"] = new_s
            _normalize_position(h)
            _validate_position(a, t)
            cash[a] = round(cash[a] + amt - com, 2)
            _validate_cash(a)
        elif act == "SELL":
            h = positions[(a, t)]
            sold = abs(s)
            if h["shares"] > 0:
                # Regular sell
                h["shares"] -= sold
                h["total_cost"] = round(h["total_cost"] - abs(amt) + com, 2)
            else:
                # Short sell (shares == 0) or additional short (shares < 0)
                h["shares"] -= sold
                h["total_cost"] = round(h["total_cost"] - abs(amt) + com, 2)
            _normalize_position(h)
            _validate_position(a, t)
            cash[a] = round(cash[a] + amt - com, 2)
            _validate_cash(a)
        elif act in ("DEPOSIT", "DIVIDEND"):
            cash[a] = round(cash[a] + amt, 2)
            _validate_cash(a)
        elif act == "WITHDRAW":
            cash[a] = round(cash[a] + amt, 2)
            _validate_cash(a)

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
            if p["shares"] != 0:
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
    from .accounts import load_accounts
    positions, cash = _replay_security_csv(records_dir)

    # Look up currency from accounts.yaml
    acct_currencies = {a["name"]: a["currency"] for a in load_accounts()
                       if a["type"] == "security"}

    accounts = {}
    for (acct_name, ticker), p in positions.items():
        if ticker:
            if p["shares"] == 0:
                continue
            if acct_name not in accounts:
                accounts[acct_name] = {
                    "currency": acct_currencies.get(acct_name, ""),
                    "cash": 0.0, "positions": {},
                }
            accounts[acct_name]["positions"][ticker] = {
                "shares": p["shares"],
                "avg_cost": round(p["total_cost"] / p["shares"], 2) if p["shares"] != 0 else 0.0,
            }

    for acct_name, c in cash.items():
        if acct_name not in accounts:
            accounts[acct_name] = {
                "currency": acct_currencies.get(acct_name, ""),
                "cash": 0.0, "positions": {},
            }
        accounts[acct_name]["cash"] = round(c, 2)

    snap = load_snapshot()
    snap.setdefault("accounts", {})["security"] = accounts

    # 清理顶层旧结构中的重复 security 账户
    top = snap["accounts"]
    for acct_name in list(accounts.keys()):
        if acct_name in top and acct_name != "security":
            del top[acct_name]

    snap["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_snapshot(snap)
    print(f"✅ 已从 CSV 重建快照: {len(accounts)} 个账户, {sum(len(a.get('positions',{})) for a in accounts.values())} 个标的")

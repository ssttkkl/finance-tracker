"""Stock trading — snapshot management + CSV recording + all stock operations"""
import csv
import math
import os
import subprocess
import sys
import tempfile
from collections import Counter
from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Optional

import yaml

from .snapshot import git_stage, load_snapshot, save_snapshot
from .schema import CRYPTO_IDS, CSV_FIELDS, CURRENCY_SYMBOLS, VALID_ACTIONS

# ── CSV fields for security trades ──────────────────────────────────────


def _models():
    from . import models
    return models


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


def _canonical_ticker(ticker: str | None) -> str:
    """Canonical storage key for ledger positions."""
    return (ticker or "").strip().lower()


def _normalize_currency_code(currency: str | None, *, field: str = "currency") -> str:
    value = (currency or "").strip().upper()
    if not value:
        raise ValueError(f"{field} is required")
    return value


def _configured_base_currencies(account: dict) -> list[str]:
    """Return account-configured allowed cash/settlement currencies.

    `base_currencies` is the authoritative multi-currency list. Older account
    files did not have it, so their single `currency` remains the only allowed
    manual stock currency.
    """
    raw = account.get("base_currencies")
    if raw is None:
        return [_normalize_currency_code(account.get("currency"), field="account currency")]
    if isinstance(raw, str):
        items = [raw]
    else:
        items = list(raw or [])
    currencies = []
    for item in items:
        code = _normalize_currency_code(str(item), field="base_currencies")
        if code not in currencies:
            currencies.append(code)
    if not currencies:
        raise ValueError(f"account {account.get('name')!r} has empty base_currencies")
    return currencies


def _has_configured_base_currencies(account: dict) -> bool:
    return "base_currencies" in account and account.get("base_currencies") is not None


def resolve_security_account_currency(
    account_name: str,
    currency: str | None = None,
    accounts_path=None,
) -> tuple[dict, str]:
    """Resolve and validate a manual stock account plus settlement currency.

    Configured security/crypto accounts have no default reporting currency, so
    direct writes must pass an explicit currency. Legacy accounts without
    base_currencies are the only fallback and allow their legacy currency.
    """
    from .accounts import load_accounts

    candidates = [a for a in load_accounts(accounts_path) if a.get("name") == account_name]
    if not candidates:
        raise ValueError(f"unknown account: {account_name}")

    stock_candidates = [a for a in candidates if a.get("type") in ("security", "crypto")]
    if not stock_candidates:
        raise ValueError(f"account {account_name!r} is not security/crypto")

    active = [a for a in stock_candidates if a.get("active", True)]
    stock_candidates = active or stock_candidates

    if currency is not None:
        requested = _normalize_currency_code(currency)
        for account in stock_candidates:
            if requested in _configured_base_currencies(account):
                return account, requested
        allowed = sorted({c for account in stock_candidates for c in _configured_base_currencies(account)})
        raise ValueError(
            f"currency {requested} is not configured for account {account_name}; "
            f"allowed: {', '.join(allowed)}"
        )

    if len(stock_candidates) > 1:
        raise ValueError(f"account {account_name!r} is ambiguous; pass --currency")

    account = stock_candidates[0]
    allowed = _configured_base_currencies(account)
    if _has_configured_base_currencies(account):
        raise ValueError(
            f"currency is required for account {account_name}; "
            f"allowed: {', '.join(allowed)}"
        )
    return account, allowed[0]


def _account_display_currency(account: dict) -> str:
    try:
        return _normalize_currency_code(account.get("currency"), field="account currency")
    except ValueError:
        return ""


def _base_currency_set(currencies) -> set[str]:
    if currencies is None:
        return set()
    if isinstance(currencies, str):
        items = [currencies]
    else:
        items = list(currencies)
    return {(str(item) or "").strip().upper() for item in items if (str(item) or "").strip()}


def _account_base_currency_set(account: dict) -> set[str]:
    return set(_configured_base_currencies(account))


def _load_security_account_base_currencies(accounts_path=None) -> dict[str, set[str]]:
    """Read account cash ticker config without creating a missing accounts.yaml."""
    path = Path(accounts_path) if accounts_path is not None else _models().ACCOUNTS_PATH
    if not path.exists():
        return {}
    try:
        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    except Exception:
        return {}

    base_by_account: dict[str, set[str]] = {}
    for account in data.get("accounts", []) or []:
        if not account.get("name"):
            continue
        base_by_account[account["name"]] = _account_base_currency_set(account)
    return base_by_account


def _validate_security_row_account_currency(account_name: str, currency: str, accounts_path=None) -> None:
    from .accounts import load_accounts

    accounts = load_accounts(accounts_path)
    if not accounts:
        return
    resolve_security_account_currency(account_name, currency, accounts_path=accounts_path)


def _normalize_trade_asset(asset: str | None, currency: str) -> str:
    asset = (asset or "").strip()
    if not asset:
        return ""
    upper = asset.upper()
    if upper == currency:
        return upper
    return asset.lower()


def _is_currency_ticker(ticker: str, base_currencies) -> bool:
    upper = _canonical_ticker(ticker).upper()
    return upper in _base_currency_set(base_currencies)


def _assert_cost_currency_compatible(
    positions: dict,
    account_name: str,
    ticker: str,
    currency: str | None,
    base_currencies,
) -> None:
    ticker = _canonical_ticker(ticker)
    currency = _normalize_currency_code(currency)
    if not ticker or _is_currency_ticker(ticker, base_currencies):
        return
    pos = positions.get(ticker)
    if not pos or abs(float(pos.get("shares", 0) or 0)) < 1e-9:
        return
    existing = (pos.get("cost_currency") or "").strip().upper()
    if existing and existing != currency:
        raise ValueError(
            f"{account_name}/{ticker} cost_currency mismatch: "
            f"existing {existing!r} vs input {currency!r}"
        )


def _assert_cash_dividend_currency_compatible(
    positions: dict,
    account_name: str,
    ticker: str,
    dividend_currency: str | None,
    base_currencies,
) -> None:
    ticker = _canonical_ticker(ticker)
    currency = _normalize_currency_code(dividend_currency)
    if not ticker or _is_currency_ticker(ticker, base_currencies):
        return
    pos = positions.get(ticker)
    if not pos or abs(float(pos.get("shares", 0) or 0)) < 1e-9:
        return
    existing = (pos.get("cost_currency") or "").strip().upper()
    if existing and existing != currency:
        raise ValueError(
            f"{account_name}/{ticker} dividend currency mismatch: "
            f"holding cost_currency {existing!r} vs dividend {currency!r}; "
            f"record the dividend in {existing} and add a separate swap if converted"
        )


def _position_bucket(cost_currency: str = "") -> dict:
    return {"shares": 0.0, "total_cost": 0.0, "cost_currency": cost_currency}


def _cost_currency_for_ticker(
    ticker: str,
    default_currency: str = "",
    base_currencies=None,
) -> str:
    ticker_upper = _canonical_ticker(ticker).upper()
    default_upper = (default_currency or "").strip().upper()
    currencies = _base_currency_set(base_currencies)
    if not currencies and default_upper:
        currencies = {default_upper}
    if ticker_upper in currencies or ticker_upper in CURRENCY_SYMBOLS:
        return ticker_upper
    return default_upper or ""


def _merge_cost_currency(existing: str, incoming: str, ticker: str) -> str:
    existing = existing or ""
    incoming = incoming or ""
    if existing and incoming and existing != incoming:
        raise ValueError(
            f"{ticker} cost_currency mismatch: {existing!r} vs {incoming!r}"
        )
    return existing or incoming


def _canonicalize_account_positions(acct: dict, cost_currency: str = "") -> None:
    """Merge positions whose keys differ only by case."""
    positions = acct.setdefault("positions", {})
    merged = {}
    for ticker, pos in list(positions.items()):
        canonical = _canonical_ticker(ticker)
        target = merged.setdefault(canonical, _position_bucket(pos.get("cost_currency") or cost_currency))
        target["cost_currency"] = _merge_cost_currency(
            target.get("cost_currency", ""),
            pos.get("cost_currency") or cost_currency,
            canonical,
        )
        target["shares"] = round(target.get("shares", 0.0) + float(pos.get("shares", 0) or 0), 10)
        target["total_cost"] = round(
            target.get("total_cost", 0.0) + float(pos.get("total_cost", 0) or 0),
            10,
        )
    positions.clear()
    positions.update(merged)


def _get_position(positions: dict, ticker: str, cost_currency: str = "") -> dict:
    ticker = _canonical_ticker(ticker)
    pos = positions.setdefault(ticker, _position_bucket(cost_currency))
    if cost_currency:
        pos["cost_currency"] = _merge_cost_currency(
            pos.get("cost_currency", ""), cost_currency, ticker
        )
    return pos


def _normalize_position(pos: dict) -> None:
    """Snap tiny floating-point residue to zero so closed positions disappear."""
    if abs(pos.get("shares", 0.0)) < 1e-9:
        pos["shares"] = 0.0
        pos["total_cost"] = 0.0
    elif abs(pos.get("total_cost", 0.0)) < 1e-9:
        pos["total_cost"] = 0.0


def _validate_position_values(account: str, ticker: str, pos: dict) -> None:
    _ensure_finite_values(
        **{
            f"{account}.{ticker}.shares": pos.get("shares", 0),
            f"{account}.{ticker}.total_cost": pos.get("total_cost", 0),
        }
    )


def _ensure_position_available(
    account: str,
    ticker: str,
    pos: dict | None,
    amount: float,
) -> None:
    have = pos.get("shares", 0) if pos else 0
    if pos is None or round(have - amount, 10) < 0:
        raise ValueError(f"{account} 的 {ticker} 持仓不足：有 {have}，需 {amount}")


def _apply_swap_to_positions(
    positions: dict,
    account_name: str,
    from_ticker: str,
    from_amount: float,
    to_ticker: str,
    to_amount: float,
    commission: float,
    commission_asset: str,
    cost_currency: str,
    base_currencies,
    enforce_available: bool = False,
) -> None:
    from_ticker = _canonical_ticker(from_ticker)
    to_ticker = _canonical_ticker(to_ticker)
    commission_asset = _canonical_ticker(commission_asset)
    _ensure_finite_values(
        from_amount=from_amount,
        to_amount=to_amount,
        commission=commission,
    )
    if from_amount < 0 or to_amount < 0 or commission < 0:
        raise ValueError("swap amounts and commission must be non-negative")
    if not from_ticker or not to_ticker:
        raise ValueError("swap requires from_ticker and to_ticker")

    from_pos = positions.get(from_ticker)
    required_from = from_amount + (commission if commission_asset == from_ticker else 0.0)
    if enforce_available:
        _ensure_position_available(account_name, from_ticker, from_pos, required_from)

    from_pos = _get_position(
        positions,
        from_ticker,
        _cost_currency_for_ticker(from_ticker, cost_currency, base_currencies),
    )
    old_shares = from_pos["shares"]
    old_cost = from_pos["total_cost"]
    released_cost = old_cost * from_amount / old_shares if old_shares > 0 else from_amount

    from_pos["shares"] = round(from_pos["shares"] - from_amount, 10)
    from_pos["total_cost"] = round(from_pos["total_cost"] - released_cost, 10)
    _normalize_position(from_pos)
    _validate_position_values(account_name, from_ticker, from_pos)

    to_pos = _get_position(
        positions,
        to_ticker,
        _cost_currency_for_ticker(to_ticker, cost_currency, base_currencies),
    )
    to_pos["shares"] = round(to_pos["shares"] + to_amount, 10)
    incoming_cost = (
        to_amount
        if _cost_currency_for_ticker(to_ticker, "", base_currencies) else released_cost
    )
    to_pos["total_cost"] = round(to_pos["total_cost"] + incoming_cost, 10)
    _normalize_position(to_pos)
    _validate_position_values(account_name, to_ticker, to_pos)

    if commission > 0 and commission_asset:
        fee_pos = positions.get(commission_asset)
        if enforce_available:
            _ensure_position_available(account_name, commission_asset, fee_pos, commission)
        fee_pos = _get_position(
            positions,
            commission_asset,
            _cost_currency_for_ticker(commission_asset, cost_currency, base_currencies),
        )
        fee_old_shares = fee_pos["shares"]
        fee_old_cost = fee_pos["total_cost"]
        fee_released_cost = (
            fee_old_cost * commission / fee_old_shares
            if fee_old_shares > 0 else commission
        )
        fee_pos["shares"] = round(fee_old_shares - commission, 10)
        fee_pos["total_cost"] = round(fee_old_cost - fee_released_cost, 10)
        _normalize_position(fee_pos)
        _validate_position_values(account_name, commission_asset, fee_pos)

        if commission_asset == from_ticker:
            to_pos["total_cost"] = round(to_pos["total_cost"] + fee_released_cost, 10)
            _normalize_position(to_pos)
            _validate_position_values(account_name, to_ticker, to_pos)


def _decimal_value(value, default: str = "0") -> Decimal:
    if value is None or value == "":
        value = default
    try:
        return Decimal(str(value))
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid numeric value: {value!r}") from exc


def _numeric_equal(left, right, tolerance: Decimal = Decimal("1e-9")) -> bool:
    return abs(_decimal_value(left) - _decimal_value(right)) <= tolerance


def _cost_currency_equal(left, right) -> bool:
    """Compare explicit cost-currency metadata; a missing side is a mismatch."""
    return (left or "") == (right or "")


def _snapshot_file_backup():
    """Return the live snapshot path and its current bytes for rollback."""
    from . import snapshot as snapshot_mod
    path = snapshot_mod._resolve_snapshot_path()
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
    day_path = _models().RECORDS_DIR / "security" / f"{date_key}.csv"
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
        for ticker, pos in account.get("positions", {}).items():
            _ensure_finite_values(
                **{
                    f"{account_name}.{ticker}.shares": pos.get("shares", 0),
                    f"{account_name}.{ticker}.total_cost": pos.get("total_cost", 0),
                }
            )


# ── CSV trade recording ─────────────────────────────────────────────────


def _ensure_account(snap: dict, account_name: str, currency: str) -> dict:
    """Get-or-create an account dict inside snap.accounts.security."""
    currency = _normalize_currency_code(currency)
    top = snap.setdefault("accounts", {})
    sec = top.setdefault("security", {})
    if account_name not in sec:
        sec[account_name] = {
            "currency": currency,
            "positions": {},
        }
    else:
        sec[account_name]["currency"] = sec[account_name].get("currency") or currency
    return sec[account_name]


def record_trade(
    date: str,
    action: str,
    from_ticker: str,
    to_ticker: str,
    from_amount: float,
    to_amount: float,
    price: float,
    commission: float,
    commission_asset: str,
    currency: str | None,
    account_name: str,
    note: str = "",
) -> dict:
    """Write a trade row to records/security/{date[:10]}.csv.

    Returns the row dict that was written.
    """
    _ensure_finite_values(from_amount=from_amount, to_amount=to_amount,
                          price=price, commission=commission)
    currency = _normalize_currency_code(currency)
    commission_asset = _normalize_trade_asset(commission_asset, currency)
    # Normalize tickers to lowercase for consistent position keys
    from_ticker = from_ticker.strip().lower() if from_ticker else ""
    to_ticker = to_ticker.strip().lower() if to_ticker else ""
    records_dir = _models().RECORDS_DIR
    date_key = date[:10]
    security_dir = records_dir / "security"
    security_dir.mkdir(parents=True, exist_ok=True)
    day_path = security_dir / f"{date_key}.csv"

    existing_rows = []
    if day_path.exists():
        with day_path.open(encoding="utf-8") as f:
            reader = csv.DictReader(f)
            existing_rows = list(reader)

    new_row = {
        "date": date,
        "action": action,
        "from_ticker": from_ticker,
        "to_ticker": to_ticker,
        "from_amount": str(from_amount),
        "to_amount": str(to_amount),
        "price": str(price),
        "commission": str(commission),
        "commission_asset": commission_asset,
        "currency": currency,
        "account_name": account_name,
        "note": note,
    }

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

    # 4. Map parser output to stock CSV 12-column unified swap format
    mapped = []
    for rec in records:
        action = rec["action"]
        if action == "BUY":
            mapped.append({
                "date": rec["date"], "action": "swap",
                "from_ticker": currency, "to_ticker": rec["ticker"],
                "from_amount": str(abs(rec["amount"])),
                "to_amount": str(rec["shares"]),
                "price": str(rec["price"]),
                "commission": str(rec["fee"]),
                "commission_asset": currency,
                "currency": currency, "account_name": account,
                "note": rec["note"],
            })
        elif action == "SELL":
            mapped.append({
                "date": rec["date"], "action": "swap",
                "from_ticker": rec["ticker"], "to_ticker": currency,
                "from_amount": str(rec["shares"]),
                "to_amount": str(abs(rec["amount"])),
                "price": str(rec["price"]),
                "commission": str(rec["fee"]),
                "commission_asset": currency,
                "currency": currency, "account_name": account,
                "note": rec["note"],
            })
        elif action == "DIVIDEND":
            # 现金红利：ticker 为空，amount 是现金金额
            # 送股/转增：ticker 非空，shares 是送股数量
            if rec.get("ticker"):
                # 送股/转增：to_ticker = 股票代码，to_amount = 送股数
                mapped.append({
                    "date": rec["date"], "action": "dividend",
                    "from_ticker": rec["ticker"], "to_ticker": rec["ticker"],
                    "from_amount": "0", "to_amount": str(rec["shares"]),
                    "price": "0", "commission": "0",
                    "commission_asset": "",
                    "currency": currency, "account_name": account,
                    "note": rec["note"],
                })
            else:
                # 现金红利：to_ticker = 货币，to_amount = 金额
                mapped.append({
                    "date": rec["date"], "action": "dividend",
                    "from_ticker": rec.get("ticker", ""), "to_ticker": currency,
                    "from_amount": "0", "to_amount": str(abs(rec["amount"])),
                    "price": "1", "commission": "0",
                    "commission_asset": "",
                    "currency": currency, "account_name": account,
                    "note": rec["note"],
                })
        elif action == "DEPOSIT":
            mapped.append({
                "date": rec["date"], "action": "deposit",
                "from_ticker": "", "to_ticker": currency,
                "from_amount": "0", "to_amount": str(abs(rec["amount"])),
                "price": "1", "commission": "0",
                "commission_asset": "",
                "currency": currency, "account_name": account,
                "note": rec["note"],
            })
        elif action == "WITHDRAW":
            mapped.append({
                "date": rec["date"], "action": "withdraw",
                "from_ticker": currency, "to_ticker": "",
                "from_amount": str(abs(rec["amount"])), "to_amount": "0",
                "price": "1", "commission": "0",
                "commission_asset": "",
                "currency": currency, "account_name": account,
                "note": rec["note"],
            })
        elif action == "CHECKIN":
            mapped.append({
                "date": rec["date"], "action": "checkin",
                "from_ticker": currency, "to_ticker": "",
                "from_amount": "0", "to_amount": str(abs(rec["amount"])),
                "price": "1", "commission": "0",
                "commission_asset": "",
                "currency": currency, "account_name": account,
                "note": rec["note"],
            })
        else:
            # Pass through as-is for unknown actions (will be caught by validation)
            mapped.append({
                "date": rec["date"], "action": action,
                "from_ticker": rec.get("ticker", ""),
                "to_ticker": rec.get("ticker", ""),
                "from_amount": str(rec.get("shares", 0)),
                "to_amount": str(rec.get("amount", 0)),
                "price": str(rec.get("price", 0)),
                "commission": str(rec.get("fee", 0)),
                "commission_asset": "",
                "currency": currency, "account_name": account,
                "note": rec.get("note", ""),
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

    # Validate account names, currencies, and types
    for i, row in enumerate(rows, 1):
        try:
            resolve_security_account_currency(row["account_name"], row["currency"])
        except ValueError as exc:
            print(f"❌ 第 {i} 行: {exc}")
            return False

    # Validate numeric fields and replay-derived finite values
    num_fields = ["from_amount", "to_amount", "price", "commission"]
    for i, row in enumerate(rows, 1):
        parsed = {}
        for field in num_fields:
            try:
                value = float(row[field] or 0)
            except (ValueError, TypeError):
                print(f"❌ 第 {i} 行: 字段 '{field}' 值 '{row.get(field, '')}' 不是有效数字")
                return False
            if not math.isfinite(value):
                print(f"❌ 第 {i} 行: 字段 '{field}' 值 '{row.get(field, '')}' 不是有限数字")
                return False
            parsed[field] = value
        try:
            _ensure_finite_values(
                from_total=parsed["from_amount"] + parsed["commission"],
                to_value=parsed["to_amount"] * parsed["price"],
            )
        except ValueError as exc:
            print(f"❌ 第 {i} 行: 派生数值不是有限数字: {exc}")
            return False

    # 2. Sort by date
    rows.sort(key=lambda r: r["date"])

    # 3. Validate the merged replay state before touching records.
    security_dir = _models().RECORDS_DIR / "security"
    merged_rows_for_replay = []
    if security_dir.exists():
        for csv_file in sorted(security_dir.glob("*.csv")):
            with csv_file.open(encoding="utf-8") as f:
                merged_rows_for_replay.extend(csv.DictReader(f))
    try:
        _replay_security_rows(
            _order_security_rows_for_replay(merged_rows_for_replay + rows),
            validate_accounts=True,
        )
    except ValueError as exc:
        print(f"❌ security 重放失败: {exc}")
        return False

    # 4. Group by date and write per-day files
    from collections import defaultdict
    by_date = defaultdict(list)
    for row in rows:
        day = row["date"][:10]
        by_date[day].append(row)

    records_dir = _models().RECORDS_DIR
    security_dir = records_dir / "security"
    security_dir.mkdir(parents=True, exist_ok=True)

    total_written = 0
    original_files: dict[Path, bytes | None] = {}
    snapshot_path = None
    snapshot_backup = None
    try:
        from . import snapshot as snapshot_mod
        snapshot_path = snapshot_mod._resolve_snapshot_path()
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

    # 5. Rebuild snapshot
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

    # 6. Git stage
    try:
        from .snapshot import git_stage
        git_stage()
    except Exception:
        pass

    # 7. Print statistics
    action_counts = Counter(r["action"] for r in rows)
    print(f"✅ 已导入 {total_written} 条记录到 security 记录")
    for act in sorted(action_counts):
        print(f"   {act}: {action_counts[act]}")
    return True


# ── Position helpers ────────────────────────────────────────────────────


def _position_cost(pos: dict) -> float:
    """Total cost basis for a position."""
    return pos.get("total_cost", 0.0)


# ── Stock operations ────────────────────────────────────────────────────


def _fmt_shares(shares: float) -> str:
    """Format share counts without dropping fractional Polymarket holdings."""
    if float(shares).is_integer():
        return f"{shares:.0f}"
    return f"{shares:.4f}".rstrip("0").rstrip(".")


def _now() -> str:
    """Return current datetime string."""
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def _cleanup_position(acct: dict, ticker: str) -> None:
    """Remove position if shares are zero."""
    pos = acct["positions"].get(ticker)
    if pos is not None and abs(pos.get("shares", 0)) < 1e-9:
        del acct["positions"][ticker]


def do_buy(
    ticker: str,
    shares: float,
    price: float,
    commission: float,
    currency: str | None,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Buy shares — updates snapshot & records trade."""
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    base_currencies = _account_base_currency_set(account)
    _ensure_finite_values(shares=shares, price=price, commission=commission)
    ticker = _canonical_ticker(ticker)

    date_key = date[:10]
    cost = shares * price + commission
    _ensure_finite_values(cost=cost, total_cost=shares * price)

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    _assert_cost_currency_compatible(
        acct["positions"], account_name, ticker, currency, base_currencies
    )

    # Reduce currency position (cash outflow)
    ccy = _canonical_ticker(currency)
    ccy_pos = acct["positions"].setdefault(
        ccy, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    ccy_pos["shares"] = round(ccy_pos["shares"] - cost, 10)
    ccy_pos["total_cost"] = round(ccy_pos["total_cost"] - cost, 10)

    # Increase ticker position
    pos = acct["positions"].setdefault(
        ticker, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    pos["shares"] = round(pos["shares"] + shares, 10)
    pos["total_cost"] = round(pos["total_cost"] + cost, 10)

    # Clean up zero positions
    _cleanup_position(acct, ccy)
    _cleanup_position(acct, ticker)

    _ensure_finite_values(
        **{f"{account_name}.{ccy}.shares": ccy_pos["shares"],
           f"{account_name}.{ccy}.total_cost": ccy_pos["total_cost"],
           f"{account_name}.{ticker}.shares": pos["shares"],
           f"{account_name}.{ticker}.total_cost": pos["total_cost"]}
    )

    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="swap", from_ticker=currency, to_ticker=ticker,
        from_amount=shares * price, to_amount=shares, price=price, commission=commission,
        commission_asset=currency, currency=currency, account_name=account_name, note=note,
    )
    print(f"✅ 买入 {_fmt_shares(shares)} 股 {ticker} @ ${price} ({account_name})")
    return True


def do_sell(
    ticker: str,
    shares: float,
    price: float,
    commission: float,
    currency: str | None,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Sell shares — updates snapshot & records trade.

    Supports regular sell (sell from existing position) and
    short sell (sell when no position, creating negative shares).
    """
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    base_currencies = _account_base_currency_set(account)
    _ensure_finite_values(shares=shares, price=price, commission=commission)
    ticker = _canonical_ticker(ticker)

    date_key = date[:10]
    proceeds = shares * price - commission
    _ensure_finite_values(proceeds=proceeds)

    # Load & update snapshot
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    _assert_cost_currency_compatible(
        acct["positions"], account_name, ticker, currency, base_currencies
    )

    # Reduce ticker position (release cost proportionally)
    pos = acct["positions"].get(ticker)
    if pos is None:
        # Short sell — create negative position
        acct["positions"][ticker] = {
            "shares": 0, "total_cost": 0.0, "cost_currency": currency,
        }
        pos = acct["positions"][ticker]

    old_shares = pos["shares"]
    old_cost = pos["total_cost"]

    if old_shares > 0:
        released_cost = old_cost * shares / old_shares
    else:
        released_cost = shares * price  # short: cost basis = entry price

    pos["shares"] = round(pos["shares"] - shares, 10)
    pos["total_cost"] = round(pos["total_cost"] - released_cost, 10)

    # Increase currency position (cash inflow)
    ccy = _canonical_ticker(currency)
    ccy_pos = acct["positions"].setdefault(
        ccy, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    ccy_pos["shares"] = round(ccy_pos["shares"] + proceeds, 10)
    ccy_pos["total_cost"] = round(ccy_pos["total_cost"] + proceeds, 10)

    # Clean up zero positions
    _cleanup_position(acct, ticker)
    _cleanup_position(acct, ccy)

    _ensure_finite_values(
        **{f"{account_name}.{ccy}.shares": ccy_pos["shares"],
           f"{account_name}.{ccy}.total_cost": ccy_pos["total_cost"],
           f"{account_name}.{ticker}.shares": pos["shares"],
           f"{account_name}.{ticker}.total_cost": pos["total_cost"]}
    )

    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="swap", from_ticker=ticker, to_ticker=currency,
        from_amount=shares, to_amount=shares * price, price=price, commission=commission,
        commission_asset=currency, currency=currency, account_name=account_name, note=note,
    )


def do_swap(
    account_name: str,
    from_ticker: str,
    from_shares: float,
    to_ticker: str,
    to_shares: float,
    currency: str | None = None,
    note: str = "",
    commission: float = 0.0,
    commission_asset: str = "",
    date: Optional[str] = None,
):
    """Crypto-to-crypto swap: carry from_ticker's released cost to to_ticker.

    Records a single SWAP row. Cash untouched.
    """
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    base_currencies = _account_base_currency_set(account)
    _ensure_finite_values(from_shares=from_shares, to_shares=to_shares, commission=commission)
    from_ticker = _canonical_ticker(from_ticker)
    to_ticker = _canonical_ticker(to_ticker)
    commission_asset = _canonical_ticker(commission_asset)
    date_key = date[:10]

    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    _assert_cost_currency_compatible(
        acct["positions"], account_name, from_ticker, currency, base_currencies
    )
    _assert_cost_currency_compatible(
        acct["positions"], account_name, to_ticker, currency, base_currencies
    )
    if commission_asset:
        _assert_cost_currency_compatible(
            acct["positions"], account_name, commission_asset, currency, base_currencies
        )
    _apply_swap_to_positions(
        acct["positions"],
        account_name,
        from_ticker,
        from_shares,
        to_ticker,
        to_shares,
        commission,
        commission_asset,
        currency,
        base_currencies,
        enforce_available=True,
    )

    # Clean up zero positions
    _cleanup_position(acct, from_ticker)
    _cleanup_position(acct, to_ticker)
    if commission_asset:
        _cleanup_position(acct, commission_asset)

    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="swap", from_ticker=from_ticker,
        to_ticker=to_ticker, from_amount=from_shares, to_amount=to_shares,
        price=0, commission=commission, commission_asset=commission_asset,
        currency=currency, account_name=account_name, note=note,
    )
    print(f"✅ 兑换 {_fmt_shares(from_shares)} {from_ticker} → "
          f"{_fmt_shares(to_shares)} {to_ticker} ({account_name})")
    return True


def do_deposit(
    amount: float,
    currency: str | None,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Deposit cash into account."""
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    _ensure_finite_values(amount=amount)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    ccy = _canonical_ticker(currency)
    ccy_pos = acct["positions"].setdefault(
        ccy, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    ccy_pos["shares"] = round(ccy_pos["shares"] + amount, 10)
    ccy_pos["total_cost"] = round(ccy_pos["total_cost"] + amount, 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="deposit", from_ticker="", to_ticker=ccy,
        from_amount=0, to_amount=amount, price=1, commission=0,
        commission_asset="", currency=currency, account_name=account_name, note=note,
    )


def do_withdraw(
    amount: float,
    currency: str | None,
    account_name: str,
    note: str = "",
    date: Optional[str] = None,
):
    """Withdraw cash from account."""
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    _ensure_finite_values(amount=amount)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    ccy = _canonical_ticker(currency)
    ccy_pos = acct["positions"].setdefault(
        ccy, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    ccy_pos["shares"] = round(ccy_pos["shares"] - amount, 10)
    ccy_pos["total_cost"] = round(ccy_pos["total_cost"] - amount, 10)
    _cleanup_position(acct, ccy)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="withdraw", from_ticker=ccy, to_ticker="",
        from_amount=amount, to_amount=0, price=1, commission=0,
        commission_asset="", currency=currency, account_name=account_name, note=note,
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
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    base_currencies = _account_base_currency_set(account)
    _ensure_finite_values(amount=amount)
    ticker = _canonical_ticker(ticker)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    _assert_cash_dividend_currency_compatible(
        acct["positions"], account_name, ticker, currency, base_currencies
    )
    ccy = _canonical_ticker(currency)
    ccy_pos = acct["positions"].setdefault(
        ccy, {"shares": 0, "total_cost": 0.0, "cost_currency": currency}
    )
    ccy_pos["shares"] = round(ccy_pos["shares"] + amount, 10)
    ccy_pos["total_cost"] = round(ccy_pos["total_cost"] + amount, 10)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="dividend", from_ticker=ticker, to_ticker=ccy,
        from_amount=0, to_amount=amount, price=1, commission=0,
        commission_asset="", currency=currency, account_name=account_name, note=note,
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
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    base_currencies = _account_base_currency_set(account)
    _ensure_finite_values(shares=shares, avg_cost=avg_cost, position_value=shares * avg_cost)
    ticker = _canonical_ticker(ticker)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    if abs(float(shares or 0)) >= 1e-9:
        _assert_cost_currency_compatible(
            acct["positions"], account_name, ticker, currency, base_currencies
        )
    acct["positions"][ticker] = {
        "shares": shares,
        "total_cost": round(shares * avg_cost, 2),
        "cost_currency": currency,
    }
    _cleanup_position(acct, ticker)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="checkin", from_ticker=ticker, to_ticker="",
        from_amount=0, to_amount=shares, price=avg_cost, commission=0,
        commission_asset="", currency=currency, account_name=account_name, note=note,
    )


def do_checkin_cash(
    cash: float,
    account_name: str,
    currency: str | None = None,
    note: str = "",
    date: Optional[str] = None,
):
    """Directly overwrite cash in the snapshot.

    Records a CHECKIN row.
    """
    if date is None:
        date = _now()
    account, currency = resolve_security_account_currency(account_name, currency)
    account_currency = _account_display_currency(account)
    _ensure_finite_values(cash=cash)

    date_key = date[:10]
    snap = load_snapshot()
    acct = _ensure_account(snap, account_name, account_currency)
    _canonicalize_account_positions(acct, currency)
    ccy = _canonical_ticker(currency)
    acct["positions"][ccy] = {
        "shares": cash,
        "total_cost": cash,
        "cost_currency": currency,
    }
    _cleanup_position(acct, ccy)
    snap["updated_at"] = date_key
    _save_snapshot_and_record_trade(
        snap,
        date=date, action="checkin", from_ticker=ccy, to_ticker="",
        from_amount=0, to_amount=cash, price=1, commission=0,
        commission_asset="", currency=currency, account_name=account_name, note=note,
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
            # Some nested/sub-market slugs stop resolving via /markets?slug=...
            # after Polymarket reorganizes the parent event. Search can still
            # return the parent event with nested markets; match the child slug.
            search_q = slug.replace("-", " ")
            search_url = f"https://gamma-api.polymarket.com/public-search?q={quote(search_q)}"
            try:
                req = urllib.request.Request(search_url, headers=headers)
                with urllib.request.urlopen(req, timeout=15) as resp:
                    search_payload = json.load(resp)
            except Exception:
                search_payload = None
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
                        market = candidate
                        break
                if market:
                    break
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
        cid = CRYPTO_IDS.get(str(t).strip().lower())
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

    crypto_tickers = [nt for nt in normalized if nt.lower() in CRYPTO_IDS]
    pm_tickers = [nt for nt in normalized if nt.startswith("pm:")]
    regular_tickers = [
        nt for nt in normalized
        if not nt.startswith("pm:") and nt.lower() not in CRYPTO_IDS
    ]

    import os
    proxy = os.environ.get("HTTP_PROXY") or os.environ.get("HTTPS_PROXY")
    if proxy:
        os.environ.setdefault("HTTP_PROXY", proxy)
        os.environ.setdefault("HTTPS_PROXY", proxy)

    prices = _fetch_crypto_prices([ticker_map[nt] for nt in crypto_tickers])
    prices.update(_fetch_polymarket_prices(pm_tickers))

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
    base_currencies_by_account = _load_security_account_base_currencies()

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

    # Configuration is the sole currency registry.  A configured settlement
    # ticker is cash wherever it appears, including when an old snapshot keeps
    # it under another account; never send it to a price API.
    configured_currency_tickers = {
        currency.lower()
        for currencies in base_currencies_by_account.values()
        for currency in currencies
    }
    all_tickers = {
        ticker
        for acct_data in all_accts.values()
        for ticker in acct_data.get("positions", {})
        if str(ticker).lower() not in configured_currency_tickers
    }
    prices = _fetch_prices(list(all_tickers))

    for acct_name, acct_data in all_accts.items():
        positions = acct_data.get("positions", {})
        allowed_cash = base_currencies_by_account.get(acct_name)
        if not allowed_cash:
            legacy = (acct_data.get("currency") or "").strip().upper()
            allowed_cash = {legacy} if legacy else set()

        grouped: dict[str, list[tuple[str, dict]]] = {}
        cash_positions: dict[str, dict] = {}
        for ticker, pos in positions.items():
            if abs(float(pos.get("shares", 0) or 0)) < 1e-9:
                continue
            ticker_currency = ticker.upper()
            if ticker_currency in allowed_cash:
                cash_positions[ticker_currency] = pos
                continue
            # A configured currency held outside this account's allowed cash
            # set remains a currency position: show it in its own denomination
            # as N/A (there is no FX conversion module), rather than pricing it
            # as a security or displaying its cost currency's symbol.
            display_currency = (
                ticker_currency
                if ticker.lower() in configured_currency_tickers
                else (pos.get("cost_currency") or "").strip().upper() or "UNKNOWN"
            )
            grouped.setdefault(display_currency, []).append((ticker, pos))

        display_currencies = sorted(set(grouped) | set(cash_positions))
        if not display_currencies:
            continue

        market_totals: dict[str, float | None] = {}
        for currency in display_currencies:
            symbol = CURRENCY_SYMBOLS.get(currency) or f"{currency} "
            cash = cash_positions.get(currency, {}).get("shares", 0.0)
            rows_for_currency = sorted(grouped.get(currency, []))

            print(f"\n  📊 持仓 [{currency}]  {acct_name}")
            print(
                f"  {'代码':<16} {'股数':>8} {'均价':>12} {'成本':>14} "
                f"{'市值':>14} {'盈亏':>14} {'涨幅':>8}"
            )
            print("  " + "-" * 90)

            total_cost = 0.0
            total_value = 0.0
            has_priced_position = False

            for ticker, pos in rows_for_currency:
                shares = pos["shares"]
                total = pos.get("total_cost", 0.0)
                avg_cost = total / shares if shares != 0 else 0.0
                current_price = prices.get(ticker)
                if current_price is not None:
                    has_priced_position = True
                    value = shares * current_price
                    pl = value - total
                    pct = (current_price - avg_cost) / avg_cost * 100 if avg_cost > 0 else 0.0
                    pl_str = f"+{symbol}{pl:>,.2f}" if pl >= 0 else f"{symbol}{pl:>,.2f}"
                    pct_str = f"+{pct:.1f}%" if pct >= 0 else f"{pct:.1f}%"
                else:
                    value = 0.0
                    pl_str = "   N/A"
                    pct_str = "  N/A"

                print(
                    f"  {ticker:<16} {_fmt_shares(shares):>8} {symbol}{avg_cost:>10,.2f} "
                    f"{symbol}{total:>12,.2f} {_fmt(value, symbol):>12} "
                    f"{pl_str:>14} {pct_str:>8}"
                )

                total_cost += total
                total_value += value

            market_totals[currency] = total_value if has_priced_position else None
            print("  " + "─" * 90)
            print(f"  {f'持仓市值 [{currency}]':<16} {'':>8} {'':>12} {'':>14} "
                  f"{_fmt(total_value, symbol):>14}")
            print(f"  {f'现金 [{currency}]':<16} {'':>8} {'':>12} {'':>14} "
                  f"{_fmt(cash, symbol):>14}")

        # A per-currency total is meaningful only when every valued position
        # and cash balance shares one denomination.  Never aggregate across
        # currencies, and do not let an unpriced foreign-currency position
        # suppress a valid same-currency account total.
        combined_currencies = {
            currency for currency, value in market_totals.items()
            if value is not None or currency in cash_positions
        }
        if len(combined_currencies) == 1:
            currency = next(iter(combined_currencies))
            symbol = CURRENCY_SYMBOLS.get(currency) or f"{currency} "
            total = (market_totals.get(currency) or 0.0) + float(
                cash_positions.get(currency, {}).get("shares", 0.0)
            )
            print(f"  {f'合计 [{currency}]':<16} {'':>8} {'':>12} {'':>14} "
                  f"{_fmt(total, symbol):>14}")
        elif len(combined_currencies) > 1:
            print("  合计：多币种，未合并")


# ── Verification ────────────────────────────────────────────────────────
def _replay_security_csv(records_dir=None, accounts_path=None):
    """Replay security CSV into positions.

    Returns dict keyed by (account, ticker) → {shares, total_cost}.
    """
    if records_dir is None:
        records_dir = _models().RECORDS_DIR
    records_dir = Path(str(records_dir))
    security_dir = records_dir / "security"

    if not security_dir.exists():
        from collections import defaultdict
        return defaultdict(lambda: {"shares": 0.0, "total_cost": 0.0})

    rows = []
    for csv_file in sorted(security_dir.glob("*.csv")):
        with open(csv_file, encoding="utf-8") as f:
            rows.extend(csv.DictReader(f))
    positions = _replay_security_rows(
        _order_security_rows_for_replay(rows),
        validate_accounts=True,
        accounts_path=accounts_path,
    )
    return positions


def _order_security_rows_for_replay(rows):
    """Return rows in deterministic chronological replay order.

    The primary ordering is the CSV ``date`` field. Ties intentionally preserve
    the input order from the source CSVs. That matches persisted records because
    each day file is written with Python's stable date sort; during append,
    existing same-timestamp rows are placed before incoming same-timestamp rows.
    """
    return sorted(rows, key=lambda row: row.get("date", ""))


def _replay_security_rows(rows, validate_accounts: bool = False, accounts_path=None):
    """Replay in-memory security rows, raising ValueError on non-finite state.

    Unified swap model: all trades are swaps between tickers.
    Positions track {shares, total_cost}. Cash is a position in the currency ticker.
    """
    from collections import defaultdict

    positions = defaultdict(lambda: _position_bucket(""))
    base_currencies_by_account = _load_security_account_base_currencies(accounts_path)

    def _row_base_currencies(account: str, cost_currency: str) -> set[str]:
        return base_currencies_by_account.get(account) or _base_currency_set(cost_currency)

    def _account_positions(account: str, cost_currency: str) -> dict:
        return {
            ticker: pos
            for (acct, ticker), pos in positions.items()
            if acct == account
        }

    def _write_account_positions(account: str, account_positions: dict) -> None:
        for ticker, pos in account_positions.items():
            positions[(account, ticker)] = pos

    def _replay_position(
        account: str,
        ticker: str,
        cost_currency: str,
        base_currencies,
    ) -> dict:
        pos = positions[(account, ticker)]
        position_cost_currency = _cost_currency_for_ticker(
            ticker, cost_currency, base_currencies
        )
        if position_cost_currency:
            pos["cost_currency"] = _merge_cost_currency(
                pos.get("cost_currency", ""), position_cost_currency, ticker
            )
        return pos

    for row in rows:
        # Security records are mixed with some transfer-style audit rows
        # that don't carry stock-trade fields. Skip anything that isn't a
        # real security action row.
        if row.get("action") not in VALID_ACTIONS or not row.get("account_name"):
            continue
        a = row["account_name"]
        act = row["action"]
        cost_currency = row.get("currency", "") or ""
        if validate_accounts:
            _validate_security_row_account_currency(a, cost_currency, accounts_path=accounts_path)
        base_currencies = _row_base_currencies(a, cost_currency)
        try:
            from_amount = float(row.get("from_amount") or 0)
            to_amount = float(row.get("to_amount") or 0)
            price = float(row.get("price") or 0)
            commission = float(row.get("commission") or 0)
        except (ValueError, KeyError):
            continue
        _ensure_finite_values(from_amount=from_amount, to_amount=to_amount,
                              price=price, commission=commission)

        if act == "swap":
            account_positions = _account_positions(a, cost_currency)
            _apply_swap_to_positions(
                account_positions,
                a,
                row.get("from_ticker", "") or "",
                from_amount,
                row.get("to_ticker", "") or "",
                to_amount,
                commission,
                row.get("commission_asset", "") or "",
                cost_currency,
                base_currencies,
                enforce_available=False,
            )
            _write_account_positions(a, account_positions)

        elif act == "deposit":
            to_ticker = _canonical_ticker(row.get("to_ticker", "") or "")
            if not to_ticker:
                continue
            h = _replay_position(a, to_ticker, cost_currency, base_currencies)
            h["shares"] = round(h["shares"] + to_amount, 10)
            h["total_cost"] = round(h["total_cost"] + to_amount, 10)
            _normalize_position(h)
            _validate_position_values(a, to_ticker, h)

        elif act == "withdraw":
            from_ticker = _canonical_ticker(row.get("from_ticker", "") or "")
            if not from_ticker:
                continue
            h = _replay_position(a, from_ticker, cost_currency, base_currencies)
            h["shares"] = round(h["shares"] - from_amount, 10)
            h["total_cost"] = round(h["total_cost"] - from_amount, 10)
            _normalize_position(h)
            _validate_position_values(a, from_ticker, h)

        elif act == "dividend":
            from_ticker = _canonical_ticker(row.get("from_ticker", "") or "")
            to_ticker = _canonical_ticker(row.get("to_ticker", "") or "")
            if not to_ticker:
                continue
            is_stock_dividend = (
                bool(from_ticker)
                and from_ticker == to_ticker
                and not _is_currency_ticker(to_ticker, base_currencies)
            )
            if not is_stock_dividend:
                _assert_cash_dividend_currency_compatible(
                    _account_positions(a, cost_currency),
                    a,
                    from_ticker,
                    cost_currency,
                    base_currencies,
                )
            h = _replay_position(a, to_ticker, cost_currency, base_currencies)
            h["shares"] = round(h["shares"] + to_amount, 10)
            # 现金分红：total_cost 增加（cash position 增加）；送股/转增：total_cost 不变
            if not is_stock_dividend:
                h["total_cost"] = round(h["total_cost"] + to_amount, 10)
            _normalize_position(h)
            _validate_position_values(a, to_ticker, h)

        elif act == "checkin":
            from_ticker = _canonical_ticker(row.get("from_ticker", "") or "")
            if not from_ticker:
                continue
            if _is_currency_ticker(from_ticker, base_currencies):
                h = _replay_position(a, from_ticker, cost_currency, base_currencies)
                h["shares"] = round(to_amount, 10)
                h["total_cost"] = round(to_amount, 10)
                _normalize_position(h)
                _validate_position_values(a, from_ticker, h)
                if h["shares"] == 0:
                    positions.pop((a, from_ticker), None)
                continue
            if abs(to_amount) < 1e-9:
                positions.pop((a, from_ticker), None)
                continue
            h = _replay_position(a, from_ticker, cost_currency, base_currencies)
            h["shares"] = round(to_amount, 10)
            h["total_cost"] = round(to_amount * price, 2)
            _normalize_position(h)
            _validate_position_values(a, from_ticker, h)

    return positions


def verify_security(records_dir=None):
    """Replay security CSV and compare against snapshot.
    Returns (ok: bool, report_lines: list[str])."""
    snap = load_snapshot()
    positions = _replay_security_csv(records_dir)
    lines = []
    ok = True

    if not positions:
        lines.append("📭 无 security CSV 记录")
        return True, lines

    # Compare with snapshot — security accounts live under accounts.security
    sec_accounts = snap.get("accounts", {}).get("security", {})
    for acct_name, acct_data in sec_accounts.items():
        for ticker, sp in acct_data.get("positions", {}).items():
            ticker = _canonical_ticker(ticker)
            csv_p = positions.get((acct_name, ticker))
            if csv_p is None:
                lines.append(f"  ❌ {acct_name}/{ticker}: snapshot有但CSV无")
                ok = False
                continue
            if not _numeric_equal(csv_p.get("shares", 0), sp.get("shares", 0)):
                lines.append(
                    f"  ❌ {acct_name}/{ticker}.shares: CSV={csv_p.get('shares', 0)} "
                    f"vs 快照={sp.get('shares', 0)}"
                )
                ok = False
            if not _numeric_equal(
                csv_p.get("total_cost", 0),
                sp.get("total_cost", 0),
                tolerance=Decimal("0.005"),
            ):
                lines.append(
                    f"  ❌ {acct_name}/{ticker}.total_cost: CSV={csv_p.get('total_cost', 0)} "
                    f"vs 快照={sp.get('total_cost', 0)}"
                )
                ok = False
            if not _cost_currency_equal(csv_p.get("cost_currency"), sp.get("cost_currency")):
                lines.append(
                    f"  ❌ {acct_name}/{ticker}.cost_currency: CSV={csv_p.get('cost_currency', '')} "
                    f"vs 快照={sp.get('cost_currency', '')}"
                )
                ok = False

    # Check CSV-only positions not in snapshot
    for (acct, ticker) in positions:
        if not sec_accounts.get(acct, {}).get("positions", {}).get(ticker):
            p = positions[(acct, ticker)]
            if p["shares"] != 0:
                lines.append(f"  ❌ {acct}/{ticker}: CSV有但快照无")
                ok = False

    if ok:
        lines.append("  ✅ Security CSV ↔ Snapshot 完全对齐")
    else:
        lines.append("  ❌ 存在差异")
    return ok, lines


def repair_security(records_dir=None, accounts_path=None, snapshot_path=None,
                    stage_changes: bool = True, emit_output: bool = True):
    """Replay security CSV and write into unified snapshot accounts.security."""
    from datetime import datetime
    from .accounts import load_accounts
    positions = _replay_security_csv(records_dir, accounts_path=accounts_path)

    # Look up currency from accounts.yaml
    acct_currencies = {a["name"]: a["currency"] for a in load_accounts(accounts_path)
                       if a["type"] in ("security", "crypto")}

    accounts = {}
    for (acct_name, ticker), p in positions.items():
        if p["shares"] == 0:
            continue
        if acct_name not in accounts:
            currency = acct_currencies.get(acct_name, "")
            accounts[acct_name] = {
                "currency": currency,
                "positions": {},
            }
        # Compute avg_cost for backward compat with display
        avg_cost = round(p["total_cost"] / p["shares"], 2) if p["shares"] != 0 else 0.0
        accounts[acct_name]["positions"][ticker] = {
            "shares": p["shares"],
            "total_cost": round(p["total_cost"], 2),
            "cost_currency": p.get("cost_currency") or acct_currencies.get(acct_name, ""),
        }

    snap = load_snapshot(snapshot_path)
    snap.setdefault("accounts", {})["security"] = accounts

    # 清理顶层旧结构中的重复 security 账户
    top = snap["accounts"]
    for acct_name in list(accounts.keys()):
        if acct_name in top and acct_name != "security":
            del top[acct_name]

    snap["updated_at"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    save_snapshot(snap, snapshot_path, stage_changes=stage_changes)
    if emit_output:
        print(f"✅ 已从 CSV 重建快照: {len(accounts)} 个账户, {sum(len(a.get('positions',{})) for a in accounts.values())} 个标的")

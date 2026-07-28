"""Charles Schwab Transaction History CSV importer.

Parses single-table Chinese-header CSV (日期/类型/说明/参照号码/杂费/佣金/金额/余额).
Fee contract (TRD): cash component = abs(金额), commission = abs(杂费)+abs(佣金) once —
never cash_leg=abs(金额+杂费) with non-zero commission (see research.md § schwab).
"""

from __future__ import annotations

import csv
import re
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any

from ft.importers.ticker_normalize import normalize_equity_ticker

KNOWN_TYPES = frozenset({"TRD", "DOI", "JRN", "WIN"})

# BOT +4 SNDK @1498.00  |  SOLD -1 SNDK @1550.00
_TRD_DESC_RE = re.compile(
    r"^(BOT|SOLD)\s+([+-]?\d+(?:\.\d+)?)\s+(\S+)\s+@\s*(\d+(?:\.\d+)?)\s*$",
    re.IGNORECASE,
)


@dataclass
class SchwabStatement:
    """Parsed Schwab Transaction History."""

    transactions: list[dict[str, Any]] = field(default_factory=list)
    ending_cash: Decimal = Decimal("0")
    currency: str = "USD"


def _d(value: str | None) -> Decimal | None:
    """Parse Schwab money/number cell to Decimal; empty/`-` → None."""
    if value is None:
        return None
    s = str(value).strip()
    if not s or s == "-":
        return None
    # ($5,992.00) or ($0.14)
    neg = False
    if s.startswith("(") and s.endswith(")"):
        neg = True
        s = s[1:-1]
    s = s.replace("$", "").replace(",", "").strip()
    if not s or s == "-":
        return None
    try:
        d = Decimal(s)
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid decimal in Schwab CSV: {value!r}") from exc
    return -d if neg else d


def _d0(value: str | None) -> Decimal:
    parsed = _d(value)
    return parsed if parsed is not None else Decimal("0")


def _fmt(value: Decimal | int | str) -> str:
    """Decimal string without scientific notation; drop trailing zeros."""
    d = value if isinstance(value, Decimal) else Decimal(str(value))
    s = format(d, "f")
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s or "0"


def _normalize_date(raw: str) -> str:
    """Normalize `YYYY/M/D HH:MM` → `YYYY-MM-DD HH:MM` (zero-padded)."""
    s = raw.strip()
    if not s:
        return s
    # Split date and optional time
    if " " in s:
        date_part, time_part = s.split(" ", 1)
    else:
        date_part, time_part = s, ""
    date_part = date_part.replace("/", "-")
    bits = date_part.split("-")
    if len(bits) == 3:
        y, m, d = bits
        date_part = f"{int(y):04d}-{int(m):02d}-{int(d):02d}"
    if time_part:
        # pad H:MM or HH:MM
        tbits = time_part.strip().split(":")
        if len(tbits) >= 2:
            h, mi = int(tbits[0]), int(tbits[1])
            sec = int(tbits[2]) if len(tbits) > 2 else 0
            if len(tbits) > 2:
                time_part = f"{h:02d}:{mi:02d}:{sec:02d}"
            else:
                time_part = f"{h:02d}:{mi:02d}"
        return f"{date_part} {time_part}"
    return date_part


def _strip_headers(fieldnames: list[str] | None) -> list[str]:
    if not fieldnames:
        return []
    return [h.strip() for h in fieldnames]


def parse_schwab_csv(path: str | Path) -> SchwabStatement:
    """Parse Schwab Transaction History CSV into chrono flows + cash CHECKIN.

    Fail-closed on unknown 类型 or unreadable money fields.
    File order is newest-first; returned flows are ascending by date then ref.
    """
    path = Path(path)
    statement = SchwabStatement()
    flow_rows: list[dict[str, Any]] = []
    newest_balance: Decimal | None = None
    newest_date_raw = ""

    with path.open(encoding="utf-8-sig", newline="") as fh:
        reader = csv.DictReader(fh)
        if not reader.fieldnames:
            raise ValueError("Schwab CSV missing header row")
        # Map stripped header → original key
        original_keys = list(reader.fieldnames)
        stripped = _strip_headers(original_keys)
        key_map = {s: o for s, o in zip(stripped, original_keys)}
        required = ["日期", "类型", "说明", "参照号码", "杂费", "佣金", "金额", "余额"]
        missing = [h for h in required if h not in key_map]
        if missing:
            raise ValueError(f"Schwab CSV missing columns: {missing}")

        def cell(row: dict[str, str | None], name: str) -> str:
            return (row.get(key_map[name]) or "").strip()

        first_data = True
        for raw in reader:
            # Skip blank lines (all empty)
            if not any((v or "").strip() for v in raw.values()):
                continue
            type_code = cell(raw, "类型")
            if not type_code:
                continue
            if type_code not in KNOWN_TYPES:
                raise ValueError(f"unknown Schwab transaction type: {type_code!r}")

            date_raw = cell(raw, "日期")
            date = _normalize_date(date_raw)
            description = cell(raw, "说明")
            ref = cell(raw, "参照号码")
            misc_fee = _d0(cell(raw, "杂费"))
            commission_col = _d0(cell(raw, "佣金"))
            amount = _d0(cell(raw, "金额"))
            balance = _d0(cell(raw, "余额"))

            if first_data:
                newest_balance = balance
                newest_date_raw = date
                first_data = False

            fee_total = abs(misc_fee) + abs(commission_col)
            side = ""
            symbol = ""
            qty = Decimal("0")
            price = Decimal("0")
            if type_code == "TRD":
                m = _TRD_DESC_RE.match(description.strip())
                if not m:
                    raise ValueError(
                        f"unparseable Schwab TRD description: {description!r}"
                    )
                side = m.group(1).upper()
                qty = abs(Decimal(m.group(2)))
                symbol = m.group(3).strip()
                price = Decimal(m.group(4))

            flow_rows.append({
                "date": date,
                "type": type_code,
                "note": description,
                "ref": ref,
                "misc_fee": misc_fee,
                "commission_col": commission_col,
                "amount": amount,
                "balance": balance,
                "fee": fee_total,
                "side": side,
                "symbol": symbol,
                "ticker": (normalize_equity_ticker(symbol, default_market="us") if symbol else ""),
                "qty": qty,
                "price": price,
                "note": description,
            })

    if not flow_rows:
        raise ValueError("Schwab CSV has no transaction rows")

    if newest_balance is None:
        raise ValueError("Schwab CSV missing balance for CHECKIN")

    statement.ending_cash = newest_balance

    # Sort ascending by date then ref for chronological replay
    flow_rows.sort(key=lambda r: (r["date"], r["ref"]))

    checkin_date = newest_date_raw or flow_rows[-1]["date"]
    checkin = {
        "date": checkin_date,
        "type": "CHECKIN",
        "note": "newest 余额",
        "ref": "",
        "misc_fee": Decimal("0"),
        "commission_col": Decimal("0"),
        "amount": newest_balance,
        "balance": newest_balance,
        "fee": Decimal("0"),
        "side": "",
        "symbol": "",
        "ticker": "",
        "qty": Decimal("0"),
        "price": Decimal("1"),
        "note": "newest 余额",
    }

    statement.transactions = flow_rows + [checkin]
    return statement


def construct_source_identity(txn: dict[str, Any]) -> str:
    """Build idempotent source_identity for a Schwab row.

    Format: schwab:{参照号码}:{类型}
    CHECKIN: schwab:{YYYYMMDD}:checkin:cash:{amount}
    Fallback when ref missing: schwab:{date}:{type}:{amount}:{balance}
    """
    type_code = txn["type"]
    if type_code == "CHECKIN":
        date = str(txn["date"])[:10].replace("-", "")
        amount = _fmt(Decimal(str(txn.get("amount") or 0)))
        return f"schwab:{date}:checkin:cash:{amount}"

    ref = str(txn.get("ref") or "").strip()
    if ref:
        return f"schwab:{ref}:{type_code}"

    date = str(txn["date"])[:10].replace("-", "")
    amount = _fmt(Decimal(str(txn.get("amount") or 0)))
    balance = _fmt(Decimal(str(txn.get("balance") or 0)))
    return f"schwab:{date}:{type_code}:{amount}:{balance}"


def map_schwab_to_investment_event(
    txn: dict[str, Any],
    account_name: str,
    currency: str = "USD",
) -> dict[str, Any]:
    """Map one Schwab row to unified investment event dict."""
    type_code = txn["type"]
    cash = currency.lower()
    date = txn["date"]
    note = txn.get("note") or txn.get("description") or ""

    base = {
        "date": date,
        "account_name": account_name,
        "currency": currency.upper(),
        "note": note,
    }

    if type_code == "TRD":
        return _map_trd(txn, base, cash)

    if type_code == "WIN":
        amount_abs = abs(Decimal(str(txn.get("amount") or 0)))
        return {
            **base,
            "action": "deposit",
            "to_ticker": cash,
            "to_amount": _fmt(amount_abs),
            "from_ticker": "",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if type_code == "DOI":
        amount = Decimal(str(txn.get("amount") or 0))
        amount_abs = abs(amount)
        if amount > 0:
            # dividend
            from_ticker = _guess_underlying(txn.get("description") or "")
            return {
                **base,
                "action": "dividend",
                "from_ticker": from_ticker,
                "to_ticker": cash,
                "to_amount": _fmt(amount_abs),
                "from_amount": "0",
                "price": "1",
                "commission": "0",
                "commission_asset": "",
            }
        # interest charge / negative DOI → withdraw
        return {
            **base,
            "action": "withdraw",
            "from_ticker": cash,
            "from_amount": _fmt(amount_abs),
            "to_ticker": "",
            "to_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if type_code == "JRN":
        amount = Decimal(str(txn.get("amount") or 0))
        amount_abs = abs(amount)
        desc = (txn.get("description") or "").upper()
        if amount > 0 or "REFUND" in desc:
            return {
                **base,
                "action": "deposit",
                "to_ticker": cash,
                "to_amount": _fmt(amount_abs),
                "from_ticker": "",
                "from_amount": "0",
                "price": "1",
                "commission": "0",
                "commission_asset": "",
            }
        return {
            **base,
            "action": "withdraw",
            "from_ticker": cash,
            "from_amount": _fmt(amount_abs),
            "to_ticker": "",
            "to_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if type_code == "CHECKIN":
        amount = abs(Decimal(str(txn.get("amount") or 0)))
        return {
            **base,
            "action": "checkin",
            "from_ticker": "",
            "to_ticker": cash,
            "to_amount": _fmt(amount),
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    raise ValueError(f"unsupported Schwab type: {type_code}")


def _map_trd(txn: dict[str, Any], base: dict[str, Any], cash: str) -> dict[str, Any]:
    side = (txn.get("side") or "").upper()
    qty = abs(Decimal(str(txn.get("qty") or 0)))
    price = abs(Decimal(str(txn.get("price") or 0)))
    amount_abs = abs(Decimal(str(txn.get("amount") or 0)))
    fee_abs = abs(Decimal(str(txn.get("fee") or 0)))
    raw = (txn.get("ticker") or txn.get("symbol") or "").strip()
    if not raw:
        raise ValueError(f"Schwab TRD missing symbol: {txn.get('description')!r}")
    # ticker may already be normalized from parse; ensure .us
    code = normalize_equity_ticker(raw, default_market="us")

    if side == "BOT":
        return {
            **base,
            "action": "swap",
            "from_ticker": cash,
            "from_amount": _fmt(amount_abs),
            "to_ticker": code,
            "to_amount": _fmt(qty),
            "price": _fmt(price),
            "commission": _fmt(fee_abs),
            "commission_asset": cash if fee_abs else "",
        }
    if side == "SOLD":
        return {
            **base,
            "action": "swap",
            "from_ticker": code,
            "from_amount": _fmt(qty),
            "to_ticker": cash,
            "to_amount": _fmt(amount_abs),
            "price": _fmt(price),
            "commission": _fmt(fee_abs),
            "commission_asset": cash if fee_abs else "",
        }
    raise ValueError(f"unsupported Schwab TRD side: {side!r}")


def _guess_underlying(description: str) -> str:
    """Best-effort ticker from DOI description; empty if unknown."""
    # Patterns like "Qualified Dividend - BROADCOM INC 4.55 US$"
    # or "PROSHARES ULTRA QQQ 2.73 US$" — we do not hardcode company→ticker;
    # leave from_ticker empty when not parseable as a bare symbol.
    return ""

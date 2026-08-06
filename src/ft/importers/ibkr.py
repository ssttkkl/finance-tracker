"""Interactive Brokers (IBKR) Activity Statement CSV importer.

Parses multi-section Chinese-label Activity CSV (Transaction History + 总结).
Fee contract (equity): cash component = abs(总额), commission = abs(佣金) once — never
cash_leg=abs(净额) with non-zero commission (see research.md / FR-015).
"""

from __future__ import annotations

import csv
from dataclasses import dataclass, field
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path
from typing import Any

from ft.importers.ticker_normalize import normalize_equity_ticker

# Max storable scale for NUMERIC(38,18); FX net noise is clamped to this.
_FX_NET_QUANTUM = Decimal("1e-18")


def _normalize_parse_ticker(code: str) -> str:
    if not code or code == "-":
        return ""
    if "." in code and code.split(".", 1)[1].upper() in {
        "USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD",
    }:
        return code.lower()
    try:
        return normalize_equity_ticker(code, default_market="us")
    except ValueError:
        return code.lower()

KNOWN_ACTIONS = frozenset({
    "买",
    "卖",
    "存款",
    "股息",
    "外国预扣税",
    "借方利息",
    "外汇交易组成部分",
})


@dataclass
class IbkrStatement:
    """Parsed IBKR Activity Statement."""

    transactions: list[dict[str, Any]] = field(default_factory=list)
    base_currency: str = ""
    ending_cash: Decimal = Decimal("0")
    beginning_cash: Decimal = Decimal("0")
    period: str = ""
    when_generated: str = ""


def _d(value: str | None) -> Decimal | None:
    """Parse CSV cell to Decimal; treat '-', empty as None."""
    if value is None:
        return None
    s = str(value).strip().replace(",", "")
    if not s or s == "-":
        return None
    try:
        return Decimal(s)
    except (InvalidOperation, ValueError) as exc:
        raise ValueError(f"invalid decimal in IBKR CSV: {value!r}") from exc


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


def _source_payload_from_cells(cells: list[str]) -> dict[str, list[str]]:
    if not cells:
        raise ValueError("IBKR 来源行为空")
    return {"原始文本单元": list(cells)}


def parse_ibkr_csv(path: str | Path) -> IbkrStatement:
    """Parse IBKR Activity CSV into flows + trailing cash CHECKIN.

    Fail-closed on unknown 交易类型 or unreadable file.
    """
    path = Path(path)
    statement = IbkrStatement()
    flow_rows: list[dict[str, Any]] = []
    max_flow_date = ""
    ending_cash_source_payload: dict[str, list[str]] | None = None

    with path.open(encoding="utf-8", newline="") as fh:
        reader = csv.reader(fh)
        for row in reader:
            if not row:
                continue
            section = row[0].strip() if row else ""
            kind = row[1].strip() if len(row) > 1 else ""

            if section == "Statement" and kind == "Data" and len(row) >= 4:
                field_name, field_val = row[2], row[3]
                if field_name == "Period":
                    statement.period = field_val
                elif field_name == "WhenGenerated":
                    statement.when_generated = field_val
                continue

            if section == "总结" and kind == "Data" and len(row) >= 4:
                field_name, field_val = row[2], row[3]
                if field_name == "基础货币":
                    statement.base_currency = str(field_val).strip().upper()
                elif field_name == "期初现金":
                    statement.beginning_cash = _d0(field_val)
                elif field_name == "期末现金":
                    statement.ending_cash = _d0(field_val)
                    ending_cash_source_payload = _source_payload_from_cells(row)
                continue

            if section == "Transaction History" and kind == "Data":
                # Header: 日期,账户,说明,交易类型,代码,数量,价格,Price Currency,总额,佣金,净额
                if len(row) < 13:
                    raise ValueError(
                        f"IBKR Transaction History row has {len(row)} columns, need 13"
                    )
                date = row[2].strip()
                account = row[3].strip()
                description = row[4].strip()
                action = row[5].strip()
                code = row[6].strip()
                if action not in KNOWN_ACTIONS:
                    raise ValueError(
                        f"unknown IBKR transaction type: {action!r}"
                    )
                qty = _d(row[7])
                price = _d(row[8])
                price_currency = row[9].strip() if row[9].strip() != "-" else ""
                gross = _d(row[10])
                commission = _d(row[11])
                net = _d(row[12])

                if date > max_flow_date:
                    max_flow_date = date

                flow_rows.append({
                    "date": date,
                    "account": account,
                    "note": description,
                    "action": action,
                    "code": code if code != "-" else "",
                    "qty": qty if qty is not None else Decimal("0"),
                    "qty_raw": qty,
                    "price": price if price is not None else Decimal("0"),
                    "price_raw": price,
                    "price_currency": price_currency,
                    "gross": gross if gross is not None else Decimal("0"),
                    "commission": commission if commission is not None else Decimal("0"),
                    "commission_raw": commission,
                    "net": net if net is not None else Decimal("0"),
                    "net_raw": net,
                    "amount": net if net is not None else Decimal("0"),
                    "shares": abs(qty) if qty is not None else Decimal("0"),
                    "fee": abs(commission) if commission is not None else Decimal("0"),
                    "ticker": _normalize_parse_ticker(code),
                    "note": description,
                    "_source_payload": _source_payload_from_cells(row),
                })

    if not flow_rows and not statement.base_currency:
        raise ValueError("IBKR CSV missing Transaction History / 总结 sections")

    checkin_date = max_flow_date
    if not checkin_date and statement.when_generated:
        checkin_date = statement.when_generated[:10]
    if not checkin_date:
        checkin_date = "1970-01-01"
    if ending_cash_source_payload is None:
        raise ValueError("IBKR CSV missing 总结.期末现金 source row")

    checkin = {
        "date": checkin_date,
        "account": "",
        "note": "总结.期末现金",
        "action": "CHECKIN",
        "code": "",
        "qty": Decimal("0"),
        "qty_raw": None,
        "price": Decimal("1"),
        "price_raw": Decimal("1"),
        "price_currency": statement.base_currency,
        "gross": statement.ending_cash,
        "commission": Decimal("0"),
        "commission_raw": None,
        "net": statement.ending_cash,
        "net_raw": statement.ending_cash,
        "amount": statement.ending_cash,
        "shares": Decimal("0"),
        "fee": Decimal("0"),
        "ticker": "",
        "note": "总结.期末现金",
        "_source_payload": ending_cash_source_payload,
    }

    statement.transactions = flow_rows + [checkin]
    return statement


def construct_source_identity(txn: dict[str, Any]) -> str:
    """Build idempotent source_identity for an IBKR row.

    Format: ibkr:{date}:{type}:{code}:{qty}:{net}:{commission}
    CHECKIN: ibkr:{date}:checkin:cash:{amount}:0
    """
    date = str(txn["date"])[:10].replace("-", "")
    action = txn["action"]

    if action == "CHECKIN":
        amount = _fmt(Decimal(str(txn.get("amount") or 0)))
        return f"ibkr:{date}:checkin:cash:{amount}:0"

    code = txn.get("code") or "cash"
    if action in {"买", "卖"}:
        qty_s = _fmt(abs(Decimal(str(txn.get("qty") or 0))))
    elif txn.get("qty_raw") is None:
        qty_s = "0"
    else:
        qty_s = _fmt(Decimal(str(txn["qty"])))

    net_val = txn.get("net_raw")
    if net_val is None:
        net_val = txn.get("net") or 0
    net_s = _fmt(Decimal(str(net_val)))

    if txn.get("commission_raw") is not None:
        comm_s = _fmt(Decimal(str(txn["commission_raw"])))
    else:
        comm_s = "0"

    return f"ibkr:{date}:{action}:{code}:{qty_s}:{net_s}:{comm_s}"


def _equity_code(txn: dict[str, Any]) -> str:
    """Normalize equity symbol; empty for cash-only rows."""
    code = (txn.get("code") or "").strip()
    if not code or code == "-":
        return ""
    # FX pairs handled separately
    if "." in code and code.split(".", 1)[1].upper() in {
        "USD", "HKD", "CNY", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD", "SGD",
    }:
        return code.lower()
    return normalize_equity_ticker(code, ccy=str(txn.get("price_currency") or txn.get("currency") or "USD"), default_market="us")


def map_ibkr_to_investment_event(
    txn: dict[str, Any],
    account_name: str,
    currency: str = "USD",
) -> dict[str, Any]:
    """Map one IBKR row to unified investment event dict."""
    action = txn["action"]
    cash = currency.lower()
    date = txn["date"]
    note = txn.get("note") or txn.get("description") or ""

    base = {
        "date": date,
        "account_name": account_name,
        "currency": currency.upper(),
        "note": note,
    }

    if action == "买":
        gross_abs = abs(Decimal(str(txn.get("gross") or 0)))
        qty_abs = abs(Decimal(str(txn.get("qty") or 0)))
        fee_abs = abs(Decimal(str(txn.get("commission") or 0)))
        code = _equity_code(txn)
        price = abs(Decimal(str(txn.get("price") or 0)))
        return {
            **base,
            "record_type": "trade", "record_subtype": "security",
            "from_ticker": cash,
            "from_amount": _fmt(gross_abs),
            "to_ticker": code,
            "to_amount": _fmt(qty_abs),
            "price": _fmt(price),
            "commission": _fmt(fee_abs),
            "commission_asset": cash if fee_abs else "",
        }

    if action == "卖":
        gross_abs = abs(Decimal(str(txn.get("gross") or 0)))
        qty_abs = abs(Decimal(str(txn.get("qty") or 0)))
        fee_abs = abs(Decimal(str(txn.get("commission") or 0)))
        code = _equity_code(txn)
        price = abs(Decimal(str(txn.get("price") or 0)))
        return {
            **base,
            "record_type": "trade", "record_subtype": "security",
            "from_ticker": code,
            "from_amount": _fmt(qty_abs),
            "to_ticker": cash,
            "to_amount": _fmt(gross_abs),
            "price": _fmt(price),
            "commission": _fmt(fee_abs),
            "commission_asset": cash if fee_abs else "",
        }

    if action == "存款":
        net_abs = abs(Decimal(str(txn.get("net") or 0)))
        return {
            **base,
            "record_type": "funding", "record_subtype": "external",
            "to_ticker": cash,
            "to_amount": _fmt(net_abs),
            "from_ticker": "",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if action == "股息":
        net_abs = abs(Decimal(str(txn.get("net") or 0)))
        code = _equity_code(txn)
        return {
            **base,
            "record_type": "income", "record_subtype": "dividend_cash",
            "from_ticker": code,
            "to_ticker": cash,
            "to_amount": _fmt(net_abs),
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if action in {"外国预扣税", "借方利息"}:
        net_abs = abs(Decimal(str(txn.get("net") or 0)))
        return {
            **base,
            "record_type": "expense", "record_subtype": "tax" if action == "外国预扣税" else "interest",
            "from_ticker": cash,
            "from_amount": _fmt(net_abs),
            "to_ticker": "",
            "to_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if action == "外汇交易组成部分":
        return _map_fx(txn, base)

    if action == "CHECKIN":
        amount = abs(Decimal(str(txn.get("amount") or txn.get("net") or 0)))
        return {
            **base,
            "record_type": "snapshot", "record_subtype": "cash",
            "from_ticker": "",
            "to_ticker": cash,
            "to_amount": _fmt(amount),
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    raise ValueError(f"unsupported IBKR action: {action}")


def _map_fx(txn: dict[str, Any], base: dict[str, Any]) -> dict[str, Any]:
    """Map 外汇交易组成部分 to a non-funding net cash adjustment.

    IBKR FX rows report the **net cash impact** in 净额/总额 (usually a tiny
    spread / P&L amount), NOT the full notional sides. The full notional is only
    informational via qty × price. Recording qty×price as a swap would double-
    count the currency movement that is already captured elsewhere (e.g. the
    deposit that funded the conversion).

    The net amount changes the account's base cash ticker but is never funding.
    Commission goes to note when 净额==总额.
    """
    code = (txn.get("code") or "").strip()
    net = Decimal(str(txn.get("net") or 0))
    # FX net is a spread/P&L adjustment; clamp precision to the storable
    # NUMERIC(38,18) scale (IBKR emits >18 dp noise like 2.75e-7).
    net = net.quantize(_FX_NET_QUANTUM, rounding=ROUND_HALF_UP)
    net_abs = abs(net)
    note = base.get("note") or ""

    commission_raw = txn.get("commission_raw")
    if commission_raw is not None:
        fee_abs = abs(Decimal(str(commission_raw)))
        if fee_abs:
            note = f"{note} 佣金{_fmt(fee_abs)}".strip()

    # Determine which currency the net amount is in.
    # For USD.HKD pair, IBKR net is denominated in the account base currency (USD).
    cash = (base.get("currency") or "USD").lower()

    if net_abs == 0:
        # Zero net impact remains an auditable, non-funding adjustment.
        return {
            **base,
            "note": note or f"FX {code} zero net",
            "record_type": "adjustment", "record_subtype": "fx_net",
            "to_ticker": cash,
            "to_amount": "0",
            "from_ticker": "",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if net > 0:
        return {
            **base,
            "note": note or f"FX {code}",
            "record_type": "adjustment", "record_subtype": "fx_net",
            "to_ticker": cash,
            "to_amount": _fmt(net_abs),
            "from_ticker": "",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }
    else:
        return {
            **base,
            "note": note or f"FX {code}",
            "record_type": "adjustment", "record_subtype": "fx_net",
            "from_ticker": cash,
            "from_amount": _fmt(net_abs),
            "to_ticker": "",
            "to_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

"""Parser and event mapper for uSmart HK monthly statements.

The public functions deliberately keep parsing separate from the investment
application service so PDF extraction, text fixtures, and mapping are all
covered by the same fail-closed financial rules.
"""
from __future__ import annotations

from calendar import monthrange
from decimal import Decimal, InvalidOperation
import re
import shutil
import subprocess
from typing import Any

from ft.importers.ticker_normalize import normalize_equity_ticker


def normalize_cjk(text: str) -> str:
    """Normalize compatibility radicals emitted by PDF text extractors."""
    return text.translate(str.maketrans({
        "⾦": "金", "⼾": "户", "⼊": "入", "⽉": "月", "⽴": "立",
        "⼦": "子", "⼿": "手", "⽇": "日", "⻩": "黄", "⾕": "谷", "⽅": "方",
    }))



def _normalize_equity_ticker(code: str, *, market: str = "", ccy: str = "") -> str:
    """Delegate to shared normalizer (US → ``.us``, HK → ``.hk``)."""
    return normalize_equity_ticker(code, market=market, ccy=ccy, default_market="us")


def _statement_profile(rendered: str) -> str:
    """Return statement product profile.

    - ``margin``: 保证金 月结单 (M21)
    - ``day``: 日内融 月结单 (M61) or 日结单 (D51) — same trade/cash layout family
    """
    head = rendered[:300]
    if "日内融日结单" in head or "日内融月结单" in head or "日内融" in head:
        return "day"
    return "margin"



def _infer_ipo_codes(rendered: str) -> list[str]:
    """Collect HK stock codes mentioned near IPO wording in the statement text."""
    codes: list[str] = []
    for m in re.finditer(
        r"(?:IPO|认购|中签|新股)[^\n]{0,40}(?<!\d)(0\d{4}|\d{5})(?!\d)"
        r"|(?<!\d)(0\d{4}|\d{5})(?!\d)[^\n]{0,40}(?:IPO|认购|中签|新股)",
        rendered,
    ):
        code = m.group(1) or m.group(2)
        if code and code not in codes:
            codes.append(code.zfill(5) if code.isdigit() else code)
    return codes

def parse_usmart_hk_text(text: str | list[str]) -> list[dict[str, Any]]:
    """Parse extracted statement text into normalized, mappable source rows.

    Supports:
    - margin monthly (保证金账户 M21-style)
    - day-trading monthly (日内融 M61-style) — independent account product
    """
    rendered = "\n".join(text) if isinstance(text, list) else text
    rendered = normalize_cjk(rendered).replace("\r", "")
    profile = _statement_profile(rendered)
    # Daily: 结单日期 YYYY-MM-DD; Monthly: YYYY-MM (CHECKIN on month-end).
    full_day = re.search(r"结单日期\s*[：:]?\s*(\d{4})-(\d{2})-(\d{2})", rendered)
    if full_day:
        period = f"{full_day.group(1)}-{full_day.group(2)}-{full_day.group(3)}"
        month_end = period  # checkin on statement day
    else:
        period_match = re.search(r"结单日期\s*[：:]?\s*(\d{4})-(\d{2})", rendered)
        if not period_match:
            raise ValueError("uSmart HK statement is missing 结单日期")
        period = f"{period_match.group(1)}-{period_match.group(2)}"
        month_end = (
            f"{period}-"
            f"{monthrange(int(period_match.group(1)), int(period_match.group(2)))[1]:02d}"
        )
    rows: list[dict[str, Any]] = []
    trades = _parse_trades(rendered, profile=profile)
    rows.extend(trades)
    cash_rows, ignored_mirrors = _parse_cash_movements(rendered, profile=profile)
    ipo_codes = _infer_ipo_codes(rendered)
    if len(ipo_codes) == 1:
        for row in cash_rows:
            fl = str(row.get("flag_norm") or row.get("flag") or "")
            if "IPO" in fl or "认购" in fl:
                row.setdefault("ipo_code", ipo_codes[0])
    rows.extend(cash_rows)
    rows.extend(_parse_cash_checkins(rendered, period, month_end, profile=profile))
    holdings = _parse_holdings(rendered, period, month_end)
    rows.extend(holdings)
    held = {str(h["ticker"]).lower() for h in holdings}
    rows.extend(_closed_position_checkins(trades, held, period, month_end))
    if rows:
        rows[0]["_usmart_ignored_trade_mirrors"] = ignored_mirrors
        rows[0]["_usmart_statement_profile"] = profile
        seen: dict[str, int] = {}
        for row in rows:
            row["_profile"] = profile
            # Provisional identity for disambiguation of true same-day duplicates.
            key = construct_source_identity(row)
            n = seen.get(key, 0)
            if n:
                row["_id_seq"] = n
            seen[key] = n + 1
    return rows


def map_usmart_hk_to_investment_event(
    txn: dict[str, Any], account_name: str, currency: str | None = None,
) -> dict[str, Any]:
    """Map one normalized uSmart HK row to a unified investment event."""
    kind = txn["kind"]
    ccy = str(txn.get("ccy") or currency or "USD").upper()
    cash = ccy.lower()
    base = {
        "date": txn["date"], "account_name": account_name, "currency": ccy,
        "note": txn.get("note", ""),
    }

    def cash_event(record_type: str, record_subtype: str) -> dict[str, Any]:
        amount = abs(Decimal(str(txn["amount"])))
        incoming = Decimal(str(txn["amount"])) >= 0
        return {
            **base,
            "record_type": record_type,
            "record_subtype": record_subtype,
            "from_ticker": "" if incoming else cash,
            "from_amount": "0" if incoming else _fmt(amount),
            "to_ticker": cash if incoming else "",
            "to_amount": _fmt(amount) if incoming else "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        }

    if kind == "trade":
        if txn["side"] == "BUY":
            return {**base, "record_type": "swap", "record_subtype": "not_applicable", "from_ticker": cash,
                    "from_amount": _fmt(txn["gross"]), "to_ticker": txn["ticker"].lower(),
                    "to_amount": _fmt(txn["qty"]), "price": _fmt(txn["gross"] / txn["qty"]),
                    "commission": _fmt(txn["commission"]), "commission_asset": cash}
        return {**base, "record_type": "swap", "record_subtype": "not_applicable", "from_ticker": txn["ticker"].lower(),
                "from_amount": _fmt(txn["qty"]), "to_ticker": cash,
                "to_amount": _fmt(txn["gross"]), "price": _fmt(txn["gross"] / txn["qty"]),
                "commission": _fmt(txn["commission"]), "commission_asset": cash}
    if kind == "fx":
        return {**base, "record_type": "swap", "record_subtype": "not_applicable", "from_ticker": txn["from_ccy"].lower(),
                "from_amount": _fmt(txn["from_amount"]), "to_ticker": txn["to_ccy"].lower(),
                "to_amount": _fmt(txn["to_amount"]), "price": "0", "commission": "0",
                "commission_asset": ""}
    if kind == "cash":
        amount = abs(txn["amount"])
        flag = str(txn.get("flag_norm") or txn.get("flag") or "")
        note = str(txn.get("note") or "")
        text = f"{flag} {note}"
        # Dividend income
        if flag == "红利入账" or flag.startswith("红利入账"):
            return {
                **base, "record_type": "dividend", "record_subtype": "not_applicable", "from_ticker": "", "from_amount": "0",
                "to_ticker": cash, "to_amount": _fmt(amount), "price": "1",
                "commission": "0", "commission_asset": "",
            }
        # Fee / tax / interest family (charges and refunds of the same kind).
        fee_flags = {
            "融资利息", "融券罚息转出", "融券利息", "罚息转出",
            "美股股息税", "股息代收费", "红利税费", "股息税",
        }
        is_fee_flag = flag in fee_flags or any(
            k in flag for k in ("利息", "罚息", "股息税", "代收费", "税费")
        )
        # 资金存 + tax refund notes, or note-only tax refund rows
        is_tax_refund = (
            ("税" in text or "tax" in text.lower() or "withhold" in text.lower())
            and ("退" in text or "refund" in text.lower() or flag == "资金存")
        )
        is_fee_refund = is_tax_refund or (
            txn["amount"] > 0 and any(
                k in text for k in ("利息", "罚息", "代收费", "税费", "fee", "返还", "退还")
            ) and any(k in text for k in ("费", "税", "息", "佣金", "fee", "tax", "interest"))
        )
        if is_fee_flag or is_tax_refund or is_fee_refund:
            if txn["amount"] >= 0 and (is_tax_refund or is_fee_refund or is_fee_flag and txn["amount"] > 0):
                subtype = "tax_refund" if is_tax_refund else "interest_refund" if "息" in text else "fee_refund"
                return cash_event("fee", subtype)
            subtype = "tax" if "税" in text else "interest" if "息" in text else "commission"
            return cash_event("fee", subtype)
        # IPO subscription lifecycle uses a dedicated non-funding record type:
        #   认购扣款: cash out (from_amount)
        #   认购退款: cash in  (to_amount)
        #   认购手续费: fee (not ipo)
        # Optional stock code may live in note/App only; not required for cash components.
        # Future allotment (中签) can stay a separate equity swap when it appears.
        if "IPO认购手续费" in flag or (flag.startswith("IPO") and "手续费" in flag) or (
            "IPO" in flag and "Handling" in note
        ):
            return cash_event("fee", "handling_fee")
        if flag in {"IPO认购扣款"} or ("IPO" in flag and "扣款" in flag) or "IPO Debit" in note:
            return cash_event("ipo", "subscription_debit")
        if flag in {"IPO认购退款"} or ("IPO" in flag and "退款" in flag) or "IPO Refund" in note:
            return cash_event("ipo", "subscription_refund")
        if flag == "出金退款":
            if txn["amount"] < 0:
                raise ValueError("出金退款必须为现金入账")
            return cash_event("withdrawal_reversal", "withdrawal_refund")
        if flag in {"优惠券"}:
            if txn["amount"] < 0:
                raise ValueError("奖励必须为现金入账")
            return cash_event("reward", "cash_reward")
        if flag in {"平台费返还", "佣金返还", "手续费返还"}:
            if txn["amount"] < 0:
                raise ValueError("费用返还必须为现金入账")
            return cash_event("fee", "fee_refund")
        if flag in {
            "转入到日内融账户", "转入到保证金账户", "从保证金账户转入",
            "从日内融账户转出", "从日内融账户转入",
        }:
            return cash_event(
                "deposit" if txn["amount"] >= 0 else "withdraw",
                "subaccount_transfer",
            )
        if flag in {"入金", "出金", "提取", "资金存", "EDDA入金", "EDDA出金"}:
            return cash_event(
                "deposit" if txn["amount"] >= 0 else "withdraw",
                "external_funding",
            )
        raise ValueError(f"unsupported uSmart HK cash flag for normalized funding: {flag!r}")
    if kind == "checkin_cash":
        return {**base, "record_type": "checkin", "record_subtype": "not_applicable", "from_ticker": "", "from_amount": "0",
                "to_ticker": cash, "to_amount": _fmt(txn["amount"]), "price": "1", "commission": "0", "commission_asset": ""}
    if kind == "checkin_position":
        return {**base, "record_type": "checkin", "record_subtype": "not_applicable", "from_ticker": "", "from_amount": "0",
                "to_ticker": txn["ticker"].lower(), "to_amount": _fmt(txn["shares"]), "price": "0", "commission": "0", "commission_asset": ""}
    raise ValueError(f"unsupported uSmart HK row kind: {kind}")


def construct_source_identity(txn: dict[str, Any]) -> str:
    """Return the stable business identity specified for a uSmart HK row."""
    kind = txn["kind"]
    # Day-margin identities are namespaced so they never collide with margin account rows.
    prefix = "usmart_hk:day" if txn.get("_profile") == "day" or txn.get("profile") == "day" else "usmart_hk"
    if kind == "trade":
        ident = prefix + ":trade:{date}:{ticker}:{side}:{qty}:{gross}:{net}:{comm}:{ccy}".format(
            date=txn["date"], ticker=txn["ticker"], side=txn["side"], qty=_fmt(txn["qty"]),
            gross=_fmt(txn["gross"]), net=_fmt(txn["net"]),
            comm=_fmt(txn.get("commission") or 0), ccy=txn["ccy"])
    elif kind == "cash":
        note = str(txn.get("note") or "").strip()
        note_key = note.replace(" ", "")[:40] if note and note != txn.get("flag_norm") else ""
        base = f"{prefix}:cash:{txn['date']}:{txn['flag_norm']}:{txn['ccy']}:{_fmt(txn['amount'])}"
        ident = f"{base}:{note_key}" if note_key else base
    elif kind == "fx":
        ident = prefix + ":fx:{from_date}:{from_ccy}:{from_amount}:{to_date}:{to_ccy}:{to_amount}".format(
            from_date=txn["from_date"], from_ccy=txn["from_ccy"], from_amount=_fmt(txn["from_amount"]),
            to_date=txn["to_date"], to_ccy=txn["to_ccy"], to_amount=_fmt(txn["to_amount"]))
    elif kind == "checkin_cash":
        ident = f"{prefix}:checkin:cash:{txn['period']}:{txn['ccy']}:{_fmt(txn['amount'])}"
    elif kind == "checkin_position":
        ident = f"{prefix}:checkin:pos:{txn['period']}:{txn['ticker']}:{_fmt(txn['shares'])}"
    else:
        raise ValueError(f"unsupported uSmart HK row kind: {kind}")
    seq = txn.get("_id_seq")
    if seq:
        ident = f"{ident}#{seq}"
    return ident



def check_external_tools() -> dict[str, str | None]:
    """Return detected PDF tool versions using the shared secure helpers."""
    result: dict[str, str | None] = {}
    for command, version_args in (("qpdf", ["--version"]), ("mutool", ["-v"])):
        if not shutil.which(command):
            result[command] = None
            continue
        try:
            completed = subprocess.run([command, *version_args], capture_output=True, text=True, timeout=5)
            result[command] = (completed.stdout or completed.stderr).strip().splitlines()[0] or "unknown"
        except (OSError, subprocess.SubprocessError, IndexError):
            result[command] = "unknown"
    return result


_FILL = re.compile(
    r"(?:(?P<market>美股|港股)\s+)?(?P<side>买入|卖出)\s+(?P<qty>[\d,.]+)\s+(?P<ccy>USD|HKD|CNY)\s+"
    r"[\d,.]+\s+(?P<amount>[\d,.]+)\s+(?P<date>\d{4}-\d{2}-\d{2})"
)
_CASH_ROW = re.compile(
    r"^\s*(?P<flag>[A-Za-z0-9\u4e00-\u9fff]+)\s+(?P<ccy>USD|HKD|CNY)\s+"
    r"(?P<amount>-?[\d,]+(?:\.\d+)?)\s+(?P<date>\d{4}-\d{2}-\d{2})(?P<note>.*)$"
)
# Vertical mutool -F text: flag / ccy / amount / date; optional Latin note only
# (must not swallow the next CJK business flag as a "note")
_CASH_STACKED = re.compile(
    r"(?P<flag>[A-Za-z0-9\u4e00-\u9fff]+)\s*\n\s*"
    r"(?P<ccy>USD|HKD|CNY)\s*\n\s*"
    r"(?P<amount>-?[\d,]+(?:\.\d+)?)\s*\n\s*"
    r"(?P<date>\d{4}-\d{2}-\d{2})"
    r"(?:\s*\n\s*(?P<note>[A-Za-z][^\n]*))?"
)


def _decimal(value: str) -> Decimal:
    try:
        return Decimal(value.replace(",", ""))
    except (InvalidOperation, AttributeError) as exc:
        raise ValueError(f"invalid decimal in uSmart HK statement: {value!r}") from exc


def _fmt(value: Decimal) -> str:
    """Format for event asset components; cap long division tails at 18 dp without forcing scale."""
    from decimal import ROUND_HALF_UP
    quantized = Decimal(value)
    exp = quantized.as_tuple().exponent
    if isinstance(exp, int) and exp < -18:
        quantized = quantized.quantize(Decimal("1e-18"), rounding=ROUND_HALF_UP)
        text = format(quantized, "f").rstrip("0").rstrip(".")
        return text or "0"
    return format(quantized, "f")


# Order-group symbol at line start: US 1–5 letters, or HK 5-digit code.
# Must be whole line or followed by " (" — never mid-name scraps like "C)" / "ETF-SPDR".
_GROUP_TICKER_LINE = re.compile(
    # CODE alone, CODE (, or CODE + name (day-margin: "MU 美光科技")
    r"(?m)^\s*(?P<code>[A-Z]{1,5}|0\d{4})(?:\s*\(|\s+\S|\s*$)"
)


def _extract_group_ticker(block: str, fill_start: int) -> str:
    """Security code for one order group: the header symbol before the first fill.

    uSmart puts ``CODE`` or ``CODE (中文名…)`` above 美股/港股 fills. Names wrap across
    lines (``GOOG (谷歌-\\nC)``, ``GLD (…\\nETF-SPDR)``). Structure, not brand blacklists:

    1. Drop page-break footers.
    2. Drop broker boilerplate lines.
    3. Take the **first** line that is exactly a ticker, or ticker + opening ``(``.
       Ticker shape: ``[A-Z]{1,5}`` (US) or ``0\\d{4}`` (HK). No hyphens/digits-in-US.
    """
    lead = block[:fill_start]
    if "\x0c" in lead:
        lead = lead.rsplit("\x0c", 1)[-1]

    cleaned: list[str] = []
    for line in lead.splitlines():
        s = line.strip()
        if not s:
            continue
        # Broker running header/footer (not security header).
        if any(x in s for x in (
            "证监会", "證監會", "usmart", "盈立", "電話", "传真", "傳真",
            "地址", "邮箱", "郵箱", "网址", "網址", "SFC", "证券/编号",
            "买/卖", "成交时间", "交收日期", "是否强平",
        )):
            continue
        cleaned.append(s)

    # Prefer first structural header in the group (symbol introduces the order).
    for line in cleaned:
        match = _GROUP_TICKER_LINE.match(line)
        if match:
            return match.group("code")

    # Layout fixtures may put symbol on same visual row as fill; scan lead as one blob.
    blob = " ".join(cleaned)
    loose = re.search(
        r"(?<![A-Z0-9])(?P<code>[A-Z]{1,5}|0\d{4})(?=\s*\(|\s+(?:美股|港股)\b)",
        blob,
    )
    if loose:
        return loose.group("code")

    raise ValueError(f"trade group missing ticker near {lead[-120:]!r}")


def _parse_trades(rendered: str, *, profile: str = "margin") -> list[dict[str, Any]]:
    start = rendered.find("交易明细")
    # Day-margin inserts 开仓记录 between trades and 持仓明细.
    end = rendered.find("开仓记录", start)
    if end < 0:
        end = rendered.find("持仓明细", start)
    if start < 0 or end < 0:
        raise ValueError("uSmart HK statement is missing 交易明细 or 持仓明细")
    section = rendered[start:end]
    # mutool -F text: 变动金额合计 and amount often on consecutive lines.
    groups = re.split(r"变动金额合计\s*\n?\s*(-?[\d,]+(?:\.\d+)?)", section)
    rows: list[dict[str, Any]] = []
    for index in range(1, len(groups), 2):
        block, net_text = groups[index - 1], groups[index]
        fills = list(_FILL.finditer(block))
        if not fills:
            continue
        first = fills[0]
        code = _extract_group_ticker(block, first.start())
        market = first.group("market") or ("" if profile != "day" else "")
        # Day-margin rows omit 美股/港股; infer from trade ccy.
        ccy_for_norm = first.group("ccy")
        ticker = _normalize_equity_ticker(
            code,
            market=market or ("美股" if ccy_for_norm == "USD" else "港股" if ccy_for_norm == "HKD" else ""),
            ccy=ccy_for_norm,
        )
        sides = {m.group("side") for m in fills}
        ccys = {m.group("ccy") for m in fills}
        if len(sides) != 1 or len(ccys) != 1:
            raise ValueError(f"mixed trade group near {first.group(0)!r}")
        # US statements use 交易金额; HK often uses 交易金额合计.
        gross_match = re.search(r"交易金额(?:合计)?\s*\n?\s*([\d,]+(?:\.\d+)?)", block)
        if not gross_match:
            raise ValueError(f"trade group missing 交易金额 near {first.group(0)!r}")
        gross = _decimal(gross_match.group(1))
        net = _decimal(net_text)
        side = "BUY" if first.group("side") == "买入" else "SELL"
        commission = abs(net) - gross if side == "BUY" else gross - abs(net)
        if commission < Decimal("-0.02"):
            raise ValueError(
                f"fee imbalance for {ticker}: side={side} gross={gross} net={net}"
            )
        if commission < 0:
            commission = Decimal("0")
        rows.append({
            "kind": "trade", "date": first.group("date"), "ticker": ticker, "side": side,
            "qty": sum((_decimal(m.group("qty")) for m in fills), Decimal("0")),
            "gross": gross, "net": net, "commission": commission,
            "ccy": next(iter(ccys)), "note": "uSmart HK order group",
        })
    # Empty is valid (e.g. day-margin month with 资金出入 only / 暂无数据).
    return rows


def _parse_holdings(rendered: str, period: str, month_end: str) -> list[dict[str, Any]]:
    """Parse 持仓明细: layout (one-line) or mutool text (stacked fields)."""
    start = rendered.find("持仓明细")
    if start < 0:
        return []
    end = rendered.find("资金出入", start)
    if end < 0:
        end = rendered.find("市值汇总", start)
    if end < 0:
        end = len(rendered)
    section = rendered[start:end]
    if "暂无数据" in section and not re.search(r"\b(?:USD|HKD|CNY)\b", section):
        return []

    rows: list[dict[str, Any]] = []
    # Layout / fixture: CODE (name)  CCY  shares ...
    # Horizontal only: do not let \s match newlines (mutool stacks fields vertically).
    pattern = re.compile(
        r"(?m)^\s*(?P<code>[A-Z]{1,5}|0\d{4})(?:[^\S\n]*\([^\n]*\))?[^\S\n]+"
        r"(?P<ccy>USD|HKD|CNY)[^\S\n]+(?P<shares>[\d,]+(?:\.\d+)?)"
    )
    for m in pattern.finditer(section):
        shares = _decimal(m.group("shares"))
        ticker = _normalize_equity_ticker(m.group("code"), ccy=m.group("ccy"))
        rows.append({
            "kind": "checkin_position", "date": month_end, "period": period,
            "ticker": ticker, "ccy": m.group("ccy"), "shares": shares,
            "note": "period-end holdings checkin",
        })
    if rows:
        return rows

    # Vertical mutool: CODE [name lines...] \n CCY \n shares \n unsettled \n price ...
    lines = [ln.strip() for ln in section.splitlines() if ln.strip()]
    i = 0
    header_noise = {
        "持仓明细", "证券", "币种", "持有数量", "未交收数量", "收市价", "市值",
        "抵押比率", "抵押市值", "市值汇总", "抵押市值汇总",
    }
    code_re = re.compile(r"^(?P<code>[A-Z]{1,5}|0\d{4})\b")
    while i < len(lines):
        line = lines[i]
        if line in header_noise or line in {"HKD", "USD", "CNY"}:
            i += 1
            continue
        m = code_re.match(line)
        if not m:
            i += 1
            continue
        code = m.group("code")
        ccy = None
        shares_s = None
        j = i + 1
        while j < len(lines) and j < i + 12:
            if lines[j] in {"USD", "HKD", "CNY"}:
                ccy = lines[j]
                if j + 1 < len(lines) and re.match(r"^[\d,]+(?:\.\d+)?$", lines[j + 1]):
                    shares_s = lines[j + 1]
                break
            j += 1
        if ccy and shares_s is not None:
            ticker = _normalize_equity_ticker(code, ccy=ccy)
            rows.append({
                "kind": "checkin_position", "date": month_end, "period": period,
                "ticker": ticker, "ccy": ccy, "shares": _decimal(shares_s),
                "note": "period-end holdings checkin",
            })
            i = j + 2
        else:
            i += 1
    return rows


def _closed_position_checkins(
    trades: list[dict[str, Any]],
    held_tickers: set[str],
    period: str,
    month_end: str,
) -> list[dict[str, Any]]:
    """Emit 0-share CHECKIN for traded symbols missing from 持仓明细.

    Statement holdings table is end-of-period only. A name that traded during
    the month but does not appear there is flat at month-end (cleared).
    """
    closed: dict[str, str] = {}
    for trade in trades:
        ticker = str(trade.get("ticker") or "").strip()
        if not ticker:
            continue
        key = ticker.lower()
        if key in held_tickers:
            continue
        closed[ticker] = str(trade.get("ccy") or "USD").upper()
    return [
        {
            "kind": "checkin_position",
            "date": month_end,
            "period": period,
            "ticker": ticker,
            "ccy": ccy,
            "shares": Decimal("0"),
            "note": "period-end flat (absent from 持仓明细)",
        }
        for ticker, ccy in sorted(closed.items(), key=lambda item: item[0].lower())
    ]


def _parse_cash_checkins(
    rendered: str, period: str, month_end: str, *, profile: str = "margin",
) -> list[dict[str, Any]]:
    """Parse ending cash balances (HKD / USD / CNY columns).

    Supports both:
    - layout extract: one line ``期末账户结余  2,021.09  期末账户结余  4,750.17  …``
    - mutool ``-F text``: label and value on alternate lines (3 markets).
    """
    values: list[str] = []
    # Same-line multi-column form (pdftotext -layout / fixtures)
    match = re.search(r"期末账户结余(?P<line>[^\n]+)", rendered)
    if match and re.search(r"--|-?[\d,]+(?:\.\d+)?", match.group("line")):
        values = re.findall(r"--|-?[\d,]+(?:\.\d+)?", match.group("line"))
    if len(values) < 3:
        # Vertical form: each market is "期末账户结余\n<amount>"
        stacked = re.findall(
            r"期末账户结余\s*\n\s*(--|-?[\d,]+(?:\.\d+)?)",
            rendered,
        )
        if len(stacked) >= 3:
            values = stacked[:3]
    if len(values) < 3 and profile == "day":
        # Day product has no 期末账户结余.
        # Prefer 期末净资产 (EOD equity proxy / cash when flat stock);
        # fall back to 变动金额汇总 only if 期末净资产 missing.
        net_assets = re.findall(
            r"期末净资产\s*\n\s*(--|-?[\d,]+(?:\.\d+)?)",
            rendered,
        )
        if len(net_assets) >= 2:
            values = [net_assets[0], net_assets[1], "--"]
        else:
            stacked = re.findall(
                r"变动金额汇总\s*\n\s*(--|-?[\d,]+(?:\.\d+)?)",
                rendered,
            )
            if len(stacked) >= 2:
                values = [stacked[0], stacked[1], "--"]
    if len(values) < 2:
        raise ValueError("uSmart HK statement is missing 期末账户结余/变动金额汇总")
    # Pad to 3 markets when day statement only has HKD/USD columns.
    while len(values) < 3:
        values.append("--")
    rows = []
    for ccy, value in zip(("HKD", "USD", "CNY"), values[:3]):
        if value != "--":
            rows.append({
                "kind": "checkin_cash", "date": month_end, "period": period,
                "ccy": ccy, "amount": _decimal(value), "note": "period-end cash checkin",
            })
    return rows


def _is_trade_mirror_flag(normalized: str) -> bool:
    """成交镜像行：买卖/沽空股票及手续费（已由交易明细记账）。"""
    n = (
        normalized.replace("續", "续").replace("手續", "手续")
        .replace("（", "(").replace("）", ")")
    )
    if "手续费" in n and ("股票" in n or "沽空" in n):
        return True
    if "股票" in n and (n.startswith("买") or n.startswith("卖") or "沽空" in n):
        return True
    return False


def _parse_cash_movements(rendered: str, *, profile: str = "margin") -> tuple[list[dict[str, Any]], int]:
    start = rendered.find("资金出入")
    end = rendered.find("证券提存", start)
    if end < 0:
        end = rendered.find("重要提示", start)
    if end < 0:
        end = len(rendered)
    if start < 0:
        raise ValueError("uSmart HK statement is missing 资金出入")
    # Margin statements still expect 证券提存; day-margin may omit it.
    if profile == "margin" and "证券提存" not in rendered[start:end + 20] and "证券提存" not in rendered[start:]:
        # tolerate if later sections missing but 资金出入 present
        pass
    section = rendered[start:end]
    # Join known multi-line flags broken by PDF extract (e.g. 手续费\n（综）).
    section = re.sub(r"手续费\s*\n\s*[（(]综[）)]", "手续费(综)", section)
    section = re.sub(r"沽空卖出股票\s*\n\s*[（(]综[）)]", "沽空卖出股票(综)", section)
    cash_rows: list[dict[str, Any]] = []

    fx_legs: list[dict[str, Any]] = []
    ignored_mirrors = 0

    def _consume(flag: str, ccy: str, amount: str, date: str, note: str) -> None:
        nonlocal ignored_mirrors
        normalized = normalize_cjk(flag)
        row = {
            "kind": "cash", "date": date, "flag": normalized, "flag_norm": normalized,
            "ccy": ccy, "amount": _decimal(amount), "note": (note or "").strip() or normalized,
        }
        if normalized == "换汇":
            fx_legs.append(row)
        elif _is_trade_mirror_flag(normalized):
            ignored_mirrors += 1
        elif normalized in {
            "IPO认购退款", "IPO认购扣款", "IPO认购手续费", "出金", "出金退款", "融资利息",
            "转入到日内融账户", "入金", "提取", "转出", "转入",
            "从保证金账户转入", "转入到保证金账户", "从日内融账户转出", "从日内融账户转入",
            "优惠券", "红利入账", "美股股息税", "股息代收费", "红利税费", "股息税", "资金存",
            "融券罚息转出", "融券利息", "罚息转出", "EDDA入金", "EDDA出金",
            "平台费返还", "佣金返还", "手续费返还",
        } or normalized.endswith("入金") or normalized.endswith("出金"):
            cash_rows.append(row)
        elif (
            # PDF sometimes splits fee labels (…活动费 → 动费); skip fee fragments.
            normalized.endswith("费")
            and "股票" not in normalized
            and "沽空" not in normalized
            and len(normalized) <= 6
        ):
            ignored_mirrors += 1
        else:
            raise ValueError(f"unknown cash flag {normalized!r}")

    # Prefer one-line rows when present (layout fixtures); else stacked mutool text.
    line_hits = 0
    for line in section.splitlines():
        match = _CASH_ROW.match(line)
        if not match:
            continue
        line_hits += 1
        _consume(
            match.group("flag"), match.group("ccy"), match.group("amount"),
            match.group("date"), match.group("note") or "",
        )
    if line_hits == 0:
        for match in _CASH_STACKED.finditer(section):
            note = match.group("note") or ""
            # Skip footer/header junk that looks stacked but is not a movement
            if match.group("flag") in {"币种", "业务标志", "金额", "交易时间", "备注"}:
                continue
            _consume(
                match.group("flag"), match.group("ccy"), match.group("amount"),
                match.group("date"), note,
            )

    while fx_legs:
        leg = fx_legs.pop(0)
        candidates = [
            other for other in fx_legs
            if other["ccy"] != leg["ccy"]
            and other["amount"] * leg["amount"] < 0
            and abs(
                (__import__("datetime").date.fromisoformat(other["date"])
                 - __import__("datetime").date.fromisoformat(leg["date"])).days
            ) <= 3
        ]
        if not candidates:
            raise ValueError(
                f"无法配对换汇流水：{leg['date']} {leg['ccy']} {_fmt(leg['amount'])}"
            )

        def _fx_rate_score(other: dict[str, Any]) -> Decimal:
            """Lower is better; prefer HKD/USD ~7.8 and closer dates."""
            from datetime import date as _date
            a, b = abs(leg["amount"]), abs(other["amount"])
            if a == 0 or b == 0:
                return Decimal("999")
            # Express as HKD per USD when possible.
            if leg["ccy"] == "HKD" and other["ccy"] == "USD":
                rate = a / b
            elif leg["ccy"] == "USD" and other["ccy"] == "HKD":
                rate = b / a
            else:
                rate = max(a, b) / min(a, b)
            day_pen = abs(
                _date.fromisoformat(other["date"]) - _date.fromisoformat(leg["date"])
            ).days
            return abs(rate - Decimal("7.8")) + Decimal(day_pen) * Decimal("0.05")

        other = min(candidates, key=_fx_rate_score)
        fx_legs.remove(other)
        out, incoming = (leg, other) if leg["amount"] < 0 else (other, leg)
        cash_rows.append({
            "kind": "fx", "date": min(leg["date"], other["date"]),
            "from_date": out["date"], "from_ccy": out["ccy"], "from_amount": abs(out["amount"]),
            "to_date": incoming["date"], "to_ccy": incoming["ccy"], "to_amount": abs(incoming["amount"]),
            "ccy": out["ccy"], "note": "换汇",
        })
    return cash_rows, ignored_mirrors

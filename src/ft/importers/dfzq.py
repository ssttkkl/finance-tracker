"""东方证券 PDF 对账单文本解析器

解析 qpdf 解密 + mutool draw -F text 后的东方证券 PDF 文本。

Required external tools:
- qpdf (version 10.0.0+): PDF decryption
- mutool (version 1.20.0+): PDF text extraction
"""

import re
import shutil
import subprocess
from decimal import Decimal, InvalidOperation
from typing import Any

ACTION_MAP = {
    "证券买入": "BUY", "证券卖出": "SELL",
    "银行转证券": "DEPOSIT", "证券转银行": "WITHDRAW",
    "OTC资金划入": "DEPOSIT", "OTC资金划出": "WITHDRAW",
    "融券回购": "BUY", "融券购回": "SELL",
    "红利入账": "DIVIDEND", "红股入账": "DIVIDEND",
    "股息红利差异扣税": "WITHDRAW", "利息归本": "DEPOSIT",
}

_ZERO_SHARES_PRICE = {"DEPOSIT", "WITHDRAW", "DIVIDEND"}
REPO_TICKER = "204001"


def _ticker_suffix(code: str) -> str:
    if code == REPO_TICKER:
        return ""
    if code.startswith("85") or code.startswith("007"):
        return ".otc"
    if code and code[0] in ("0", "1", "2"):
        return ".sz"
    if code and code[0] in ("5", "6"):
        return ".sh"
    return ""


def _is_page_marker(line: str) -> bool:
    return bool(re.match(r"^第\d+页，共\d+页", line)) or line == "\f"


def _is_summary_section(line: str) -> bool:
    return any(kw in line for kw in ["股票资料", "汇总", "成交汇总", "证券持有", "市值"])


_COLUMN_HEADERS = frozenset([
    "发生日期", "买卖类别", "证券代码", "证券名称",
    "成交数量", "成交价格", "总发生金额", "手续费",
    "印花税", "过户费", "资金余额",
])


def _is_numeric(s: str) -> bool:
    """Check if a string is purely numeric (integer or decimal)."""
    try:
        Decimal(s)
        return True
    except (InvalidOperation, ValueError):
        return False


def check_external_tools() -> dict[str, str | None]:
    """Check availability and versions of required external tools.

    Returns:
        Dictionary with tool names as keys and version strings as values.
        Value is None if tool is not found.
    """
    tools = {}

    # Check qpdf
    qpdf_path = shutil.which("qpdf")
    if qpdf_path:
        try:
            result = subprocess.run(
                ["qpdf", "--version"],
                capture_output=True,
                text=True,
                timeout=5
            )
            # Extract version from "qpdf version X.Y.Z"
            version_line = result.stdout.strip().split("\n")[0]
            version = version_line.split()[-1] if "version" in version_line else "unknown"
            tools["qpdf"] = version
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError, IndexError):
            tools["qpdf"] = "unknown"
    else:
        tools["qpdf"] = None

    # Check mutool
    mutool_path = shutil.which("mutool")
    if mutool_path:
        try:
            result = subprocess.run(
                ["mutool", "version"],
                capture_output=True,
                text=True,
                timeout=5
            )
            # Extract version from "mutool version X.Y.Z"
            version = result.stdout.strip().split()[-1] if result.stdout else "unknown"
            tools["mutool"] = version
        except (subprocess.TimeoutExpired, subprocess.CalledProcessError, IndexError):
            tools["mutool"] = "unknown"
    else:
        tools["mutool"] = None

    return tools


def parse_dfzq_text(lines: list[str]) -> list[dict[str, Any]]:
    # 1. 定位资金流水明细段
    start_idx = None
    for i, line in enumerate(lines):
        if "资金流水明细" in line:
            start_idx = i + 1
            break
    if start_idx is None:
        return []

    # 2. 清理数据行
    data: list[str] = []
    in_summary = False
    for line in lines[start_idx:]:
        s = line.strip()
        if _is_page_marker(s):
            in_summary = False
            continue
        if _is_summary_section(s):
            in_summary = True
            continue
        if in_summary:
            continue
        if "资金流水明细" in s or s in _COLUMN_HEADERS or not s:
            continue
        data.append(s)

    # 3. 按行号索引日期位置
    date_indices = [i for i, s in enumerate(data) if s.isdigit() and len(s) == 8]

    # 4. 解析每笔交易（从日期行到下一个日期行之间）
    txns: list[dict[str, Any]] = []

    for idx, di in enumerate(date_indices):
        date_str = data[di]
        end = date_indices[idx + 1] if idx + 1 < len(date_indices) else len(data)

        # 一个交易块至少需要 3 行（日期+类别+代码）
        block = data[di:end]
        if len(block) < 3:
            continue

        action_raw = block[1]

        # ---- 红股入账：仅 6 字段 ----
        if action_raw == "红股入账":
            if len(block) < 6:
                continue
            txns.append(_make_txn(
                date_str, action_raw,
                ticker=block[2], name=block[3],
                shares=Decimal(block[4]), price=Decimal(block[5]),
                total_amount=Decimal("0"), fee=Decimal("0"),
                stamp_tax=Decimal("0"), transfer_fee=Decimal("0"),
                balance=Decimal("0"),
            ))
            continue

        # 判断是否有"证券名称"列
        # 标准格式: date, action, ticker, name, shares, price, total_amount, fee, [stamp, transfer,] balance
        # 无名称格式: date, action, ticker, price, shares, total_amount, fee, [stamp, transfer,] balance
        # 名称列如果是纯数字（如 "0.0000"）说明是 DEPOSIT/WITHDRAW 无名称
        has_name_col = not _is_numeric(block[3]) if len(block) > 3 else False

        if not has_name_col:
            # ---- 无名称列（DEPOSIT / WITHDRAW / OTC 等）----
            # 标准: date, action, ticker, name, shares, price, total_amount, fee, [stamp, transfer,] balance
            # OTC格式（缺1列名称）:     date, action, ticker, shares, price, total_amount, fee, [stamp, transfer,] balance
            # 银行转账格式（缺2列名称+股数）: date, action, ticker, price, total_amount, fee, [stamp, transfer,] balance
            # 判断：block[3]是小数（含.）→ 银行转账格式，整数 → OTC格式
            if '.' in block[3]:
                # 银行转账格式：缺名称+成交数量
                if len(block) < 7:
                    continue
                ticker = block[2]
                name = ""
                price = Decimal(block[3])
                ta_str = block[4]
                if ta_str == "--":
                    continue
                total_amount = Decimal(ta_str)
                fee = Decimal(block[5])
                if len(block) >= 8:
                    stamp_tax = Decimal(block[6])
                    transfer_fee = Decimal(block[7])
                    balance = Decimal(block[8]) if len(block) >= 9 else Decimal("0")
                else:
                    stamp_tax = Decimal("0")
                    transfer_fee = Decimal("0")
                    balance = Decimal(block[6])
                shares = Decimal("0")
            else:
                # OTC格式 / 纯整数 → 缺名称
                if len(block) < 8:
                    continue
                ticker = block[2]
                name = ""
                shares = Decimal(block[3])
                price = Decimal(block[4])
                ta_str = block[5]
                if ta_str == "--":
                    continue
                total_amount = Decimal(ta_str)
                fee = Decimal(block[6])
                if len(block) >= 9:
                    stamp_tax = Decimal(block[7])
                    transfer_fee = Decimal(block[8])
                    balance = Decimal(block[9]) if len(block) >= 10 else Decimal("0")
                else:
                    stamp_tax = Decimal("0")
                    transfer_fee = Decimal("0")
                    balance = Decimal(block[7])
        else:
            # ---- 标准交易（含名称列）至少 9 字段 ----
            if len(block) < 9:
                continue
            ticker = block[2]
            name = block[3]
            shares = Decimal(block[4])
            price = Decimal(block[5])

            # 处理 -- 总金额
            ta_str = block[6]
            if ta_str == "--":
                continue
            total_amount = Decimal(ta_str)
            fee = Decimal(block[7])

            # 11 字段（含印花税+过户费）或 9 字段
            if len(block) >= 11:
                stamp_tax = Decimal(block[8])
                transfer_fee = Decimal(block[9])
                balance = Decimal(block[10])
            else:
                stamp_tax = Decimal("0")
                transfer_fee = Decimal("0")
                balance = Decimal(block[8])

        txns.append(_make_txn(
            date_str, action_raw,
            ticker=ticker, name=name,
            shares=shares, price=price,
            total_amount=total_amount, fee=fee,
            stamp_tax=stamp_tax, transfer_fee=transfer_fee,
            balance=balance,
        ))

    # 5. 排序
    txns.sort(key=lambda t: t["date"])

    # 6. CHECKIN from statement summary (cash + security positions with cost)
    holdings = _parse_holdings_summary(lines)
    cash_balance = _parse_cash_balance(lines)
    checkin_date = txns[-1]["date"] if txns else None
    if checkin_date is None and (holdings or cash_balance is not None):
        # No flow rows but summary exists — still emit checkins with print date fallback
        checkin_date = _parse_print_date(lines) or "1970-01-01 00:00:00"

    if checkin_date:
        cash = cash_balance
        cash_note = "statement cash balance"
        if cash is None and txns:
            # Prefer last cash-affecting flow balance (skip pure stock dividend / empty)
            for t in reversed(txns):
                if t["action"] in {"BUY", "SELL", "DEPOSIT", "WITHDRAW"}:
                    cash = t["balance"]
                    cash_note = "fallback last trade balance"
                    break
                if t["action"] == "DIVIDEND" and not t.get("ticker"):
                    cash = t["balance"]
                    cash_note = "fallback last cash dividend balance"
                    break
            if cash is None:
                cash = txns[-1]["balance"]
                cash_note = "fallback last balance"
        if cash is not None:
            txns.append({
                "date": checkin_date,
                "action": "CHECKIN",
                "ticker": "", "name": "",
                "shares": Decimal("0"), "price": Decimal("0"),
                "amount": cash,
                "total_amount": cash,
                "fee": Decimal("0"), "stamp_tax": Decimal("0"),
                "transfer_fee": Decimal("0"), "balance": cash, "note": cash_note,
            })
        for h in holdings:
            txns.append({
                "date": checkin_date,
                "action": "CHECKIN",
                "ticker": h["ticker"], "name": h.get("name", ""),
                "shares": h["shares"], "price": h["cost_price"],
                "amount": h["shares"] * h["cost_price"],
                "total_amount": h["shares"] * h["cost_price"],
                "fee": Decimal("0"), "stamp_tax": Decimal("0"),
                "transfer_fee": Decimal("0"), "balance": Decimal("0"),
                "note": f"statement holding cost; market={h.get('market_price')}",
            })
    elif txns:
        last = txns[-1]
        cash = last["balance"]
        for t in reversed(txns):
            if t["action"] in {"BUY", "SELL", "DEPOSIT", "WITHDRAW"} or (
                t["action"] == "DIVIDEND" and not t.get("ticker")
            ):
                cash = t["balance"]
                break
        txns.append({
            "date": last["date"],
            "action": "CHECKIN",
            "ticker": "", "name": "",
            "shares": Decimal("0"), "price": Decimal("0"),
            "amount": cash,
            "total_amount": cash,
            "fee": Decimal("0"), "stamp_tax": Decimal("0"),
            "transfer_fee": Decimal("0"), "balance": Decimal("0"), "note": "fallback last balance",
        })

    return txns


def _parse_print_date(lines: list[str]) -> str | None:
    for line in lines[:20]:
        s = line.strip()
        # 打印日期：2026-06-13 01:26:00
        m = re.search(r"打印日期[:：]\s*(\d{4}-\d{2}-\d{2})", s)
        if m:
            return f"{m.group(1)} 00:00:00"
    return None


def _parse_cash_balance(lines: list[str]) -> Decimal | None:
    """Parse 资金余额(RMB) from statement header."""
    for i, line in enumerate(lines[:80]):
        if "资金余额(RMB)" in line or "资金余额（RMB）" in line:
            # value may be on same line or next non-empty line
            m = re.search(r"资金余额\(?RMB\)?[:：]?\s*([0-9,]+\.\d+)", line)
            if m:
                return Decimal(m.group(1).replace(",", ""))
            for j in range(i + 1, min(i + 4, len(lines))):
                s = lines[j].strip().replace(",", "")
                if not s:
                    continue
                try:
                    return Decimal(s)
                except InvalidOperation:
                    break
    return None


def _parse_holdings_summary(lines: list[str]) -> list[dict[str, Any]]:
    """Parse 汇总股票资料 holdings table from DFZQ statement header.

    mutool text layout is one field per line:
      汇总股票资料
      交易市场 / 证券代码 / 证券名称 / 持仓数量 / 市价 / 成本价 / 证券市值
      深市A股 / 159740 / 恒生科技 / 95200 / 0.587 / 0.718 / 55882.40
    """
    start = None
    end = None
    for i, line in enumerate(lines):
        if "汇总股票资料" in line:
            start = i
        if start is not None and "资金流水明细" in line:
            end = i
            break
    if start is None:
        return []
    if end is None:
        end = min(start + 80, len(lines))

    section = [ln.strip() for ln in lines[start:end] if ln.strip()]
    # Drop section title and column headers
    headers = {
        "汇总股票资料", "交易市场", "证券代码", "证券名称",
        "持仓数量", "市价", "成本价", "证券市值",
    }
    market_labels = {"深市A股", "沪市A股", "沪市B股", "深市B股", "港股", "美股", "场外"}
    values = [v for v in section if v not in headers]

    holdings: list[dict[str, Any]] = []
    i = 0
    while i < len(values):
        # Optional leading market label
        if values[i] in market_labels:
            i += 1
            if i >= len(values):
                break
        if i + 5 >= len(values):
            break
        code, name, qty_s, mkt_s, cost_s, mv_s = values[i:i + 6]
        if not re.fullmatch(r"\d{5,6}", code):
            i += 1
            continue
        try:
            shares = Decimal(qty_s.replace(",", ""))
            market_price = Decimal(mkt_s.replace(",", ""))
            cost_price = Decimal(cost_s.replace(",", ""))
        except InvalidOperation:
            i += 1
            continue
        if shares != 0:
            holdings.append({
                "ticker": code + _ticker_suffix(code),
                "name": name,
                "shares": shares,
                "market_price": market_price,
                "cost_price": cost_price,
            })
        i += 6
    return holdings


def _make_txn(date_str, action_raw, **kw) -> dict[str, Any]:
    action = ACTION_MAP.get(action_raw, action_raw)

    ticker = kw["ticker"]
    name = kw.get("name", "")
    if action_raw in ("融券回购", "融券购回"):
        ticker = REPO_TICKER
    suffix = _ticker_suffix(ticker)
    full_ticker = ticker + suffix

    shares = kw["shares"]
    price = kw["price"]
    # 送股/转增（仅"红股入账"）：保留 ticker；"红利入账"（现金分红）/DEPOSIT/WITHDRAW：清空
    # 注意：红利入账 PDF 中 shares 是分红的股数（非额外送股），不能用 shares>0 判断
    is_stock_dividend = action_raw == "红股入账" and shares > 0 and full_ticker
    if (action in _ZERO_SHARES_PRICE and not is_stock_dividend) or action in ("DEPOSIT", "WITHDRAW"):
        ticker = ""
        full_ticker = ""
    # 现金红利：shares/price 清零；送股/转增：保留
    if action in _ZERO_SHARES_PRICE and not is_stock_dividend:
        shares = Decimal("0")
        price = Decimal("0")

    total_amount = kw["total_amount"]
    fee = kw["fee"]
    # DFZQ「总发生金额」已是资金变动全额（含手续费/印花税/过户费等）。
    # 不再做 total_amount+fee；fee 仅作审计字段。
    amount = total_amount

    note_parts = []
    if fee:
        note_parts.append(f"手续费{fee:.2f}")
    if kw.get("stamp_tax"):
        note_parts.append(f"印花税{kw['stamp_tax']:.2f}")
    if kw.get("transfer_fee"):
        note_parts.append(f"过户费{kw['transfer_fee']:.2f}")
    note = " ".join(note_parts)

    date_fmt = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:8]} 00:00:00"

    return {
        "date": date_fmt,
        "action": action,
        "ticker": full_ticker,
        "name": name,
        "shares": shares,
        "price": price,
        "amount": amount,
        "total_amount": total_amount,
        "fee": fee,
        "stamp_tax": kw.get("stamp_tax", Decimal("0")),
        "transfer_fee": kw.get("transfer_fee", Decimal("0")),
        "balance": kw.get("balance", Decimal("0")),
        "note": note,
    }


def _split_commission(
    amount_abs: Decimal, fee_abs: Decimal, *, side: str
) -> tuple[Decimal, Decimal]:
    """Split statement net cash vs 手续费 for projection.

    DFZQ ``总发生金额`` is the **net** cash impact (already after 手续费/印花税/过户费).
    Projection applies ``from/to_amount ± commission`` when commission_asset is the cash leg.

    Returns ``(cash_leg, commission)`` such that total cash impact still equals
    ``amount_abs``:

    - BUY:  cash out = from_amount + commission  → from_amount = net - fee, commission = fee
    - SELL: cash in  = to_amount - commission    → to_amount = net + fee, commission = fee

    If fee is missing or cannot be peeled from BUY net (fee >= net), keep net and
    commission=0 (fees stay embedded in the cash leg / note).
    """
    if fee_abs <= 0:
        return amount_abs, Decimal("0")
    if side == "BUY":
        if fee_abs >= amount_abs:
            return amount_abs, Decimal("0")
        return amount_abs - fee_abs, fee_abs
    if side == "SELL":
        return amount_abs + fee_abs, fee_abs
    return amount_abs, Decimal("0")


def construct_source_identity(txn: dict[str, Any]) -> str:
    """Construct unique source_identity for DFZQ transaction.

    Format: dfzq:{date}:{ticker}:{action}:{amount}:{balance}

    Constitution I: Ensures idempotency via business key composite.
    """
    date = txn["date"][:10].replace("-", "")  # YYYYMMDD
    ticker = txn.get("ticker") or "cash"
    action = txn["action"]
    amount = format(txn["amount"], "f")
    balance = format(txn["balance"], "f")

    return f"dfzq:{date}:{ticker}:{action}:{amount}:{balance}"


def map_dfzq_to_investment_event(txn: dict[str, Any], account_name: str, currency: str = "CNY") -> dict[str, Any]:
    """Map DFZQ transaction to unified investment event schema.

    Converts DFZQ actions (BUY/SELL/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN) to
    unified event format (swap/deposit/withdraw/dividend/checkin).

    Constitution II: Follows data-model.md event schema specification.
    """
    action = txn["action"]
    ticker = txn.get("ticker", "")
    cash_ticker = currency.lower()

    # Base event structure
    event = {
        "date": txn["date"],
        "account_name": account_name,
        "currency": currency,
        "note": txn.get("note", ""),
    }

    # DFZQ「总发生金额」(amount) is the full cash delta (net of 手续费/印花税/过户费).
    # Projection: cash changes by from/to_amount ± commission (same asset).
    # Policy: if 手续费 is separable, put it in commission and adjust the cash leg
    # so total cash impact still equals abs(amount); otherwise keep net, commission=0.
    # 印花税/过户费 stay inside the cash leg (not always cleanly invertible).
    amount_abs = abs(txn.get("amount") or Decimal("0"))
    shares_abs = abs(txn.get("shares") or Decimal("0"))
    fee_abs = abs(txn.get("fee") or Decimal("0"))
    cash_leg, commission = _split_commission(amount_abs, fee_abs, side=action)

    if action == "BUY":
        # BUY → SWAP (cash → ticker).
        # cash_leg + commission == amount_abs (total cash out).
        event.update({
            "action": "swap",
            "from_ticker": cash_ticker,
            "from_amount": format(cash_leg, "f"),
            "to_ticker": ticker,
            "to_amount": format(shares_abs, "f"),
            "price": format(abs(txn["price"]), "f"),
            "commission": format(commission, "f"),
            "commission_asset": cash_ticker if commission else "",
        })

    elif action == "SELL":
        # SELL → SWAP (ticker → cash).
        # Projection does to_amount - commission when commission_asset == cash;
        # so to_amount = net + commission, commission = fee → cash in = net.
        event.update({
            "action": "swap",
            "from_ticker": ticker,
            "from_amount": format(shares_abs, "f"),
            "to_ticker": cash_ticker,
            "to_amount": format(cash_leg, "f"),
            "price": format(abs(txn["price"]), "f"),
            "commission": format(commission, "f"),
            "commission_asset": cash_ticker if commission else "",
        })

    elif action == "DEPOSIT":
        event.update({
            "action": "deposit",
            "to_ticker": cash_ticker,
            "to_amount": format(amount_abs, "f"),
            "from_ticker": "",
            "from_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        })

    elif action == "WITHDRAW":
        event.update({
            "action": "withdraw",
            "from_ticker": cash_ticker,
            "from_amount": format(amount_abs, "f"),
            "to_ticker": "",
            "to_amount": "0",
            "price": "1",
            "commission": "0",
            "commission_asset": "",
        })

    elif action == "DIVIDEND":
        # Cash or stock dividend
        if ticker:
            # Stock dividend (送股/转增)
            event.update({
                "action": "dividend",
                "from_ticker": ticker,
                "to_ticker": ticker,
                "to_amount": format(shares_abs, "f"),
                "from_amount": "0",
                "price": format(abs(txn["price"]), "f"),
                "commission": "0",
                "commission_asset": "",
            })
        else:
            # Cash dividend (红利入账)
            event.update({
                "action": "dividend",
                "from_ticker": txn.get("name", ""),
                "to_ticker": cash_ticker,
                "to_amount": format(amount_abs, "f"),
                "from_amount": "0",
                "price": "1",
                "commission": "0",
                "commission_asset": "",
            })

    elif action == "CHECKIN":
        # Balance / position reconciliation.
        # Cash checkin: ticker empty, amount=cash.
        # Position checkin: ticker set, shares=qty, price=avg cost (cost basis).
        if ticker:
            event.update({
                "action": "checkin",
                "from_ticker": "",
                "to_ticker": ticker,
                "to_amount": format(shares_abs, "f"),
                "from_amount": "0",
                "price": format(abs(txn["price"]), "f"),
                "commission": "0",
                "commission_asset": "",
            })
        else:
            event.update({
                "action": "checkin",
                "from_ticker": "",
                "to_ticker": cash_ticker,
                "to_amount": format(amount_abs, "f"),
                "from_amount": "0",
                "price": "1",
                "commission": "0",
                "commission_asset": "",
            })

    else:
        raise ValueError(f"Unsupported DFZQ action: {action}")

    return event

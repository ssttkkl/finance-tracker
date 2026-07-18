"""东方证券 PDF 对账单文本解析器

解析 qpdf 解密 + mutool draw -F text 后的东方证券 PDF 文本。
"""

import re
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

    # 6. CHECKIN
    if txns:
        last = txns[-1]
        txns.append({
            "date": last["date"],
            "action": "CHECKIN",
            "ticker": "", "name": "",
            "shares": Decimal("0"), "price": Decimal("0"),
            "amount": last["balance"],
            "fee": Decimal("0"), "stamp_tax": Decimal("0"),
            "transfer_fee": Decimal("0"), "balance": Decimal("0"), "note": "",
        })

    return txns


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
    amount = total_amount + fee

    note_parts = []
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
        "fee": fee,
        "stamp_tax": kw.get("stamp_tax", Decimal("0")),
        "transfer_fee": kw.get("transfer_fee", Decimal("0")),
        "balance": kw.get("balance", Decimal("0")),
        "note": note,
    }

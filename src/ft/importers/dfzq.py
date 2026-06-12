"""东方证券 PDF 对账单文本解析器

解析 qpdf 解密 + mutool draw -F text 后的东方证券 PDF 文本，
提取资金流水明细段，返回结构化的交易记录列表。

PDF文本结构（每字段占一行）：
    资金流水明细(2024/07/01-2026/06/13)
    20240701           ← 发生日期
    证券买入           ← 买卖类别
    000001             ← 证券代码
    平安银行           ← 证券名称
    1000               ← 成交数量
    11.50              ← 成交价格
    -11505.00          ← 总发生金额
    5.00               ← 手续费
    1.15               ← 印花税
    0.50               ← 过户费
    50000.00           ← 资金余额
    ...
    第1页，共5页       ← 页码标记
    \f                  ← 翻页符

资金流水明细段内的数据按时间倒序排列（最新在前）。
输出按日期升序排序，末尾追加 CHECKIN 行。
"""

import re
from typing import Any

# ── Action 映射 ────────────────────────────────────────────

ACTION_MAP = {
    "证券买入": "BUY",
    "证券卖出": "SELL",
    "银行转证券": "DEPOSIT",
    "OTC资金划出": "WITHDRAW",
    "融券回购": "BUY",
    "融券购回": "SELL",
    "红利入账": "DIVIDEND",
    "红股入账": "INIT",
    "股息红利差异扣税": "WITHDRAW",
}

# 需要清空 shares/price 的 action 类型
_ZERO_SHARES_PRICE = {"DEPOSIT", "WITHDRAW", "DIVIDEND"}

# 融券回购/购回的固定 ticker
REPO_TICKER = "204001"


def _ticker_suffix(code: str) -> str:
    """证券代码后缀映射

    规则:
    - 0/1/2 开头（含 159 ETFs）→ .sz
    - 5/6 开头 → .sh
    - OTC 代码（851890, 007011 等）→ .otc
    - 204001 → 无后缀
    """
    if code == REPO_TICKER:
        return ""
    # OTC 代码（以 85 或 007 开头）
    if code.startswith("85") or code.startswith("007"):
        return ".otc"
    # 深圳市场
    if code and code[0] in ("0", "1", "2"):
        return ".sz"
    # 上海市场
    if code and code[0] in ("5", "6"):
        return ".sh"
    return ""


def _is_page_marker(line: str) -> bool:
    """判断是否为页码标记或翻页符"""
    return bool(re.match(r"^第\d+页，共\d+页", line)) or line == "\f"


def _is_summary_section(line: str) -> bool:
    """判断是否为汇总/股票资料段标记"""
    keywords = ["股票资料", "汇总", "成交汇总", "证券持有", "市值"]
    return any(kw in line for kw in keywords)


def parse_dfzq_text(lines: list[str]) -> list[dict[str, Any]]:
    """解析东方证券 PDF 文本，返回结构化交易记录列表。

    Args:
        lines: PDF 文本行列表（每字段占一行）

    Returns:
        按日期升序排列的交易记录列表，末尾追加 CHECKIN 行。
        每条记录包含: date, action, ticker, name, shares, price,
                     amount, fee, stamp_tax, transfer_fee, balance, note
    """
    # 1. 定位资金流水明细段起点
    start_idx = None
    for i, line in enumerate(lines):
        if "资金流水明细" in line:
            start_idx = i + 1
            break
    if start_idx is None:
        return []

    # 2. 收集有效数据行（跳过页眉、页码、汇总段）
    data_lines: list[str] = []
    in_summary_section = False
    for line in lines[start_idx:]:
        stripped = line.strip()

        # 跳过空行和页码标记
        if _is_page_marker(stripped):
            in_summary_section = False
            continue

        # 遇到汇总段标记，跳过直到下一个日期行
        if _is_summary_section(stripped):
            in_summary_section = True
            continue
        if in_summary_section:
            continue

        # 跳过重复的页眉
        if "资金流水明细" in stripped:
            continue

        data_lines.append(stripped)

    # 3. 解析交易（每条交易 = 11 个连续字段行）
    txns: list[dict[str, Any]] = []
    i = 0
    while i < len(data_lines):
        # 交易必须从 8 位数字日期开始
        cur = data_lines[i]
        if not (cur.isdigit() and len(cur) == 8):
            i += 1
            continue

        # 需要至少 11 行完成一条交易
        if i + 10 >= len(data_lines):
            break

        try:
            date_str = cur
            action_raw = data_lines[i + 1]
            ticker = data_lines[i + 2]
            name = data_lines[i + 3]
            shares = float(data_lines[i + 4])
            price = float(data_lines[i + 5])
            total_amount = float(data_lines[i + 6])
            fee = float(data_lines[i + 7])
            stamp_tax = float(data_lines[i + 8])
            transfer_fee = float(data_lines[i + 9])
            balance = float(data_lines[i + 10])
        except (ValueError, IndexError):
            i += 1
            continue

        # 确定 action
        action = ACTION_MAP.get(action_raw, action_raw)

        # 确定 ticker（含后缀）
        if action_raw == "融券回购" or action_raw == "融券购回":
            ticker = REPO_TICKER
        suffix = _ticker_suffix(ticker)
        full_ticker = ticker + suffix

        # 对 DIVIDEND/WITHDRAW/DEPOSIT 清空 shares 和 price
        # 对 INIT（红股入账）保留 shares, price=0
        if action in _ZERO_SHARES_PRICE:
            shares = 0.0
            price = 0.0
        elif action == "INIT":
            price = 0.0

        # 计算 amount（不含佣金的净额）
        # amount = 总发生金额 + 手续费
        # 买入: total_amount = -(shares * price) - fee → amount = -shares * price
        # 卖出: total_amount = shares * price - fee → amount = shares * price
        amount = total_amount + fee

        # 对非买卖类交易（DEPOSIT/WITHDRAW），amount 直接取 total_amount
        # （它们 shares=0, price=0, fee=0，所以 total_amount + fee = total_amount）

        # 组装 note
        note_parts = []
        if stamp_tax != 0:
            note_parts.append(f"印花税{stamp_tax:.2f}")
        if transfer_fee != 0:
            note_parts.append(f"过户费{transfer_fee:.2f}")
        note = " ".join(note_parts) if note_parts else ""

        # 格式化为日期（stock CSV 需要 HH:MM:SS）
        date_fmt = f"{date_str[:4]}-{date_str[4:6]}-{date_str[6:8]} 00:00:00"

        txns.append({
            "date": date_fmt,
            "action": action,
            "ticker": full_ticker,
            "name": name,
            "shares": shares,
            "price": price,
            "amount": amount,
            "fee": fee,
            "stamp_tax": stamp_tax,
            "transfer_fee": transfer_fee,
            "balance": balance,
            "note": note,
        })

        i += 11

    # 4. 按日期升序排序
    txns.sort(key=lambda t: t["date"])

    # 5. 追加 CHECKIN 行（取最后一笔交易的资金余额）
    if txns:
        last = txns[-1]
        txns.append({
            "date": last["date"],
            "action": "CHECKIN",
            "ticker": "",
            "name": "",
            "shares": 0,
            "price": 0,
            "amount": last["balance"],
            "fee": 0,
            "stamp_tax": 0,
            "transfer_fee": 0,
            "balance": 0,
            "note": "",
        })

    return txns

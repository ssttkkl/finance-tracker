"""建行储蓄卡 XLS 转换器"""
import xlrd
import re
from datetime import datetime, timedelta
from ft.convert import _normalize_counterparty


def _extract_ccb_counterparty(location: str) -> str | None:
    """从建行交易地点列提取纯 counterparty

    模式：
      财付通-微信支付-商户名  →  商户名
      支付宝-淘宝-商户名      →  商户名
      美团支付-商户名         →  商户名
      PAYPAL_XXX              →  PAYPAL_XXX
      直接商户名               →  直接商户名
      ***                     →  None（回退对方户名）
    """
    if not location or location == "***":
        return None

    # 支付源前缀映射（按长度降序匹配）
    PAYMENT_PREFIXES = [
        ("财付通-", ["微信支付-", "微信转账"]),
        ("支付宝-", ["淘宝-", "支付宝外部商户-", "支付宝-转账-"]),
        ("美团支付-", []),
    ]

    for prefix, subs in PAYMENT_PREFIXES:
        if location.startswith(prefix):
            rest = location[len(prefix):]
            for sub in subs:
                if rest.startswith(sub):
                    rest = rest[len(sub):]
                    break
            return rest

    return location


def _infer_ccb_payment_source(location: str, card_last4: str = "") -> str:
    """从交易地点推断支付源"""
    if location.startswith("财付通-"):
        return "微信支付"
    if location.startswith("支付宝-"):
        return "支付宝"
    if location.startswith("美团支付-"):
        return "美团支付"
    if "PAYPAL" in location.upper():
        return "PayPal"
    suffix = f"({card_last4})" if card_last4 else ""
    return f"建行储蓄卡{suffix}"


def read_ccb_debit(path: str):
    """解析建行个人活期账户交易明细 XLS，返回 (records, tracking_pairs)

    tracking_pairs 始终为空 — 退款配对由 convert.py 的 _pair_refunds 统一处理。
    """
    wb = xlrd.open_workbook(path)
    sh = wb.sheet_by_index(0)

    # 提取卡号（第 2 行，row index 1）
    card = ""
    for c in range(sh.ncols):
        val = str(sh.cell_value(1, c))
        m = re.search(r"卡号/账号[：:](\d+)", val)
        if m:
            card = m.group(1)
            break
    card_last4 = card[-4:] if card else ""

    # 币别映射
    cur_map = {"人民币元": "CNY", "人民币": "CNY", "美元": "USD", "港币": "HKD", "日元": "JPY"}

    records = []

    # 数据从第 5 行开始（row index 4），是 Excel 的第 5 行（前面是标题/卡号/合计/表头）
    for ri in range(4, sh.nrows):
        row_vals = [sh.cell_value(ri, c) for c in range(sh.ncols)]

        # 跳过空行
        if not row_vals or not row_vals[0]:
            continue

        # 确定数据起始：第 1 列是序号（纯数字字符串如 '1'/'2'）
        seq = str(int(float(str(row_vals[0])))) if row_vals[0] else ""
        if not seq:
            continue

        summary = str(row_vals[1] or "").strip()
        cur_str = str(row_vals[2] or "").strip()
        currency = cur_map.get(cur_str, "CNY")

        # 日期 YYYYMMDD → YYYY-MM-DD
        date_raw = str(int(float(str(row_vals[4])))) if row_vals[4] else ""
        date = f"{date_raw[:4]}-{date_raw[4:6]}-{date_raw[6:8]} 00:00:00" if len(date_raw) == 8 else ""

        # 金额
        amt_str = str(row_vals[5] or "").replace(",", "").strip()
        try:
            amount = round(float(amt_str), 2)
        except ValueError:
            continue

        # 交易地点（列 7）
        location = str(row_vals[7] or "").strip() if len(row_vals) > 7 else ""

        # 对方账号与户名（列 8）
        acct_name_raw = str(row_vals[8] or "").strip() if len(row_vals) > 8 else ""

        # counterparty：优先从交易地点提取，失败则回退对方户名
        cpy = _extract_ccb_counterparty(location)
        if cpy is None:
            # 旧版：回退对方户名 / 分割
            if "/" in acct_name_raw:
                cpy = acct_name_raw.split("/", 1)[1].strip()
            else:
                cpy = acct_name_raw

        category = "expense" if amount < 0 else "income"

        # payment_method：从交易地点推断
        pm = _infer_ccb_payment_source(location, card_last4)

        normalized_cp, enriched_desc = _normalize_counterparty(cpy, summary, "ccb")
        records.append({
            "date": date,
            "amount": amount,
            "currency": currency,
            "card_number": card_last4,
            "counterparty": normalized_cp,
            "description": enriched_desc,
            "category": category,
            "payment_method": pm,
        })

    wb.release_resources()
    return records, []

# _pair_ccb_refunds 已删除 — 退款配对由 convert.py 的 _pair_refunds 统一处理

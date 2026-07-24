"""建行储蓄卡 XLS 转换器"""
import xlrd
import re
from decimal import Decimal, InvalidOperation
from datetime import datetime, timedelta
from ft.convert import _normalize_counterparty, _stable_short_hash


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

    # 去掉"消费-"前缀（新格式银行流水常在商户名前加此标记）
    stripped_loc = location
    if stripped_loc.startswith("消费-"):
        stripped_loc = stripped_loc[3:]

    # 支付源前缀映射（按长度降序匹配）
    PAYMENT_PREFIXES = [
        ("财付通-", ["微信支付-", "微信转账", "消费-"]),
        ("支付宝-", ["淘宝-", "支付宝外部商户-", "支付宝-转账-", "支付宝-", "消费-"]),
        ("美团支付-", ["消费-"]),
    ]

    for prefix, subs in PAYMENT_PREFIXES:
        if stripped_loc.startswith(prefix):
            rest = stripped_loc[len(prefix):]
            # 连续剥掉所有匹配的子前缀（如"支付宝-消费-"先后剥掉）
            while True:
                matched = False
                for sub in subs:
                    if rest.startswith(sub):
                        rest = rest[len(sub):]
                        matched = True
                        break
                if not matched:
                    break
            return rest

    # 证券转账：剥离账号和内部代码（如"银行转证券8888086011314150转入086"→"银行转证券"）
    sec_m = re.match(r"^(银行转证券|证券转银行|银转证|证转银)\d+\S*$", stripped_loc)
    if sec_m:
        return sec_m.group(1)

    return stripped_loc


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


def _extract_ccb_acct_name_counterparty(acct_name_raw: str) -> str:
    if "/" in acct_name_raw:
        return acct_name_raw.split("/", 1)[1].strip()
    return acct_name_raw.strip()


def _infer_ccb_refund_signal(summary: str, amount: Decimal) -> str:
    if amount <= 0:
        return ""
    text = summary.strip()
    if any(token in text for token in ("退货", "退款", "冲正", "撤销")):
        return "ccb_debit_refund"
    return ""


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
        seq = str(int(Decimal(str(row_vals[0])))) if row_vals[0] else ""
        if not seq:
            continue

        summary = str(row_vals[1] or "").strip()
        cur_str = str(row_vals[2] or "").strip()
        currency = cur_map.get(cur_str, "CNY")

        # 日期 YYYYMMDD → YYYY-MM-DD（原始账单无时间，不伪造 00:00:00）
        date_raw = str(int(Decimal(str(row_vals[4])))) if row_vals[4] else ""
        date = f"{date_raw[:4]}-{date_raw[4:6]}-{date_raw[6:8]}" if len(date_raw) == 8 else ""

        # 金额
        amt_str = str(row_vals[5] or "").replace(",", "").strip()
        try:
            amount = Decimal(amt_str)
        except (InvalidOperation, ValueError):
            continue
        balance = str(row_vals[6] or "").replace(",", "").strip()

        # 交易地点（列 7）
        location = str(row_vals[7] or "").strip() if len(row_vals) > 7 else ""

        # 对方账号与户名（列 8）
        acct_name_raw = str(row_vals[8] or "").strip() if len(row_vals) > 8 else ""

        # counterparty：优先从交易地点提取，失败则回退对方户名
        location_cp = _extract_ccb_counterparty(location)
        acct_cp = _extract_ccb_acct_name_counterparty(acct_name_raw)
        cpy = location_cp if location_cp is not None else acct_cp

        category = "expense" if amount < 0 else "income"

        # payment_method：从交易地点推断
        pm = _infer_ccb_payment_source(location, card_last4)
        refund_signal = _infer_ccb_refund_signal(summary, amount)

        normalized_cp, enriched_desc = _normalize_counterparty(cpy, summary, "ccb")
        fact_hash = _stable_short_hash(
            date,
            f"{amount:.2f}",
            currency,
            normalized_cp,
            enriched_desc,
            card_last4,
            balance,
        )
        record = {
            "date": date,
            "amount": amount,
            "currency": currency,
            "card_number": card_last4,
            "counterparty": normalized_cp,
            "note": enriched_desc,
            "category": category,
            "payment_method": pm,
            "summary": summary,
            "location": location,
            "acct_name_raw": acct_name_raw,
            "_raw_cp": acct_cp or cpy,
            "_ccb_location_cp": location_cp or "",
            "_ccb_acct_cp": acct_cp,
            "_ccb_refund_signal": refund_signal,
            "_fact_id": f"ccb_debit_{fact_hash}",
        }
        if refund_signal:
            record["_refund_signal"] = refund_signal
        records.append(record)

    wb.release_resources()
    return records, []

# _pair_ccb_refunds 已删除 — 退款配对由 convert.py 的 _pair_refunds 统一处理

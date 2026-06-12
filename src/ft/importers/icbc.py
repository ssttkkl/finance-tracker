"""工行账单导入 — 按指定账户名+币种匹配"""
import re
import subprocess, os

from ..models import FOREIGN_EXCHANGE_KEYWORDS


def import_icbc(pdf_path: str, password: str, account_name: str, currency: str = "CNY"):
    """导入工行账单到指定账户"""
    from ..db import get_db
    conn = get_db()

    # 验证账户存在
    acct = conn.execute(
        "SELECT id, currency FROM accounts WHERE name=? AND currency=? AND is_active=1",
        (account_name, currency),
    ).fetchone()
    if not acct:
        print(f"❌ 未找到活跃账户: {account_name}({currency})")
        conn.close()
        return
    account_id = acct["id"]

    # 解密PDF
    decrypted = pdf_path + ".decrypted.pdf"
    ret = subprocess.run(
        ["qpdf", "--decrypt", "--password=" + password, pdf_path, decrypted],
        capture_output=True, text=True, timeout=30,
    )
    if ret.returncode != 0:
        print(f"❌ 解密失败: {ret.stderr.strip()}")
        conn.close()
        return

    # 提取文本
    txt_path = pdf_path + ".txt"
    ret = subprocess.run(
        ["mutool", "draw", "-F", "text", "-o", txt_path, decrypted],
        capture_output=True, text=True, timeout=60,
    )
    os.unlink(decrypted)
    if ret.returncode != 0:
        print(f"❌ 提取文本失败: {ret.stderr.strip()}")
        conn.close()
        return

    with open(txt_path, encoding="utf-8") as f:
        text = f.read()
    os.unlink(txt_path)

    # 判断类型
    is_credit = "信用卡" in text
    is_debit = "借记账户" in text

    if is_credit:
        txns = _parse_credit(text, account_id, currency, pdf_path)
    elif is_debit:
        txns = _parse_debit(text, account_id, currency, pdf_path)
    else:
        print("❌ 无法识别账单类型")
        conn.close()
        return

    # 批量插入
    from ..txn import insert_txn
    new_count = 0
    skip_count = 0
    inserted = set()
    for t in txns:
        key = (t["date"], round(t["amount"], 2), t["account_id"], t["category"])
        if key in inserted:
            skip_count += 1
            continue
        inserted.add(key)
        insert_txn(conn, **t)
        new_count += 1

    source_bill = "icbc_credit" if is_credit else "icbc_debit"
    conn.execute(
        "INSERT INTO import_log(source_bill, filename, total, new, skipped) VALUES (?, ?, ?, ?, ?)",
        (source_bill, pdf_path, len(txns), new_count, skip_count),
    )
    conn.commit()
    conn.close()

    charges = sum(abs(t["amount"]) for t in txns if t["category"] == "expense")
    income = sum(t["amount"] for t in txns if t["category"] == "income" and t["amount"] > 0)
    transfers = sum(abs(t["amount"]) for t in txns if t["category"] == "transfer")
    print(f"✅ ICBC导入完成: 新增{new_count}条, 跳过{skip_count}条")
    print(f"   支出 {charges:.2f}  收入 {income:.2f}  转账 {transfers:.2f}")


def _parse_amount(s: str) -> float:
    s = s.strip().replace(",", "").replace("+", "")
    try:
        return float(s)
    except ValueError:
        return 0.0


def _parse_credit(text: str, account_id: int, currency: str, source_file: str):
    """解析信用卡账单"""
    txns = []
    lines = text.split("\n")
    i = 0
    current_date = None

    while i < len(lines):
        line = lines[i].strip()
        date_m = re.match(r"^(\d{4}-\d{2}-\d{2})$", line)
        if date_m:
            current_date = date_m.group(1)
            i += 1
            continue

        if not current_date:
            i += 1
            continue

        amt_m = re.match(r"^([+-]?[\d,]+\.[\d]{2})$", line)
        if amt_m:
            amount = _parse_amount(amt_m.group(1))
            ctx = "\n".join(lines[max(0, i-10):i+1])
            is_charge = "借" in ctx
            is_repayment = "贷" in ctx

            if is_charge:
                amount = -amount
                category = "expense"
            elif is_repayment:
                category = "transfer"
            else:
                category = "expense"
                if amount > 0:
                    category = "transfer"
                else:
                    amount = -amount

            description = _extract_merchant(ctx, lines[max(0, i-8):i+1])

            txns.append({
                "date": current_date,
                "amount": amount,
                "account_id": account_id,
                "category": category,
                "counterparty": "",
                "description": description[:30],
                "source_bill": "icbc_credit",
                "source_file": source_file,
            })
            current_date = None
        i += 1

    return txns


def _parse_debit(text: str, account_id: int, currency: str, source_file: str):
    """解析借记卡账单"""
    lines = text.split("\n")
    txns = []
    i = 0

    while i < len(lines):
        line = lines[i].strip()
        amt_m = re.match(r"^([+-][\d,]+\.[\d]{2})$", line)
        if not amt_m:
            i += 1
            continue

        amount = _parse_amount(amt_m.group(1))

        date = ""
        date_line_idx = -1
        for lookback in range(1, min(11, i + 1)):
            potential = lines[i - lookback].strip()
            dm = re.match(r"^(\d{4}-\d{2}-\d{2})$", potential)
            if dm:
                date = dm.group(1)
                date_line_idx = i - lookback
                break

        if not date:
            i += 1
            continue

        ctx_text = " ".join(lines[max(0, date_line_idx):min(len(lines), i + 8)])

        is_salary = "工资" in ctx_text or "年终" in ctx_text
        is_transfer_to_self = bool(re.search(r"\*\*\*\*", ctx_text))
        is_family = bool(re.search(r"梁碧玲|黄雨生", ctx_text))
        is_forex = any(kw in ctx_text for kw in FOREIGN_EXCHANGE_KEYWORDS)
        is_fund = bool(re.search(r"基金|9990", ctx_text))
        is_interest = "利息" in ctx_text
        is_income_other = bool(re.search(r"银联入账|他行汇入|网转", ctx_text))
        is_rent = bool(re.search(r"北京信富|住房租赁", ctx_text))
        is_fund_redemption = bool(re.search(r"基金赎回", ctx_text))
        is_jinzhexuan = "金哲玄" in ctx_text
        is_reversal = "撤销" in ctx_text

        counterparty = ""
        for j in range(i + 1, min(len(lines), i + 6)):
            s = lines[j].strip()
            if s and not re.match(r"^[\d,]+\.\d{2}$", s):
                if s not in ("手机银行", "网上银行", "快捷支付", "其他", "批量业务", "(空)"):
                    counterparty = s
                    break

        description = ""
        for j in range(date_line_idx + 1, i):
            s = lines[j].strip()
            if s and len(s) <= 10 and s not in ("活期", "00000", "人民币", "钞", "汇", "1614", "4600", "2116", "6982"):
                summary = s.replace("支", "").strip()
                if summary:
                    description = summary
                    break

        if is_reversal:
            i += 1
            continue

        if amount > 0:
            if is_interest:
                category, desc_text = "income", "利息"
            elif is_salary:
                category, desc_text = "income", "工资"
            elif is_fund_redemption:
                category, desc_text = "transfer", "基金赎回"
            elif is_forex:
                category, desc_text = "transfer", "购汇入账"
            elif "银联入账" in ctx_text:
                category, desc_text = "income", "银联入账"
            elif is_jinzhexuan:
                category, desc_text = "income", "金哲玄还款"
            elif "网转" in ctx_text or "网转" in description:
                category, desc_text = "income", "转账入账"
            elif is_income_other:
                category, desc_text = "income", "他行汇入"
            else:
                category, desc_text = "income", description or "入账"
        else:
            if is_family:
                category, desc_text = "expense", f"给{counterparty}"
            elif is_transfer_to_self:
                category, desc_text = "transfer", "转自己"
            elif is_rent:
                category, desc_text = "expense", "房租"
            elif is_forex:
                category, desc_text = "transfer", "购汇跨境"
            elif is_fund:
                category, desc_text = "transfer", "基金购买"
            else:
                category, desc_text = "expense", description or counterparty

        txns.append({
            "date": date,
            "amount": amount,
            "account_id": account_id,
            "category": category,
            "counterparty": counterparty,
            "description": desc_text[:30],
            "source_bill": "icbc_debit",
            "source_file": source_file,
        })
        i += 1

    return txns


def _extract_merchant(ctx: str, nearby: list) -> str:
    """从信用卡交易上下文中提取商户名"""
    candidates = []
    for line in nearby:
        s = line.strip()
        if s in ("", "借", "贷", "消费", "入账日期", "交易卡号", "收", "支",
                 "交易币种", "入账币种", "入账金额", "账户余额",
                 "人民币", "美元", "港币", "欧元", "日元",
                 "对方户名", "对方账号", "摘要", "交易场所"):
            continue
        if re.match(r"^[\d,]+\.[\d]{2}$", s):
            continue
        if re.match(r"^\d{4}-\d{2}-\d{2}$", s):
            continue
        if re.match(r"^\d{2}:\d{2}:\d{2}$", s):
            continue
        if re.match(r"^\d{16,}$", s):
            continue
        if len(s) < 2:
            continue
        candidates.append(s)

    for c in candidates:
        for kw in ["美团支付-", "京东支付-", "财付通-", "支付宝-", "网银在线-"]:
            if kw in c:
                after = c.split(kw, 1)[1]
                after = after.split(",")[0].split("（")[0].strip()
                after = after.split("…")[0].strip()
                return f"{kw.split('-')[0]}-{after[:24]}"

    candidates = [c for c in candidates if c != "消费"]
    return candidates[0][:30] if candidates else ""

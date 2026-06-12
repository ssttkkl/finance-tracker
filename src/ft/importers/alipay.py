"""支付宝 CSV 账单导入 → 按支付方式+币种匹配账户"""
import csv

ENCODINGS = ["utf-8", "gbk", "gb18030", "utf-8-sig"]

from ..models import FOREIGN_EXCHANGE_KEYWORDS


def _detect_encoding(path):
    with open(path, "rb") as f:
        raw = f.read(4096)
    for enc in ENCODINGS:
        try:
            raw.decode(enc)
            return enc
        except UnicodeDecodeError:
            continue
    return "gbk"


def _resolve_account(conn, payment_method: str, currency: str) -> int | None:
    """按支付方式+币种查找账户ID"""
    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND currency=? AND is_active=1",
        (payment_method, currency),
    ).fetchone()
    if acct:
        return acct["id"]
    # 再试试不带币种的纯名匹配（兼容旧账户名没有币种后缀的情况）
    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND is_active=1 LIMIT 1",
        (payment_method,),
    ).fetchone()
    return acct["id"] if acct else None


def import_alipay(path: str):
    from ..db import get_db

    conn = get_db()
    enc = _detect_encoding(path)

    with open(path, "r", encoding=enc) as f:
        text = f.read()
    lines = text.splitlines()

    # 找表头
    header_ln = None
    for i, line in enumerate(lines):
        if "交易时间" in line and "收/支" in line and "金额" in line:
            header_ln = i
            break
    if header_ln is None:
        print("❌ 无法找到支付宝账单表头")
        conn.close()
        return

    reader = csv.reader(lines[header_ln:])
    header = next(reader)
    h = {col: idx for idx, col in enumerate(header)}

    txns = []
    account_mismatch = 0

    for row in reader:
        if len(row) < 7:
            continue

        date_str = row[h.get("交易时间", 0)].strip()[:10].replace("/", "-")
        direction = row[h.get("收/支", 5)].strip()
        amount_str = row[h.get("金额", 6)].strip()

        try:
            amount = float(amount_str)
        except ValueError:
            continue

        if amount == 0:
            continue

        if direction == "支出":
            amount = -amount
        elif direction == "收入":
            pass
        else:
            continue

        payment_method = row[h.get("收/付款方式", 7)].strip() if "收/付款方式" in h else ""
        counterparty = row[h.get("交易对方", 2)].strip()
        desc = row[h.get("商品说明", 4)].strip() or counterparty
        txn_type = row[h.get("交易分类", 1)].strip()

        # 交易币种 — 支付宝账单有"币种"或"交易币种"列
        currency = "CNY"
        for cur_key in ("币种", "交易币种"):
            if cur_key in h:
                raw_cur = row[h[cur_key]].strip().upper()
                if raw_cur in ("CNY", "USD", "HKD"):
                    currency = raw_cur
                break

        # 查找账户
        account_id = _resolve_account(conn, payment_method, currency)
        if account_id is None:
            print(f"  ⚠️ 未找到账户: 支付方式='{payment_method}' 币种={currency}，跳过该笔")
            account_mismatch += 1
            continue

        # 分类逻辑
        is_exchange = any(kw in desc for kw in FOREIGN_EXCHANGE_KEYWORDS)

        if is_exchange:
            category = "transfer"
        elif "退款" in txn_type and amount > 0:
            category = "expense"
        else:
            category = "expense" if amount < 0 else "income"

        txns.append({
            "date": date_str,
            "amount": amount,
            "account_id": account_id,
            "category": category,
            "counterparty": counterparty,
            "description": desc[:30],
            "payment_method": payment_method,
            "source_bill": "alipay",
            "source_file": path,
        })

    # 去重插入（全字段精确匹配）
    from ..txn import insert_txn
    dedup_skipped = 0
    new_count = 0
    inserted_keys = set()
    for t in txns:
        key = (t["date"], round(t["amount"], 2), t["category"],
               t["counterparty"], t["description"], t["payment_method"])
        if key in inserted_keys:
            dedup_skipped += 1
            continue
        existing = conn.execute(
            """SELECT 1 FROM transactions WHERE date=? AND amount=?
               AND source_bill='alipay' AND category=? AND counterparty=?
               AND description=? AND payment_method=? LIMIT 1""",
            (t["date"], t["amount"], t["category"], t["counterparty"],
             t["description"], t["payment_method"]),
        ).fetchone()
        if existing:
            dedup_skipped += 1
            continue
        inserted_keys.add(key)
        insert_txn(conn, **t)
        new_count += 1

    total_skip = account_mismatch + dedup_skipped
    conn.execute(
        "INSERT INTO import_log(source_bill, filename, total, new, skipped) VALUES (?, ?, ?, ?, ?)",
        ("alipay", path, len(txns), new_count, total_skip),
    )
    conn.commit()
    conn.close()

    print(f"✅ 支付宝导入完成: 新增{new_count}条, 跳过{total_skip}条")
    if account_mismatch:
        print(f"   ⚠️ 其中 {account_mismatch} 条因找不到匹配账户跳过")

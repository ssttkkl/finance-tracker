# 三步导入流水线 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace one-step `ft import` with three-stage pipeline: `convert` (bill→CSV) → `merge` (dedup) → `load` (CSV→DB).

**Architecture:** 4 new modules. Existing parsers (alipay/wechat/icbc) preserved for their parsing logic — `convert` calls them then writes CSV instead of DB. `mapping.py` handles YAML rule matching. `load.py` reads CSV and inserts via existing `insert_txn`.

**Tech Stack:** Python 3.11+, SQLite, argparse, PyYAML, csv

**Spec:** `docs/superpowers/specs/2026-06-11-import-pipeline-design.md`

---

## File Structure

**New:**
- `src/ft/mapping.py` — YAML rule parser + glob matching
- `src/ft/convert.py` — bill → CSV conversion
- `src/ft/merge.py` — multiple CSV merge + dedup
- `src/ft/load.py` — CSV → DB load

**Modified:**
- `src/ft/cli.py` — add convert/merge/load subcommands
- `~/.ft/mapping.yaml` — default rules file (created on first use)

**Untouched:**
- `src/ft/importers/*.py` — parsing logic kept as-is
- `src/ft/db.py`, `src/ft/txn.py`, `src/ft/acct.py`, `src/ft/report.py` — no changes

---

### Task 1: mapping.py — YAML Rule Parser

**Files:**
- Create: `src/ft/mapping.py`

- [ ] **Step 1: Write mapping.py**

```python
"""YAML 映射规则解析 + glob 匹配"""
import fnmatch
from pathlib import Path

MAPPING_PATH = Path.home() / ".ft" / "mapping.yaml"

# Default rules to create if mapping.yaml doesn't exist
DEFAULT_RULES = """rules:
  - source: alipay
    match: "工商银行信用卡(1200)&*"
    account: "工行信用卡(1200)"
    currency: CNY
  - source: alipay
    match: "网商银行储蓄卡(4164)&*"
    account: "网商储蓄卡(4164)"
    currency: CNY
  - source: alipay
    match: "账户余额"
    account: "支付宝余额"
    currency: CNY
  - source: wechat
    match: "零钱"
    account: "微信零钱"
    currency: CNY
  - source: icbc_debit
    match: "*"
    account: "工行借记卡"
    currency: CNY
  - source: icbc_credit
    match: "*"
    account: "工行信用卡(1200)"
    currency: CNY

default: error
"""


def load_rules(path=None) -> tuple[list[dict], str]:
    """加载 mapping.yaml，返回 (rules, default_action)"""
    if path is None:
        path = MAPPING_PATH
    path = Path(path)

    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_RULES, encoding="utf-8")
        print(f"  📝 已创建默认规则: {path}")

    import yaml
    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    rules = data.get("rules", [])
    default_action = data.get("default", "error")
    return rules, default_action


def match_payment_method(rules: list[dict], source: str, payment_method: str) -> dict | None:
    """按 (source, payment_method) 匹配规则，返回 {account, currency} 或 None
    
    优先级：精确匹配 > 前缀匹配 > 后缀匹配 > 通配(*)
    同优先级下，较长的 match 优先。
    """
    candidates = []
    for rule in rules:
        if rule.get("source") != source:
            continue
        pattern = rule["match"]
        if fnmatch.fnmatch(payment_method, pattern):
            candidates.append((len(pattern), rule))

    if not candidates:
        return None

    # 按匹配长度降序（长规则优先）
    candidates.sort(key=lambda x: -x[0])
    return {
        "account": candidates[0][1]["account"],
        "currency": candidates[0][1]["currency"],
    }
```

- [ ] **Step 2: Create default mapping.yaml and verify**

```bash
cd ~/Projects/finance-tracker
rm -f ~/.ft/mapping.yaml
python -c "
from src.ft.mapping import load_rules, match_payment_method
rules, default = load_rules()
print(f'Rules: {len(rules)}, default: {default}')
r = match_payment_method(rules, 'alipay', '工商银行信用卡(1200)&千问每日立减')
print(f'Match: {r}')
r = match_payment_method(rules, 'alipay', 'unknown_card')
print(f'No match: {r}')
"
```

Expected:
```
  📝 已创建默认规则: /Users/huangwenlong/.ft/mapping.yaml
Rules: 6, default: error
Match: {'account': '工行信用卡(1200)', 'currency': 'CNY'}
No match: None
```

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/mapping.py
git commit -m "feat(mapping): YAML rule parser with glob matching"
```

---

### Task 2: convert.py — Bill to CSV

**Files:**
- Create: `src/ft/convert.py`

- [ ] **Step 1: Write convert.py**

This module calls the existing parsers but captures their parsed transactions as a list, then applies mapping rules and writes CSV.

Key design: The existing importers print to stdout and insert to DB. For `convert`, we need them to just parse and return data. The cleanest approach: write a thin wrapper that monkey-patches the importer's output capture.

Actually, the simplest approach: The existing importers have parsing logic interleaved with DB insertion. Rather than refactor every importer, `convert.py` will import each importer function but patch the process to intercept data.

Simplest approach: For each bill type, read the file, parse it with the same logic as the importer, and write CSV instead of inserting to DB. Let me just implement this directly.

```python
"""convert — 账单 → 统一CSV"""
import csv
import sys
from .mapping import load_rules, match_payment_method


def _interleave_parse_and_convert(path: str, source: str, password: str = None,
                                   account_override: str = None, currency_override: str = None):
    """解析账单 → 返回统一CSV行列表"""
    rules, default_action = load_rules()

    rows = []

    if source == "alipay":
        from .importers.alipay import _detect_encoding
        csv_data = _read_alipay_raw(path)
        for record in csv_data:
            payment_method = record.get("payment_method", "")
            counterparty = record.get("counterparty", "")
            desc = record.get("description", "")
            txn_type = record.get("txn_type", "")

            # Use mapping rules
            if account_override:
                acct_name = account_override
                currency = currency_override or "CNY"
            else:
                match = match_payment_method(rules, "alipay", payment_method)
                if match:
                    acct_name = match["account"]
                    currency = match["currency"]
                elif default_action == "skip":
                    continue
                else:
                    print(f"  ⚠️ 未匹配规则: source=alipay payment_method='{payment_method}'", file=sys.stderr)
                    continue

            rows.append([
                record["date"],
                record["amount"],
                currency,
                counterparty,
                desc[:80],
                record["category"],
                acct_name,
                "alipay",
            ])

    elif source == "wechat":
        from .importers.wechat import _resolve_account as _unused
        from .models import FOREIGN_EXCHANGE_KEYWORDS
        records = _read_wechat_raw(path)
        for record in records:
            payment_method = record.get("payment_method", "")
            desc = record.get("description", "")

            if account_override:
                acct_name = account_override
                currency = currency_override or "CNY"
            else:
                match = match_payment_method(rules, "wechat", payment_method)
                if match:
                    acct_name = match["account"]
                    currency = match["currency"]
                elif default_action == "skip":
                    continue
                else:
                    print(f"  ⚠️ 未匹配规则: source=wechat payment_method='{payment_method}'", file=sys.stderr)
                    continue

            rows.append([
                record["date"],
                record["amount"],
                currency,
                record["counterparty"],
                (desc or record["counterparty"])[:80],
                record["category"],
                acct_name,
                "wechat",
            ])

    elif source in ("icbc_debit", "icbc_credit"):
        if not password:
            print("❌ ICBC 需要 --password", file=sys.stderr)
            return None
        records = _read_icbc_raw(path, password)
        src = "icbc_debit" if records and records[0].get("source_bill") == "icbc_debit" else "icbc_credit"

        for record in records:
            if account_override:
                acct_name = account_override
                currency = currency_override or "CNY"
            else:
                match = match_payment_method(rules, src, "*")
                if match:
                    acct_name = match["account"]
                    currency = match["currency"]
                elif default_action == "skip":
                    continue
                else:
                    print(f"  ⚠️ 未匹配规则: source={src}", file=sys.stderr)
                    continue

            rows.append([
                record["date"],
                record["amount"],
                currency,
                record["counterparty"],
                record["description"][:80],
                record["category"],
                acct_name,
                src,
            ])

    return rows if rows else None


def _read_alipay_raw(path: str):
    """同 alipay.py 解析逻辑，但不插入 DB，返回列表"""
    import csv as csv_mod
    # 复用 alipay 的 _detect_encoding
    from .importers.alipay import _detect_encoding
    from .models import FOREIGN_EXCHANGE_KEYWORDS

    enc = _detect_encoding(path)
    with open(path, "r", encoding=enc) as f:
        text = f.read()
    lines = text.splitlines()

    header_ln = None
    for i, line in enumerate(lines):
        if "交易时间" in line and "收/支" in line and "金额" in line:
            header_ln = i
            break
    if header_ln is None:
        print("❌ 无法找到支付宝账单表头")
        return []

    reader = csv_mod.reader(lines[header_ln:])
    header = next(reader)
    h = {col: idx for idx, col in enumerate(header)}

    records = []
    for row in reader:
        if len(row) < 7:
            continue
        date_str = row[h.get("交易时间", 0)].strip()[:19].replace("/", "-")
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

        is_exchange = any(kw in desc for kw in FOREIGN_EXCHANGE_KEYWORDS)
        if is_exchange:
            category = "transfer"
        elif "退款" in txn_type and amount > 0:
            category = "expense"
        elif amount < 0:
            category = "expense"
        else:
            category = "income"

        records.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": counterparty,
            "description": desc[:80],
            "txn_type": txn_type,
            "category": category,
        })
    return records


def _read_wechat_raw(path: str):
    """同 wechat.py 解析逻辑，但不插入 DB，返回列表"""
    try:
        import openpyxl
    except ImportError:
        print("❌ 需要 openpyxl")
        return []

    from .importers.wechat import INCOME_OK, EXPENSE_OK
    from .models import FOREIGN_EXCHANGE_KEYWORDS

    wb = openpyxl.load_workbook(path)
    ws = wb.active

    header_row_i = None
    for i, row in enumerate(ws.iter_rows(min_row=1, max_row=50, values_only=True), 1):
        if row[0] and "交易时间" in str(row[0]):
            header_row_i = i
            break
    if not header_row_i:
        print("❌ 无法找到微信账单表头")
        return []

    header = [str(c or "") for c in next(ws.iter_rows(min_row=header_row_i, max_row=header_row_i, values_only=True))]
    h = {col: idx for idx, col in enumerate(header)}

    records = []
    for row in ws.iter_rows(min_row=header_row_i + 1, values_only=True):
        if not row or not any(v for v in row if v is not None):
            continue
        vals = [str(c or "") for c in row]
        direction = vals[h["收/支"]] if "收/支" in h else ""
        status = vals[h["当前状态"]] if "当前状态" in h else ""

        if direction == "支出" and status not in EXPENSE_OK:
            continue
        if direction == "收入":
            is_refund = "退款" in status
            if not is_refund and status not in INCOME_OK:
                continue

        try:
            amount = float(vals[h["金额(元)"]])
        except (ValueError, KeyError):
            continue
        if direction == "支出":
            amount = -amount
        elif direction != "收入":
            continue
        if amount == 0:
            continue

        payment_method = vals[h["支付方式"]] if "支付方式" in h else ""
        counterparty = vals[h["交易对方"]] if "交易对方" in h else ""
        desc = vals[h["商品"]] if "商品" in h else ""
        is_refund = "退款" in status
        is_exchange = any(kw in (desc + counterparty) for kw in FOREIGN_EXCHANGE_KEYWORDS)
        is_credit_repay = "信用卡还款" in (desc + counterparty)

        if is_exchange:
            category = "transfer"
        elif is_credit_repay and amount < 0:
            category = "transfer"
        elif is_refund and amount > 0:
            category = "expense"
        elif amount < 0:
            category = "expense"
        else:
            category = "income"

        date_raw = vals[h["交易时间"]] if "交易时间" in h else ""
        date_str = date_raw[:19].replace("/", "-")

        records.append({
            "date": date_str,
            "amount": round(amount, 2),
            "payment_method": payment_method,
            "counterparty": counterparty,
            "description": desc[:80],
            "category": category,
        })
    return records


def _read_icbc_raw(path: str, password: str):
    """同 icbc.py 解析逻辑，但不插入 DB，返回列表"""
    import subprocess, os, re
    from .models import FOREIGN_EXCHANGE_KEYWORDS

    decrypted = path + ".decrypted.pdf"
    ret = subprocess.run(
        ["qpdf", "--decrypt", "--password=" + password, path, decrypted],
        capture_output=True, text=True, timeout=30,
    )
    if ret.returncode != 0:
        print(f"❌ 解密失败: {ret.stderr.strip()}")
        return []

    txt_path = path + ".txt"
    ret = subprocess.run(
        ["mutool", "draw", "-F", "text", "-o", txt_path, decrypted],
        capture_output=True, text=True, timeout=60,
    )
    os.unlink(decrypted)
    if ret.returncode != 0:
        print(f"❌ 提取文本失败: {ret.stderr.strip()}")
        return []

    with open(txt_path, encoding="utf-8") as f:
        text = f.read()
    os.unlink(txt_path)

    is_credit = "信用卡" in text
    records = []
    lines = text.split("\n")

    if is_credit:
        i, current_date = 0, None
        while i < len(lines):
            line = lines[i].strip()
            dm = re.match(r"^(\d{4}-\d{2}-\d{2})$", line)
            if dm:
                current_date = dm.group(1)
                i += 1
                continue
            if not current_date:
                i += 1
                continue
            amt_m = re.match(r"^([+-]?[\d,]+\.[\d]{2})$", line)
            if amt_m:
                amount = _parse_amt(amt_m.group(1))
                ctx = "\n".join(lines[max(0, i-10):i+1])
                is_charge = "借" in ctx
                if is_charge:
                    amount = -amount
                    category = "expense"
                else:
                    category = "transfer"
                description = _extract_merchant(ctx, lines[max(0, i-8):i+1])
                records.append({
                    "date": f"{current_date} 00:00:00",
                    "amount": round(amount, 2),
                    "counterparty": "",
                    "description": description[:80],
                    "category": category,
                })
                current_date = None
            i += 1
    else:
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            amt_m = re.match(r"^([+-][\d,]+\.[\d]{2})$", line)
            if not amt_m:
                i += 1
                continue
            amount = _parse_amt(amt_m.group(1))
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
            description = ""
            for j in range(date_line_idx + 1, i):
                s = lines[j].strip()
                if s and len(s) <= 10 and s not in ("活期", "00000", "人民币", "钞", "汇", "1614", "4600", "2116", "6982"):
                    summary = s.replace("支", "").strip()
                    if summary:
                        description = summary
                        break

            cpy = ""
            for j in range(i + 1, min(len(lines), i + 6)):
                s = lines[j].strip()
                if s and not re.match(r"^[\d,]+\.\d{2}$", s):
                    if s not in ("手机银行", "网上银行", "快捷支付", "其他", "批量业务", "(空)"):
                        cpy = s
                        break

            is_reversal = "撤销" in ctx_text
            if is_reversal:
                i += 1
                continue

            is_forex = any(kw in ctx_text for kw in FOREIGN_EXCHANGE_KEYWORDS)
            if amount > 0:
                category = "transfer" if is_forex else "income"
            else:
                category = "transfer" if is_forex else "expense"

            records.append({
                "date": f"{date} 00:00:00",
                "amount": round(amount, 2),
                "counterparty": cpy,
                "description": description or cpy,
                "category": category,
            })
            i += 1

    return records


def _parse_amt(s: str) -> float:
    s = s.strip().replace(",", "").replace("+", "")
    try:
        return float(s)
    except ValueError:
        return 0.0


def _extract_merchant(ctx: str, nearby: list) -> str:
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
        if len(s) < 2:
            continue
        candidates.append(s)

    import re as _re
    for c in candidates:
        for kw in ["美团支付-", "京东支付-", "财付通-", "支付宝-", "网银在线-"]:
            if kw in c:
                after = c.split(kw, 1)[1]
                after = after.split(",")[0].split("（")[0].strip()
                after = after.split("…")[0].strip()
                return f"{kw.split('-')[0]}-{after[:24]}"

    candidates = [c for c in candidates if c not in ("消费", "622599000000000000")]
    return candidates[0][:60] if candidates else ""


def do_convert(path: str, source: str, output: str, password: str = None,
               account: str = None, currency: str = None):
    """convert 命令入口"""
    if source == "icbc":
        # auto-detect credit vs debit
        rows = _interleave_parse_and_convert(path, "icbc_debit", password, account, currency)
        if not rows:
            rows = _interleave_parse_and_convert(path, "icbc_credit", password, account, currency)
    else:
        rows = _interleave_parse_and_convert(path, source, password, account, currency)

    if not rows:
        print("❌ 无数据可输出")
        return

    with open(output, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["date", "amount", "currency", "counterparty",
                         "description", "category", "account_name", "source"])
        writer.writerows(rows)

    print(f"✅ 已转换 {len(rows)} 条: {output}")
```

- [ ] **Step 2: Test with Alipay CSV**

```bash
cd ~/Projects/finance-tracker
python -c "
from src.ft.convert import do_convert
do_convert('/Users/huangwenlong/Downloads/支付宝交易明细(20260101-20260609).csv', 'alipay', '/tmp/test_alipay.csv')
"
head -5 /tmp/test_alipay.csv
```

Expected: CSV with header + data rows, all 8 columns.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/convert.py
git commit -m "feat(convert): bill to unified CSV with mapping rules"
```

---

### Task 3: merge.py — CSV Merge + Dedup

**Files:**
- Create: `src/ft/merge.py`

- [ ] **Step 1: Write merge.py**

```python
"""merge — 多个 CSV 合并去重"""
import csv
import sys


def do_merge(inputs: list[str], output: str):
    """合并多个 CSV，按 (datetime, amount, currency, account_name) 去重"""
    seen = set()
    all_rows = []

    for path in inputs:
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                key = (row["date"], row["amount"], row["currency"], row["account_name"])
                if key in seen:
                    continue
                seen.add(key)
                all_rows.append(row)

    if not all_rows:
        print("❌ 无数据")
        return

    # 按 datetime 排序
    all_rows.sort(key=lambda r: r["date"])

    with open(output, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "amount", "currency", "counterparty",
            "description", "category", "account_name", "source",
        ])
        writer.writeheader()
        writer.writerows(all_rows)

    print(f"✅ 合并完成: {len(all_rows)} 条 (去重 {len(seen)} 唯一) → {output}")
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/merge.py
git commit -m "feat(merge): CSV merge with (datetime, amount, currency, account_name) dedup"
```

---

### Task 4: load.py — CSV to DB

**Files:**
- Create: `src/ft/load.py`

- [ ] **Step 1: Write load.py**

```python
"""load — CSV 落库"""
import csv
import sys
from .db import get_db, resolve_account
from .txn import insert_txn


CSV_HEADER = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source"]


def do_load(path: str, default_action: str = "error"):
    """读取 CSV，匹配账户，插入 DB"""
    conn = get_db()
    new_count = 0
    skip_count = 0
    no_account_count = 0

    from .mapping import load_rules
    rules, _ = load_rules()  # for default_action if not provided

    with open(path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row_num, row in enumerate(reader, start=2):  # 1-indexed, header=1
            # 匹配账户
            acct_name = row.get("account_name", "").strip()
            currency = row.get("currency", "CNY")
            if not acct_name:
                no_account_count += 1
                if default_action == "error":
                    print(f"  ❌ 第{row_num}行: account_name 为空", file=sys.stderr)
                    continue
                else:
                    skip_count += 1
                    continue

            acct = conn.execute(
                "SELECT id FROM accounts WHERE name=? AND currency=? AND is_active=1",
                (acct_name, currency),
            ).fetchone()

            if not acct:
                no_account_count += 1
                if default_action == "error":
                    print(f"  ❌ 第{row_num}行: 未找到账户 '{acct_name}' ({currency})", file=sys.stderr)
                    continue
                else:
                    skip_count += 1
                    continue

            # 解析 amount
            try:
                amount = float(row["amount"])
            except (ValueError, KeyError):
                skip_count += 1
                print(f"  ⚠️ 第{row_num}行: 无效金额 '{row.get('amount', '')}'", file=sys.stderr)
                continue

            if amount == 0:
                skip_count += 1
                continue

            category = row.get("category", "").strip() or None
            if category not in ("income", "expense", "transfer"):
                # 自动推断
                category = "expense" if amount < 0 else "income"

            insert_txn(conn,
                date=row["date"],
                amount=amount,
                account_id=acct["id"],
                category=category,
                counterparty=row.get("counterparty", ""),
                description=row.get("description", ""),
                source_bill=row.get("source", ""),
                source_file=path,
                payment_method="",
            )
            new_count += 1

    conn.commit()
    conn.close()

    print(f"✅ 导入完成: 新增{new_count}条")
    if skip_count:
        print(f"   跳过: {skip_count}条")
    if no_account_count:
        print(f"   无匹配账户: {no_account_count}条")
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/load.py
git commit -m "feat(load): CSV to DB with account matching"
```

---

### Task 5: CLI — Wire Up convert/merge/load

**Files:**
- Modify: `src/ft/cli.py`

- [ ] **Step 1: Add subcommands to cli.py**

Add these three subcommands to the CLI, keeping the existing `import` command as-is.

In the `main()` function, after the existing subcommands, add:

```python
    # convert
    cv = sub.add_parser("convert", help="步骤① 账单→统一CSV")
    cv.add_argument("file", help="账单文件路径")
    cv.add_argument("-s", "--source", required=True,
                    choices=["alipay", "wechat", "icbc"], help="账单类型")
    cv.add_argument("-o", "--output", required=True, help="输出CSV路径")
    cv.add_argument("--password", help="工行PDF密码")
    cv.add_argument("--account", help="覆盖账户名")
    cv.add_argument("--currency", default="CNY", choices=["CNY", "USD", "HKD"], help="覆盖币种")

    # merge
    mg = sub.add_parser("merge", help="步骤② 合并去重CSV")
    mg.add_argument("files", nargs="+", help="输入CSV文件列表")
    mg.add_argument("-o", "--output", required=True, help="输出CSV路径")

    # load
    ld = sub.add_parser("load", help="步骤③ CSV落库")
    ld.add_argument("file", help="CSV文件路径")
```

In the dispatch section (after `args = parser.parse_args()`), add:

```python
    if args.cmd == "convert":
        from .convert import do_convert
        do_convert(args.file, args.source, args.output,
                  password=args.password, account=args.account, currency=args.currency)
        return

    if args.cmd == "merge":
        from .merge import do_merge
        do_merge(args.files, args.output)
        return

    if args.cmd == "load":
        from .load import do_load
        do_load(args.file)
        return
```

- [ ] **Step 2: Full pipeline test**

```bash
cd ~/Projects/finance-tracker
rm -f ~/.ft/ft.db
python -m src.ft.cli init

# Add accounts
python -m src.ft.cli acct add "微信零钱" --type cash --currency CNY
python -m src.ft.cli acct add "工行借记卡" --type cash --currency CNY

# Step ①: Convert Alipay CSV
python -m src.ft.cli convert ~/Downloads/支付宝交易明细\(20260101-20260609\).csv -s alipay -o /tmp/test_out.csv
echo "--- rows ---"
wc -l /tmp/test_out.csv

# Step ③: Load directly (no merge needed for single source)
python -m src.ft.cli load /tmp/test_out.csv
echo "--- loaded ---"

# Check
python -m src.ft.cli acct list
python -m src.ft.cli list --limit 5
```

Expected: Clean conversion and loading. Accounts show updated balances.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/cli.py
git commit -m "feat(cli): add convert/merge/load subcommands for three-stage import"
```

---

### Task 6: Tests

**Files:**
- Modify: `tests/test_import.py`

- [ ] **Step 1: Add convert/merge/load tests**

Add these tests to the existing test file:

```python
class TestPipeline:
    def test_mapping_rules(self):
        from src.ft.mapping import load_rules, match_payment_method
        rules, default = load_rules()
        assert len(rules) > 0
        assert default == "error"
        r = match_payment_method(rules, "alipay", "工商银行信用卡(1200)&千问每日必减")
        assert r is not None
        assert r["account"] == "工行信用卡(1200)"
        # no match
        r = match_payment_method(rules, "alipay", "UNKNOWN")
        assert r is None

    def test_merge_dedup(self, tmp_path):
        from src.ft.merge import do_merge
        import csv as csv_m
        # Create two CSV files with overlap
        f1 = tmp_path / "a.csv"
        f2 = tmp_path / "b.csv"
        header = ["date","amount","currency","counterparty","description","category","account_name","source"]
        rows1 = [
            ["2026-06-06 23:46:23","-31.00","CNY","t***4","cable","expense","工行借记卡","alipay"],
            ["2026-06-06 23:41:24","-170.00","CNY","哥","主板","expense","工行借记卡","alipay"],
        ]
        rows2 = [
            # duplicate
            ["2026-06-06 23:46:23","-31.00","CNY","t***4","cable","expense","工行借记卡","alipay"],
            # unique
            ["2026-06-05 10:00:00","-50.00","CNY","starbucks","coffee","expense","微信零钱","wechat"],
        ]
        for f, rows in [(f1, rows1), (f2, rows2)]:
            with open(f, "w", newline="") as fh:
                w = csv_m.writer(fh)
                w.writerow(header)
                w.writerows(rows)
        out = tmp_path / "merged.csv"
        do_merge([str(f1), str(f2)], str(out))
        with open(out) as fh:
            merged = list(csv_m.DictReader(fh))
        assert len(merged) == 3  # 1 duplicate removed
```

- [ ] **Step 2: Run tests**

```bash
cd ~/Projects/finance-tracker
python -m pytest tests/ -v
```

Expected: All 15+ tests pass.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add tests/
git commit -m "test: add pipeline tests for mapping, merge, convert"
```

---

### Task 7: Integration Smoke Test

- [ ] **Step 1: Run full pipeline end-to-end**

```bash
cd ~/Projects/finance-tracker
rm -f ~/.ft/ft.db

python -m src.ft.cli init

python -m src.ft.cli acct add "微信零钱" --type cash --currency CNY
python -m src.ft.cli acct add "支付宝余额" --type cash --currency CNY
python -m src.ft.cli acct add "工行信用卡(1200)" --type loan --currency CNY
python -m src.ft.cli acct add "工行借记卡" --type cash --currency CNY
python -m src.ft.cli acct add "建行储蓄卡(2820)" --type cash --currency CNY

# Convert
python -m src.ft.cli convert ~/Downloads/支付宝交易明细\(20260101-20260609\).csv -s alipay -o /tmp/alipay.csv
echo "Converted: $(wc -l < /tmp/alipay.csv) lines"

# Load
python -m src.ft.cli load /tmp/alipay.csv

# Report
python -m src.ft.cli acct list
python -m src.ft.cli list --limit 5
```

Expected: Clean end-to-end pipeline.

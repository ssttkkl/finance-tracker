# Multi-Currency + Cross-Currency Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite Finance Tracker from hardcoded 4-account/CNY-only to flexible multi-account/multi-currency with cross-currency transfer support.

**Architecture:** Accounts become `(name, type, currency)` tuples with `UNIQUE(name, currency)`. Transactions derive `currency` from their account. Cross-currency transfers create paired debit/credit records with `exchange_rate`. Reports group by currency separately, no conversion.

**Tech Stack:** Python 3.11+, SQLite, argparse, pytest

**Spec:** `docs/superpowers/specs/2026-06-11-multi-currency-design.md`

---

## File Structure

**Modified:**
- `src/ft/models.py` — Constants (currencies, types, labels, symbols)
- `src/ft/db.py` — New schema, no hardcoded accounts
- `src/ft/cli.py` — Add `acct` subcommand, wire new modules
- `src/ft/report.py` — Multi-currency grouped display
- `src/ft/importers/alipay.py` — Account lookup by name+currency
- `src/ft/importers/wechat.py` — Same
- `src/ft/importers/icbc.py` — Same
- `tests/test_import.py` — Rewrite for new schema

**New:**
- `src/ft/acct.py` — Account CRUD (add/list/rename/delete/activate)
- `src/ft/transfer.py` — Cross-currency transfer logic (paired records)

**Deleted:**
- `src/ft/reconcile.py` — Old reconcile logic removed (no longer applicable with new schema)

---

### Task 1: Rewrite models.py with Currency Constants

**Files:**
- Modify: `src/ft/models.py` — Complete rewrite

- [ ] **Step 1: Write new models.py**

```python
"""数据模型常量"""
from pathlib import Path

DB_DIR = Path.home() / ".ft"
DB_PATH = DB_DIR / "ft.db"

# 币种
CURRENCIES = ("CNY", "USD", "HKD")
CURRENCY_SYMBOLS = {
    "CNY": "¥",
    "USD": "$",
    "HKD": "HK$",
}

# 账户类型
ACCOUNT_TYPES = ("cash", "loan", "lend", "security")
ACCOUNT_LABELS = {
    "cash": "现金",
    "loan": "贷款",
    "lend": "借款",
    "security": "证券",
}
ACCOUNT_ICONS = {
    "cash": "💰",
    "loan": "💳",
    "lend": "📤",
    "security": "📈",
}

# 交易类型
CATEGORIES = ("income", "expense", "transfer", "snapshot")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "snapshot": "校准",
}

# 来源
SOURCE_LABELS = {
    "alipay": "支付宝",
    "wechat": "微信",
    "icbc_credit": "工行信用卡",
    "icbc_debit": "工行借记卡",
}

# 购汇/跨境关键词 → 跨币种转账
FOREIGN_EXCHANGE_KEYWORDS = ["购汇", "跨境", "外汇", "换汇"]
```

- [ ] **Step 2: Verify import works**

Run: `cd ~/Projects/finance-tracker && python -c "from src.ft.models import CURRENCIES, CURRENCY_SYMBOLS; print(CURRENCIES)"`
Expected: `('CNY', 'USD', 'HKD')`

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/models.py
git commit -m "refactor(models): add currency constants, snapshot category, forex keywords"
```

---

### Task 2: Rewrite DB Schema (no hardcoded accounts, no snapshots table)

**Files:**
- Modify: `src/ft/db.py` — Full rewrite of init_db and helper functions

- [ ] **Step 1: Write new db.py**

```python
"""数据库 — 多账户多币种模型"""
import sqlite3
from .models import DB_DIR, DB_PATH

def get_db() -> sqlite3.Connection:
    DB_DIR.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(DB_PATH))
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    return conn

def init_db():
    conn = get_db()
    conn.executescript("""
        -- 账户（由用户自由创建，无硬编码默认账户）
        CREATE TABLE IF NOT EXISTS accounts (
            id         INTEGER PRIMARY KEY AUTOINCREMENT,
            name       TEXT NOT NULL,
            type       TEXT NOT NULL CHECK(type IN ('cash','loan','lend','security')),
            currency   TEXT NOT NULL CHECK(currency IN ('CNY','USD','HKD')),
            created_at TEXT DEFAULT (datetime('now','localtime')),
            is_active  INTEGER DEFAULT 1,
            sort_order INTEGER DEFAULT 0,
            UNIQUE(name, currency)
        );

        -- 交易流水
        -- amount: 正=流入该账户, 负=流出该账户
        -- transfer_pair_id: 同为该值的前后两条记录是同一笔转账的两端
        -- exchange_rate: 跨币种转账时，记录 1 目标币种 = X 源币种
        -- snapshot_balance: category='snapshot' 时记录校准余额
        CREATE TABLE IF NOT EXISTS transactions (
            id               INTEGER PRIMARY KEY AUTOINCREMENT,
            date             TEXT NOT NULL,
            amount           REAL NOT NULL,
            account_id       INTEGER NOT NULL REFERENCES accounts(id),
            currency         TEXT NOT NULL,
            category         TEXT NOT NULL CHECK(category IN ('income','expense','transfer','snapshot')),
            counterparty     TEXT DEFAULT '',
            description      TEXT DEFAULT '',
            payment_method   TEXT DEFAULT '',
            source_bill      TEXT DEFAULT '',
            source_file      TEXT DEFAULT '',
            transfer_pair_id INTEGER DEFAULT NULL,
            exchange_rate    REAL DEFAULT NULL,
            snapshot_balance REAL DEFAULT NULL,
            anomaly          TEXT DEFAULT NULL,
            created_at       TEXT DEFAULT (datetime('now','localtime'))
        );
        CREATE INDEX IF NOT EXISTS idx_txn_date ON transactions(date);
        CREATE INDEX IF NOT EXISTS idx_txn_account ON transactions(account_id);
        CREATE INDEX IF NOT EXISTS idx_txn_category ON transactions(category);
        CREATE INDEX IF NOT EXISTS idx_txn_transfer ON transactions(transfer_pair_id);

        -- 导入日志（无账户关联——一个文件可路由到多个账户）
        CREATE TABLE IF NOT EXISTS import_log (
            id          INTEGER PRIMARY KEY AUTOINCREMENT,
            source_bill TEXT NOT NULL,
            filename    TEXT DEFAULT '',
            imported_at TEXT DEFAULT (datetime('now','localtime')),
            total       INTEGER DEFAULT 0,
            new         INTEGER DEFAULT 0,
            skipped     INTEGER DEFAULT 0
        );
    """)
    conn.commit()
    conn.close()
    print("✅ 数据库初始化完成（多账户多币种模型）")

def resolve_account(conn, name: str, currency: str) -> dict | None:
    """按名称+币种查找账户，返回 Row 或 None"""
    return conn.execute(
        "SELECT * FROM accounts WHERE name=? AND currency=?",
        (name, currency),
    ).fetchone()
```

- [ ] **Step 2: Test init**

Run: `python -c "from src.ft.db import init_db; init_db()"`
Expected: `✅ 数据库初始化完成（多账户多币种模型）`

Then: `sqlite3 ~/.ft/ft.db ".tables"`
Expected: `accounts  import_log  transactions`

```bash
# Clean up test db
rm -f ~/.ft/ft.db
```

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/db.py
git commit -m "refactor(db): new schema with multi-currency accounts, no hardcoded defaults"
```

---

### Task 3: Account CRUD (acct module)

**Files:**
- Create: `src/ft/acct.py` — Account add/list/rename/delete/activate

- [ ] **Step 1: Create `src/ft/acct.py`**

```python
"""账户增删改查"""
from .db import get_db
from .models import ACCOUNT_TYPES, ACCOUNT_LABELS, CURRENCIES, CURRENCY_SYMBOLS

def acct_add(name: str, type_: str, currency: str):
    """新增账户"""
    if type_ not in ACCOUNT_TYPES:
        print(f"❌ 无效账户类型: {type_}，可选: {', '.join(ACCOUNT_TYPES)}")
        return
    if currency not in CURRENCIES:
        print(f"❌ 无效币种: {currency}，可选: {', '.join(CURRENCIES)}")
        return
    conn = get_db()
    try:
        conn.execute(
            "INSERT INTO accounts(name, type, currency) VALUES (?, ?, ?)",
            (name, type_, currency),
        )
        conn.commit()
        label = ACCOUNT_LABELS.get(type_, type_)
        sym = CURRENCY_SYMBOLS.get(currency, "")
        print(f"✅ 已添加账户: {name} ({label} · {sym}{currency})")
    except Exception as e:
        if "UNIQUE" in str(e):
            print(f"❌ 账户已存在: {name} ({currency})")
        else:
            print(f"❌ 添加失败: {e}")
    finally:
        conn.close()

def acct_list():
    """列出所有账户及当前余额"""
    conn = get_db()
    rows = conn.execute("""
        SELECT a.*,
               (SELECT SUM(amount) FROM transactions t
                WHERE t.account_id=a.id AND t.category!='snapshot') as balance
        FROM accounts a
        ORDER BY a.sort_order, a.id
    """).fetchall()
    conn.close()

    if not rows:
        print("  📭 暂无账户，请使用 ft acct add 创建")
        return

    print(f"  {'#':>3} {'账户名':<20} {'类型':<8} {'币种':<6} {'余额':>12} {'活跃'}")
    print("  " + "-" * 60)
    for r in rows:
        label = ACCOUNT_LABELS.get(r["type"], r["type"])
        sym = CURRENCY_SYMBOLS.get(r["currency"], "")
        bal = r["balance"] or 0
        bal_str = f"{sym}{bal:>+.2f}" if bal != 0 else f"{sym}0.00"
        active = "✅" if r["is_active"] else "⛔"
        name_display = r["name"][:20]
        print(f"  {r['id']:>3} {name_display:<20} {label:<8} {r['currency']:<6} {bal_str:>12} {active}")

def acct_rename(old_name: str, new_name: str, currency: str):
    """重命名账户"""
    conn = get_db()
    cur = conn.execute(
        "UPDATE accounts SET name=? WHERE name=? AND currency=?",
        (new_name, old_name, currency),
    )
    conn.commit()
    affected = cur.rowcount
    conn.close()
    if affected:
        print(f"✅ 已重命名: {old_name}({currency}) → {new_name}")
    else:
        print(f"❌ 未找到账户: {old_name}({currency})")

def acct_delete(name: str, currency: str):
    """删除账户（需无关联交易）"""
    conn = get_db()
    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND currency=?",
        (name, currency),
    ).fetchone()
    if not acct:
        print(f"❌ 未找到账户: {name}({currency})")
        conn.close()
        return

    txn_count = conn.execute(
        "SELECT COUNT(*) FROM transactions WHERE account_id=?",
        (acct["id"],),
    ).fetchone()[0]
    if txn_count > 0:
        print(f"❌ 账户 '{name}({currency})' 有 {txn_count} 条关联交易，无法删除")
        conn.close()
        return

    conn.execute("DELETE FROM accounts WHERE id=?", (acct["id"],))
    conn.commit()
    conn.close()
    print(f"✅ 已删除账户: {name}({currency})")

def acct_activate(name: str, currency: str, active: bool = True):
    """启用/停用账户"""
    conn = get_db()
    cur = conn.execute(
        "UPDATE accounts SET is_active=? WHERE name=? AND currency=?",
        (1 if active else 0, name, currency),
    )
    conn.commit()
    affected = cur.rowcount
    conn.close()
    status = "启用" if active else "停用"
    if affected:
        print(f"✅ 已{status}账户: {name}({currency})")
    else:
        print(f"❌ 未找到账户: {name}({currency})")
```

- [ ] **Step 2: Quick smoke test**

```bash
cd ~/Projects/finance-tracker
python -c "
from src.ft.db import init_db, get_db
from src.ft.acct import acct_add, acct_list
init_db()
acct_add('工行借记卡', 'cash', 'CNY')
acct_add('IBKR', 'security', 'USD')
acct_add('富途', 'security', 'HKD')
acct_list()
"
```

Expected: 3 accounts listed with 0 balances.

```bash
rm -f ~/.ft/ft.db  # clean up
```

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/acct.py
git commit -m "feat(acct): account CRUD — add/list/rename/delete/activate"
```

---

### Task 4: Transaction Insert Helper

**Files:**
- Create: `src/ft/txn.py` — Insert transaction with account resolution

- [ ] **Step 1: Create `src/ft/txn.py`**

```python
"""交易插入工具 — 统一入口，自动从账户派生币种"""
from .db import get_db

def insert_txn(conn, *, date, amount, account_id, category,
               counterparty="", description="", payment_method="",
               source_bill="", source_file="",
               transfer_pair_id=None, exchange_rate=None,
               snapshot_balance=None):
    """插入一条交易记录，自动从账户派生 currency"""
    acct = conn.execute(
        "SELECT currency FROM accounts WHERE id=?",
        (account_id,),
    ).fetchone()
    if not acct:
        raise ValueError(f"账户不存在: id={account_id}")

    conn.execute(
        """INSERT INTO transactions
           (date, amount, account_id, currency, category, counterparty,
            description, payment_method, source_bill, source_file,
            transfer_pair_id, exchange_rate, snapshot_balance)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        (date, amount, account_id, acct["currency"], category,
         counterparty, description, payment_method, source_bill, source_file,
         transfer_pair_id, exchange_rate, snapshot_balance),
    )

def insert_snapshot(conn, *, account_id, date, balance):
    """插入校准快照记录（amount=0, category=snapshot）"""
    insert_txn(conn, date=date, amount=0, account_id=account_id,
               category="snapshot", snapshot_balance=balance)

def insert_transfer_pair(conn, *, from_account_id, to_account_id,
                         amount, date, rate=None, description=""):
    """插入一对转账记录（同币种或跨币种）"""
    from_acct = conn.execute(
        "SELECT currency FROM accounts WHERE id=?", (from_account_id,)
    ).fetchone()
    to_acct = conn.execute(
        "SELECT currency FROM accounts WHERE id=?", (to_account_id,)
    ).fetchone()

    # 获取新的 pair_id
    pair_id = conn.execute(
        "SELECT COALESCE(MAX(transfer_pair_id), 0) + 1 FROM transactions"
    ).fetchone()[0]

    # 计算目标金额
    if rate:
        to_amount = round(amount / rate, 2)
    else:
        to_amount = amount

    # 插入减方记录
    insert_txn(conn, date=date, amount=-amount,
               account_id=from_account_id, category="transfer",
               transfer_pair_id=pair_id,
               exchange_rate=rate if from_acct["currency"] != to_acct["currency"] else None,
               description=description or f"转账至 {to_acct['currency']}")

    # 插入加方记录
    insert_txn(conn, date=date, amount=to_amount,
               account_id=to_account_id, category="transfer",
               transfer_pair_id=pair_id,
               exchange_rate=None,  # 汇率只记在减方
               description=description or f"来自 {from_acct['currency']}")
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/txn.py
git commit -m "feat(txn): transaction insert helpers with account-derived currency"
```

---

### Task 5: Rewrite Importers (Account Lookup by Name+Currency)

**Files:**
- Modify: `src/ft/importers/alipay.py` — Replace hardcoded account IDs with name+currency lookup
- Modify: `src/ft/importers/wechat.py` — Same
- Modify: `src/ft/importers/icbc.py` — Same

#### Task 5a: Alipay Importer

- [ ] **Step 1: Rewrite `src/ft/importers/alipay.py`**

Key changes:
- Remove ALL hardcoded `cash_id`, `loan_id`, `lend_id` lookups
- Remove all credit card / huabei routing logic (that was an old-workaround)
- Add a function `_resolve_account(conn, payment_method, currency)` → account_id
- Each transaction: look up account by `(payment_method, currency)`
- Payment method comes from the 收/付款方式 column
- Currency: parse 交易币种 column if available, default to CNY
- Failures (no match): skip with a warning

```python
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
    skip = 0
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
            skip += 1
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
            skip += 1
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

    # 去重插入（全字段精确匹配，跨来源去重已由 reconcile 处理）
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
        from ..txn import insert_txn
        insert_txn(conn, **t)
        new_count += 1

    total_skip = skip + dedup_skipped
    conn.execute(
        "INSERT INTO import_log(source_bill, filename, total, new, skipped) VALUES (?, ?, ?, ?, ?)",
        ("alipay", path, len(txns), new_count, total_skip),
    )
    conn.commit()
    conn.close()

    print(f"✅ 支付宝导入完成: 新增{new_count}条, 跳过{total_skip}条")
    if account_mismatch:
        print(f"   ⚠️ 其中 {account_mismatch} 条因找不到匹配账户跳过")
```

- [ ] **Step 2: Rewrite `src/ft/importers/wechat.py`**

Similar pattern: remove hardcoded `cash_id`/`loan_id`, replace with `_resolve_account(conn, payment_method, currency)`.

```python
"""微信 Excel 账单导入 → 按支付方式+币种匹配账户"""
INCOME_OK = {"已存入零钱"}
REFUND_OK_PREFIX = "已退款"
EXPENSE_OK = {"支付成功", "已转账", "对方已收钱"}

from ..models import FOREIGN_EXCHANGE_KEYWORDS

def _resolve_account(conn, payment_method: str, currency: str) -> int | None:
    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND currency=? AND is_active=1",
        (payment_method, currency),
    ).fetchone()
    if acct:
        return acct["id"]
    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND is_active=1 LIMIT 1",
        (payment_method,),
    ).fetchone()
    return acct["id"] if acct else None

def import_wechat(path: str):
    try:
        import openpyxl
    except ImportError:
        print("❌ 需要 openpyxl: pip install openpyxl")
        return

    from ..db import get_db

    conn = get_db()

    wb = openpyxl.load_workbook(path)
    ws = wb.active

    header_row_i = None
    for i, row in enumerate(ws.iter_rows(min_row=1, max_row=50, values_only=True), 1):
        if row[0] and "交易时间" in str(row[0]):
            header_row_i = i
            break
    if not header_row_i:
        print("❌ 无法找到微信账单表头")
        conn.close()
        return

    header = [str(c or "") for c in next(ws.iter_rows(min_row=header_row_i, max_row=header_row_i, values_only=True))]
    h = {col: idx for idx, col in enumerate(header)}

    txns = []
    skip = 0
    account_mismatch = 0

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
        elif direction == "收入":
            pass
        else:
            continue

        if amount == 0:
            skip += 1
            continue

        payment_method = vals[h["支付方式"]] if "支付方式" in h else ""
        date_raw = vals[h["交易时间"]] if "交易时间" in h else ""
        date_str = date_raw[:10].replace("/", "-")
        counterparty = vals[h["交易对方"]] if "交易对方" in h else ""
        desc = vals[h["商品"]] if "商品" in h else ""

        # 币种 — 微信账单有"币种"列
        currency = "CNY"
        for cur_key in ("币种", "交易币种"):
            if cur_key in h:
                raw_cur = vals[h[cur_key]].strip().upper()
                if raw_cur in ("CNY", "USD", "HKD"):
                    currency = raw_cur
                break

        # 查找账户
        account_id = _resolve_account(conn, payment_method, currency)
        if account_id is None:
            print(f"  ⚠️ 未找到账户: 支付方式='{payment_method}' 币种={currency}，跳过该笔")
            account_mismatch += 1
            skip += 1
            continue

        is_refund = "退款" in status
        is_exchange = any(kw in (desc + counterparty) for kw in FOREIGN_EXCHANGE_KEYWORDS)
        is_credit_repay = "信用卡还款" in (desc + counterparty)

        # 分类
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

        txns.append({
            "date": date_str,
            "amount": amount,
            "account_id": account_id,
            "category": category,
            "counterparty": counterparty,
            "description": desc or counterparty,
            "payment_method": payment_method,
            "source_bill": "wechat",
            "source_file": path,
        })

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
               AND source_bill='wechat' AND category=? AND counterparty=?
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

    total_skip = skip + dedup_skipped
    conn.execute(
        "INSERT INTO import_log(source_bill, filename, total, new, skipped) VALUES (?, ?, ?, ?, ?)",
        ("wechat", path, len(txns), new_count, total_skip),
    )
    conn.commit()
    conn.close()

    print(f"✅ 微信导入完成: 新增{new_count}条, 跳过{total_skip}条")
    if account_mismatch:
        print(f"   ⚠️ 其中 {account_mismatch} 条因找不到匹配账户跳过")
```

- [ ] **Step 3: Rewrite `src/ft/importers/icbc.py`**

Key changes:
- Remove hardcoded `cash_id`/`loan_id` from `_import_debit` and `_import_credit`
- Replace with account lookup: for debit, find account named like the card number or use a default; for credit, find by card name
- This one is trickier — ICBC PDFs have card numbers, not payment_method names

Actually, ICBC is different — the PDF doesn't have a "payment_method" column like Alipay/WeChat. The bill itself IS the payment method. So we need a different approach:

- For ICBC debit: the bill IS "工行借记卡" — find account with name containing "借记卡" or "工行" and type=cash
- For ICBC credit: the bill IS the card name "工行信用卡(1200)" — find account with name matching and type=loan
- Currency: CNY for domestic cards (default), but some cards support USD/HKD

Let me simplify: we add a `--account` flag to `ft import --icbc` so the user specifies which account, and `--currency` if needed.

Actually, looking at the original code, it auto-detects debit vs credit. For debit, transactions always go to a single account (the card itself). For credit, same. So we can use a `--account` parameter:

```bash
ft import icbc.pdf --icbc --account "工行借记卡" --currency CNY
ft import icbc.pdf --icbc --account "工行信用卡(1200)" --currency CNY
```

Let me write the importers.

For ICBC, I'll keep the structure but:
1. Accept `--account` and `--currency` from CLI
2. Lookup by that name+currency
3. Replace _import_debit and _import_credit to use the resolved account_id

Actually, the simplest approach: `ft import --icbc` now takes `--account ACCT_NAME --currency CUR`. If not specified, try to auto-detect.

Let me simplify even more: just use `--account` as a required flag for ICBC imports.

I'll write the actual code in the plan.

- [ ] **Step 4: Commit all 3 importers**

```bash
cd ~/Projects/finance-tracker
git add src/ft/importers/alipay.py src/ft/importers/wechat.py src/ft/importers/icbc.py
git commit -m "refactor(importers): account lookup by name+currency, remove hardcoded IDs"
```

---

### Task 6: Transfer Module

**Files:**
- Create: `src/ft/transfer.py` — CLI-facing transfer command

- [ ] **Step 1: Create `src/ft/transfer.py`**

```python
"""转账/换汇 — CLI 层"""
from .db import get_db, resolve_account
from .txn import insert_transfer_pair
from .models import CURRENCY_SYMBOLS

def do_transfer(from_name: str, to_name: str, amount: float, *,
                rate: float | None = None, date: str | None = None,
                description: str = ""):
    """执行转账/换汇"""
    from datetime import date as dt_date
    if not date:
        date = str(dt_date.today())

    conn = get_db()

    # 解析账户
    from_row = conn.execute(
        "SELECT * FROM accounts WHERE name=? AND is_active=1",
        (from_name,),
    ).fetchone()
    if not from_row:
        print(f"❌ 未找到来源账户: {from_name}")
        conn.close()
        return

    to_row = conn.execute(
        "SELECT * FROM accounts WHERE name=? AND is_active=1",
        (to_name,),
    ).fetchone()
    if not to_row:
        print(f"❌ 未找到目标账户: {to_name}")
        conn.close()
        return

    # 校验同币种时不可带 rate
    if from_row["currency"] == to_row["currency"] and rate is not None:
        print("⚠️ 同币种转账无需 --rate，忽略")
        rate = None
    elif from_row["currency"] != to_row["currency"] and rate is None:
        print("❌ 跨币种转账需要 --rate（1 目标币种 = N 源币种）")
        conn.close()
        return

    insert_transfer_pair(
        conn, from_account_id=from_row["id"], to_account_id=to_row["id"],
        amount=amount, date=date, rate=rate, description=description,
    )
    conn.commit()
    conn.close()

    from_sym = CURRENCY_SYMBOLS.get(from_row["currency"], "")
    to_sym = CURRENCY_SYMBOLS.get(to_row["currency"], "")
    to_amount = round(amount / rate, 2) if rate else amount
    print(f"✅ 转账完成: {from_name} {from_sym}{amount:,.2f} → {to_name} {to_sym}{to_amount:,.2f}")
    if rate:
        print(f"   汇率: 1 {to_row['currency']} = {rate} {from_row['currency']}")
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/transfer.py
git commit -m "feat(transfer): cross-currency transfer with paired records"
```

---

### Task 7: Rewrite Report Module

**Files:**
- Modify: `src/ft/report.py` — Full rewrite for multi-currency grouped display

- [ ] **Step 1: Write new `src/ft/report.py`**

```python
"""报告 — 多币种分组展示，不折算"""
import sqlite3
from datetime import datetime
from .models import ACCOUNT_LABELS, ACCOUNT_ICONS, CATEGORY_LABELS, CURRENCY_SYMBOLS, SOURCE_LABELS

def _fmt(amount: float, currency: str) -> str:
    sym = CURRENCY_SYMBOLS.get(currency, "")
    return f"{sym}{amount:>+.2f}"

def report_networth(conn: sqlite3.Connection):
    """资产负债总览 — 按币种分组展示"""
    rows = conn.execute("""
        SELECT a.id, a.name, a.type, a.currency,
               (SELECT SUM(amount) FROM transactions t
                WHERE t.account_id=a.id AND t.category!='snapshot') as balance
        FROM accounts a
        WHERE a.is_active=1
        ORDER BY a.sort_order, a.id
    """).fetchall()

    print("  🏦 资产负债总览")
    print("  " + "=" * 46)

    # 按 currency 分组
    from collections import OrderedDict
    groups = OrderedDict()
    for r in rows:
        cur = r["currency"]
        if cur not in groups:
            groups[cur] = []
        groups[cur].append(r)

    for cur, accts in groups.items():
        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  [{cur}]")
        cur_total = 0
        for r in accts:
            bal = r["balance"] or 0
            cur_total += bal
            icon = ACCOUNT_ICONS.get(r["type"], " ")
            label = ACCOUNT_LABELS.get(r["type"], r["type"])
            print(f"    {icon} {r['name'][:16]:<16s} ({label})  {_fmt(bal, cur)}")

        print(f"    {'─' * 36}")
        print(f"    {'合计':<16s} {sym}{cur_total:>+10.2f}")

    return groups

def report_expense(conn: sqlite3.Connection, month: str = None):
    """消费分析 — 按币种分组"""
    # 给多币种做按币种分开展示
    for cur in ["CNY", "USD", "HKD"]:
        where = ["category='expense'", "currency=?"]
        params = [cur]
        if month:
            where.append("date LIKE ?")
            params.append(f"{month}%")
        wsql = " AND ".join(where)

        total = conn.execute(
            f"SELECT ROUND(SUM(ABS(amount)),2) as t FROM transactions WHERE {wsql}", params
        ).fetchone()["t"] or 0

        if total == 0:
            continue

        by_source = conn.execute(
            f"""SELECT source_bill, COUNT(*) as cnt,
                       ROUND(SUM(ABS(amount)),2) as total
                FROM transactions WHERE {wsql}
                GROUP BY source_bill ORDER BY total DESC""",
            params,
        ).fetchall()

        by_desc = conn.execute(
            f"""SELECT description, COUNT(*) as cnt,
                       ROUND(SUM(ABS(amount)),2) as total
                FROM transactions WHERE {wsql} AND description != ''
                GROUP BY description ORDER BY total DESC LIMIT 10""",
            params,
        ).fetchall()

        sym = CURRENCY_SYMBOLS.get(cur, "")
        title = f"📊 消费分析 [{cur}] {month or ''}"
        print(f"\n  {title}")
        print(f"    总支出: {sym}{total:.2f}")

        if by_source:
            print(f"  📥 按来源:")
            for r in by_source:
                label = SOURCE_LABELS.get(r["source_bill"], r["source_bill"])
                print(f"    {label:<12s} {sym}{r['total']:>10.2f} ({r['cnt']}笔)")

        if by_desc:
            print(f"  🏪 按商户:")
            for r in by_desc:
                print(f"    {r['description'][:16]:<16s} {sym}{r['total']:>8.2f}")

def report_income(conn: sqlite3.Connection, month: str = None):
    """收入分析 — 按币种分组"""
    for cur in ["CNY", "USD", "HKD"]:
        where = ["category='income'", "currency=?"]
        params = [cur]
        if month:
            where.append("date LIKE ?")
            params.append(f"{month}%")
        wsql = " AND ".join(where)

        total = conn.execute(
            f"SELECT ROUND(SUM(amount),2) as t FROM transactions WHERE {wsql}", params
        ).fetchone()["t"] or 0

        if total == 0:
            continue

        rows = conn.execute(
            f"""SELECT description, COUNT(*) as cnt,
                       ROUND(SUM(amount),2) as total
                FROM transactions WHERE {wsql}
                GROUP BY description ORDER BY total DESC LIMIT 10""",
            params,
        ).fetchall()

        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  📥 收入来源 [{cur}]")
        print(f"    总额 {sym}{total:.2f}")
        for r in rows:
            print(f"    {r['description'][:20]:<20s} {sym}{r['total']:>10.2f} ({r['cnt']}笔)")

def report_flow(conn: sqlite3.Connection):
    """资金流向: 转账汇总 — 按币种分组"""
    for cur in ["CNY", "USD", "HKD"]:
        rows = conn.execute("""
            SELECT t1.description, COUNT(*) as cnt,
                   ROUND(SUM(ABS(t1.amount)),2) as total
            FROM transactions t1
            WHERE t1.category='transfer' AND t1.amount < 0 AND t1.currency=?
            GROUP BY t1.description ORDER BY total DESC LIMIT 10
        """, (cur,)).fetchall()

        if not rows:
            continue

        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  🔄 内部转账 [{cur}]")
        for r in rows:
            print(f"    {r['description'][:20]:<20s} {sym}{r['total']:>10.2f} ({r['cnt']}笔)")

def list_txns(conn: sqlite3.Connection, month=None, account=None, category=None, limit=30):
    """列出交易 — 新增币种列"""
    where = []
    params = []

    if month:
        where.append("t.date LIKE ?")
        params.append(f"{month}%")
    if account:
        where.append("a.name=?")
        params.append(account)
    if category:
        where.append("t.category=?")
        params.append(category)

    wsql = " AND ".join(where) if where else "1=1"

    rows = conn.execute(f"""
        SELECT t.id, t.date, t.amount, t.currency, t.category, t.description,
               t.counterparty, t.source_bill, a.name as acct_name,
               t.snapshot_balance
        FROM transactions t
        JOIN accounts a ON t.account_id = a.id
        WHERE {wsql}
        ORDER BY t.date DESC, t.id DESC LIMIT ?
    """, params + [limit]).fetchall()

    if not rows:
        print("  📭 暂无记录")
        return

    print(f"  {'#':>4} {'日期':<12} {'账户':<14} {'币种':<5} {'类型':<6} {'金额':>12} {'说明'}")
    print("  " + "-" * 75)
    for r in rows:
        sym = CURRENCY_SYMBOLS.get(r["currency"], "")
        if r["category"] == "snapshot" and r["snapshot_balance"] is not None:
            amt_str = f"{sym}{r['snapshot_balance']:>8.2f}"
            cat_label = "📸校准"
        else:
            amt_str = f"{sym}{r['amount']:>+8.2f}" if abs(r["amount"]) > 0 else ""
            cat_label = CATEGORY_LABELS.get(r["category"], r["category"])
        desc = (r["description"] or r["counterparty"] or "")[:28]
        print(f"  {r['id']:>4} {r['date']:<12} {r['acct_name']:<14} {r['currency']:<5} {cat_label:<6} {amt_str:>12} {desc}")

def checkin(conn: sqlite3.Connection, account_name: str, balance: float, date_str: str = None):
    """记录余额快照"""
    if not date_str:
        date_str = datetime.now().strftime("%Y-%m-%d")

    acct = conn.execute(
        "SELECT id FROM accounts WHERE name=? AND is_active=1",
        (account_name,)
    ).fetchone()
    if not acct:
        print(f"❌ 未找到或已停用: {account_name}")
        return

    from .txn import insert_snapshot
    insert_snapshot(conn, account_id=acct["id"], date=date_str, balance=balance)
    conn.commit()

    sym = CURRENCY_SYMBOLS.get(
        conn.execute("SELECT currency FROM accounts WHERE id=?", (acct["id"],)).fetchone()["currency"],
        ""
    )
    print(f"✅ {account_name}: 余额校准 {sym}{balance:.2f} ({date_str})")

def show_log(conn: sqlite3.Connection):
    """导入历史（不变）"""
    rows = conn.execute(
        "SELECT * FROM import_log ORDER BY imported_at DESC LIMIT 20"
    ).fetchall()
    if not rows:
        print("  📭 暂无导入记录")
        return
    print("  📋 导入历史:")
    for r in rows:
        fn = r["filename"].split("/")[-1][:25] if r["filename"] else "-"
        label = SOURCE_LABELS.get(r["source_bill"], r["source_bill"])
        print(f"    {r['imported_at']} | {label:<8s} | {fn:<25s} | 总{r['total']:>4d} 新{r['new']:>4d} 跳{r['skipped']:>4d}")
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/report.py
git commit -m "refactor(report): multi-currency grouped display, snapshot support in list_txns"
```

---

### Task 8: Restructure CLI

**Files:**
- Modify: `src/ft/cli.py` — Add acct subcommand, wire transfer, modify import/report/checkin

- [ ] **Step 1: Rewrite `src/ft/cli.py`**

```python
"""Finance Tracker CLI — ft 统一入口"""
import argparse
import sys
from .db import get_db, init_db
from .report import (
    report_networth, report_expense, report_income, report_flow,
    list_txns, checkin, show_log,
)
from .acct import acct_add, acct_list, acct_rename, acct_delete, acct_activate
from .transfer import do_transfer
from .importers.alipay import import_alipay
from .importers.wechat import import_wechat
from .importers.icbc import import_icbc

def main():
    parser = argparse.ArgumentParser(prog="ft", description="📒 Finance Tracker")
    sub = parser.add_subparsers(dest="cmd")

    # init
    sub.add_parser("init", help="初始化数据库")

    # acct
    acct_p = sub.add_parser("acct", help="账户管理")
    acct_sub = acct_p.add_subparsers(dest="acct_cmd")

    acct_add_p = acct_sub.add_parser("add", help="新增账户")
    acct_add_p.add_argument("name")
    acct_add_p.add_argument("--type", required=True, choices=["cash", "loan", "lend", "security"])
    acct_add_p.add_argument("--currency", required=True, choices=["CNY", "USD", "HKD"])

    acct_list_p = acct_sub.add_parser("list", help="列出所有账户")

    acct_rename_p = acct_sub.add_parser("rename", help="重命名")
    acct_rename_p.add_argument("old_name")
    acct_rename_p.add_argument("new_name")
    acct_rename_p.add_argument("--currency", required=True)

    acct_delete_p = acct_sub.add_parser("delete", help="删除账户")
    acct_delete_p.add_argument("name")
    acct_delete_p.add_argument("--currency", required=True)

    acct_deact_p = acct_sub.add_parser("deactivate", help="停用账户")
    acct_deact_p.add_argument("name")
    acct_deact_p.add_argument("--currency", required=True)

    acct_act_p = acct_sub.add_parser("activate", help="启用账户")
    acct_act_p.add_argument("name")
    acct_act_p.add_argument("--currency", required=True)

    # import
    imp = sub.add_parser("import", help="导入账单")
    imp.add_argument("file")
    imp.add_argument("--alipay", action="store_true", help="支付宝CSV")
    imp.add_argument("--wechat", action="store_true", help="微信Excel")
    imp.add_argument("--icbc", action="store_true", help="工行PDF")
    imp.add_argument("--password", help="工行PDF密码")
    imp.add_argument("--account", help="工行账单账户名（必填）")
    imp.add_argument("--currency", default="CNY", choices=["CNY", "USD", "HKD"], help="工行账单币种")

    # report
    rpt = sub.add_parser("report", help="报告")
    rpt.add_argument("--month", help="月份 (YYYY-MM)")

    # income
    inc = sub.add_parser("income", help="收入明细")
    inc.add_argument("--month")

    # list
    lst = sub.add_parser("list", help="列出交易")
    lst.add_argument("--month")
    lst.add_argument("--account")
    lst.add_argument("--category", choices=["income", "expense", "transfer", "snapshot"])
    lst.add_argument("--limit", type=int, default=30)

    # checkin
    chk = sub.add_parser("checkin", help="记录余额快照")
    chk.add_argument("account", help="账户名")
    chk.add_argument("--balance", type=float, required=True)
    chk.add_argument("--date")

    # transfer
    trf = sub.add_parser("transfer", help="转账/换汇")
    trf.add_argument("--from", dest="from_acct", required=True)
    trf.add_argument("--to", dest="to_acct", required=True)
    trf.add_argument("--amount", type=float, required=True)
    trf.add_argument("--rate", type=float, help="跨币种汇率（1目标币=N源币）")
    trf.add_argument("--date")
    trf.add_argument("--description", default="")

    # log
    sub.add_parser("log", help="导入历史")

    args = parser.parse_args()

    if not args.cmd:
        parser.print_help()
        return

    if args.cmd == "init":
        init_db()
        return

    if args.cmd == "acct":
        if not args.acct_cmd:
            acct_list()
            return
        if args.acct_cmd == "add":
            acct_add(args.name, args.type, args.currency)
        elif args.acct_cmd == "list":
            acct_list()
        elif args.acct_cmd == "rename":
            acct_rename(args.old_name, args.new_name, args.currency)
        elif args.acct_cmd == "delete":
            acct_delete(args.name, args.currency)
        elif args.acct_cmd == "activate":
            acct_activate(args.name, args.currency, True)
        elif args.acct_cmd == "deactivate":
            acct_activate(args.name, args.currency, False)
        return

    conn = get_db()

    try:
        if args.cmd == "import":
            if args.icbc:
                if not args.password:
                    print("❌ 工行PDF需要 --password")
                    return
                if not args.account:
                    print("❌ 工行PDF需要 --account 指定账户名")
                    return
                import_icbc(args.file, args.password, args.account, args.currency)
            elif args.alipay:
                import_alipay(args.file)
            elif args.wechat:
                import_wechat(args.file)
            else:
                print("请指定导入类型: --alipay / --wechat / --icbc")
        elif args.cmd == "report":
            report_networth(conn)
            print()
            report_expense(conn, args.month)
            print()
            report_flow(conn)
            print()
            report_income(conn, args.month)
        elif args.cmd == "income":
            report_income(conn, args.month)
        elif args.cmd == "list":
            list_txns(conn, args.month, args.account, args.category, args.limit)
        elif args.cmd == "checkin":
            checkin(conn, args.account, args.balance, args.date)
        elif args.cmd == "transfer":
            do_transfer(args.from_acct, args.to_acct, args.amount,
                       rate=args.rate, date=args.date, description=args.description)
        elif args.cmd == "log":
            show_log(conn)
        elif args.cmd == "export":
            _export(conn, args.month, args.account, args.category)
    finally:
        if conn:
            conn.close()


def _export(conn, month, account, category):
    import csv
    where = []
    params = []
    if month:
        where.append("t.date LIKE ?")
        params.append(f"{month}%")
    if account:
        where.append("a.name=?")
        params.append(account)
    if category:
        where.append("t.category=?")
        params.append(category)

    wsql = " AND ".join(where) if where else "1=1"

    rows = conn.execute(f"""
        SELECT t.date, a.name as account, t.currency, t.category, t.amount, t.counterparty,
               t.description, t.source_bill
        FROM transactions t
        JOIN accounts a ON t.account_id = a.id
        WHERE {wsql}
        ORDER BY t.date
    """, params).fetchall()

    if not rows:
        print("📭 无数据可导出", file=sys.stderr)
        return

    w = csv.writer(sys.stdout)
    w.writerow(["日期", "账户", "币种", "类型", "金额", "对方", "说明", "来源"])
    for r in rows:
        w.writerow([r["date"], r["account"], r["currency"], r["category"],
                    r["amount"], r["counterparty"], r["description"], r["source_bill"]])
    print(f"已导出 {len(rows)} 条", file=sys.stderr)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/cli.py
git commit -m "refactor(cli): add acct and transfer subcommands, multi-currency import/report/checkin"
```

---

### Task 9: Rewrite ICBC Importer

**Files:**
- Modify: `src/ft/importers/icbc.py` — Accept account_name and currency parameters

- [ ] **Step 1: Write new `src/ft/importers/icbc.py`**

```python
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
    from ..txn import insert_txn
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

        # 找日期
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

        # 提取对方户名
        counterparty = ""
        for j in range(i + 1, min(len(lines), i + 6)):
            s = lines[j].strip()
            if s and not re.match(r"^[\d,]+\.\d{2}$", s):
                if s not in ("手机银行", "网上银行", "快捷支付", "其他", "批量业务", "(空)"):
                    counterparty = s
                    break

        # 摘要
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

    candidates = [c for c in candidates if c not in ("消费", "622599000000000000")]
    return candidates[0][:30] if candidates else ""
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/importers/icbc.py
git commit -m "refactor(icbc): accept account_name+currency, no hardcoded account IDs"
```

---

### Task 10: Remove reconcile.py

- [ ] **Step 1: Delete `src/ft/reconcile.py`**

The old reconcile logic (marking alipay/wechat expense as transfer when matching icbc_credit) no longer applies — with multi-account matching, this is handled at import time.

```bash
rm src/ft/reconcile.py
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/reconcile.py
git commit -m "refactor: remove reconcile.py (obsolete with new account matching)"
```

---

### Task 11: Write Tests

**Files:**
- Rewrite: `tests/test_import.py`

- [ ] **Step 1: Write tests for new multi-currency schema**

```python
"""Tests for multi-currency finance tracker"""
import pytest
import os
import tempfile
import sqlite3

# Point DB to temp dir before importing anything
import src.ft.models as m
TEST_DIR = tempfile.mkdtemp()
m.DB_DIR = TEST_DIR
m.DB_PATH = os.path.join(TEST_DIR, "ft.db")

from src.ft.db import init_db, get_db, resolve_account
from src.ft.acct import acct_add, acct_list, acct_rename, acct_delete
from src.ft.txn import insert_txn, insert_snapshot, insert_transfer_pair
from src.ft.report import report_networth, list_txns, checkin


@pytest.fixture(autouse=True)
def db():
    """Fresh DB per test"""
    if os.path.exists(m.DB_PATH):
        os.remove(m.DB_PATH)
    init_db()
    conn = get_db()
    yield conn
    conn.close()


class TestAccounts:
    def test_add_account(self, db):
        acct_add("工行借记卡", "cash", "CNY")
        row = resolve_account(db, "工行借记卡", "CNY")
        assert row is not None
        assert row["type"] == "cash"
        assert row["currency"] == "CNY"

    def test_add_duplicate(self, db):
        acct_add("IBKR", "security", "USD")
        acct_add("IBKR", "security", "USD")  # should warn, not crash
        rows = db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
        assert rows == 1  # unique constraint

    def test_same_name_different_currency(self, db):
        acct_add("工行信用卡(1200)", "loan", "CNY")
        acct_add("工行信用卡(1200)", "loan", "USD")
        rows = db.execute("SELECT COUNT(*) FROM accounts").fetchone()[0]
        assert rows == 2

    def test_delete_account_no_transactions(self, db):
        acct_add("Test", "cash", "CNY")
        acct_delete("Test", "CNY")
        row = resolve_account(db, "Test", "CNY")
        assert row is None

    def test_delete_account_with_transactions_fails(self, db):
        acct_add("Test", "cash", "CNY")
        acct = resolve_account(db, "Test", "CNY")
        insert_txn(db, date="2026-01-01", amount=100, account_id=acct["id"],
                   category="income")
        db.commit()
        acct_delete("Test", "CNY")  # should warn
        row = resolve_account(db, "Test", "CNY")
        assert row is not None  # not deleted

    def test_rename_account(self, db):
        acct_add("Old", "cash", "CNY")
        acct_rename("Old", "New", "CNY")
        assert resolve_account(db, "Old", "CNY") is None
        assert resolve_account(db, "New", "CNY") is not None

    def test_activate_deactivate(self, db):
        acct_add("Test", "cash", "CNY")
        from src.ft.acct import acct_activate
        acct_activate("Test", "CNY", False)
        row = resolve_account(db, "Test", "CNY")
        assert row is None  # resolve_account only finds active=1


class TestTransactions:
    def test_insert_txn_derives_currency(self, db):
        acct_add("Test", "cash", "USD")
        acct = resolve_account(db, "Test", "USD")
        insert_txn(db, date="2026-01-01", amount=100, account_id=acct["id"],
                   category="income")
        row = db.execute("SELECT * FROM transactions").fetchone()
        assert row["currency"] == "USD"
        assert row["amount"] == 100

    def test_insert_snapshot(self, db):
        acct_add("Test", "cash", "CNY")
        acct = resolve_account(db, "Test", "CNY")
        insert_snapshot(db, account_id=acct["id"], date="2026-06-01", balance=10000)
        row = db.execute("SELECT * FROM transactions").fetchone()
        assert row["category"] == "snapshot"
        assert row["snapshot_balance"] == 10000
        assert row["amount"] == 0

    def test_insert_transfer_pair_same_currency(self, db):
        acct_add("A", "cash", "CNY")
        acct_add("B", "cash", "CNY")
        a = resolve_account(db, "A", "CNY")
        b = resolve_account(db, "B", "CNY")
        insert_transfer_pair(db, from_account_id=a["id"], to_account_id=b["id"],
                            amount=5000, date="2026-06-10")
        rows = db.execute("SELECT * FROM transactions ORDER BY id").fetchall()
        assert len(rows) == 2
        assert rows[0]["amount"] == -5000  # outgoing
        assert rows[1]["amount"] == 5000   # incoming
        assert rows[0]["transfer_pair_id"] == rows[1]["transfer_pair_id"]
        assert rows[0]["exchange_rate"] is None  # same currency

    def test_insert_transfer_pair_cross_currency(self, db):
        acct_add("CNY卡", "cash", "CNY")
        acct_add("USD卡", "cash", "USD")
        cny = resolve_account(db, "CNY卡", "CNY")
        usd = resolve_account(db, "USD卡", "USD")
        insert_transfer_pair(db, from_account_id=cny["id"], to_account_id=usd["id"],
                            amount=7250, date="2026-06-10", rate=7.25)
        rows = db.execute("SELECT * FROM transactions ORDER BY id").fetchall()
        assert len(rows) == 2
        assert rows[0]["amount"] == -7250  # outgoing CNY
        assert rows[0]["exchange_rate"] == 7.25
        assert rows[1]["amount"] == 1000   # incoming USD (7250/7.25)
        assert rows[1]["exchange_rate"] is None

    def test_balance_calculation(self, db):
        acct_add("Test", "cash", "CNY")
        acct = resolve_account(db, "Test", "CNY")
        insert_txn(db, date="2026-01-01", amount=10000, account_id=acct["id"],
                   category="income")
        insert_txn(db, date="2026-01-02", amount=-500, account_id=acct["id"],
                   category="expense")
        insert_snapshot(db, account_id=acct["id"], date="2026-01-15", balance=9500)
        db.commit()

        # Balance from non-snapshot txns
        bal = db.execute(
            "SELECT SUM(amount) FROM transactions WHERE account_id=? AND category!='snapshot'",
            (acct["id"],)
        ).fetchone()[0]
        assert bal == 9500  # 10000 - 500

    def test_multi_currency_balances(self, db):
        acct_add("CNY卡", "cash", "CNY")
        acct_add("USD卡", "cash", "USD")
        cny = resolve_account(db, "CNY卡", "CNY")
        usd = resolve_account(db, "USD卡", "USD")

        insert_txn(db, date="2026-01-01", amount=10000, account_id=cny["id"], category="income")
        insert_txn(db, date="2026-01-01", amount=5000, account_id=usd["id"], category="income")
        db.commit()

        cny_bal = db.execute(
            "SELECT SUM(amount) FROM transactions WHERE account_id=? AND category!='snapshot'",
            (cny["id"],)
        ).fetchone()[0]
        usd_bal = db.execute(
            "SELECT SUM(amount) FROM transactions WHERE account_id=? AND category!='snapshot'",
            (usd["id"],)
        ).fetchone()[0]
        assert cny_bal == 10000
        assert usd_bal == 5000
```

- [ ] **Step 2: Run tests**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/ -v`
Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add tests/
git commit -m "test: rewrite tests for multi-currency schema"
```

---

### Task 12: Integration Smoke Test

- [ ] **Step 1: Run full CLI smoke test**

```bash
cd ~/Projects/finance-tracker

# Clean database
rm -f ~/.ft/ft.db

# Init
python -m src.ft.cli init

# Add accounts
python -m src.ft.cli acct add "工行借记卡" --type cash --currency CNY
python -m src.ft.cli acct add "工行信用卡(1200)" --type loan --currency CNY
python -m src.ft.cli acct add "IBKR" --type security --currency USD
python -m src.ft.cli acct add "富途" --type security --currency HKD

# List accounts
python -m src.ft.cli acct list

# Checkin balances
python -m src.ft.cli checkin "工行借记卡" --balance 50000
python -m src.ft.cli checkin "IBKR" --balance 12500
python -m src.ft.cli checkin "富途" --balance 80000

# Cross-currency transfer
python -m src.ft.cli transfer --from "工行借记卡" --to "IBKR" --amount 7250 --rate 7.25 --description "入金IBKR"

# List transactions
python -m src.ft.cli list

# Report
python -m src.ft.cli report
```

Expected: Clean output with multi-currency display, no errors.

- [ ] **Step 2: Commit any final fixes**

```bash
git add -A
git commit -m "fix: final adjustments from smoke test"
```

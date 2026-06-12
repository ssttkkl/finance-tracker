# CSV-Only Architecture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate SQLite from finance-tracker. All data stored as CSV files in `~/.ft/records/{type}/YYYY-MM-DD.csv`. Accounts in `~/.ft/accounts.yaml`.

**Architecture:** Convert/merge pipeline untouched. `load` replaced by `append` that routes records to type-dir CSVs. Reports scan CSV files with checkin-based balance reset. Accounts managed via YAML instead of SQL.

**Tech Stack:** Python 3.11+, PyYAML, csv, pytest

**Spec:** `docs/superpowers/specs/2026-06-12-csv-only-design.md`

---

## File Structure

**New:**
- `src/ft/accounts.py` — YAML account management: load/save/find by name
- `src/ft/append.py` — merged.csv → `records/{type}/YYYY-MM-DD.csv`

**Rename:**
- `src/ft/load.py` → **deleted** (replaced by append.py)

**Modify:**
- `src/ft/models.py` — remove DB_PATH, add RECORDS_DIR, ACCOUNTS_PATH; keep constants
- `src/ft/cli.py` — remove init/import/log, rename load→append, update all wiring
- `src/ft/report.py` — rewrite: scan CSVs, checkin-based balance reset
- `src/ft/transfer.py` — rewrite: write to CSV instead of DB
- `src/ft/acct.py` — rewrite: use accounts.py instead of DB

**Delete:**
- `src/ft/db.py` — SQLite connection/init
- `src/ft/txn.py` — transaction insert helpers

**Tests:**
- Replace: `tests/test_import.py` → `tests/test_accounts.py` (YAML CRUD)
- New: `tests/test_append.py` (append routing + sort)
- New: `tests/test_report_csv.py` (CSV-based reports with checkin reset)
- New: `tests/test_transfer_csv.py` (CSV-based transfers)
- Untouched: `tests/test_convert.py`, `tests/test_dedup.py`, `tests/test_ccb_debit.py`

---

### Task 1: Update models.py — New Paths, Drop DB

**Files:**
- Modify: `src/ft/models.py`

**No tests needed** — constants-only change.

- [ ] **Step 1: Rewrite models.py**

```python
"""数据模型常量"""
from pathlib import Path

# 数据目录
FT_DIR = Path.home() / ".ft"
RECORDS_DIR = FT_DIR / "records"
ACCOUNTS_PATH = FT_DIR / "accounts.yaml"

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
CATEGORIES = ("income", "expense", "transfer", "checkin")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "checkin": "校准",
}

# 来源
SOURCE_LABELS = {
    "alipay": "支付宝",
    "wechat": "微信",
    "icbc_credit": "工行信用卡",
    "icbc_debit": "工行借记卡",
}

# CSV 字段（10 列）
CSV_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
```

- [ ] **Step 2: Verify import**

Run: `cd ~/Projects/finance-tracker && python -c "from src.ft.models import RECORDS_DIR, ACCOUNTS_PATH, CSV_FIELDS; print(RECORDS_DIR, ACCOUNTS_PATH, CSV_FIELDS)"`
Expected: Print paths and field list without error.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/models.py
git commit -m "refactor(models): drop DB_PATH, add RECORDS_DIR + ACCOUNTS_PATH + CSV_FIELDS"
```

---

### Task 2: Create accounts.py — YAML Account Management

**Files:**
- Create: `src/ft/accounts.py`
- Create: `tests/test_accounts.py`

- [ ] **Step 1: Write failing test**

Create `tests/test_accounts.py`:

```python
"""Tests for YAML account management"""
import pytest
import tempfile
import yaml
from pathlib import Path
from ft.accounts import load_accounts, save_accounts, find_account, DEFAULT_ACCOUNTS_YAML

@pytest.fixture
def tmp_accounts_path():
    d = Path(tempfile.mkdtemp())
    p = d / "accounts.yaml"
    yield p
    # cleanup: remove temp dir
    import shutil
    shutil.rmtree(d, ignore_errors=True)

def test_load_default_creates_file(tmp_accounts_path):
    accounts = load_accounts(tmp_accounts_path)
    assert len(accounts) > 0
    assert tmp_accounts_path.exists()
    # Verify YAML structure
    data = yaml.safe_load(tmp_accounts_path.read_text())
    assert "accounts" in data
    first = data["accounts"][0]
    assert "name" in first
    assert "type" in first

def test_save_and_reload(tmp_accounts_path):
    accounts = [
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "IBKR", "type": "security", "currency": "USD", "active": True},
    ]
    save_accounts(accounts, tmp_accounts_path)
    loaded = load_accounts(tmp_accounts_path)
    assert len(loaded) == 2
    assert loaded[0]["name"] == "工行借记卡"
    assert loaded[1]["currency"] == "USD"

def test_find_account(tmp_accounts_path):
    accounts = [
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": False},
    ]
    save_accounts(accounts, tmp_accounts_path)
    # Exact match
    found = find_account("支付宝余额", tmp_accounts_path)
    assert found == accounts[0]
    # Not found
    assert find_account("nonexistent", tmp_accounts_path) is None

def test_find_account_not_found(tmp_accounts_path):
    accounts = [{"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True}]
    save_accounts(accounts, tmp_accounts_path)
    assert find_account("不存在的账户", tmp_accounts_path) is None

def test_add_account(tmp_accounts_path):
    load_accounts(tmp_accounts_path)  # ensure created
    from ft.accounts import add_account
    add_account("新账户", "cash", "CNY", tmp_accounts_path)
    found = find_account("新账户", tmp_accounts_path)
    assert found is not None
    assert found["type"] == "cash"
    assert found["active"] is True

def test_add_duplicate_name_currency(tmp_accounts_path):
    from ft.accounts import load_accounts, add_account
    load_accounts(tmp_accounts_path)
    add_account("新账户", "cash", "CNY", tmp_accounts_path)
    add_account("新账户", "loan", "CNY", tmp_accounts_path)  # should warn, not crash
    found = find_account("新账户", tmp_accounts_path)
    assert found is not None

def test_add_same_name_different_currency(tmp_accounts_path):
    from ft.accounts import load_accounts, add_account
    load_accounts(tmp_accounts_path)
    add_account("工行信用卡(1200)", "loan", "CNY", tmp_accounts_path)
    add_account("工行信用卡(1200)", "loan", "USD", tmp_accounts_path)
    accounts = load_accounts(tmp_accounts_path)
    names = [a["currency"] for a in accounts if a["name"] == "工行信用卡(1200)"]
    assert "CNY" in names
    assert "USD" in names
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_accounts.py -v`
Expected: All FAIL (module not found).

- [ ] **Step 3: Implement accounts.py**

Create `src/ft/accounts.py`:

```python
"""YAML account management"""
import yaml
from pathlib import Path
from .models import ACCOUNT_TYPES, CURRENCIES

DEFAULT_ACCOUNTS_YAML = """accounts:
  - name: 支付宝余额
    type: cash
    currency: CNY
    active: true
  - name: 微信零钱
    type: cash
    currency: CNY
    active: true
  - name: 工行借记卡
    type: cash
    currency: CNY
    active: true
  - name: 工行信用卡(1200)
    type: loan
    currency: CNY
    active: true
"""


def load_accounts(path=None) -> list[dict]:
    """Load accounts from YAML. Creates default if file doesn't exist."""
    if path is None:
        from .models import ACCOUNTS_PATH
        path = ACCOUNTS_PATH
    path = Path(path)

    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(DEFAULT_ACCOUNTS_YAML, encoding="utf-8")
        print(f"  📝 已创建默认账户: {path}")

    data = yaml.safe_load(path.read_text(encoding="utf-8"))
    return data.get("accounts", [])


def save_accounts(accounts: list[dict], path=None):
    """Write accounts back to YAML."""
    if path is None:
        from .models import ACCOUNTS_PATH
        path = ACCOUNTS_PATH
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        yaml.dump({"accounts": accounts}, f, allow_unicode=True, default_flow_style=False)


def find_account(name: str, path=None) -> dict | None:
    """Find account by name. Returns the first active match if multiple."""
    accounts = load_accounts(path)
    matches = [a for a in accounts if a["name"] == name]
    # Prefer active accounts
    active = [a for a in matches if a.get("active", True)]
    return active[0] if active else (matches[0] if matches else None)


def add_account(name: str, type_: str, currency: str, path=None):
    """Add a new account. Warns if name+currency exists."""
    accounts = load_accounts(path)

    # Check duplicate
    for a in accounts:
        if a["name"] == name and a["currency"] == currency:
            print(f"⚠️ 账户已存在: {name} ({currency})")
            return

    if type_ not in ACCOUNT_TYPES:
        print(f"❌ 无效账户类型: {type_}，可选: {', '.join(ACCOUNT_TYPES)}")
        return
    if currency not in CURRENCIES:
        print(f"❌ 无效币种: {currency}，可选: {', '.join(CURRENCIES)}")
        return

    accounts.append({
        "name": name,
        "type": type_,
        "currency": currency,
        "active": True,
    })
    save_accounts(accounts, path)
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_accounts.py -v`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/accounts.py tests/test_accounts.py
git commit -m "feat(accounts): YAML account management — load/save/find/add"
```

---

### Task 3: Rewrite acct.py — Wire to accounts.py

**Files:**
- Modify: `src/ft/acct.py`

**No separate tests** — covered by test_accounts.py + integration tests later.

- [ ] **Step 1: Rewrite acct.py**

```python
"""账户增删改查 — YAML backend"""
from .accounts import (
    load_accounts, save_accounts, find_account, add_account as _add_account,
)
from .models import ACCOUNT_TYPES, ACCOUNT_LABELS, CURRENCIES, CURRENCY_SYMBOLS, RECORDS_DIR


def _compute_balance(account_name: str, currency: str) -> float:
    """Compute balance from CSV files for one account."""
    from pathlib import Path
    import csv
    from datetime import datetime

    acct = find_account(account_name)
    if not acct:
        return 0.0

    type_dir = RECORDS_DIR / acct["type"]
    if not type_dir.exists():
        return 0.0

    # Collect all records for this account, sorted by date
    all_records = []
    for csv_file in sorted(type_dir.glob("*.csv")):
        with open(csv_file, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                if row.get("account_name", "").strip() != account_name:
                    continue
                dt = datetime.strptime(row["date"], "%Y-%m-%d %H:%M:%S")
                all_records.append((dt, row))

    if not all_records:
        return 0.0

    all_records.sort(key=lambda x: x[0])

    # Find last checkin
    last_checkin = None
    last_checkin_idx = -1
    for i, (dt, row) in enumerate(all_records):
        if row.get("category", "") == "checkin":
            last_checkin = row
            last_checkin_idx = i

    if last_checkin:
        # Parse snapshot balance from description
        desc = last_checkin.get("description", "")
        # Format: "余额校准¥5000.00" or "余额校准 ¥5000.00"
        import re
        m = re.search(r'[\d,]+\.?\d*', desc.replace(",", ""))
        balance = float(m.group()) if m else 0.0
        # Sum records after checkin
        for dt, row in all_records[last_checkin_idx + 1:]:
            cat = row.get("category", "")
            if cat in ("checkin", "transfer"):
                continue
            try:
                balance += float(row["amount"])
            except (ValueError, KeyError):
                pass
        return round(balance, 2)
    else:
        # Sum all non-checkin, non-transfer records
        balance = 0.0
        for dt, row in all_records:
            cat = row.get("category", "")
            if cat in ("checkin", "transfer"):
                continue
            try:
                balance += float(row["amount"])
            except (ValueError, KeyError):
                pass
        return round(balance, 2)


def acct_add(name: str, type_: str, currency: str):
    """新增账户"""
    _add_account(name.strip(), type_, currency)
    label = ACCOUNT_LABELS.get(type_, type_)
    sym = CURRENCY_SYMBOLS.get(currency, "")
    print(f"✅ 已添加账户: {name} ({label} · {sym}{currency})")


def acct_list():
    """列出所有账户及当前余额"""
    accounts = load_accounts()
    if not accounts:
        print("  📭 暂无账户，请使用 ft acct add 创建")
        return

    print(f"  {'账户名':<20} {'类型':<8} {'币种':<6} {'余额':>12} {'活跃'}")
    print("  " + "-" * 62)
    for a in accounts:
        label = ACCOUNT_LABELS.get(a["type"], a["type"])
        sym = CURRENCY_SYMBOLS.get(a["currency"], "")
        bal = _compute_balance(a["name"], a["currency"])
        bal_str = f"{sym}{bal:>+.2f}" if bal != 0 else f"{sym}0.00"
        active = "✅" if a.get("active", True) else "⛔"
        name_display = a["name"][:20]
        print(f"  {name_display:<20} {label:<8} {a['currency']:<6} {bal_str:>12} {active}")


def acct_rename(old_name: str, new_name: str, currency: str):
    """重命名账户"""
    new_name = new_name.strip()
    if not new_name:
        print("❌ 新账户名不能为空")
        return
    accounts = load_accounts()
    found = False
    for a in accounts:
        if a["name"] == old_name and a["currency"] == currency:
            a["name"] = new_name
            found = True
            break
    if found:
        save_accounts(accounts)
        print(f"✅ 已重命名: {old_name}({currency}) → {new_name}")
    else:
        print(f"❌ 未找到账户: {old_name}({currency})")


def acct_delete(name: str, currency: str):
    """删除账户"""
    accounts = load_accounts()
    new_accounts = [a for a in accounts if not (a["name"] == name and a["currency"] == currency)]
    if len(new_accounts) == len(accounts):
        print(f"❌ 未找到账户: {name}({currency})")
        return
    save_accounts(new_accounts)
    print(f"✅ 已删除账户: {name}({currency})")


def acct_activate(name: str, currency: str, active: bool = True):
    """启用/停用账户"""
    accounts = load_accounts()
    found = False
    for a in accounts:
        if a["name"] == name and a["currency"] == currency:
            a["active"] = active
            found = True
            break
    if found:
        save_accounts(accounts)
        status = "启用" if active else "停用"
        print(f"✅ 已{status}账户: {name}({currency})")
    else:
        print(f"❌ 未找到账户: {name}({currency})")
```

- [ ] **Step 2: Quick smoke test**

Run:
```bash
cd ~/Projects/finance-tracker
python -c "
from src.ft.acct import acct_add, acct_list
acct_add('测试账户', 'cash', 'CNY')
acct_list()
"
```

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/acct.py
git commit -m "refactor(acct): rewrite for YAML backend via accounts.py"
```

---

### Task 4: Create append.py — merged.csv → records CSV

**Files:**
- Create: `src/ft/append.py`
- Create: `tests/test_append.py`

- [ ] **Step 1: Write failing tests**

Create `tests/test_append.py`:

```python
"""Tests for CSV append (records management)"""
import pytest
import tempfile
import csv
import os
from pathlib import Path
from ft.accounts import save_accounts

@pytest.fixture
def tmp_env():
    """Setup temp .ft environment with records dir and accounts"""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path

    # Set up test accounts
    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    # Restore
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    import shutil
    shutil.rmtree(d, ignore_errors=True)

def create_merged_csv(path: Path, rows: list[dict]):
    """Helper: create a merged CSV file"""
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

def test_append_creates_date_file(tmp_env):
    records_dir, accounts_path = tmp_env
    merged_path = records_dir.parent / "merged.csv"
    create_merged_csv(merged_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "霸王茶姬", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "霸王茶姬",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(merged_path))

    # Verify file created
    day_csv = records_dir / "cash" / "2026-06-12.csv"
    assert day_csv.exists()

    with open(day_csv, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    assert len(rows) == 1
    assert rows[0]["account_name"] == "支付宝余额"
    assert rows[0]["amount"] == "-30.00"

def test_append_routes_by_type(tmp_env):
    records_dir, accounts_path = tmp_env
    merged_path = records_dir.parent / "merged.csv"
    create_merged_csv(merged_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
        {"date": "2026-06-12 11:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "工行信用卡(1200)", "source": "京东支付", "platform": "京东",
         "bill_source": "icbc_credit"},
    ])

    from ft.append import do_append
    do_append(str(merged_path))

    # cash type
    cash_csv = records_dir / "cash" / "2026-06-12.csv"
    assert cash_csv.exists()
    with open(cash_csv, encoding="utf-8") as f:
        cash_rows = list(csv.DictReader(f))
    assert len(cash_rows) == 1
    assert cash_rows[0]["account_name"] == "支付宝余额"

    # loan type
    loan_csv = records_dir / "loan" / "2026-06-12.csv"
    assert loan_csv.exists()
    with open(loan_csv, encoding="utf-8") as f:
        loan_rows = list(csv.DictReader(f))
    assert len(loan_rows) == 1
    assert loan_rows[0]["account_name"] == "工行信用卡(1200)"

def test_append_sorts_by_date(tmp_env):
    records_dir, accounts_path = tmp_env
    merged_path = records_dir.parent / "merged.csv"
    # Add out-of-order records
    create_merged_csv(merged_path, [
        {"date": "2026-06-12 12:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "午饭", "description": "午饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
        {"date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
         "counterparty": "早饭", "description": "早饭", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(merged_path))

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    # Should be sorted by date
    assert rows[0]["date"] == "2026-06-12 08:00:00"
    assert rows[1]["date"] == "2026-06-12 12:00:00"

def test_append_multiple_dates(tmp_env):
    records_dir, accounts_path = tmp_env
    merged_path = records_dir.parent / "merged.csv"
    create_merged_csv(merged_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
        {"date": "2026-06-13 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "外卖", "description": "外卖", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(merged_path))

    csv1 = records_dir / "cash" / "2026-06-12.csv"
    csv2 = records_dir / "cash" / "2026-06-13.csv"
    assert csv1.exists()
    assert csv2.exists()

def test_append_unknown_account(tmp_env):
    records_dir, accounts_path = tmp_env
    merged_path = records_dir.parent / "merged.csv"
    create_merged_csv(merged_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "不存在的账户", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(merged_path))

    # Should not create file for unknown account
    for t in ["cash", "loan", "lend", "security"]:
        day_csv = records_dir / t / "2026-06-12.csv"
        assert not day_csv.exists(), f"Should not create {day_csv}"
```

- [ ] **Step 2: Run test — expect FAIL**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_append.py -v`
Expected: All FAIL (module not found / import error).

- [ ] **Step 3: Implement append.py**

Create `src/ft/append.py`:

```python
"""append — merged CSV → records/{type}/YYYY-MM-DD.csv"""
import csv
import sys
from pathlib import Path
from collections import defaultdict
from .accounts import find_account, load_accounts
from .models import RECORDS_DIR, CSV_FIELDS


def do_append(merged_csv_path: str):
    """Read merged.csv, split by date, route to records/{type}/YYYY-MM-DD.csv."""
    merged_path = Path(merged_csv_path)
    if not merged_path.exists():
        print(f"❌ 文件不存在: {merged_csv_path}", file=sys.stderr)
        return

    # Preload account lookup
    accounts = load_accounts()
    acct_map = {a["name"]: a for a in accounts}

    # Read and group by (type, date)
    groups: dict[tuple[str, str], list[dict]] = defaultdict(list)
    stats: dict[str, int] = defaultdict(int)  # date label → count

    with open(merged_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            acct_name = row.get("account_name", "").strip()
            if not acct_name:
                print(f"  ⚠️ 跳过: account_name 为空", file=sys.stderr)
                continue

            acct = acct_map.get(acct_name)
            if not acct:
                print(f"  ❌ 未知账户: '{acct_name}'，请先 ft acct add", file=sys.stderr)
                continue

            date_str = row["date"][:10]  # YYYY-MM-DD
            typ = acct["type"]
            groups[(typ, date_str)].append(row)
            stats[date_str] += 1

    if not groups:
        print("📭 无数据", file=sys.stderr)
        return

    # Write each group
    for (typ, date_str), rows in groups.items():
        type_dir = RECORDS_DIR / typ
        type_dir.mkdir(parents=True, exist_ok=True)

        day_path = type_dir / f"{date_str}.csv"
        existing_rows = []

        if day_path.exists():
            # Read existing records
            with open(day_path, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                existing_rows = list(reader)

        # Merge and sort
        all_rows = existing_rows + rows
        all_rows.sort(key=lambda r: r["date"])

        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerows(all_rows)

    # Print stats grouped by date
    for date_str in sorted(stats):
        print(f"  {date_str}: +{stats[date_str]} 条")
    total = sum(stats.values())
    print(f"✅ 总计: 追加 {total} 条")
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_append.py -v`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/append.py tests/test_append.py
git commit -m "feat(append): merged.csv → records/{type}/YYYY-MM-DD.csv with sort"
```

---

### Task 5: Rewrite report.py — Scan CSVs with Checkin Reset

**Files:**
- Modify: `src/ft/report.py`
- Create: `tests/test_report_csv.py`

- [ ] **Step 1: Write failing tests**

Create `tests/test_report_csv.py`:

```python
"""Tests for CSV-based reports with checkin reset"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts

@pytest.fixture
def tmp_env():
    """Setup temp records dir with test data"""
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    import shutil
    shutil.rmtree(d, ignore_errors=True)

def write_csv(path: Path, rows: list[dict]):
    path.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

def test_networth_simple_sum(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "霸王茶姬",
         "bill_source": "alipay"},
        {"date": "2026-06-12 11:00:00", "amount": "+2000.00", "currency": "CNY",
         "counterparty": "工资", "description": "工资", "category": "income",
         "account_name": "支付宝余额", "source": "转账", "platform": "",
         "bill_source": ""},
    ])

    from ft.report import report_networth
    result = report_networth(records_dir)
    # result is dict: {currency: {account_name: balance}}
    assert "CNY" in result
    assert result["CNY"]["支付宝余额"] == 1950.0

def test_networth_with_checkin_reset(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-06-10.csv", [
        {"date": "2026-06-10 12:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
    ])
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "京东",
         "bill_source": "alipay"},
    ])

    from ft.report import report_networth
    result = report_networth(records_dir)
    # Before checkin: -100 (ignored). After checkin: 5000 - 200 = 4800
    assert result["CNY"]["支付宝余额"] == 4800.0

def test_networth_checkin_before_all_records(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 09:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 4900.0

def test_expense_with_checkin(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-01.csv", [
        {"date": "2026-06-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "超市", "description": "超市", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-06-10.csv", [
        {"date": "2026-06-10 12:00:00", "amount": "0", "currency": "CNY",
         "counterparty": "", "description": "余额校准¥5000.00", "category": "checkin",
         "account_name": "支付宝余额", "source": "手动", "platform": "",
         "bill_source": ""},
    ])
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-200.00", "currency": "CNY",
         "counterparty": "京东", "description": "耳机", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "京东",
         "bill_source": "alipay"},
    ])

    from ft.report import report_expense
    result = report_expense(records_dir, month="2026-06")
    # Only records after checkin count for expense
    assert result["CNY"]["total"] == 200.0

def test_month_filter(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-50.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])
    write_csv(records_dir / "cash" / "2026-07-01.csv", [
        {"date": "2026-07-01 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "外卖", "description": "外卖", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay"},
    ])

    from ft.report import report_expense
    result_june = report_expense(records_dir, month="2026-06")
    result_july = report_expense(records_dir, month="2026-07")
    assert result_june["CNY"]["total"] == 50.0
    assert result_july["CNY"]["total"] == 100.0
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_report_csv.py -v`
Expected: All FAIL.

- [ ] **Step 3: Implement report.py**

```python
"""报告 — 多币种分组展示，checkin 余额重置"""
from pathlib import Path
import csv
from datetime import datetime
from collections import defaultdict, OrderedDict
from .models import CURRENCY_SYMBOLS, RECORDS_DIR
from .accounts import load_accounts


def _fmt(amount: float, currency: str) -> str:
    sym = CURRENCY_SYMBOLS.get(currency, "")
    return f"{sym}{amount:>+.2f}"


def _read_records(records_dir=None, month=None) -> list[dict]:
    """Read all records from records/{type}/*.csv, optionally filtered by month."""
    if records_dir is None:
        records_dir = RECORDS_DIR
    records_dir = Path(records_dir)

    all_records = []
    if not records_dir.exists():
        return all_records

    for type_dir in sorted(records_dir.iterdir()):
        if not type_dir.is_dir():
            continue
        for csv_file in sorted(type_dir.glob("*.csv")):
            if month and not csv_file.stem.startswith(month):
                continue
            with open(csv_file, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                for row in reader:
                    all_records.append(row)
    return all_records


def _compute_balances(records_dir=None, month=None) -> dict:
    """Compute per-account balances with checkin reset.
    
    Returns: {currency: [(acct_name, balance, type, is_active), ...]}
    """
    accounts = load_accounts()
    acct_map = {a["name"]: a for a in accounts}

    all_records = _read_records(records_dir, month)
    # Sort all records by date for each account
    acct_records: dict[str, list[dict]] = defaultdict(list)
    for row in all_records:
        acct_name = row.get("account_name", "").strip()
        if acct_name:
            acct_records[acct_name].append(row)

    for acct_name in acct_records:
        acct_records[acct_name].sort(key=lambda r: r["date"])

    result: dict[str, list] = defaultdict(list)

    for acct_name, records in acct_records.items():
        acct = acct_map.get(acct_name)
        if not acct:
            continue
        if not acct.get("active", True):
            continue

        currency = acct["currency"]

        # Find last checkin
        last_checkin_idx = -1
        balance = 0.0
        for i, row in enumerate(records):
            if row.get("category", "") == "checkin":
                last_checkin_idx = i

        if last_checkin_idx >= 0:
            # Parse snapshot balance
            desc = records[last_checkin_idx].get("description", "")
            import re
            m = re.search(r'[\d,]+\.?\d*', desc.replace(",", ""))
            balance = float(m.group()) if m else 0.0
            start_idx = last_checkin_idx + 1
        else:
            start_idx = 0

        for row in records[start_idx:]:
            cat = row.get("category", "")
            if cat in ("checkin",):
                continue
            try:
                balance += float(row["amount"])
            except (ValueError, KeyError):
                pass

        result[currency].append((acct_name, round(balance, 2), acct["type"], True))

    return result


def report_networth(records_dir=None, month=None):
    """资产负债总览 — 按币种分组展示"""
    balances = _compute_balances(records_dir, month)
    ACCOUNT_ICONS = {"cash": "💰", "loan": "💳", "lend": "📤", "security": "📈"}
    ACCOUNT_LABELS = {"cash": "现金", "loan": "贷款", "lend": "借款", "security": "证券"}

    print("  🏦 资产负债总览")
    print("  " + "=" * 46)

    # Return structured data for testing
    result = {}
    for cur in sorted(balances.keys()):
        sym = CURRENCY_SYMBOLS.get(cur, "")
        result[cur] = {}
        print(f"\n  [{cur}]")
        cur_total = 0
        for name, bal, typ, _ in balances[cur]:
            cur_total += bal
            icon = ACCOUNT_ICONS.get(typ, " ")
            label = ACCOUNT_LABELS.get(typ, typ)
            print(f"    {icon} {name[:16]:<16s} ({label})  {_fmt(bal, cur)}")
            result[cur][name] = bal
        print(f"    {'─' * 36}")
        print(f"    {'合计':<16s} {sym}{cur_total:>+10.2f}")

    return result


def _filter_by_month(records, month):
    """Filter records for a specific month (YYYY-MM prefix match)."""
    if not month:
        return records
    return [r for r in records if r["date"].startswith(month)]


def report_expense(records_dir=None, month=None):
    """消费分析 — 按币种分组。return structured data for tests."""
    all_records = _read_records(records_dir, month)

    # Build checkin reset per account
    accounts = load_accounts()
    acct_map = {a["name"]: a for a in accounts}

    acct_records = defaultdict(list)
    for row in all_records:
        acct_name = row.get("account_name", "").strip()
        if acct_name:
            acct_records[acct_name].append(row)
    for n in acct_records:
        acct_records[n].sort(key=lambda r: r["date"])

    result = {}
    for acct_name, records in acct_records.items():
        acct = acct_map.get(acct_name)
        if not acct:
            continue

        # Find last checkin
        last_checkin_idx = -1
        for i, r in enumerate(records):
            if r.get("category") == "checkin":
                last_checkin_idx = i

        start_idx = last_checkin_idx + 1
        currency = acct["currency"]

        if currency not in result:
            result[currency] = {"total": 0.0}

        for r in records[start_idx:]:
            cat = r.get("category", "")
            if cat != "expense":
                continue
            try:
                amt = float(r["amount"])
                result[currency]["total"] += abs(amt)
            except (ValueError, KeyError):
                pass

    for cur in result:
        result[cur]["total"] = round(result[cur]["total"], 2)

    # Print
    for cur in sorted(result.keys()):
        if result[cur]["total"] == 0:
            continue
        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  📊 消费分析 [{cur}] {month or ''}")
        print(f"    总支出: {sym}{result[cur]['total']:.2f}")

    return result


def report_income(records_dir=None, month=None):
    """收入分析"""
    all_records = _read_records(records_dir, month)
    # Group by currency
    totals = defaultdict(float)
    for r in all_records:
        cat = r.get("category", "")
        cur = r.get("currency", "CNY")
        if cat == "income":
            try:
                totals[cur] += float(r["amount"])
            except (ValueError, KeyError):
                pass

    for cur in sorted(totals.keys()):
        totals[cur] = round(totals[cur], 2)
        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"\n  📥 收入来源 [{cur}]")
        print(f"    总额 {sym}{totals[cur]:.2f}")

    return dict(totals)


def report_flow(records_dir=None, month=None):
    """资金流向 — 转账汇总"""
    all_records = _read_records(records_dir, month)
    transfers = [r for r in all_records if r.get("category") == "transfer"]

    if not transfers:
        return

    # Group by description
    from collections import Counter
    by_desc = Counter()
    for r in transfers:
        try:
            amt = abs(float(r["amount"]))
        except (ValueError, KeyError):
            continue
        desc = r.get("description", "")
        cur = r.get("currency", "CNY")
        by_desc[(desc, cur)] += amt

    for (desc, cur), total in by_desc.most_common(10):
        sym = CURRENCY_SYMBOLS.get(cur, "")
        print(f"  🔄 {desc[:20]:<20s} {sym}{total:>10.2f}")


def list_txns(records_dir=None, month=None, account=None, category=None, limit=30):
    """列出交易"""
    all_records = _read_records(records_dir, month)

    # Filter
    if account:
        all_records = [r for r in all_records
                       if r.get("account_name", "").strip() == account]
    if category:
        all_records = [r for r in all_records
                       if r.get("category", "") == category]

    # Sort descending
    all_records.sort(key=lambda r: r["date"], reverse=True)
    all_records = all_records[:limit]

    if not all_records:
        print("  📭 暂无记录")
        return

    CATEGORY_LABELS = {"income": "收入", "expense": "支出",
                       "transfer": "转账", "checkin": "📸校准"}

    print(f"  {'日期':<21} {'账户':<16} {'币种':<5} {'类型':<6} {'金额':>12} {'说明'}")
    print("  " + "-" * 80)
    for r in all_records:
        sym = CURRENCY_SYMBOLS.get(r.get("currency", "CNY"), "")
        cat_label = CATEGORY_LABELS.get(r.get("category", ""), r.get("category", ""))
        try:
            amt = float(r["amount"])
        except (ValueError, KeyError):
            amt = 0
        if r.get("category") == "checkin":
            amt_str = r.get("description", "")[:12]
        else:
            amt_str = f"{sym}{amt:>+8.2f}" if abs(amt) > 0 else ""
        desc = (r.get("description") or r.get("counterparty") or "")[:30]
        acct = r.get("account_name", "")[:16]
        date_str = r.get("date", "")[:19]
        print(f"  {date_str:<21} {acct:<16} {r.get('currency','CNY'):<5} {cat_label:<6} {amt_str:>12} {desc}")
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_report_csv.py -v`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/report.py tests/test_report_csv.py
git commit -m "refactor(report): CSV-based reports with checkin balance reset"
```

---

### Task 6: Rewrite transfer.py — CSV Backend

**Files:**
- Modify: `src/ft/transfer.py`
- Create: `tests/test_transfer_csv.py`

- [ ] **Step 1: Write failing tests**

Create `tests/test_transfer_csv.py`:

```python
"""Tests for CSV-based transfers"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts

@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "IBKR", "type": "security", "currency": "USD", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    import shutil
    shutil.rmtree(d, ignore_errors=True)

def test_same_currency_transfer(tmp_env):
    records_dir, _ = tmp_env
    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="工行信用卡(1200)",
        amount=3000, date="2026-06-12"
    )

    # Check from account record
    from_csv = records_dir / "cash" / "2026-06-12.csv"
    assert from_csv.exists()
    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["account_name"] == "工行借记卡"
    assert float(rows[0]["amount"]) == -3000
    assert rows[0]["category"] == "transfer"

    # Check to account record
    to_csv = records_dir / "loan" / "2026-06-12.csv"
    assert to_csv.exists()
    with open(to_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["account_name"] == "工行信用卡(1200)"
    assert float(rows[0]["amount"]) == 3000
    assert rows[0]["category"] == "transfer"

def test_cross_currency_transfer(tmp_env):
    records_dir, _ = tmp_env
    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="IBKR",
        amount=36250, to_amount=5000, date="2026-06-12"
    )

    # from: CNY
    from_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert float(rows[0]["amount"]) == -36250
    assert rows[0]["currency"] == "CNY"
    assert "购汇至USD" in rows[0]["description"]

    # to: USD
    to_csv = records_dir / "security" / "2026-06-12.csv"
    with open(to_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert float(rows[0]["amount"]) == 5000
    assert rows[0]["currency"] == "USD"
    assert "购汇自CNY" in rows[0]["description"]

def test_transfer_sorts_file(tmp_env):
    records_dir, _ = tmp_env
    # Pre-populate file with an existing record
    from_csv = records_dir / "cash" / "2026-06-12.csv"
    from_csv.parent.mkdir(parents=True, exist_ok=True)
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
    with open(from_csv, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerow({"date": "2026-06-12 14:00:00", "amount": "-100",
                         "currency": "CNY", "counterparty": "超市",
                         "description": "超市", "category": "expense",
                         "account_name": "工行借记卡", "source": "支付宝",
                         "platform": "", "bill_source": "alipay"})

    from ft.transfer import do_transfer
    do_transfer(
        from_name="工行借记卡", to_name="工行信用卡(1200)",
        amount=3000, date="2026-06-12", time_str="10:00:00"
    )

    # Verify sorted
    with open(from_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
    assert rows[0]["date"] == "2026-06-12 10:00:00"  # transfer inserted
    assert rows[1]["date"] == "2026-06-12 14:00:00"  # existed
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_transfer_csv.py -v`
Expected: All FAIL.

- [ ] **Step 3: Implement transfer.py**

```python
"""转账/换汇 — CSV backend"""
import csv
from datetime import datetime
from pathlib import Path
from .accounts import find_account
from .models import CURRENCY_SYMBOLS, RECORDS_DIR, CSV_FIELDS


def _write_transfer_row(path: Path, date_str: str, amount: float, currency: str,
                        description: str, account_name: str):
    """Write a transfer row to a day CSV, then sort."""
    path.parent.mkdir(parents=True, exist_ok=True)

    existing = []
    if path.exists():
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            existing = list(reader)

    new_row = {
        "date": date_str,
        "amount": str(amount),
        "currency": currency,
        "counterparty": "",
        "description": description,
        "category": "transfer",
        "account_name": account_name,
        "source": "手动",
        "platform": "",
        "bill_source": "",
    }

    all_rows = existing + [new_row]
    all_rows.sort(key=lambda r: r["date"])

    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(all_rows)


def do_transfer(from_name: str, to_name: str, amount: float, *,
                to_amount: float = None, date: str = None,
                time_str: str = None, description: str = ""):
    """Execute a transfer between two accounts."""
    if not date:
        date = datetime.now().strftime("%Y-%m-%d")
    if not time_str:
        time_str = datetime.now().strftime("%H:%M:%S")
    date_str = f"{date} {time_str}"

    from_acct = find_account(from_name)
    if not from_acct:
        print(f"❌ 未找到来源账户: {from_name}")
        return

    to_acct = find_account(to_name)
    if not to_acct:
        print(f"❌ 未找到目标账户: {to_name}")
        return

    from_cur = from_acct["currency"]
    to_cur = to_acct["currency"]

    # Validate
    if from_cur == to_cur and to_amount is not None:
        print("⚠️ 同币种转账无需 --to-amount，忽略")
        to_amount = None
    elif from_cur != to_cur and to_amount is None:
        print("❌ 跨币种转账需要 --to-amount")
        return

    # Write from side
    from_path = RECORDS_DIR / from_acct["type"] / f"{date}.csv"
    from_desc = description or f"转账至{to_name}"
    if from_cur != to_cur:
        from_desc = description or f"购汇至{to_cur}"
    _write_transfer_row(from_path, date_str, -amount, from_cur, from_desc, from_name)

    # Write to side
    to_path = RECORDS_DIR / to_acct["type"] / f"{date}.csv"
    to_desc = description or f"来自{from_name}"
    if from_cur != to_cur:
        to_desc = description or f"购汇自{from_cur}"
    _write_transfer_row(to_path, date_str, to_amount or amount, to_cur, to_desc, to_name)

    # Print confirmation
    from_sym = CURRENCY_SYMBOLS.get(from_cur, "")
    to_sym = CURRENCY_SYMBOLS.get(to_cur, "")
    real_to = to_amount or amount
    print(f"✅ {from_name} {from_sym}{-amount:,.2f} → {to_name} {to_sym}{real_to:,.2f} ({date})")
    if from_cur != to_cur:
        rate = amount / real_to
        print(f"   汇率: 1 {to_cur} = {rate:.4f} {from_cur}")
```

- [ ] **Step 4: Run tests — expect PASS**

Run: `cd ~/Projects/finance-tracker && python -m pytest tests/test_transfer_csv.py -v`
Expected: All 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/transfer.py tests/test_transfer_csv.py
git commit -m "refactor(transfer): CSV backend with cross-currency support (--to-amount)"
```

---

### Task 7: Rewrite cli.py — Wire Everything

**Files:**
- Modify: `src/ft/cli.py`

**No separate tests** — covered by existing unit tests + integration tests.

- [ ] **Step 1: Rewrite cli.py**

```python
"""Finance Tracker CLI — ft 统一入口"""
import argparse
import sys
from .report import (
    report_networth, report_expense, report_income, report_flow, list_txns,
)
from .acct import acct_add, acct_list, acct_rename, acct_delete, acct_activate
from .transfer import do_transfer


def main():
    parser = argparse.ArgumentParser(prog="ft", description="📒 Finance Tracker")
    sub = parser.add_subparsers(dest="cmd")

    # acct
    acct_p = sub.add_parser("acct", help="账户管理")
    acct_sub = acct_p.add_subparsers(dest="acct_cmd")

    acct_add_p = acct_sub.add_parser("add", help="新增账户")
    acct_add_p.add_argument("name")
    acct_add_p.add_argument("--type", required=True,
                            choices=["cash", "loan", "lend", "security"])
    acct_add_p.add_argument("--currency", required=True,
                            choices=["CNY", "USD", "HKD"])

    acct_sub.add_parser("list", help="列出所有账户")

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

    # report
    rpt = sub.add_parser("report", help="资产负债 + 消费总览")
    rpt.add_argument("--month", help="月份 (YYYY-MM)")

    # list
    lst = sub.add_parser("list", help="列出交易")
    lst.add_argument("--month")
    lst.add_argument("--account")
    lst.add_argument("--category", choices=["income", "expense", "transfer", "checkin"])
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
    trf.add_argument("--to-amount", dest="to_amount", type=float,
                     help="跨币种目标金额")
    trf.add_argument("--date")
    trf.add_argument("--description", default="")

    # convert
    cv = sub.add_parser("convert", help="步骤① 账单→统一CSV")
    cv.add_argument("file", help="账单文件路径")
    cv.add_argument("-s", "--source", required=True,
                    choices=["alipay", "wechat", "icbc", "icbc-debit", "ccb-debit"],
                    help="账单类型")
    cv.add_argument("-o", "--output", required=True, help="输出CSV路径")
    cv.add_argument("--password", help="工行PDF密码")
    cv.add_argument("--account", help="覆盖账户名")
    cv.add_argument("--currency", default="CNY", choices=["CNY", "USD", "HKD"],
                    help="覆盖币种")

    # merge
    mg = sub.add_parser("merge", help="步骤② 合并去重CSV")
    mg.add_argument("files", nargs="+", help="输入CSV文件列表")
    mg.add_argument("-o", "--output", required=True, help="输出目录")

    # append
    ap = sub.add_parser("append", help="步骤③ 合并CSV落库")
    ap.add_argument("file", help="merged.csv 路径")

    args = parser.parse_args()

    if not args.cmd:
        parser.print_help()
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

    # 三步导入流水线
    if args.cmd == "convert":
        from .convert import do_convert
        do_convert(args.file, args.source, args.output,
                   password=args.password, account=args.account,
                   currency=args.currency)
        return

    if args.cmd == "merge":
        from .merge import do_merge
        do_merge(args.files, args.output)
        return

    if args.cmd == "append":
        from .append import do_append
        do_append(args.file)
        return

    if args.cmd == "report":
        report_networth()
        print()
        report_expense(month=args.month)
        print()
        report_flow()
        print()
        report_income(month=args.month)
        return

    if args.cmd == "list":
        list_txns(month=args.month, account=args.account,
                  category=args.category, limit=args.limit)
        return

    if args.cmd == "checkin":
        from datetime import datetime
        import csv
        from .accounts import find_account
        from .models import RECORDS_DIR, CSV_FIELDS

        if not args.date:
            date_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        else:
            date_str = args.date + " 00:00:00"

        acct = find_account(args.account)
        if not acct:
            print(f"❌ 未找到账户: {args.account}")
            return

        sym = {"CNY": "¥", "USD": "$", "HKD": "HK$"}.get(acct["currency"], "")
        from pathlib import Path

        type_dir = RECORDS_DIR / acct["type"]
        type_dir.mkdir(parents=True, exist_ok=True)
        day = date_str[:10]
        day_path = type_dir / f"{day}.csv"

        existing = []
        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                reader = csv.DictReader(f)
                existing = list(reader)

        new_row = {
            "date": date_str,
            "amount": "0",
            "currency": acct["currency"],
            "counterparty": "",
            "description": f"余额校准{sym}{args.balance:.2f}",
            "category": "checkin",
            "account_name": args.account,
            "source": "手动",
            "platform": "",
            "bill_source": "",
        }

        all_rows = existing + [new_row]
        all_rows.sort(key=lambda r: r["date"])

        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
            writer.writeheader()
            writer.writerows(all_rows)

        print(f"✅ {args.account}: 余额校准 {sym}{args.balance:.2f} ({day})")
        return

    if args.cmd == "transfer":
        do_transfer(
            from_name=args.from_acct, to_name=args.to_acct,
            amount=args.amount, to_amount=args.to_amount,
            date=args.date, description=args.description,
        )
        return


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Quick smoke test — acct**

```bash
cd ~/Projects/finance-tracker
rm -f ~/.ft/accounts.yaml
python -m ft acct list
```

Expected: Creates default accounts.yaml, shows default accounts.

- [ ] **Step 3: Commit**

```bash
cd ~/Projects/finance-tracker
git add src/ft/cli.py
git commit -m "refactor(cli): remove DB dep, wire CSV-based modules"
```

---

### Task 8: Cleanup — Remove DB Files + Verify All Tests

**Files:**
- Delete: `src/ft/db.py`
- Delete: `src/ft/txn.py`
- Delete: `src/ft/load.py`
- Modify: `tests/test_import.py` → replace with integration test

- [ ] **Step 1: Delete old files**

```bash
cd ~/Projects/finance-tracker
git rm src/ft/db.py src/ft/txn.py src/ft/load.py
```

- [ ] **Step 2: Replace test_import.py with integration test**

`tests/test_import.py` uses DB fixtures. Replace with a simple integration test:

```python
"""Integration tests for CSV-only pipeline"""
import pytest
import tempfile
import csv
from pathlib import Path
from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    records_dir = d / "records"
    accounts_path = d / "accounts.yaml"

    from ft import models
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    models.RECORDS_DIR = records_dir
    models.ACCOUNTS_PATH = accounts_path

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], accounts_path)

    yield records_dir, accounts_path

    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    import shutil
    shutil.rmtree(d, ignore_errors=True)


def test_full_pipeline_append_report(tmp_env):
    """End-to-end: append merged.csv → verify networth"""
    records_dir, _ = tmp_env

    # Simulate a merged CSV from convert+merge pipeline
    merged_path = records_dir.parent / "merged.csv"
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]
    with open(merged_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows([
            {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
             "counterparty": "奶茶", "description": "奶茶", "category": "expense",
             "account_name": "支付宝余额", "source": "支付宝", "platform": "霸王茶姬",
             "bill_source": "alipay"},
            {"date": "2026-06-12 11:00:00", "amount": "-200.00", "currency": "CNY",
             "counterparty": "京东", "description": "耳机", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "京东支付",
             "platform": "京东", "bill_source": "icbc_credit"},
            {"date": "2026-06-12 12:00:00", "amount": "+2000.00", "currency": "CNY",
             "counterparty": "工资", "description": "工资", "category": "income",
             "account_name": "支付宝余额", "source": "转账", "platform": "",
             "bill_source": ""},
        ])

    from ft.append import do_append
    do_append(str(merged_path))

    # Verify files created
    cash_csv = records_dir / "cash" / "2026-06-12.csv"
    loan_csv = records_dir / "loan" / "2026-06-12.csv"
    assert cash_csv.exists()
    assert loan_csv.exists()

    # Verify networth
    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 1970.0  # -30 + 2000
    assert result["CNY"]["工行信用卡(1200)"] == -200.0  # -200


def test_transfer_and_checkin_flow(tmp_env):
    """End-to-end: transfer → checkin → networth reflects reset"""
    records_dir, _ = tmp_env

    from ft.transfer import do_transfer
    do_transfer(
        from_name="支付宝余额", to_name="微信零钱",
        amount=500, date="2026-06-12"
    )

    # Checkin after transfer
    import csv
    from ft.accounts import find_account
    from ft.models import CSV_FIELDS

    cash_dir = records_dir / "cash"
    day_path = cash_dir / "2026-06-12.csv"
    # Append checkin row manually (simulating ft checkin)
    existing = []
    with open(day_path, encoding="utf-8") as f:
        existing = list(csv.DictReader(f))

    existing.append({
        "date": "2026-06-12 13:00:00", "amount": "0", "currency": "CNY",
        "counterparty": "", "description": "余额校准¥10000.00",
        "category": "checkin", "account_name": "支付宝余额",
        "source": "手动", "platform": "", "bill_source": "",
    })
    existing.sort(key=lambda r: r["date"])
    with open(day_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(existing)

    # Networth: checkin at 10000, after that no records → 10000
    from ft.report import report_networth
    result = report_networth(records_dir)
    assert result["CNY"]["支付宝余额"] == 10000.0
    assert result["CNY"]["微信零钱"] == 500.0
```

- [ ] **Step 3: Run all tests**

```bash
cd ~/Projects/finance-tracker
python -m pytest tests/ -v
```

Expected: New tests PASS. Old test_convert/test_dedup/test_ccb_debit may need updating if they import db modules.

- [ ] **Step 4: Fix any broken imports in old tests**

If `test_import.py` was the only DB-dependent test and we replaced it, old tests should be fine (convert/dedup/ccb_debit don't import db). If any breakage, fix inline.

- [ ] **Step 5: Commit**

```bash
cd ~/Projects/finance-tracker
git add tests/test_import.py
git commit -m "refactor(tests): replace DB-based tests with CSV pipeline integration tests"
```

---

### Task 9: Update Skill File

**Files:**
- Modify: `~/.hermes/skills/finance/finance-tracker/SKILL.md`

- [ ] **Step 1: Update skill to reflect CSV-only architecture**

Replace the section about `ft load` and database with `ft append` and CSV storage. Update the data flow diagram to show `records/{type}/YYYY-MM-DD.csv` instead of DB.

```bash
cd ~/.hermes
# Use skill_manage to patch the skill
```

- [ ] **Step 2: Commit**

```bash
cd ~/Projects/finance-tracker
git add docs/superpowers/plans/2026-06-12-csv-only-plan.md
git commit -m "docs(plan): CSV-only architecture implementation plan"
```

---

## Self-Review Checklist

- [x] Spec coverage: models, accounts, append, report, transfer, checkin, CLI — all covered
- [x] No placeholders: all code shown inline, no TBD/TODO
- [x] Type consistency: accounts.py `find_account(name)` signature matches usage in append/report/transfer
- [x] Category values: `checkin` (was `snapshot` in old DB model) — consistent across all modules
- [x] CSV_FIELDS defined in models.py and used in append + cli checkin — consistent

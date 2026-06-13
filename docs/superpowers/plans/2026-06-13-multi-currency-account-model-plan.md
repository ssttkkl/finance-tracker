# Multi-Currency Account Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `(account_name, currency)` the identity for cash, loan, and lend accounts so one logical account name can safely carry multiple currencies across append, snapshot rebuild, reports, and account listing.

**Architecture:** Keep CSV and `accounts.yaml` formats unchanged, but change in-memory lookup and snapshot storage for cash/loan/lend from flat `account_name -> balance` to nested `account_name -> currency -> balance`. Use compatibility reads for legacy snapshot data, but make all write paths emit the new nested shape and rebuild balances from records where possible.

**Tech Stack:** Python 3.11, pytest, YAML snapshot storage, CSV record files

---

## File Map

- Modify: `src/ft/accounts.py`
  Responsibility: account lookup semantics; add currency-aware lookup without breaking existing callers immediately.
- Modify: `src/ft/append.py`
  Responsibility: route appended rows using `(account_name, currency)` and update snapshot balances with currency-aware helpers.
- Modify: `src/ft/snapshot.py`
  Responsibility: snapshot schema, compatibility reads, currency-aware balance helpers, full rebuild from records.
- Modify: `src/ft/acct.py`
  Responsibility: account list and balance display for same-name multi-currency accounts.
- Modify: `src/ft/report.py`
  Responsibility: net worth and expense/income views against nested snapshot/account metadata without name collisions.
- Modify: `src/ft/models.py`
  Responsibility: category constants if helper updates require transfer compatibility cleanup while touching snapshot logic.
- Modify: `tests/test_accounts.py`
  Responsibility: verify same-name different-currency account lookup behavior.
- Modify: `tests/test_import.py`
  Responsibility: verify append and reporting still work when account identity includes currency.
- Modify: `tests/test_report_csv.py`
  Responsibility: verify report output and net worth grouping for same-name multi-currency accounts.
- Modify: `tests/test_snapshot.py`
  Responsibility: verify nested snapshot structure, compatibility read path, and rebuild behavior.
- Create or modify: `tests/test_append.py`
  Responsibility: verify routing with same-name multi-currency accounts.

### Task 1: Add Currency-Aware Account Lookup

**Files:**
- Modify: `src/ft/accounts.py`
- Modify: `tests/test_accounts.py`

- [ ] **Step 1: Write the failing tests**

Add these tests to `tests/test_accounts.py`:

```python
def test_find_account_by_name_and_currency(tmp_accounts_path):
    accounts = [
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ]
    save_accounts(accounts, tmp_accounts_path)

    found = find_account("工行信用卡(1200)", "USD", tmp_accounts_path)

    assert found is not None
    assert found["currency"] == "USD"


def test_find_account_by_name_without_currency_still_returns_active(tmp_accounts_path):
    accounts = [
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ]
    save_accounts(accounts, tmp_accounts_path)

    found = find_account("工行信用卡(1200)", path=tmp_accounts_path)

    assert found is not None
    assert found["name"] == "工行信用卡(1200)"


def test_find_account_by_name_and_missing_currency_returns_none(tmp_accounts_path):
    accounts = [
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ]
    save_accounts(accounts, tmp_accounts_path)

    assert find_account("工行信用卡(1200)", "USD", tmp_accounts_path) is None
```

- [ ] **Step 2: Run tests to verify failure**

Run: `uv run pytest tests/test_accounts.py -v`
Expected: FAIL because `find_account` does not accept a currency argument yet.

- [ ] **Step 3: Implement currency-aware account lookup**

Update `src/ft/accounts.py` so `find_account` accepts optional currency and prefers exact `(name, currency)` when provided:

```python
def find_account(
    name: str,
    currency: Optional[str] = None,
    path: Optional[Path] = None,
) -> Optional[dict]:
    """按名称查找账户。

    当提供 currency 时，按 (name, currency) 精确查找；
    否则保持旧行为，优先返回 active=True 的同名账户。
    """
    accounts = load_accounts(path)

    def _matches(acct: dict) -> bool:
        if acct.get("name") != name:
            return False
        if currency is not None and acct.get("currency") != currency:
            return False
        return True

    for acct in accounts:
        if _matches(acct) and acct.get("active", True):
            return acct

    for acct in accounts:
        if _matches(acct):
            return acct

    return None
```

- [ ] **Step 4: Run tests to verify pass**

Run: `uv run pytest tests/test_accounts.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/accounts.py tests/test_accounts.py
git commit -m "feat: add currency-aware account lookup"
```

### Task 2: Route Append by `(account_name, currency)`

**Files:**
- Modify: `src/ft/append.py`
- Modify: `tests/test_append.py`

- [ ] **Step 1: Write the failing test**

Add this test to `tests/test_append.py`:

```python
def test_append_routes_same_name_multi_currency_accounts(tmp_env):
    records_dir, accounts_path = tmp_env

    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], accounts_path)

    csv_path = records_dir.parent / "multi_currency.csv"
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows([
            {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
             "counterparty": "测试", "description": "", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
            {"date": "2026-06-12 11:00:00", "amount": "-10.00", "currency": "USD",
             "counterparty": "TEST", "description": "", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
        ])

    do_append([str(csv_path)])

    day_csv = records_dir / "loan" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    assert [row["currency"] for row in rows] == ["CNY", "USD"]
```

- [ ] **Step 2: Run the test to verify failure**

Run: `uv run pytest tests/test_append.py::test_append_routes_same_name_multi_currency_accounts -v`
Expected: FAIL or produce wrong lookup behavior because append maps accounts by name only.

- [ ] **Step 3: Implement append routing with `(name, currency)`**

Update the preload and row validation block in `src/ft/append.py`:

```python
accounts = load_accounts(models.ACCOUNTS_PATH)
acct_map = {(a["name"], a["currency"]): a for a in accounts}
```

and:

```python
acct_name = row.get("account_name", "").strip()
row_currency = row.get("currency", "").strip()
if not acct_name:
    raise ValueError("❌ append CSV 中存在 account_name 为空的记录")
if not row_currency:
    raise ValueError(f"❌ append CSV 中存在 currency 为空的记录 (account={acct_name})")

acct = acct_map.get((acct_name, row_currency))
if not acct:
    raise ValueError(
        f"❌ 账户 '{acct_name}({row_currency})' 不存在，请先 ft acct add 再重试"
    )
```

Keep the rest of the file unchanged in this task.

- [ ] **Step 4: Run the focused tests**

Run: `uv run pytest tests/test_append.py::test_append_routes_same_name_multi_currency_accounts tests/test_import.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/append.py tests/test_append.py
git commit -m "feat: route appended rows by account and currency"
```

### Task 3: Migrate Snapshot Cash/Loan/Lend to Nested Currency Balances

**Files:**
- Modify: `src/ft/snapshot.py`
- Modify: `tests/test_snapshot.py`

- [ ] **Step 1: Write the failing tests**

Add these tests to `tests/test_snapshot.py`:

```python
def test_set_balance_uses_nested_currency_buckets():
    snap = {"accounts": {"cash": {}, "loan": {}, "lend": {}, "security": {}}, "updated_at": ""}

    set_balance(snap, "工行信用卡(1200)", "loan", "CNY", -100.0)
    set_balance(snap, "工行信用卡(1200)", "loan", "USD", -10.0)

    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == -100.0
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["USD"] == -10.0


def test_update_balance_updates_matching_currency_only():
    snap = {
        "accounts": {
            "cash": {},
            "loan": {"工行信用卡(1200)": {"CNY": -100.0, "USD": -10.0}},
            "lend": {},
            "security": {},
        },
        "updated_at": "",
    }

    update_balance(snap, "工行信用卡(1200)", "USD", -5.0)

    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == -100.0
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["USD"] == -15.0
```

- [ ] **Step 2: Run tests to verify failure**

Run: `uv run pytest tests/test_snapshot.py -v`
Expected: FAIL because `set_balance` and `update_balance` only support flat balances.

- [ ] **Step 3: Implement nested snapshot helpers and compatibility read**

Update `src/ft/snapshot.py` helpers to accept currency:

```python
def set_balance(snap: dict, acct_name: str, typ: str, currency: str, balance: float) -> None:
    snap.setdefault("accounts", {}).setdefault(typ, {}).setdefault(acct_name, {})[currency] = balance


def update_balance(snap: dict, acct_name: str, currency: str, delta: float) -> None:
    search_types = ("cash", "loan", "lend")
    accounts = snap.get("accounts", {})
    for typ in search_types:
        accts = accounts.get(typ, {})
        acct_bucket = accts.get(acct_name)
        if isinstance(acct_bucket, dict) and currency in acct_bucket:
            acct_bucket[currency] += delta
            return
        if isinstance(acct_bucket, (int, float)):
            acct_bucket += delta
            accts[acct_name] = acct_bucket
            return
```

Update `get_balance` to accept optional currency and to read both nested and legacy flat values:

```python
def get_balance(acct_name: str, currency: Optional[str] = None, path: Optional[str] = None) -> tuple:
    snap = load_snapshot(path)
    for typ in ("cash", "loan", "lend"):
        accts = snap.get("accounts", {}).get(typ, {})
        if acct_name not in accts:
            continue
        bucket = accts[acct_name]
        if isinstance(bucket, dict):
            if currency is None:
                return bucket, typ
            if currency in bucket:
                return bucket[currency], typ
        else:
            return bucket, typ
    return None, None
```

Update `rebuild_snapshot_from_records` grouping key from `acct_name` to `(acct_name, currency)` and write nested balances:

```python
acct = row.get("account_name", "").strip()
currency = row.get("currency", "").strip() or "CNY"
if acct:
    acct_records[(acct, currency)].append(row)
```

and:

```python
for (acct_name, currency), records in acct_records.items():
    ...
    set_balance(snap, acct_name, typ, currency, round(bal, 2))
```

Keep legacy flat values readable, but all rebuild writes must use the nested form.

- [ ] **Step 4: Run the focused tests**

Run: `uv run pytest tests/test_snapshot.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/snapshot.py tests/test_snapshot.py
git commit -m "feat: store cash balances by account and currency"
```

### Task 4: Update Append Snapshot Mutations to Pass Currency

**Files:**
- Modify: `src/ft/append.py`
- Modify: `tests/test_import.py`

- [ ] **Step 1: Write the failing test**

Extend `tests/test_import.py` with this case:

```python
def test_append_updates_snapshot_by_account_and_currency(tmp_env):
    records_dir, accounts_path = tmp_env

    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], accounts_path)

    converted_path = records_dir.parent / "converted_multi_currency.csv"
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source"]
    with open(converted_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows([
            {"date": "2026-06-12 10:00:00", "amount": "-200.00", "currency": "CNY",
             "counterparty": "京东", "description": "耳机", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "京东支付", "bill_source": "icbc_credit"},
            {"date": "2026-06-12 11:00:00", "amount": "-10.00", "currency": "USD",
             "counterparty": "TEST", "description": "", "category": "expense",
             "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
        ])

    do_append([str(converted_path)])

    snap = load_snapshot()
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["CNY"] == -200.0
    assert snap["accounts"]["loan"]["工行信用卡(1200)"]["USD"] == -10.0
```

- [ ] **Step 2: Run the test to verify failure**

Run: `uv run pytest tests/test_import.py::test_append_updates_snapshot_by_account_and_currency -v`
Expected: FAIL because append still updates snapshot by account name only.

- [ ] **Step 3: Update append snapshot writes**

In `src/ft/append.py`, pass row currency into snapshot helpers:

```python
row_currency = row.get("currency", "").strip() or "CNY"
...
if cat == "checkin":
    ...
    if m:
        set_balance(snap, acct, typ, row_currency, float(m.group()))
elif cat != "transfer":
    try:
        update_balance(snap, acct, row_currency, float(row["amount"]))
    except (ValueError, KeyError):
        pass
```

If `transfer` handling is still present here, leave it untouched except for any
signature changes needed to satisfy the helper calls.

- [ ] **Step 4: Run focused tests**

Run: `uv run pytest tests/test_import.py tests/test_append.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/append.py tests/test_import.py
git commit -m "feat: update snapshot mutations with row currency"
```

### Task 5: Update Account List and Report Rendering

**Files:**
- Modify: `src/ft/acct.py`
- Modify: `src/ft/report.py`
- Modify: `tests/test_report_csv.py`

- [ ] **Step 1: Write the failing tests**

Add these checks to `tests/test_report_csv.py`:

```python
def test_networth_separates_same_name_multi_currency_accounts(tmp_env):
    records_dir, accounts_path = tmp_env

    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], accounts_path)

    snap = {"accounts": {"cash": {}, "loan": {"工行信用卡(1200)": {"CNY": -200.0, "USD": -10.0}}, "lend": {}, "security": {}}, "updated_at": ""}
    save_snapshot(snap)

    result = report_networth(records_dir)
    assert result["CNY"]["工行信用卡(1200) [CNY]"] == -200.0
    assert result["USD"]["工行信用卡(1200) [USD]"] == -10.0
```

- [ ] **Step 2: Run the test to verify failure**

Run: `uv run pytest tests/test_report_csv.py::test_networth_separates_same_name_multi_currency_accounts -v`
Expected: FAIL because report metadata is keyed only by account name.

- [ ] **Step 3: Implement multi-currency rendering**

In `src/ft/report.py`, replace the flat metadata map:

```python
acct_meta = {(a["name"], a["currency"]): a for a in load_acct_yaml()}
```

Expand nested balances in `report_networth`:

```python
for typ in ("cash", "loan", "lend"):
    for acct_name, balance_bucket in snap["accounts"].get(typ, {}).items():
        if isinstance(balance_bucket, dict):
            items = balance_bucket.items()
        else:
            items = [("CNY", balance_bucket)]
        for cur, balance in items:
            if abs(balance) < 0.005:
                continue
            if cur not in result:
                result[cur] = {}
            display_name = f"{acct_name} [{cur}]"
            result[cur][display_name] = balance
```

In `src/ft/acct.py`, update balance lookup to call the new `get_balance(name, currency)` and print one row per account entry from `accounts.yaml`.

- [ ] **Step 4: Run focused tests**

Run: `uv run pytest tests/test_report_csv.py tests/test_accounts.py -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/ft/acct.py src/ft/report.py tests/test_report_csv.py
git commit -m "feat: render same-name multi-currency balances separately"
```

### Task 6: Run Full Suite and Rebuild Local Snapshot

**Files:**
- Modify: `~/.ft/snapshot.yaml`

- [ ] **Step 1: Run full test suite**

Run: `uv run pytest`
Expected: PASS

- [ ] **Step 2: Rebuild local snapshot from records**

Run: `uv run ft verify --fix`
Expected: snapshot rebuilt successfully using records as source of truth.

- [ ] **Step 3: Inspect same-name multi-currency account output**

Run: `uv run ft report`
Expected: same-name multi-currency accounts appear as separate `账户名 [币种]` entries under the correct currency sections.

- [ ] **Step 4: Review working tree**

Run: `git -C ~/.ft status --short`
Expected: only expected snapshot or audit/data changes remain.

- [ ] **Step 5: Commit**

```bash
git add src/ft tests ~/.ft/snapshot.yaml
git commit -m "feat: support multi-currency balances per account"
```

## Self-Review

- Spec coverage:
  Account identity, append routing, snapshot nesting, compatibility reads, account list, report rendering, and rebuild-based migration are all mapped to explicit tasks.
- Placeholder scan:
  No `TBD`, `TODO`, or deferred implementation markers remain.
- Type consistency:
  Plan consistently uses nested `snap["accounts"][typ][acct_name][currency]` for cash/loan/lend and keeps security unchanged.

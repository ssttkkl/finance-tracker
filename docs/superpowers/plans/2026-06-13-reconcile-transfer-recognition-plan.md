# Reconcile Transfer Recognition Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `ft reconcile` to identify high-confidence transfer pairs, mark them as directional transfers, and exclude them from income/expense reports.

**Architecture:** Add a `transfer_account` column to normal cash/loan/lend records and keep stock records on their existing schema. Implement transfer recognition as a post-dedup phase inside `reconcile`, using normalized candidate objects, effective datetimes, and conservative rule-specific matchers. Keep low-confidence or ambiguous cases in audit output without mutating ledger rows.

**Tech Stack:** Python 3.11, pytest, CSV records, YAML snapshot storage

---

## File Map

- Modify: `src/ft/models.py`
  Responsibility: normal CSV fields and category constants.
- Modify: `src/ft/append.py`
  Responsibility: tolerate old input rows without `transfer_account`, write normal records with the expanded header, update snapshot skip logic.
- Modify: `src/ft/cli.py`
  Responsibility: `ft add`, `ft checkin`, and category filter choices.
- Modify: `src/ft/transfer.py`
  Responsibility: manual transfer writes use `transfer_out` / `transfer_in` and populate `transfer_account`.
- Modify: `src/ft/reconcile.py`
  Responsibility: read old/new normal records, read security flow records, run dedup, run transfer recognition, write touched normal files, write audit, rebuild snapshot.
- Modify: `src/ft/report.py`
  Responsibility: exclude directional transfers from income/expense and summarize transfer flows.
- Modify: `src/ft/snapshot.py`
  Responsibility: skip `transfer_in` / `transfer_out` during rebuild.
- Modify: `tests/test_append.py`
  Responsibility: validate expanded normal CSV header on append.
- Modify: `tests/test_transfer_csv.py`
  Responsibility: validate manual transfer categories and `transfer_account`.
- Modify: `tests/test_reconcile.py`
  Responsibility: validate transfer recognition rules, conflict behavior, audit output, and schema migration.
- Modify: `tests/test_report_csv.py`
  Responsibility: validate income/expense exclusion and transfer flow summary.
- Modify: `tests/test_snapshot.py`
  Responsibility: validate rebuild skips directional transfers.

### Task 1: Expand Normal CSV Schema

**Files:**
- Modify: `src/ft/models.py`
- Modify: `src/ft/append.py`
- Modify: `src/ft/cli.py`
- Modify: `tests/test_append.py`

- [ ] **Step 1: Write failing append schema tests**

Add these tests to `tests/test_append.py`:

```python
def test_append_writes_transfer_account_column(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted.csv"
    create_merged_csv(csv_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
         "counterparty": "奶茶", "description": "奶茶", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝",
         "bill_source": "alipay"},
    ])

    from ft.append import do_append
    do_append(str(csv_path))

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert "transfer_account" in reader.fieldnames
        rows = list(reader)
    assert rows[0]["transfer_account"] == ""


def test_append_preserves_input_transfer_account(tmp_env):
    records_dir, accounts_path = tmp_env
    csv_path = records_dir.parent / "converted_with_transfer_account.csv"
    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source", "transfer_account"]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-12 10:00:00", "amount": "-30.00", "currency": "CNY",
            "counterparty": "测试", "description": "", "category": "expense",
            "account_name": "支付宝余额", "source": "支付宝",
            "bill_source": "alipay", "transfer_account": "微信零钱",
        })

    from ft.append import do_append
    do_append(str(csv_path))

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert rows[0]["transfer_account"] == "微信零钱"
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_append.py::test_append_writes_transfer_account_column tests/test_append.py::test_append_preserves_input_transfer_account -v
```

Expected: FAIL because `models.CSV_FIELDS` does not include `transfer_account`.

- [ ] **Step 3: Update constants**

In `src/ft/models.py`, update the category constants and CSV fields:

```python
CATEGORIES = ("income", "expense", "transfer", "transfer_in", "transfer_out", "checkin")
CATEGORY_LABELS = {
    "income": "收入",
    "expense": "支出",
    "transfer": "转账",
    "transfer_in": "转入",
    "transfer_out": "转出",
    "checkin": "校准",
}

CSV_FIELDS = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "bill_source", "transfer_account"]
```

- [ ] **Step 4: Normalize missing normal fields in append**

In `src/ft/append.py`, add this helper near the top:

```python
def _normal_row(row: dict) -> dict:
    """Return a normal record row with every current CSV field present."""
    return {field: row.get(field, "") for field in models.CSV_FIELDS}
```

Then, when appending validated incoming rows, store normalized rows:

```python
incoming_rows.append((acct["type"], date_str, _normal_row(row)))
```

Leave validation reads from the original row before normalization.

- [ ] **Step 5: Update CLI category filter choices**

In `src/ft/cli.py`, update the `list --category` choices:

```python
lst.add_argument("--category", choices=["income", "expense", "transfer", "transfer_in", "transfer_out", "checkin"])
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
uv run pytest tests/test_append.py tests/test_cli.py -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/ft/models.py src/ft/append.py src/ft/cli.py tests/test_append.py
git commit -m "feat: add transfer account column to normal records"
```

### Task 2: Update Manual Transfer Writes

**Files:**
- Modify: `src/ft/transfer.py`
- Modify: `tests/test_transfer_csv.py`

- [ ] **Step 1: Write failing manual transfer tests**

Update `tests/test_transfer_csv.py` so the same-currency transfer expectations become:

```python
assert rows[0]["category"] == "transfer_out"
assert rows[0]["transfer_account"] == "微信零钱"
```

for the source-side file, and:

```python
assert rows[0]["category"] == "transfer_in"
assert rows[0]["transfer_account"] == "支付宝余额"
```

for the destination-side file.

Add equivalent assertions to `test_cross_currency_transfer`:

```python
assert from_rows[0]["category"] == "transfer_out"
assert from_rows[0]["transfer_account"] == "工行信用卡(1200)"
assert to_rows[0]["category"] == "transfer_in"
assert to_rows[0]["transfer_account"] == "支付宝余额"
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_transfer_csv.py::test_same_currency_transfer tests/test_transfer_csv.py::test_cross_currency_transfer -v
```

Expected: FAIL because manual transfers still write `category=transfer` and no `transfer_account`.

- [ ] **Step 3: Update transfer row writer signature**

In `src/ft/transfer.py`, change `_write_transfer_row` to accept category and counterpart:

```python
def _write_transfer_row(path: Path, date_str: str, amount: float, currency: str,
                        description: str, account_name: str,
                        category: str, transfer_account: str):
```

Build `new_row` as:

```python
new_row = {
    "date": date_str,
    "amount": str(amount),
    "currency": currency,
    "counterparty": "",
    "description": description,
    "category": category,
    "account_name": account_name,
    "source": "手动",
    "bill_source": "",
    "transfer_account": transfer_account,
}
```

When reading existing rows before writing, normalize them:

```python
existing_rows = [{field: row.get(field, "") for field in models.CSV_FIELDS} for row in reader]
```

- [ ] **Step 4: Update manual transfer call sites**

In `do_transfer`, update the source-side write:

```python
_write_transfer_row(
    from_path, date_str, -amount, from_cur, from_desc, from_name,
    "transfer_out", to_name,
)
```

Update the destination-side write:

```python
_write_transfer_row(
    to_path, date_str, to_amount or amount, to_cur, to_desc, to_name,
    "transfer_in", from_name,
)
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
uv run pytest tests/test_transfer_csv.py -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/ft/transfer.py tests/test_transfer_csv.py
git commit -m "feat: write directional manual transfers"
```

### Task 3: Extract Reconcile IO and Effective Time Helpers

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Write failing helper tests**

Add these tests to `tests/test_reconcile.py`:

```python
def test_reconcile_migrates_touched_file_to_transfer_account_column(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
    ])

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        reader = csv.DictReader(f)
        assert "transfer_account" in reader.fieldnames
```

Add this helper-level test:

```python
def test_effective_datetime_uses_time_from_description():
    from ft.reconcile import _effective_datetime

    row = {
        "date": "2026-04-17 00:00:00",
        "counterparty": "黄文龙",
        "description": "12:40:03",
        "source": "银行卡",
        "bill_source": "icbc_debit",
    }

    assert _effective_datetime(row).strftime("%Y-%m-%d %H:%M:%S") == "2026-04-17 12:40:03"
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_effective_datetime_uses_time_from_description tests/test_reconcile.py::test_reconcile_migrates_touched_file_to_transfer_account_column -v
```

Expected: FAIL because `_effective_datetime` does not exist and reconcile does not rewrite no-dedup touched transfer files.

- [ ] **Step 3: Add row normalization and effective datetime helpers**

In `src/ft/reconcile.py`, add imports:

```python
import re
```

Add helpers near `_in_scope`:

```python
TIME_RE = re.compile(r"\b([01]?\d|2[0-3]):[0-5]\d:[0-5]\d\b")


def _normal_row(row: dict) -> dict:
    return {field: row.get(field, "") for field in models.CSV_FIELDS}


def _clean_row(row: dict) -> dict:
    return _normal_row({k: v for k, v in row.items() if not k.startswith("_")})


def _effective_datetime(row: dict) -> datetime:
    dt = datetime.strptime(row["date"], "%Y-%m-%d %H:%M:%S")
    if dt.time() != datetime.min.time():
        return dt
    text = " ".join([
        row.get("counterparty", ""),
        row.get("description", ""),
        row.get("source", ""),
        row.get("bill_source", ""),
    ])
    match = TIME_RE.search(text)
    if not match:
        return dt
    hour, minute, second = map(int, match.group(0).split(":"))
    return dt.replace(hour=hour, minute=minute, second=second)
```

- [ ] **Step 4: Normalize normal rows during reconcile read/write**

In `do_reconcile`, when reading cash/loan/lend rows, change:

```python
row = dict(row)
```

to:

```python
row = _normal_row(dict(row))
```

When adding rows to `rows_by_file`, replace duplicated clean logic with:

```python
rows_by_file[row["_record_file"]].append(_clean_row(row))
```

For kept rows:

```python
rows_by_file[row["_record_file"]].append(_clean_row(row))
```

- [ ] **Step 5: Run focused tests**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_effective_datetime_uses_time_from_description tests/test_reconcile.py::test_reconcile_migrates_touched_file_to_transfer_account_column -v
```

Expected: PASS after Task 4 transfer recognition exists. If this still fails here because reconcile returns early when there are no dedup removals, leave the test failing until Task 4 and do not commit this task separately.

- [ ] **Step 6: Commit only if tests pass**

If the focused tests pass, commit:

```bash
git add src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: normalize reconcile rows and effective times"
```

If they do not pass because transfer recognition is not implemented, continue to Task 4 and commit Tasks 3 and 4 together.

### Task 4: Implement Same-Currency Transfer Recognition

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Add failing same-currency tests**

Add these tests to `tests/test_reconcile.py`:

```python
def test_reconcile_marks_same_currency_cash_transfer(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
        {"name": "微信零钱", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "微信零钱", "source": "微信", "bill_source": "wechat"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    by_amount = {row["amount"]: row for row in rows}
    assert by_amount["-100.00"]["category"] == "transfer_out"
    assert by_amount["-100.00"]["transfer_account"] == "微信零钱"
    assert by_amount["100.00"]["category"] == "transfer_in"
    assert by_amount["100.00"]["transfer_account"] == "支付宝余额"


def test_reconcile_does_not_mark_equal_consumption_without_transfer_signal(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "支付宝余额", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
        {"date": "2026-06-12 10:00:02", "amount": "40.00", "currency": "CNY",
         "counterparty": "北京市自来水集团", "description": "水费", "category": "income",
         "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert [row["category"] for row in rows] == ["expense", "income"]
    assert [row["transfer_account"] for row in rows] == ["", ""]
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_marks_same_currency_cash_transfer tests/test_reconcile.py::test_reconcile_does_not_mark_equal_consumption_without_transfer_signal -v
```

Expected: first test FAIL, second may PASS because nothing marks transfers yet.

- [ ] **Step 3: Add candidate helpers and same-currency matcher**

In `src/ft/reconcile.py`, add constants:

```python
TRANSFER_OUT = "transfer_out"
TRANSFER_IN = "transfer_in"
TRANSFER_CATEGORIES = {"transfer", TRANSFER_OUT, TRANSFER_IN}
STRONG_TRANSFER_SIGNALS = (
    "转账支取", "转账存入", "银联入账", "手机银行", "转帐",
    "还款", "花呗", "月付",
)
```

Add helper functions:

```python
def _amount(row: dict) -> float:
    return float(row.get("amount") or 0)


def _account_key(row: dict) -> tuple[str, str]:
    return (row.get("account_name", ""), row.get("currency", ""))


def _search_text(row: dict) -> str:
    return " ".join([
        row.get("counterparty", ""),
        row.get("description", ""),
        row.get("source", ""),
        row.get("bill_source", ""),
        row.get("account_name", ""),
    ])


def _has_signal(row_a: dict, row_b: dict, signals=STRONG_TRANSFER_SIGNALS) -> bool:
    text = _search_text(row_a) + " " + _search_text(row_b)
    return any(signal in text for signal in signals)


def _mark_transfer(out_row: dict, in_row: dict, rule: str) -> tuple[dict, dict]:
    out_row["category"] = TRANSFER_OUT
    out_row["transfer_account"] = in_row.get("account_name", "")
    out_row["_transfer_rule"] = rule
    in_row["category"] = TRANSFER_IN
    in_row["transfer_account"] = out_row.get("account_name", "")
    in_row["_transfer_rule"] = rule
    return out_row, in_row


def _match_same_currency_exact(rows: list[dict]) -> list[tuple[dict, dict, str]]:
    matches = []
    used = set()
    candidates = [
        row for row in rows
        if row.get("category") in ("income", "expense") and abs(_amount(row)) > 0
    ]
    for out_row in sorted([r for r in candidates if _amount(r) < 0], key=lambda r: r["date"]):
        if id(out_row) in used:
            continue
        possible = []
        for in_row in candidates:
            if id(in_row) in used or _amount(in_row) <= 0:
                continue
            if _account_key(out_row) == _account_key(in_row):
                continue
            if out_row.get("currency") != in_row.get("currency"):
                continue
            if abs(abs(_amount(out_row)) - abs(_amount(in_row))) > 0.01:
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff > 10:
                continue
            if not _has_signal(out_row, in_row):
                continue
            possible.append((diff, in_row))
        if len(possible) != 1:
            continue
        _diff, in_row = possible[0]
        used.add(id(out_row))
        used.add(id(in_row))
        matches.append((out_row, in_row, "same_currency_exact"))
    return matches
```

- [ ] **Step 4: Wire transfer recognition after dedup**

Refactor `do_reconcile` so it does not return early when no duplicates are removed.

After dedup returns `kept`, run:

```python
transfer_matches = _match_same_currency_exact(kept)
for out_row, in_row, rule in transfer_matches:
    _mark_transfer(out_row, in_row, rule)
```

Set:

```python
if not removed and not transfer_matches:
    print("无重复项")
    return
```

When building rows to write back, use the mutated `kept` list.

- [ ] **Step 5: Run focused tests**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_marks_same_currency_cash_transfer tests/test_reconcile.py::test_reconcile_does_not_mark_equal_consumption_without_transfer_signal tests/test_reconcile.py::test_reconcile_migrates_touched_file_to_transfer_account_column -v
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: recognize same-currency transfers in reconcile"
```

### Task 5: Add Foreign-Currency Loan Repayment Recognition

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Add failing FX repayment tests**

Add this test to `tests/test_reconcile.py`:

```python
def test_reconcile_marks_foreign_currency_credit_card_repayment(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "工行借记卡", "type": "cash", "currency": "CNY", "active": True},
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "USD", "active": True},
    ], models.ACCOUNTS_PATH)

    cash_path = models.RECORDS_DIR / "cash" / "2026-04-17.csv"
    loan_path = models.RECORDS_DIR / "loan" / "2026-04-17.csv"
    _write_rows(cash_path, [
        {"date": "2026-04-17 00:00:00", "amount": "-34.21", "currency": "CNY",
         "counterparty": "黄文龙", "description": "12:40:03", "category": "expense",
         "account_name": "工行借记卡", "source": "银行卡", "bill_source": "icbc_debit"},
    ])
    _write_rows(loan_path, [
        {"date": "2026-04-17 12:40:04", "amount": "5.00", "currency": "USD",
         "counterparty": "转帐", "description": "手机银行", "category": "income",
         "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-04")

    with open(cash_path, encoding="utf-8") as f:
        cash_rows = list(csv.DictReader(f))
    with open(loan_path, encoding="utf-8") as f:
        loan_rows = list(csv.DictReader(f))
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["transfer_account"] == "工行信用卡(1200)"
    assert loan_rows[0]["category"] == "transfer_in"
    assert loan_rows[0]["transfer_account"] == "工行借记卡"
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_marks_foreign_currency_credit_card_repayment -v
```

Expected: FAIL because only same-currency matching exists.

- [ ] **Step 3: Add account metadata lookup**

In `src/ft/reconcile.py`, import account loading:

```python
from .accounts import load_accounts
```

Add:

```python
def _account_type_map() -> dict[tuple[str, str], str]:
    return {(a["name"], a["currency"]): a["type"] for a in load_accounts()}
```

- [ ] **Step 4: Add FX matcher**

Add this helper:

```python
def _match_fx_loan_repayment(rows: list[dict], used_ids: set[int]) -> list[tuple[dict, dict, str]]:
    acct_types = _account_type_map()
    matches = []
    candidates = [
        row for row in rows
        if row.get("category") in ("income", "expense") and abs(_amount(row)) > 0
    ]
    out_rows = [
        row for row in candidates
        if id(row) not in used_ids and _amount(row) < 0
        and acct_types.get(_account_key(row)) == "cash"
    ]
    in_rows = [
        row for row in candidates
        if id(row) not in used_ids and _amount(row) > 0
        and acct_types.get(_account_key(row)) == "loan"
        and row.get("currency") != "CNY"
        and ("手机银行" in _search_text(row) or "转帐" in _search_text(row))
    ]
    for in_row in sorted(in_rows, key=lambda r: _effective_datetime(r)):
        possible = []
        for out_row in out_rows:
            if id(out_row) in used_ids:
                continue
            if out_row.get("currency") == in_row.get("currency"):
                continue
            diff = abs((_effective_datetime(out_row) - _effective_datetime(in_row)).total_seconds())
            if diff <= 10:
                possible.append((diff, abs(_amount(out_row)), out_row))
        if len(possible) != 1:
            continue
        _diff, _abs_amount, out_row = sorted(possible, key=lambda x: (x[0], x[1]))[0]
        used_ids.add(id(out_row))
        used_ids.add(id(in_row))
        matches.append((out_row, in_row, "fx_loan_repayment"))
    return matches
```

- [ ] **Step 5: Wire matcher after same-currency matching**

Change transfer recognition wiring:

```python
transfer_matches = _match_same_currency_exact(kept)
used_transfer_ids = {id(row) for match in transfer_matches for row in match[:2]}
transfer_matches.extend(_match_fx_loan_repayment(kept, used_transfer_ids))
for out_row, in_row, rule in transfer_matches:
    _mark_transfer(out_row, in_row, rule)
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_marks_foreign_currency_credit_card_repayment tests/test_reconcile.py::test_reconcile_marks_same_currency_cash_transfer -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: recognize fx loan repayments in reconcile"
```

### Task 6: Add Cash/Security Transfer Recognition

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Add failing cash/security test**

Add this test:

```python
def test_reconcile_links_cash_security_transfer_without_rewriting_security(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "建行储蓄卡(0523)", "type": "cash", "currency": "CNY", "active": True},
        {"name": "东方证券", "type": "security", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    cash_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    security_path = models.RECORDS_DIR / "security" / "2026-06-12.csv"
    _write_rows(cash_path, [
        {"date": "2026-06-12 00:00:00", "amount": "-10000.00", "currency": "CNY",
         "counterparty": "银行转证券", "description": "银转证", "category": "expense",
         "account_name": "建行储蓄卡(0523)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
    ])
    security_path.parent.mkdir(parents=True, exist_ok=True)
    with open(security_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "action", "ticker", "shares", "price", "amount",
            "commission", "currency", "account_name", "note",
        ])
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-12 00:00:00", "action": "DEPOSIT", "ticker": "",
            "shares": "0", "price": "0", "amount": "10000.00",
            "commission": "0", "currency": "CNY", "account_name": "东方证券",
            "note": "",
        })

    do_reconcile(month="2026-06")

    with open(cash_path, encoding="utf-8") as f:
        cash_rows = list(csv.DictReader(f))
    with open(security_path, encoding="utf-8") as f:
        security_fields = csv.DictReader(f).fieldnames
    assert cash_rows[0]["category"] == "transfer_out"
    assert cash_rows[0]["transfer_account"] == "东方证券"
    assert "transfer_account" not in security_fields
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_links_cash_security_transfer_without_rewriting_security -v
```

Expected: FAIL because reconcile does not read security flow records.

- [ ] **Step 3: Add security flow reader**

In `do_reconcile`, after reading normal entries, also read security flow rows into a separate `security_entries` list:

```python
security_entries: list[dict] = []
security_dir = models.RECORDS_DIR / "security"
if security_dir.exists():
    for csv_file in sorted(security_dir.glob("*.csv")):
        with open(csv_file, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                if row.get("action") not in ("DEPOSIT", "WITHDRAW"):
                    continue
                row = dict(row)
                row["_record_file"] = str(csv_file)
                row["_record_type"] = "security"
                row["_security_flow"] = "1"
                security_entries.append(row)
```

Make `security_scoped` use `_in_scope(row["date"], start, end)`.

- [ ] **Step 4: Add security candidate helpers**

Add:

```python
SECURITY_TRANSFER_SIGNALS = ("银转证", "证转银", "银行转证券", "证券转银行")


def _security_amount(row: dict) -> float:
    return float(row.get("amount") or 0)


def _security_search_text(row: dict) -> str:
    return " ".join([
        row.get("action", ""),
        row.get("ticker", ""),
        row.get("note", ""),
        row.get("account_name", ""),
    ])
```

Add matcher:

```python
def _match_cash_security_transfer(normal_rows: list[dict], security_rows: list[dict], used_ids: set[int]) -> list[tuple[dict, dict, str]]:
    matches = []
    cash_rows = [
        row for row in normal_rows
        if id(row) not in used_ids and row.get("_record_type") == "cash"
        and row.get("category") in ("income", "expense")
    ]
    for cash_row in cash_rows:
        cash_text = _search_text(cash_row)
        if not any(signal in cash_text for signal in SECURITY_TRANSFER_SIGNALS):
            continue
        cash_amt = _amount(cash_row)
        possible = []
        for sec_row in security_rows:
            if id(sec_row) in used_ids:
                continue
            if cash_row.get("currency") != sec_row.get("currency"):
                continue
            if cash_row["date"][:10] != sec_row["date"][:10]:
                continue
            sec_amt = _security_amount(sec_row)
            if abs(abs(cash_amt) - abs(sec_amt)) > 0.01:
                continue
            action = sec_row.get("action")
            if cash_amt < 0 and action == "DEPOSIT" and ("银转证" in cash_text or "银行转证券" in cash_text):
                possible.append(sec_row)
            elif cash_amt > 0 and action == "WITHDRAW" and ("证转银" in cash_text or "证券转银行" in cash_text):
                possible.append(sec_row)
        if len(possible) != 1:
            continue
        sec_row = possible[0]
        used_ids.add(id(cash_row))
        used_ids.add(id(sec_row))
        matches.append((cash_row, sec_row, "cash_security_transfer"))
    return matches
```

- [ ] **Step 5: Wire security matcher and row mutation**

After FX matching:

```python
security_matches = _match_cash_security_transfer(kept, security_scoped, used_transfer_ids)
for cash_row, sec_row, rule in security_matches:
    if _amount(cash_row) < 0:
        cash_row["category"] = TRANSFER_OUT
    else:
        cash_row["category"] = TRANSFER_IN
    cash_row["transfer_account"] = sec_row.get("account_name", "")
    cash_row["_transfer_rule"] = rule
```

Include `security_matches` in the condition that decides whether reconcile did work.

- [ ] **Step 6: Run focused test**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_links_cash_security_transfer_without_rewriting_security -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: link cash security transfers in reconcile"
```

### Task 7: Update Snapshot and Reports

**Files:**
- Modify: `src/ft/snapshot.py`
- Modify: `src/ft/report.py`
- Modify: `tests/test_snapshot.py`
- Modify: `tests/test_report_csv.py`

- [ ] **Step 1: Add failing snapshot test**

Add this to `tests/test_snapshot.py`:

```python
def test_rebuild_snapshot_skips_directional_transfers(tmp_env):
    from ft import models
    from ft.snapshot import rebuild_snapshot_from_records

    records_dir = tmp_env / "records"
    day_path = records_dir / "cash" / "2026-06-12.csv"
    day_path.parent.mkdir(parents=True, exist_ok=True)
    with open(day_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "amount", "currency", "counterparty", "description",
            "category", "account_name", "source", "bill_source", "transfer_account",
        ])
        writer.writeheader()
        writer.writerow({
            "date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
            "counterparty": "", "description": "", "category": "transfer_out",
            "account_name": "wallet", "source": "", "bill_source": "",
            "transfer_account": "bank",
        })

    snap = rebuild_snapshot_from_records(records_dir)
    assert snap["accounts"].get("cash", {}).get("wallet", {}).get("CNY") is None
```

Add `import csv` at the top of `tests/test_snapshot.py`.

- [ ] **Step 2: Add failing report tests**

Add this to `tests/test_report_csv.py`:

```python
def test_reports_exclude_directional_transfers(tmp_env):
    records_dir, _ = tmp_env
    write_csv(records_dir / "cash" / "2026-06-12.csv", [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "transfer_out",
         "account_name": "支付宝余额", "source": "支付宝", "platform": "",
         "bill_source": "alipay", "transfer_account": "微信零钱"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "transfer_in",
         "account_name": "微信零钱", "source": "微信", "platform": "",
         "bill_source": "wechat", "transfer_account": "支付宝余额"},
    ])

    from ft.report import report_expense, report_income
    assert report_expense(records_dir, month="2026-06") == {}
    assert report_income(records_dir, month="2026-06") == {}
```

- [ ] **Step 3: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_snapshot.py::test_rebuild_snapshot_skips_directional_transfers tests/test_report_csv.py::test_reports_exclude_directional_transfers -v
```

Expected: snapshot test FAIL because rebuild skips only `transfer`, report test may already pass for income/expense but still validates the behavior.

- [ ] **Step 4: Update snapshot skip logic**

In `src/ft/snapshot.py`, replace:

```python
if cat in ("checkin", "transfer"):
```

with:

```python
if cat in ("checkin", "transfer", "transfer_in", "transfer_out"):
```

- [ ] **Step 5: Update report labels and flow summary**

In `src/ft/report.py`, update `report_flow`:

```python
transfers = [r for r in all_records if r.get("category") in ("transfer", "transfer_in", "transfer_out")]
```

For directional rows, summarize only outflow side:

```python
for r in transfers:
    if r.get("category") == "transfer_in":
        continue
    try:
        amt = abs(float(r["amount"]))
    except (ValueError, KeyError):
        continue
    desc = r.get("transfer_account") or r.get("description", "")
    cur = r.get("currency", "CNY")
    by_desc[(desc, cur)] += amt
```

Update `CATEGORY_LABELS` inside `list_txns`:

```python
CATEGORY_LABELS = {"income": "收入", "expense": "支出",
                   "transfer": "转账", "transfer_in": "转入",
                   "transfer_out": "转出", "checkin": "📸校准"}
```

- [ ] **Step 6: Run focused tests**

Run:

```bash
uv run pytest tests/test_snapshot.py::test_rebuild_snapshot_skips_directional_transfers tests/test_report_csv.py::test_reports_exclude_directional_transfers tests/test_report_csv.py -v
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/ft/snapshot.py src/ft/report.py tests/test_snapshot.py tests/test_report_csv.py
git commit -m "feat: exclude directional transfers from reports"
```

### Task 8: Reconcile Audit and Ambiguity

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Add failing audit and ambiguity tests**

Add this audit assertion to `test_reconcile_marks_same_currency_cash_transfer`:

```python
audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
assert len(audit_files) == 1
with open(audit_files[0], encoding="utf-8") as f:
    audit_rows = list(csv.DictReader(f))
assert any(row.get("reconcile_status") == "transfer_matched" for row in audit_rows)
assert any(row.get("match_rule") == "same_currency_exact" for row in audit_rows)
```

Add this ambiguity test:

```python
def test_reconcile_leaves_ambiguous_transfer_candidates_unmodified(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    save_accounts([
        {"name": "A", "type": "cash", "currency": "CNY", "active": True},
        {"name": "B", "type": "cash", "currency": "CNY", "active": True},
        {"name": "C", "type": "cash", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    day_path = models.RECORDS_DIR / "cash" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:00", "amount": "-100.00", "currency": "CNY",
         "counterparty": "微信", "description": "转账支取", "category": "expense",
         "account_name": "A", "source": "银行", "bill_source": "bank"},
        {"date": "2026-06-12 10:00:02", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "B", "source": "银行", "bill_source": "bank"},
        {"date": "2026-06-12 10:00:03", "amount": "100.00", "currency": "CNY",
         "counterparty": "微信", "description": "银联入账", "category": "income",
         "account_name": "C", "source": "银行", "bill_source": "bank"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert [row["category"] for row in rows] == ["expense", "income", "income"]
    audit_files = list((models.FT_DIR / "audit" / "reconcile").glob("*.csv"))
    assert len(audit_files) == 1
    with open(audit_files[0], encoding="utf-8") as f:
        audit_rows = list(csv.DictReader(f))
    assert any(row.get("reconcile_status") == "transfer_candidate" for row in audit_rows)
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
uv run pytest tests/test_reconcile.py::test_reconcile_marks_same_currency_cash_transfer tests/test_reconcile.py::test_reconcile_leaves_ambiguous_transfer_candidates_unmodified -v
```

Expected: FAIL because transfer audit metadata and ambiguity audit do not exist yet.

- [ ] **Step 3: Extend audit writer fields**

Replace `_write_audit` with a version that accepts both dedup pairs and transfer audit rows:

```python
def _write_audit(run_at: str, scope_from: str, scope_to: str,
                 dedup_pairs: list[tuple[dict, dict]],
                 transfer_audit_rows: list[dict]) -> Path:
    path = _audit_path(run_at)
    fields = [
        "run_at", "scope_from", "scope_to", "date", "amount", "currency",
        "counterparty", "description", "category", "account_name", "source",
        "bill_source", "transfer_account", "record_file",
        "dedup_status", "reconcile_status", "transfer_side", "match_rule",
        "match_confidence", "counterpart_file", "counterpart_account",
        "counterpart_currency", "counterpart_amount",
    ]
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for keep_row, remove_row in dedup_pairs:
            for status, row in (("保留", keep_row), ("去除", remove_row)):
                clean = _clean_row(row)
                writer.writerow({
                    "run_at": run_at,
                    "scope_from": scope_from or "",
                    "scope_to": scope_to or "",
                    **clean,
                    "record_file": row.get("_record_file", ""),
                    "dedup_status": status,
                    "reconcile_status": "dedup",
                })
        for row in transfer_audit_rows:
            writer.writerow({
                "run_at": run_at,
                "scope_from": scope_from or "",
                "scope_to": scope_to or "",
                **{field: row.get(field, "") for field in fields
                   if field not in ("run_at", "scope_from", "scope_to")},
            })
    return path
```

- [ ] **Step 4: Create transfer audit row helpers**

Add:

```python
def _transfer_audit_row(row: dict, counterpart: dict, *, status: str, side: str, rule: str, confidence: str) -> dict:
    clean = _clean_row(row) if row.get("_record_type") != "security" else {
        "date": row.get("date", ""),
        "amount": row.get("amount", ""),
        "currency": row.get("currency", ""),
        "counterparty": row.get("ticker", ""),
        "description": row.get("note", ""),
        "category": row.get("action", ""),
        "account_name": row.get("account_name", ""),
        "source": "security",
        "bill_source": "security",
        "transfer_account": counterpart.get("account_name", ""),
    }
    clean.update({
        "record_file": row.get("_record_file", ""),
        "reconcile_status": status,
        "transfer_side": side,
        "match_rule": rule,
        "match_confidence": confidence,
        "counterpart_file": counterpart.get("_record_file", ""),
        "counterpart_account": counterpart.get("account_name", ""),
        "counterpart_currency": counterpart.get("currency", ""),
        "counterpart_amount": counterpart.get("amount", ""),
    })
    return clean
```

- [ ] **Step 5: Track ambiguous same-currency candidates**

Modify `_match_same_currency_exact` to return `(matches, candidate_audit_rows)`:

```python
candidate_audit = []
...
if len(possible) > 1:
    for _diff, in_row in possible:
        candidate_audit.append(_transfer_audit_row(
            out_row, in_row, status="transfer_candidate",
            side="out", rule="same_currency_exact", confidence="manual_review",
        ))
    continue
```

For successful matches, append two audit rows:

```python
transfer_audit_rows.append(_transfer_audit_row(
    out_row, in_row, status="transfer_matched", side="out",
    rule=rule, confidence="auto",
))
transfer_audit_rows.append(_transfer_audit_row(
    in_row, out_row, status="transfer_matched", side="in",
    rule=rule, confidence="auto",
))
```

- [ ] **Step 6: Write audit when any transfer audit rows exist**

After matching, call:

```python
audit_path = _write_audit(run_at, scope_from, scope_to, pairs, transfer_audit_rows)
```

Also ensure no-dedup/no-transfer still prints `无重复项` and returns without creating an audit file.

- [ ] **Step 7: Run focused tests**

Run:

```bash
uv run pytest tests/test_reconcile.py -v
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: audit reconcile transfer matches"
```

### Task 9: Full Verification

**Files:**
- No planned code edits.

- [ ] **Step 1: Run full suite**

Run:

```bash
uv run pytest
```

Expected: PASS.

- [ ] **Step 2: Run narrow real-data dry verification through report**

Run:

```bash
uv run ft reconcile --month 2026-04
uv run ft verify --fix
uv run ft report --month 2026-04
```

Expected:
- `ft reconcile` completes and writes an audit file if it finds matches.
- `ft verify --fix` passes.
- `ft report --month 2026-04` does not count recognized transfer rows in income or expense.

- [ ] **Step 3: Review data diff**

Run:

```bash
git -C ~/.ft status --short
git -C ~/.ft diff --stat
```

Expected: only expected `records`, `snapshot.yaml`, and `audit/reconcile` changes for the selected month.

- [ ] **Step 4: Commit code**

If the repo working tree has code/test changes:

```bash
git status --short
git add src/ft tests
git commit -m "feat: recognize transfers during reconcile"
```

- [ ] **Step 5: Commit data separately if requested**

Only if the user asks to keep the real-data reconciliation changes:

```bash
ft commit -m "chore: reconcile transfer records for 2026-04"
```

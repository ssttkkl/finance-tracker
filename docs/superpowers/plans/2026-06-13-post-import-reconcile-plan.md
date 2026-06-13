# Post-Import Reconcile Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the pre-import `merge` step with a post-import `reconcile` command, while keeping refund pairing and counterparty normalization inside `convert`.

**Architecture:** Keep `src/ft/dedup.py` as the pure cross-source duplicate matcher and add a new `src/ft/reconcile.py` orchestration layer for scanning records, filtering by time range, writing audit files, rewriting affected day CSVs, and rebuilding snapshot state. Expand `src/ft/append.py` to atomically import multiple converted CSV files in one pass, and update the CLI to expose `ft reconcile` while removing `ft merge`.

**Tech Stack:** Python, argparse, csv, pathlib, pytest, uv

**Spec:** `docs/superpowers/specs/2026-06-13-post-import-reconcile-design.md`

---

### Task 1: CLI Surface — remove `merge`, add multi-file `append`, add `reconcile`

**Files:**
- Modify: `src/ft/cli.py`
- Create: `tests/test_cli.py`

- [ ] **Step 1: Write the failing CLI parser tests**

Create `tests/test_cli.py` with:

```python
import pytest
from ft import cli


def test_append_accepts_multiple_files(monkeypatch):
    called = {}

    def fake_append(files):
        called["files"] = files

    monkeypatch.setattr("ft.append.do_append", fake_append)
    cli.main(["append", "a.csv", "b.csv"])
    assert called["files"] == ["a.csv", "b.csv"]


def test_reconcile_month_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--month", "2026-06"])
    assert called["args"] == ("2026-06", None, None)


def test_reconcile_range_dispatch(monkeypatch):
    called = {}

    def fake_reconcile(*, month=None, date_from=None, date_to=None):
        called["args"] = (month, date_from, date_to)

    monkeypatch.setattr("ft.reconcile.do_reconcile", fake_reconcile)
    cli.main(["reconcile", "--from", "2026-06-01", "--to", "2026-06-30"])
    assert called["args"] == (None, "2026-06-01", "2026-06-30")


def test_reconcile_rejects_month_plus_range():
    with pytest.raises(SystemExit):
        cli.main(["reconcile", "--month", "2026-06", "--from", "2026-06-01"])
```

- [ ] **Step 2: Run the new tests and confirm they fail**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_cli.py -q`

Expected: FAIL because `cli.main()` does not accept argv injection yet, `append` only accepts one file, and `reconcile` does not exist.

- [ ] **Step 3: Modify the parser and command dispatch**

Update `src/ft/cli.py`:

```python
def main(argv=None):
    parser = argparse.ArgumentParser(prog="ft", description="📒 Finance Tracker")
    sub = parser.add_subparsers(dest="cmd")
    ...
    # reconcile
    reconcile_p = sub.add_parser("reconcile", help="导入后统一对账整理")
    scope = reconcile_p.add_mutually_exclusive_group()
    scope.add_argument("--month", help="月份 (YYYY-MM)")
    reconcile_p.add_argument("--from", dest="date_from", help="起始日期 (YYYY-MM-DD)")
    reconcile_p.add_argument("--to", dest="date_to", help="结束日期 (YYYY-MM-DD)")
    ...
    # append
    ap = sub.add_parser("append", help="统一CSV落库")
    ap.add_argument("files", nargs="+", help="converted CSV 路径")
    ...
    args = parser.parse_args(argv)
    ...
    if args.cmd == "append":
        from .append import do_append
        do_append(args.files)
        return

    if args.cmd == "reconcile":
        if args.month and (args.date_from or args.date_to):
            parser.error("--month 与 --from/--to 不能同时使用")
        from .reconcile import do_reconcile
        do_reconcile(month=args.month, date_from=args.date_from, date_to=args.date_to)
        return
```

Also delete the `merge` parser block and the `if args.cmd == "merge"` dispatch branch.

- [ ] **Step 4: Run the CLI tests and confirm they pass**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_cli.py -q`

Expected: PASS

- [ ] **Step 5: Commit the CLI surface change**

```bash
cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker
git add src/ft/cli.py tests/test_cli.py
git commit -m "feat: replace merge CLI with reconcile"
```

### Task 2: Multi-File Append — atomically import multiple converted CSV files

**Files:**
- Modify: `src/ft/append.py`
- Modify: `tests/test_append.py`
- Modify: `tests/test_import.py`

- [ ] **Step 1: Write the failing append tests**

Append these tests to `tests/test_append.py`:

```python
def test_append_accepts_multiple_input_files(tmp_env):
    records_dir, _ = tmp_env
    path_a = records_dir.parent / "a.csv"
    path_b = records_dir.parent / "b.csv"

    create_merged_csv(path_a, [{
        "date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
        "counterparty": "早餐", "description": "早餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])
    create_merged_csv(path_b, [{
        "date": "2026-06-12 09:00:00", "amount": "-20.00", "currency": "CNY",
        "counterparty": "午餐", "description": "午餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])

    from ft.append import do_append
    do_append([str(path_a), str(path_b)])

    day_csv = records_dir / "cash" / "2026-06-12.csv"
    with open(day_csv, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert [r["amount"] for r in rows] == ["-10.00", "-20.00"]


def test_append_is_atomic_across_multiple_files(tmp_env):
    records_dir, _ = tmp_env
    good_path = records_dir.parent / "good.csv"
    bad_path = records_dir.parent / "bad.csv"

    create_merged_csv(good_path, [{
        "date": "2026-06-12 08:00:00", "amount": "-10.00", "currency": "CNY",
        "counterparty": "早餐", "description": "早餐", "category": "expense",
        "account_name": "支付宝余额", "source": "支付宝", "bill_source": "alipay",
    }])
    create_merged_csv(bad_path, [{
        "date": "2026-06-12 09:00:00", "amount": "-20.00", "currency": "CNY",
        "counterparty": "午餐", "description": "午餐", "category": "expense",
        "account_name": "不存在的账户", "source": "支付宝", "bill_source": "alipay",
    }])

    from ft.append import do_append
    with pytest.raises(ValueError, match="不存在的账户"):
        do_append([str(good_path), str(bad_path)])

    assert not (records_dir / "cash" / "2026-06-12.csv").exists()
```

Update `tests/test_import.py` to rename the pipeline fixture description and pass a list into `do_append`:

```python
    from ft.append import do_append
    do_append([str(merged_path)])
```

- [ ] **Step 2: Run the append-focused tests and confirm they fail**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_append.py tests/test_import.py -q`

Expected: FAIL because `do_append()` only accepts a single path string.

- [ ] **Step 3: Refactor `do_append()` to load, validate, and write atomically**

Replace the top-level flow in `src/ft/append.py` with:

```python
def do_append(csv_paths: list[str]):
    records_dir = models.RECORDS_DIR
    csv_fields = models.CSV_FIELDS

    accounts = load_accounts(models.ACCOUNTS_PATH)
    acct_map = {a["name"]: a for a in accounts}

    incoming_rows = []
    stats = defaultdict(int)

    for csv_path in csv_paths:
        path = Path(csv_path)
        if not path.exists():
            raise FileNotFoundError(f"❌ 文件不存在: {csv_path}")
        with open(path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                acct_name = row.get("account_name", "").strip()
                if not acct_name:
                    raise ValueError("❌ append CSV 中存在 account_name 为空的记录")
                acct = acct_map.get(acct_name)
                if not acct:
                    raise ValueError(f"❌ 账户 '{acct_name}' 不存在，请先 ft acct add 再重试")
                date_val = row.get("date", "").strip()
                if not date_val:
                    raise ValueError(f"❌ append CSV 中存在 date 为空的记录 (account={acct_name})")
                incoming_rows.append((acct["type"], date_val[:10], row))
                stats[date_val[:10]] += 1
```

Then add a second phase that groups validated rows by `(type, date)`, merges with existing rows, sorts by `date`, and writes only after validation succeeds:

```python
    groups = defaultdict(list)
    for typ, date_str, row in incoming_rows:
        groups[(typ, date_str)].append(row)

    for (typ, date_str), rows in groups.items():
        type_dir = records_dir / typ
        type_dir.mkdir(parents=True, exist_ok=True)
        day_path = type_dir / f"{date_str}.csv"
        existing_rows = []
        if day_path.exists():
            with open(day_path, encoding="utf-8") as f:
                existing_rows = list(csv.DictReader(f))
        all_rows = existing_rows + rows
        all_rows.sort(key=lambda r: r["date"])
        with open(day_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=csv_fields)
            writer.writeheader()
            writer.writerows(all_rows)
```

Leave the snapshot update logic in place, but iterate `incoming_rows` instead of the old single-file grouping.

- [ ] **Step 4: Run the append tests and confirm they pass**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_append.py tests/test_import.py -q`

Expected: PASS

- [ ] **Step 5: Commit the multi-file append work**

```bash
cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker
git add src/ft/append.py tests/test_append.py tests/test_import.py
git commit -m "feat: support atomic multi-file append"
```

### Task 3: Reconcile Engine — post-import duplicate removal, audit output, snapshot rebuild

**Files:**
- Create: `src/ft/reconcile.py`
- Modify: `src/ft/dedup.py`
- Create: `tests/test_reconcile.py`

- [ ] **Step 1: Write the failing reconcile tests**

Create `tests/test_reconcile.py` with:

```python
import csv
import tempfile
from pathlib import Path

import pytest

from ft.accounts import save_accounts


@pytest.fixture
def tmp_env():
    d = Path(tempfile.mkdtemp())
    from ft import models
    import ft.snapshot as ft_snap
    old_ft_dir = models.FT_DIR
    old_records = models.RECORDS_DIR
    old_accounts = models.ACCOUNTS_PATH
    old_snapshot = ft_snap.SNAPSHOT_PATH

    models.FT_DIR = d
    models.RECORDS_DIR = d / "records"
    models.ACCOUNTS_PATH = d / "accounts.yaml"
    ft_snap.SNAPSHOT_PATH = d / "snapshot.yaml"

    save_accounts([
        {"name": "工行信用卡(1200)", "type": "loan", "currency": "CNY", "active": True},
    ], models.ACCOUNTS_PATH)

    yield d

    models.FT_DIR = old_ft_dir
    models.RECORDS_DIR = old_records
    models.ACCOUNTS_PATH = old_accounts
    ft_snap.SNAPSHOT_PATH = old_snapshot


def _write_rows(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "date", "amount", "currency", "counterparty",
            "description", "category", "account_name", "source", "bill_source",
        ])
        writer.writeheader()
        writer.writerows(rows)


def test_reconcile_removes_bank_duplicate_and_writes_audit(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY", "counterparty": "麦当劳", "description": "", "category": "expense", "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay"},
        {"date": "2026-06-12 10:00:05", "amount": "-30.00", "currency": "CNY", "counterparty": "麦当劳", "description": "", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "alipay"

    audit_dir = models.FT_DIR / "audit" / "reconcile"
    audit_files = list(audit_dir.glob("*.csv"))
    assert len(audit_files) == 1


def test_reconcile_does_not_cross_match_outside_scope(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile

    day_a = models.RECORDS_DIR / "loan" / "2026-06-30.csv"
    day_b = models.RECORDS_DIR / "loan" / "2026-07-01.csv"
    _write_rows(day_a, [
        {"date": "2026-06-30 23:59:58", "amount": "-30.00", "currency": "CNY", "counterparty": "Steam", "description": "", "category": "expense", "account_name": "工行信用卡(1200)", "source": "支付宝", "bill_source": "alipay"},
    ])
    _write_rows(day_b, [
        {"date": "2026-07-01 00:00:02", "amount": "-30.00", "currency": "CNY", "counterparty": "Steam", "description": "", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_a, encoding="utf-8") as f:
        june_rows = list(csv.DictReader(f))
    with open(day_b, encoding="utf-8") as f:
        july_rows = list(csv.DictReader(f))
    assert len(june_rows) == 1
    assert len(july_rows) == 1


def test_reconcile_skips_audit_file_when_no_duplicates(tmp_env, capsys):
    from ft import models
    from ft.reconcile import do_reconcile

    day_path = models.RECORDS_DIR / "loan" / "2026-06-12.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 10:00:03", "amount": "-30.00", "currency": "CNY", "counterparty": "麦当劳", "description": "", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")
    out = capsys.readouterr().out

    assert "无重复项" in out
    assert not (models.FT_DIR / "audit" / "reconcile").exists()
```

- [ ] **Step 2: Run the new reconcile tests and confirm they fail**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_reconcile.py -q`

Expected: FAIL because `ft.reconcile` does not exist.

- [ ] **Step 3: Keep `dedup.py` pure and add a richer result helper**

Extend `src/ft/dedup.py` without changing its match rules:

```python
def dedup_with_pairs(records: list[dict]) -> tuple[list[dict], list[dict], list[tuple[dict, dict]]]:
    ...
    return kept, removed, removed_pairs


def dedup(records: list[dict]) -> tuple[list[dict], list[dict]]:
    kept, removed, _ = dedup_with_pairs(records)
    return kept, removed
```

The body of `dedup_with_pairs()` should be the current `dedup()` implementation, with the existing `removed_pairs` list returned alongside `kept` and `removed`.

- [ ] **Step 4: Implement `src/ft/reconcile.py`**

Create `src/ft/reconcile.py`:

```python
import csv
from collections import defaultdict
from datetime import datetime
from pathlib import Path

from . import models
from .dedup import dedup_with_pairs
from .snapshot import git_stage


def _parse_scope(month=None, date_from=None, date_to=None):
    if month:
        start = datetime.strptime(month + "-01", "%Y-%m-%d").date()
        if month.endswith("-12"):
            next_month = datetime(start.year + 1, 1, 1).date()
        else:
            year, mon = map(int, month.split("-"))
            next_month = datetime(year + (mon // 12), (mon % 12) + 1, 1).date()
        end = next_month.fromordinal(next_month.toordinal() - 1)
        return start, end
    start = datetime.strptime(date_from, "%Y-%m-%d").date() if date_from else None
    end = datetime.strptime(date_to, "%Y-%m-%d").date() if date_to else None
    if start and end and start > end:
        raise ValueError("❌ --from 不能晚于 --to")
    return start, end


def _in_scope(date_text, start, end):
    day = datetime.strptime(date_text[:10], "%Y-%m-%d").date()
    if start and day < start:
        return False
    if end and day > end:
        return False
    return True


def _load_records():
    rows = []
    for typ in ("cash", "loan"):
        type_dir = models.RECORDS_DIR / typ
        if not type_dir.exists():
            continue
        for csv_file in sorted(type_dir.glob("*.csv")):
            with open(csv_file, encoding="utf-8") as f:
                for idx, row in enumerate(csv.DictReader(f), start=2):
                    rows.append({"row": row, "file_path": csv_file, "line_no": idx, "account_type": typ})
    return rows
```

Continue the file with:

```python
def _write_audit(pairs, scope_from, scope_to):
    audit_dir = models.FT_DIR / "audit" / "reconcile"
    audit_dir.mkdir(parents=True, exist_ok=True)
    run_at = datetime.now().strftime("%Y-%m-%d_%H-%M-%S")
    audit_path = audit_dir / f"{run_at}.csv"
    fields = [
        "run_at", "scope_from", "scope_to", "date", "amount", "currency",
        "counterparty", "description", "category", "account_name", "source",
        "bill_source", "record_file", "dedup_status",
    ]
    with open(audit_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        for keep_row, remove_row in pairs:
            writer.writerow({
                "run_at": run_at, "scope_from": scope_from or "", "scope_to": scope_to or "",
                **keep_row, "record_file": keep_row["_record_file"], "dedup_status": "保留",
            })
            writer.writerow({
                "run_at": run_at, "scope_from": scope_from or "", "scope_to": scope_to or "",
                **remove_row, "record_file": remove_row["_record_file"], "dedup_status": "去除",
            })
    return audit_path


def do_reconcile(*, month=None, date_from=None, date_to=None):
    start, end = _parse_scope(month=month, date_from=date_from, date_to=date_to)
    entries = _load_records()
    scoped = []
    untouched = []

    for entry in entries:
        row = dict(entry["row"])
        row["_record_file"] = str(entry["file_path"])
        row["_line_no"] = str(entry["line_no"])
        if _in_scope(row["date"], start, end):
            scoped.append(row)
        else:
            untouched.append((entry["file_path"], entry["row"]))

    kept, removed, pairs = dedup_with_pairs(scoped)
    if not removed:
        print("无重复项")
        return
```

Finish `do_reconcile()` by regrouping `kept` and `untouched` rows per file, rewriting only changed files, deleting empty files, rebuilding snapshot via the existing CLI fix path, writing audit output, and staging:

```python
    rows_by_file = defaultdict(list)
    for file_path, row in untouched:
        rows_by_file[file_path].append(row)
    for row in kept:
        clean = {k: v for k, v in row.items() if not k.startswith("_")}
        rows_by_file[Path(row["_record_file"])].append(clean)

    touched_files = set(Path(r["_record_file"]) for r in scoped)
    for file_path in touched_files:
        final_rows = rows_by_file.get(file_path, [])
        if not final_rows:
            if file_path.exists():
                file_path.unlink()
            continue
        final_rows.sort(key=lambda r: r["date"])
        with open(file_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=models.CSV_FIELDS)
            writer.writeheader()
            writer.writerows(final_rows)

    from .cli import main as cli_main
    cli_main(["verify", "--fix"])
    audit_path = _write_audit(pairs, start.isoformat() if start else None, end.isoformat() if end else None)
    print(f"✅ 去重完成，审计文件: {audit_path}")
    git_stage(models.FT_DIR)
```

- [ ] **Step 5: Run the reconcile tests and confirm they pass**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_reconcile.py tests/test_dedup.py -q`

Expected: PASS

- [ ] **Step 6: Commit the reconcile engine**

```bash
cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker
git add src/ft/dedup.py src/ft/reconcile.py tests/test_reconcile.py
git commit -m "feat: add post-import reconcile workflow"
```

### Task 4: Docs, cleanup, and regression verification

**Files:**
- Delete: `src/ft/merge.py`
- Modify: `SKILL.md`
- Modify: `README.md`
- Modify: `tests/test_dedup.py`

- [ ] **Step 1: Update the dedup tests to match the 9-column schema**

In `tests/test_dedup.py`, replace the helper with:

```python
def _rec(date, amount, currency, counterparty, description,
         category, account_name, source, bill_source):
    return {
        "date": date,
        "amount": str(amount),
        "currency": currency,
        "counterparty": counterparty,
        "description": description,
        "category": category,
        "account_name": account_name,
        "source": source,
        "bill_source": bill_source,
    }
```

Then remove the `platform` argument from every `_rec(...)` call site.

- [ ] **Step 2: Delete the dead merge module**

Run:

```bash
cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker
rm src/ft/merge.py
```

Expected: file removed from the working tree because `merge` is no longer referenced by the CLI or docs.

- [ ] **Step 3: Update the user-facing docs**

In `SKILL.md`, replace the old flow block:

```text
① convert → ② AI审查转换 → ③ AI修正 → ④ merge → ⑤ AI审查合并 → ⑥ append
```

with:

```text
① convert → ② AI审查转换 → ③ AI修正 → ④ append → ⑤ reconcile → ⑥ commit
```

Add command examples:

```bash
ft append alipay.csv wechat.csv icbc.csv
ft reconcile --month 2026-06
```

In `README.md`, update every example that says `merged.csv` or `ft merge` so it instead uses:

```bash
ft append a.csv b.csv c.csv
ft reconcile
```

- [ ] **Step 4: Run the regression suite**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/test_cli.py tests/test_append.py tests/test_import.py tests/test_reconcile.py tests/test_dedup.py tests/test_convert.py tests/test_convert_normalize.py -q`

Expected: PASS

- [ ] **Step 5: Commit docs and cleanup**

```bash
cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker
git add SKILL.md README.md tests/test_dedup.py
git add -u src/ft/merge.py
git commit -m "docs: document append then reconcile import flow"
```

### Task 5: Final verification and handoff

**Files:**
- No code changes

- [ ] **Step 1: Run the full test suite**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run pytest tests/ -q`

Expected: PASS

- [ ] **Step 2: Smoke-test the new CLI help**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && uv run python -m ft.cli append --help && uv run python -m ft.cli reconcile --help`

Expected: both commands print usage with the new argument shapes and exit 0.

- [ ] **Step 3: Inspect the diff for the intended scope**

Run: `cd /Users/huangwenlong/.hermes/skills/finance/finance-tracker && git status --short && git log --oneline -5`

Expected: only the planned files are changed, and the recent commits match the task sequence above.

- [ ] **Step 4: Prepare execution handoff**

Record these expected user-visible behaviors in the execution summary:

```text
1. `ft append` accepts multiple converted CSV files in one invocation.
2. `ft reconcile` removes post-import duplicates from cash/loan records, writes an audit CSV under ~/.ft/audit/reconcile/, and rebuilds snapshot state.
3. `ft merge` no longer exists.
```

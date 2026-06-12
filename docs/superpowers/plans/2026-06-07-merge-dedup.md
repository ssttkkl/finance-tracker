# Merge Dedup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ft merge` 跨源去重：支付宝/微信记录优先保留，银行重复记录剔除，输出干净 CSV + 重复记录 CSV。

**Architecture:** 新增 `src/ft/dedup.py` 做核心去重（按分钟分组 + 跨源匹配 + 交叉验证），convert 阶段新增 `bill_source` 列标记账单来源，merge 阶段串联调用。

**Tech Stack:** Python 3, csv stdlib, datetime, pytest

---

### Task 1: Add `bill_source` column to convert output

**Files:**
- Modify: `src/ft/convert.py:827-830` (header)
- Modify: `src/ft/convert.py:813-823` (row building)

`do_convert` 内部已有 `bill_type` 变量（"alipay"/"wechat"/"icbc_credit"/"icbc_debit"），直接映射为 `bill_source` 值写入每行。

- [ ] **Step 1: Add `bill_source` to header**

```python
# Line 827-829, add "bill_source" after "platform"
writer.writerow(["date", "amount", "currency", "counterparty",
                 "description", "category", "account_name", "source",
                 "platform", "bill_source"])
```

- [ ] **Step 2: Add `bill_source` value to each output row**

```python
# Line 813-823, add bill_source as 10th element
output_rows.append([
    rec["date"],
    rec["amount"],
    rec.get("currency", cur) or cur,
    cpy,
    rec.get("description", ""),
    rec["category"],
    acct_name,
    payment_src,
    rec.get("platform", ""),
    bill_type,  # "alipay" / "wechat" / "icbc_credit" / "icbc_debit"
])
```

- [ ] **Step 3: Run existing convert tests to verify no regression**

Run: `pytest tests/test_convert.py -v`
Expected: all 124 tests PASS (they use `csv.reader` not `DictReader`, so extra column won't break them)

- [ ] **Step 4: Commit**

```bash
git add src/ft/convert.py
git commit -m "feat: add bill_source column to convert CSV output"
```

---

### Task 2: Write failing tests for `dedup()`

**Files:**
- Create: `tests/test_dedup.py`

All tests fabricate records as dicts with `bill_source` field. None depend on convert or merge.

- [ ] **Step 1: Write all 10 tests (RED — function not defined yet)**

```python
"""tests for src/ft/dedup.py"""
import pytest
from ft.dedup import dedup


def _rec(date, amount, currency, counterparty, description,
         category, account_name, source, platform, bill_source):
    return {
        "date": date, "amount": str(amount), "currency": currency,
        "counterparty": counterparty, "description": description,
        "category": category, "account_name": account_name,
        "source": source, "platform": platform, "bill_source": bill_source,
    }


# ── Test 1: different time/amount → both kept ──
def test_different_time_amount_both_kept():
    a = _rec("2026-01-01 13:00:00", -30, "CNY", "麦当劳", "",
             "expense", "支付宝余额", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 14:00:00", -50, "CNY", "星巴克", "",
             "expense", "工行信用卡(1200)", "支付宝", "星巴克", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 2: same amount, time diff > 5s → both kept ──
def test_time_diff_exceeds_5s_both_kept():
    a = _rec("2026-01-01 13:00:00", -30, "CNY", "麦当劳", "",
             "expense", "支付宝余额", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:10", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 3: same amount, ≤5s, platform match → bank removed ──
def test_platform_match_bank_removed():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "支付宝余额", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"
    assert len(removed) == 2
    assert removed[0]["dedup_status"] == "保留"
    assert removed[1]["dedup_status"] == "去除"
    assert removed[1]["bill_source"] == "icbc_credit"


# ── Test 4: same amount, ≤5s, counterparty substring → bank removed ──
def test_counterparty_substring_bank_removed():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳(望京店)", "",
             "expense", "支付宝余额", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 5: same amount, ≤5s, all cross-verify fail → both kept ──
def test_cross_verify_fail_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "午餐",
             "expense", "支付宝余额", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "肯德基", "晚餐",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 6: cross-minute boundary (12:59:58 vs 13:00:02) → bank removed ──
def test_cross_minute_boundary():
    a = _rec("2026-01-01 12:59:58", -30, "CNY", "麦当劳", "",
             "expense", "支付宝余额", "支付宝", "麦当劳", "alipay")
    b = _rec("2026-01-01 13:00:02", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "alipay"


# ── Test 7: multiple matches, pick closest time ──
def test_multiple_matches_closest_time():
    alipay_rec = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
                       "expense", "支付宝余额", "支付宝", "麦当劳", "alipay")
    bank_close = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
                       "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    bank_far = _rec("2026-01-01 13:00:07", -30, "CNY", "麦当劳", "",
                     "expense", "工行借记卡", "银行卡", "麦当劳", "icbc_debit")
    kept, removed = dedup([alipay_rec, bank_close, bank_far])
    # bank_close matched (diff=1s), bank_far also within 5s but farther
    assert len(kept) == 2  # alipay + bank_far
    assert len(removed) == 2
    removed_sources = [r["bill_source"] for r in removed if r["dedup_status"] == "去除"]
    assert "icbc_credit" in removed_sources


# ── Test 8: same source (bank vs bank) → both kept ──
def test_same_source_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "支付宝", "麦当劳", "icbc_credit")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(9166)", "支付宝", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 9: all cross-verify fields empty → both kept ──
def test_empty_cross_verify_fields_both_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "", "",
             "expense", "支付宝余额", "支付宝", "", "alipay")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "", "",
             "expense", "工行信用卡(1200)", "支付宝", "", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 2
    assert len(removed) == 0


# ── Test 10: wechat vs bank → wechat kept ──
def test_wechat_vs_bank_wechat_kept():
    a = _rec("2026-01-01 13:00:03", -30, "CNY", "麦当劳", "",
             "expense", "微信零钱", "微信", "麦当劳", "wechat")
    b = _rec("2026-01-01 13:00:04", -30, "CNY", "麦当劳", "",
             "expense", "工行信用卡(1200)", "微信支付", "麦当劳", "icbc_credit")
    kept, removed = dedup([a, b])
    assert len(kept) == 1
    assert kept[0]["bill_source"] == "wechat"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pytest tests/test_dedup.py -v`
Expected: all 10 FAIL with `ModuleNotFoundError: No module named 'ft.dedup'`

- [ ] **Step 3: Commit**

```bash
git add tests/test_dedup.py
git commit -m "test: add dedup test cases (RED)"
```

---

### Task 3: Implement `src/ft/dedup.py`

**Files:**
- Create: `src/ft/dedup.py`

- [ ] **Step 1: Create minimal stub so tests fail with AssertionError (not ImportError)**

```python
"""跨源去重：支付宝/微信优先，银行重复剔除"""
from collections import defaultdict
from datetime import datetime


def dedup(records):
    return records, []
```

Run: `pytest tests/test_dedup.py -v`
Expected: 3 PASS (tests 1,2,8 — both-kept scenarios), 7 FAIL (wrong assertions)

- [ ] **Step 2: Implement full `dedup()`**

```python
"""跨源去重：支付宝/微信优先，银行重复剔除"""
from collections import defaultdict
from datetime import datetime


def _parse_dt(s: str) -> datetime:
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S")


def _truncate_minute(dt: datetime) -> datetime:
    return dt.replace(second=0, microsecond=0)


def _source_group(bs: str) -> str:
    if bs == "alipay":
        return "alipay"
    elif bs == "wechat":
        return "wechat"
    return "bank"


def _cross_verify(a: dict, b: dict) -> bool:
    # 1. platform 匹配（双方非空）
    pa, pb = a.get("platform", ""), b.get("platform", "")
    if pa and pb and pa == pb:
        return True
    # 2. counterparty 双向子串（双方非空）
    ca, cb = a.get("counterparty", ""), b.get("counterparty", "")
    if ca and cb and (ca in cb or cb in ca):
        return True
    # 3. description 双向子串（双方非空）
    da, db = a.get("description", ""), b.get("description", "")
    if da and db and (da in db or db in da):
        return True
    return False


def dedup(records: list[dict]) -> tuple[list[dict], list[dict]]:
    """返回 (保留记录, 被删记录含dedup_status)"""
    if not records:
        return [], []

    # Parse dates and group by (minute, amount, currency)
    groups: dict[tuple, list[tuple[datetime, dict]]] = defaultdict(list)
    for r in records:
        dt = _parse_dt(r["date"])
        key = (_truncate_minute(dt), float(r["amount"]), r["currency"])
        groups[key].append((dt, r))

    removed_ids = set()
    removed_pairs: list[tuple[dict, dict]] = []  # (kept_rec, removed_rec)

    sorted_minutes = sorted(groups.keys())

    for i, minute_key in enumerate(sorted_minutes):
        group = groups[minute_key]

        # Classify current group
        alipay = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "alipay"]
        wechat = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "wechat"]
        bank   = [(dt, r) for dt, r in group if _source_group(r["bill_source"]) == "bank"]

        # Candidates: alipay + wechat from current group
        candidates = alipay + wechat

        # Add alipay + wechat from previous minute (cross-minute boundary)
        if i > 0:
            prev = groups[sorted_minutes[i - 1]]
            candidates += [(dt, r) for dt, r in prev
                           if _source_group(r["bill_source"]) in ("alipay", "wechat")]

        # Match bank records against candidates
        for b_dt, b_rec in bank:
            if id(b_rec) in removed_ids:
                continue
            best_match = None
            best_diff = float("inf")
            for c_dt, c_rec in candidates:
                diff = abs((b_dt - c_dt).total_seconds())
                if diff <= 5 and _cross_verify(b_rec, c_rec):
                    if diff < best_diff:
                        best_diff = diff
                        best_match = c_rec
            if best_match is not None:
                removed_ids.add(id(b_rec))
                removed_pairs.append((best_match, b_rec))

    # Split
    kept = [r for r in records if id(r) not in removed_ids]

    # Build removed list with dedup_status
    removed = []
    for keep_rec, remove_rec in removed_pairs:
        removed.append({**keep_rec, "dedup_status": "保留"})
        removed.append({**remove_rec, "dedup_status": "去除"})

    # Sort by date
    kept.sort(key=lambda r: r["date"])
    removed.sort(key=lambda r: r["date"])

    return kept, removed
```

- [ ] **Step 3: Run tests to verify all pass**

Run: `pytest tests/test_dedup.py -v`
Expected: all 10 PASS

- [ ] **Step 4: Commit**

```bash
git add src/ft/dedup.py
git commit -m "feat: implement cross-source dedup with grouping and cross-verify"
```

---

### Task 4: Modify `do_merge` to use `dedup()`

**Files:**
- Modify: `src/ft/merge.py`

- [ ] **Step 1: Rewrite `do_merge`**

```python
"""merge — 多个 CSV 合并去重"""
import csv
import os

from ft.dedup import dedup


def do_merge(inputs: list[str], output_dir: str):
    """合并多个 CSV，跨源去重，输出 merged.csv + removed.csv"""
    all_rows = []
    for path in inputs:
        with open(path, encoding="utf-8") as f:
            reader = csv.DictReader(f)
            for row in reader:
                all_rows.append(row)

    if not all_rows:
        print("❌ 无数据")
        return

    kept, removed = dedup(all_rows)

    os.makedirs(output_dir, exist_ok=True)

    merged_path = os.path.join(output_dir, "merged.csv")
    removed_path = os.path.join(output_dir, "removed.csv")

    fields = ["date", "amount", "currency", "counterparty",
              "description", "category", "account_name", "source",
              "platform", "bill_source"]

    # merged.csv
    with open(merged_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fields)
        writer.writeheader()
        writer.writerows(kept)

    # removed.csv
    removed_fields = fields + ["dedup_status"]
    with open(removed_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=removed_fields)
        writer.writeheader()
        writer.writerows(removed)

    removed_count = len([r for r in removed if r.get("dedup_status") == "去除"])
    print(f"✅ 去重完成: {len(all_rows)}条 → {len(kept)}条（删除{removed_count}条重复）→ {merged_path}",
          file=__import__("sys").stderr)

    # Per-source stats
    from collections import Counter
    kept_sources = Counter(r["bill_source"] for r in kept)
    for src, count in sorted(kept_sources.items()):
        src_label = {"alipay": "支付宝", "wechat": "微信",
                     "icbc_credit": "工行信用卡", "icbc_debit": "工行借记卡"}.get(src, src)
        print(f"  {src_label}保留: {count}条", file=__import__("sys").stderr)
```

- [ ] **Step 2: Update CLI if needed**

Check `src/ft/cli.py` for merge command signature. If it passes `output_dir` instead of `output` file, update call site.

Run: `grep -n "do_merge\|merge" src/ft/cli.py`

If CLI uses `output` (single file), change to accept `output_dir` and update the argument:

```python
# Find merge subcommand in cli.py and update:
# Old: do_merge(input_files, output)
# New: do_merge(input_files, output_dir)
```

- [ ] **Step 3: Verify with full pipeline test**

```bash
# Re-convert test bills (now with bill_source column)
ft convert ~/Downloads/alipay_sample.csv -s alipay -o /tmp/test_alipay.csv
ft convert ~/Downloads/icbc_sample.csv -s icbc -o /tmp/test_icbc.csv
# Merge
ft merge /tmp/test_alipay.csv /tmp/test_icbc.csv -o /tmp/merged/
# Check outputs
head /tmp/merged/merged.csv
head /tmp/merged/removed.csv
```

- [ ] **Step 4: Run all tests**

Run: `pytest tests/ -v`
Expected: all tests PASS (146 existing + 10 new = 156)

- [ ] **Step 5: Commit**

```bash
git add src/ft/merge.py src/ft/cli.py
git commit -m "feat: use dedup() in do_merge, output merged.csv + removed.csv"
```

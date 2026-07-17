# Records 月度单文件组织 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 ft 的 records 落盘组织从按天单文件切换为按月单文件 `records/{type}/YYYY-MM.csv`，且不兼容旧日文件布局。

**Architecture:** 先用测试锁定新的月文件路径行为，再提取统一的 records 月文件路径规则，接入 append、transfer、checkin、stock 等写入入口，最后修正 report / snapshot / reconcile / security 扫描等读取路径与文档。整个改动只调整文件组织方式，不改变行级业务语义。

**Tech Stack:** Python 3.11、pytest、csv、pathlib

---

### Task 1: 锁定月文件落盘预期

**Files:**
- Modify: `tests/test_append.py`
- Modify: `tests/test_transfer_csv.py`
- Modify: `tests/test_stock.py`
- Modify: `tests/test_cli.py`

- [ ] **Step 1: 写失败测试，先把按天路径改成按月路径**

```python
# tests/test_append.py
month_csv = records_dir / "cash" / "2026-06.csv"
assert month_csv.exists()

# tests/test_transfer_csv.py
from_csv = records_dir / "cash" / "2026-06.csv"
to_csv = records_dir / "loan" / "2026-06.csv"

# tests/test_stock.py
day_csv = security_dir / "2026-06.csv"
assert day_csv.exists()

# tests/test_cli.py
day_csv = models.RECORDS_DIR / "security" / "2026-06.csv"
```

- [ ] **Step 2: 运行测试，确认因仍写日文件而失败**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_append.py tests/test_transfer_csv.py tests/test_stock.py tests/test_cli.py -q
```

Expected: FAIL，断言仍在寻找 `2026-06.csv` 但代码还在写 `2026-06-12.csv` / `2026-06-30.csv`

- [ ] **Step 3: 实现统一月文件路径规则并接入所有写入口**

```python
# src/ft/models.py
from pathlib import Path

def month_key(date_str: str) -> str:
    return date_str[:7]


def records_month_path(record_type: str, date_str: str, records_dir: Path | None = None) -> Path:
    base = records_dir or RECORDS_DIR
    return Path(base) / record_type / f"{month_key(date_str)}.csv"
```

```python
# src/ft/append.py
month_str = date_val[:7]
incoming_rows.append((acct["type"], month_str, _normal_row(row)))
...
month_path = models.records_month_path(typ, month_str, records_dir)
```

```python
# src/ft/transfer.py
from_path = models.records_month_path(from_acct["type"], date, records_dir)
to_path = models.records_month_path(to_acct["type"], date, records_dir)
```

```python
# src/ft/stock.py
month_path = models.records_month_path("security", date, records_dir)
```

```python
# src/ft/cli.py
month_path = models.records_month_path(typ, date_str, models.RECORDS_DIR)
```

- [ ] **Step 4: 重跑这组测试，确认通过**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_append.py tests/test_transfer_csv.py tests/test_stock.py tests/test_cli.py -q
```

Expected: PASS

### Task 2: 锁定月文件读取与重建行为

**Files:**
- Modify: `tests/test_report_csv.py`
- Modify: `tests/test_snapshot.py`
- Modify: `tests/test_import.py`

- [ ] **Step 1: 写失败测试，把手工造数路径改成月文件**

```python
# tests/test_report_csv.py
write_csv(records_dir / "cash" / "2026-06.csv", [...])
write_csv(records_dir / "security" / "2026-06.csv", [...])

# tests/test_snapshot.py
month_path = records_dir / "cash" / "2026-06.csv"

# tests/test_import.py
cash_csv = records_dir / "cash" / "2026-06.csv"
loan_csv = records_dir / "loan" / "2026-06.csv"
```

- [ ] **Step 2: 运行测试，确认旧代码仍有按天假设导致失败**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_report_csv.py tests/test_snapshot.py tests/test_import.py -q
```

Expected: FAIL，至少有路径断言或读取结果不匹配

- [ ] **Step 3: 让读取侧彻底以月文件工作**

```python
# src/ft/report.py
for csv_file in sorted(type_dir.glob("*.csv")):
    if month and csv_file.stem != month:
        continue
```

```python
# src/ft/snapshot.py
for csv_file in sorted(typedir.glob("*.csv")):
    with open(csv_file, encoding="utf-8") as f:
        ...
```

说明：这里不做日/月兼容分支，只保留对 `*.csv` 月文件的直接扫描。

- [ ] **Step 4: 重跑测试，确认读取与重建通过**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_report_csv.py tests/test_snapshot.py tests/test_import.py -q
```

Expected: PASS

### Task 3: 锁定 reconcile 与 security 扫描对月文件的支持

**Files:**
- Modify: `tests/test_reconcile.py`
- Modify: `tests/test_reconcile_locked.py`
- Modify: `src/ft/reconcile.py`
- Modify: `src/ft/sync_common.py`
- Modify: `src/ft/polymarket_sync.py`
- Modify: `src/ft/stock.py`

- [ ] **Step 1: 写失败测试，把 reconcile 造数路径改成月文件**

```python
# tests/test_reconcile.py
month_path = models.RECORDS_DIR / "loan" / "2026-06.csv"

# tests/test_reconcile_locked.py
month_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
files = list(cash_dir.glob("*.csv"))
```

- [ ] **Step 2: 运行测试，确认月文件场景下失败**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_reconcile.py tests/test_reconcile_locked.py -q
```

Expected: FAIL

- [ ] **Step 3: 修正 reconcile 与 security 相关扫描逻辑**

```python
# src/ft/reconcile.py
for csv_file in sorted(type_dir.glob("*.csv")):
    ...
```

```python
# src/ft/stock.py
for csv_file in sorted(security_dir.glob("*.csv")):
    rows.extend(csv.DictReader(f))
```

```python
# src/ft/sync_common.py
for path in sorted(security_dir.glob("*.csv")):
    ...
```

```python
# src/ft/polymarket_sync.py
for path in sorted(security_dir.glob("*.csv")):
    ...
```

说明：实现重点不是新增复杂分支，而是确保所有 security 扫描点都不再依赖日文件命名。

- [ ] **Step 4: 重跑测试，确认 reconcile 与 security 行为通过**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_reconcile.py tests/test_reconcile_locked.py -q
```

Expected: PASS

### Task 4: 更新文档与跑核心回归

**Files:**
- Modify: `README.md`
- Modify: `src/ft/append.py`
- Modify: `src/ft/stock.py`

- [ ] **Step 1: 更新文档中的 records 结构与切换说明**

```markdown
~/.ft/
└── records/
    ├── cash/2026-01.csv
    ├── loan/2026-01.csv
    └── security/2026-06.csv
```

```markdown
注意：records 现已改为按月单文件组织，不兼容旧 `YYYY-MM-DD.csv` 布局；切换前请先执行 `ft verify --fix` 或直接重建数据。
```

- [ ] **Step 2: 同步模块 docstring，避免继续描述按天文件**

```python
# src/ft/append.py
"""append — converted CSV → records/{type}/YYYY-MM.csv"""

# src/ft/stock.py
"""Write a trade row to records/security/{date[:7]}.csv."""
```

- [ ] **Step 3: 运行核心回归测试**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src pytest tests/test_append.py tests/test_transfer_csv.py tests/test_stock.py tests/test_report_csv.py tests/test_snapshot.py tests/test_reconcile.py tests/test_reconcile_locked.py tests/test_cli.py tests/test_import.py -q
```

Expected: PASS

- [ ] **Step 4: 做一次 focused sanity 检查**

Run:
```bash
cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python -m ft.cli verify --fix
```

Expected: 命令成功，输出“已从 CSV 重建全部账户快照”且无异常

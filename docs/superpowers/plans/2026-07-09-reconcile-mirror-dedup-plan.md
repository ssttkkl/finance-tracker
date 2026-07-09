# Reconcile Mirror Dedup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 reconcile 增加镜像重复识别层，对高置信强弱源镜像自动删弱源，对低置信镜像只做字段标注并进入 pending/audit。

**Architecture:** 新增 `mirror_rules.py` 负责按场景输出镜像 pair 与 high/low 决策；`dedup.py` 变为协调层；`reconcile.py` 接入 mirror 元数据并把 low 候选写入 pending / audit。规则优先覆盖信用卡消费镜像、工行借记卡消费镜像、建行唯一匹配消费镜像，再覆盖极窄的微信二维码/钱包充值镜像。

**Tech Stack:** Python 3, pytest, csv, 现有 `ft.reconcile` / `ft.dedup` / `ft.ai_working_csv` 基础设施

---

## File structure

- Create: `src/ft/mirror_rules.py` — 镜像候选生成、规则分类、high/low 决策结构
- Modify: `src/ft/dedup.py` — 接入 mirror rules，输出自动删除与低置信候选
- Modify: `src/ft/reconcile.py` — 写入 mirror 标注，串联 audit / pending
- Modify: `tests/test_dedup.py` — 规则级高/低置信回归
- Create: `tests/test_mirror_rules.py` — 镜像规则专测
- Modify: `tests/test_reconcile.py` — 集成行为与 pending/audit 断言

### Task 1: 为信用卡镜像自动删除建立最小规则

**Files:**
- Create: `src/ft/mirror_rules.py`
- Modify: `src/ft/dedup.py`
- Test: `tests/test_mirror_rules.py`
- Test: `tests/test_dedup.py`

- [ ] **Step 1: Write the failing test**

```python
# tests/test_mirror_rules.py
from ft.mirror_rules import detect_mirror_pairs


def test_detects_high_confidence_icbc_credit_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    result = detect_mirror_pairs(rows)

    assert len(result.auto_drop_pairs) == 1
    pair = result.auto_drop_pairs[0]
    assert pair.keep_row["bill_source"] == "wechat"
    assert pair.drop_row["bill_source"] == "icbc_credit"
    assert pair.rule_hint == "card_channel_purchase_mirror"
    assert pair.confidence == "high"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_credit_purchase_mirror -v`
Expected: FAIL with `ModuleNotFoundError` or missing `detect_mirror_pairs`

- [ ] **Step 3: Write minimal implementation**

```python
# src/ft/mirror_rules.py
from dataclasses import dataclass


@dataclass
class MirrorPair:
    keep_row: dict
    drop_row: dict
    rule_hint: str
    confidence: str


@dataclass
class MirrorDetectionResult:
    auto_drop_pairs: list
    review_pairs: list


def detect_mirror_pairs(rows: list[dict]) -> MirrorDetectionResult:
    for a in rows:
        for b in rows:
            if a is b:
                continue
            if a.get("bill_source") == "wechat" and b.get("bill_source") == "icbc_credit":
                if a.get("account_name") == b.get("account_name") and a.get("amount") == b.get("amount") and a.get("currency") == b.get("currency"):
                    return MirrorDetectionResult(
                        auto_drop_pairs=[MirrorPair(a, b, "card_channel_purchase_mirror", "high")],
                        review_pairs=[],
                    )
    return MirrorDetectionResult(auto_drop_pairs=[], review_pairs=[])
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_credit_purchase_mirror -v`
Expected: PASS

- [ ] **Step 5: Add dedup integration test**

```python
# tests/test_dedup.py
from ft.dedup import dedup


def test_dedup_removes_high_confidence_icbc_credit_mirror():
    rows = [
        {
            "date": "2026-06-01 09:42:02",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "date": "2026-06-01 09:42:03",
            "amount": "-20.4",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "北京食品有限公司",
            "category": "expense",
            "account_name": "工行信用卡(1200)",
            "source": "银行卡",
            "bill_source": "icbc_credit",
        },
    ]

    kept, removed = dedup(rows)

    assert len(kept) == 1
    assert kept[0]["bill_source"] == "wechat"
    assert any(r["bill_source"] == "icbc_credit" and r["dedup_status"] == "去除" for r in removed)
```

- [ ] **Step 6: Run test to verify it fails**

Run: `pytest tests/test_dedup.py::test_dedup_removes_high_confidence_icbc_credit_mirror -v`
Expected: FAIL because `dedup.py` still uses old matching path

- [ ] **Step 7: Wire dedup.py to mirror_rules**

```python
# src/ft/dedup.py
from ft.mirror_rules import detect_mirror_pairs


def dedup_with_pairs(records: list[dict]) -> tuple[list[dict], list[dict], list[tuple[dict, dict]]]:
    mirror = detect_mirror_pairs(records)
    removed_ids = {id(pair.drop_row) for pair in mirror.auto_drop_pairs}
    kept = [r for r in records if id(r) not in removed_ids]
    removed = []
    removed_pairs = []
    for pair in mirror.auto_drop_pairs:
        removed_pairs.append((pair.keep_row, pair.drop_row))
        removed.append({**pair.keep_row, "dedup_status": "保留"})
        removed.append({**pair.drop_row, "dedup_status": "去除"})
    kept.sort(key=lambda r: r["date"])
    removed.sort(key=lambda r: r["date"])
    return kept, removed, removed_pairs
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_credit_purchase_mirror tests/test_dedup.py::test_dedup_removes_high_confidence_icbc_credit_mirror -v`
Expected: PASS

### Task 2: 扩展到工行借记卡与建行唯一匹配消费镜像

**Files:**
- Modify: `src/ft/mirror_rules.py`
- Create: `tests/test_mirror_rules.py`
- Modify: `tests/test_dedup.py`

- [ ] **Step 1: Write the failing test for icbc_debit**

```python
def test_detects_high_confidence_icbc_debit_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2023-07-21 14:47:00",
            "amount": "-5.0",
            "currency": "CNY",
            "counterparty": "立普世",
            "description": "拿铁咖啡",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2023-07-21 14:47:00",
            "amount": "-5.0",
            "currency": "CNY",
            "counterparty": "立普世咖啡",
            "description": "消费",
            "category": "expense",
            "account_name": "工行借记卡",
            "source": "银行卡",
            "bill_source": "icbc_debit",
        },
    ]
    result = detect_mirror_pairs(rows)
    assert len(result.auto_drop_pairs) == 1
    assert result.auto_drop_pairs[0].drop_row["bill_source"] == "icbc_debit"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_debit_purchase_mirror -v`
Expected: FAIL with no auto_drop_pairs

- [ ] **Step 3: Write the failing test for ccb_debit unique day match**

```python
def test_detects_high_confidence_ccb_debit_unique_day_purchase_mirror():
    rows = [
        {
            "record_id": "a1",
            "date": "2025-09-24 18:06:55",
            "amount": "-31.0",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "麦当劳",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2025-09-24",
            "amount": "-31.0",
            "currency": "CNY",
            "counterparty": "麦当劳",
            "description": "消费",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "建行储蓄卡",
            "bill_source": "ccb_debit",
        },
    ]
    result = detect_mirror_pairs(rows)
    assert len(result.auto_drop_pairs) == 1
    assert result.auto_drop_pairs[0].drop_row["bill_source"] == "ccb_debit"
    assert result.auto_drop_pairs[0].rule_hint == "debit_purchase_mirror_ccb_unique_day"
```

- [ ] **Step 4: Run tests to verify they fail**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_debit_purchase_mirror tests/test_mirror_rules.py::test_detects_high_confidence_ccb_debit_unique_day_purchase_mirror -v`
Expected: FAIL

- [ ] **Step 5: Extend mirror_rules.py minimally**

```python
# src/ft/mirror_rules.py
# 在 detect_mirror_pairs 中补充分支：
# - wechat/alipay -> icbc_debit，近时刻 + 文本子串
# - wechat/alipay -> ccb_debit，同日唯一 + 文本明确
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `pytest tests/test_mirror_rules.py::test_detects_high_confidence_icbc_debit_purchase_mirror tests/test_mirror_rules.py::test_detects_high_confidence_ccb_debit_unique_day_purchase_mirror -v`
Expected: PASS

### Task 3: 为低置信微信充值/群收款镜像建立 review 标注

**Files:**
- Modify: `src/ft/mirror_rules.py`
- Modify: `tests/test_mirror_rules.py`
- Modify: `src/ft/dedup.py`

- [ ] **Step 1: Write the failing test**

```python
def test_marks_wechat_group_collection_vs_ccb_topup_as_low_confidence_review():
    rows = [
        {
            "record_id": "a1",
            "date": "2026-06-12 12:35:31",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "群收款",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "record_id": "b1",
            "date": "2026-06-12",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "充值",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "建行储蓄卡",
            "bill_source": "ccb_debit",
        },
    ]
    result = detect_mirror_pairs(rows)
    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
    pair = result.review_pairs[0]
    assert pair.confidence == "low"
    assert pair.rule_hint == "possible_wechat_topup_or_group_collection_mirror"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mirror_rules.py::test_marks_wechat_group_collection_vs_ccb_topup_as_low_confidence_review -v`
Expected: FAIL

- [ ] **Step 3: Implement review pair output**

```python
# src/ft/mirror_rules.py
# 为 low 置信候选新增 review_pairs 输出，保留 keep_row/drop_row 结构，confidence="low"
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_mirror_rules.py::test_marks_wechat_group_collection_vs_ccb_topup_as_low_confidence_review -v`
Expected: PASS

- [ ] **Step 5: Add dedup non-removal test**

```python
def test_dedup_keeps_low_confidence_review_pair():
    rows = [
        {
            "date": "2026-06-12 12:35:31",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "群收款",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "微信",
            "bill_source": "wechat",
        },
        {
            "date": "2026-06-12",
            "amount": "-55.2",
            "currency": "CNY",
            "counterparty": "微信",
            "description": "充值",
            "category": "expense",
            "account_name": "建行储蓄卡(2820)",
            "source": "建行储蓄卡",
            "bill_source": "ccb_debit",
        },
    ]
    kept, removed = dedup(rows)
    assert len(kept) == 2
    assert removed == []
```

- [ ] **Step 6: Run test to verify it passes**

Run: `pytest tests/test_dedup.py::test_dedup_keeps_low_confidence_review_pair -v`
Expected: PASS

### Task 4: 在 reconcile 中写入 mirror 标注并把低置信候选带入 pending/audit

**Files:**
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_reconcile.py`

- [ ] **Step 1: Write the failing low-confidence pending test**

```python
def test_reconcile_writes_low_confidence_mirror_fields_into_pending(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile
    import csv

    day_path = models.RECORDS_DIR / "cash" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-12 12:35:31", "amount": "-55.2", "currency": "CNY", "counterparty": "微信", "description": "群收款", "category": "expense", "account_name": "建行储蓄卡(2820)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-12", "amount": "-55.2", "currency": "CNY", "counterparty": "微信", "description": "充值", "category": "expense", "account_name": "建行储蓄卡(2820)", "source": "建行储蓄卡", "bill_source": "ccb_debit"},
    ])

    do_reconcile(month="2026-06")

    sessions = list((models.PENDING_DIR / "reconcile").glob("*"))
    with open(sessions[0] / "ai_working.csv", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert any(r.get("rule_hint") == "possible_mirror_low_confidence" for r in rows)
    assert any(r.get("ai_group", "").startswith("mirror_") for r in rows)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_reconcile.py::test_reconcile_writes_low_confidence_mirror_fields_into_pending -v`
Expected: FAIL because reconcile does not yet propagate mirror metadata

- [ ] **Step 3: Write minimal reconcile integration**

```python
# src/ft/reconcile.py
# 在 _prepare_reconcile_state 中接收 dedup/mirror 结果
# 对 review_pairs 的行补充：
# - ai_group=mirror_xxx
# - rule_hint=possible_mirror_low_confidence
# - mirror_* 字段
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_reconcile.py::test_reconcile_writes_low_confidence_mirror_fields_into_pending -v`
Expected: PASS

- [ ] **Step 5: Write the failing high-confidence auto-drop audit test**

```python
def test_reconcile_auto_drops_high_confidence_mirror_and_writes_audit(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile
    import csv

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 09:42:02", "amount": "-20.4", "currency": "CNY", "counterparty": "麦当劳", "description": "麦当劳", "category": "expense", "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat"},
        {"date": "2026-06-01 09:42:03", "amount": "-20.4", "currency": "CNY", "counterparty": "麦当劳", "description": "北京食品有限公司", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])

    do_reconcile(month="2026-06")

    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 1
    assert rows[0]["bill_source"] == "wechat"
```

- [ ] **Step 6: Run test to verify it fails**

Run: `pytest tests/test_reconcile.py::test_reconcile_auto_drops_high_confidence_mirror_and_writes_audit -v`
Expected: FAIL

- [ ] **Step 7: Implement auto-drop in reconcile pipeline**

```python
# src/ft/reconcile.py
# 将 high mirror auto-drop 结果并入 kept/removed/pairs，继续走现有 audit 输出路径
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `pytest tests/test_reconcile.py::test_reconcile_writes_low_confidence_mirror_fields_into_pending tests/test_reconcile.py::test_reconcile_auto_drops_high_confidence_mirror_and_writes_audit -v`
Expected: PASS

### Task 5: 为退款链与 locked 加安全阀

**Files:**
- Modify: `src/ft/mirror_rules.py`
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_mirror_rules.py`
- Modify: `tests/test_reconcile.py`
- Modify: `tests/test_reconcile_locked.py`

- [ ] **Step 1: Write the failing refund-gate test**

```python
def test_mirror_rule_downgrades_to_review_when_refund_chain_present():
    rows = [
        {"record_id": "a1", "date": "2025-03-10 18:21:08", "amount": "-25.8", "currency": "CNY", "counterparty": "麦当劳", "description": "麦当劳", "category": "expense", "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat", "offset_group": "refund_001", "offset_role": "expense"},
        {"record_id": "b1", "date": "2025-03-10 18:21:08", "amount": "-25.8", "currency": "CNY", "counterparty": "麦当劳", "description": "北京食品有限公司", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit", "offset_group": "refund_002", "offset_role": "expense"},
    ]
    result = detect_mirror_pairs(rows)
    assert len(result.auto_drop_pairs) == 0
    assert len(result.review_pairs) == 1
```

- [ ] **Step 2: Run test to verify it fails**

Run: `pytest tests/test_mirror_rules.py::test_mirror_rule_downgrades_to_review_when_refund_chain_present -v`
Expected: FAIL

- [ ] **Step 3: Implement refund gate**

```python
# src/ft/mirror_rules.py
# 若任一候选含 offset_group / offset_role / merge_refund_into，则禁止 high auto-drop，降级为 low review
```

- [ ] **Step 4: Run test to verify it passes**

Run: `pytest tests/test_mirror_rules.py::test_mirror_rule_downgrades_to_review_when_refund_chain_present -v`
Expected: PASS

- [ ] **Step 5: Write the failing locked test**

```python
def test_locked_rows_do_not_enter_mirror_detection(tmp_env):
    from ft import models
    from ft.reconcile import do_reconcile
    import csv

    day_path = models.RECORDS_DIR / "loan" / "2026-06.csv"
    _write_rows(day_path, [
        {"date": "2026-06-01 09:42:02", "amount": "-20.4", "currency": "CNY", "counterparty": "麦当劳", "description": "麦当劳", "category": "expense", "account_name": "工行信用卡(1200)", "source": "微信", "bill_source": "wechat", "locked": "1"},
        {"date": "2026-06-01 09:42:03", "amount": "-20.4", "currency": "CNY", "counterparty": "麦当劳", "description": "北京食品有限公司", "category": "expense", "account_name": "工行信用卡(1200)", "source": "银行卡", "bill_source": "icbc_credit"},
    ])
    do_reconcile(month="2026-06")
    with open(day_path, encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    assert len(rows) == 2
```

- [ ] **Step 6: Run test to verify it passes**

Run: `pytest tests/test_reconcile_locked.py::test_locked_rows_do_not_enter_mirror_detection -v`
Expected: PASS

### Task 6: 跑聚焦测试并修正回归

**Files:**
- Modify: `src/ft/mirror_rules.py`
- Modify: `src/ft/dedup.py`
- Modify: `src/ft/reconcile.py`
- Modify: `tests/test_mirror_rules.py`
- Modify: `tests/test_dedup.py`
- Modify: `tests/test_reconcile.py`
- Modify: `tests/test_reconcile_locked.py`

- [ ] **Step 1: Run focused reconcile/dedup test set**

Run: `pytest tests/test_mirror_rules.py tests/test_dedup.py tests/test_reconcile.py tests/test_reconcile_locked.py -q`
Expected: FAIL initially on integration mismatches

- [ ] **Step 2: Fix minimal regressions revealed by the focused suite**

```python
# 仅修复 mirror 字段传递、排序、pending/audit 输出、幂等性问题；不扩展额外功能。
```

- [ ] **Step 3: Run focused suite again**

Run: `pytest tests/test_mirror_rules.py tests/test_dedup.py tests/test_reconcile.py tests/test_reconcile_locked.py -q`
Expected: PASS

- [ ] **Step 4: Run a broader safety subset**

Run: `pytest tests/test_ai_apply.py tests/test_pending.py tests/test_transfer.py tests/test_convert.py -q`
Expected: PASS

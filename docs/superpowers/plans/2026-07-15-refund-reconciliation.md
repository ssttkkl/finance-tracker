# Reconcile 退款核销 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `ft reconcile` 的镜像去重后执行强退款自动核销，并让弱退款在 pending 确认后使用相同的净额化逻辑。

**Architecture:** 新增一个纯函数退款结算模块，负责从 records 元数据构造关系、将被去重记录的关系端规范化到保留记录、结算已确认关系并生成审计行。`reconcile.py` 负责按“去重 -> 退款结算 -> pending/写回”的顺序编排；`ai_apply.py` 仅将人工 `merge_refund_into` 决定转换为同一结算模块的输入。

**Tech Stack:** Python 3.11、pytest、CSV 标准库。

## Global Constraints

- 不改变 `convert.py` 的退款识别、关系字段或 strong/weak 分类规则。
- `locked=1` 的任何退款关系端不得自动修改。
- 全额退款不保留零金额 records；部分退款保留原消费 `record_id` 和净额。
- 自动、人工、重绑、冲突和失效都写入 `audit/reconcile`。
- 所有新行为必须先写失败测试，再写最小实现。

---

### Task 1: 退款关系规范化与结算器

**Files:**
- Create: `src/ft/refund_reconcile.py`
- Test: `tests/test_refund_reconcile.py`

**Interfaces:**
- Consumes: 去重前带 `offset_*`、`proposed_action`、`_record_file` 的 records 行、去重后保留行，以及 `{dropped_id: kept_id}` 去重映射。
- Produces: `resolve_refund_relations(source_rows, kept_rows, canonical_ids)`，返回可结算关系、需 pending 的弱/冲突关系、关系失效审计行；`settle_refund_relations(rows, relations, confidence)`，返回替换后的行和消费/退款双边审计行。

- [ ] **Step 1: 写失败测试**

```python
def test_strong_partial_refund_keeps_net_expense_and_removes_refund():
    rows = [expense("expense", -100), refund("refund", 30, "expense", "strong")]
    result, audit_rows = settle_refund_relations(rows, [relation("refund", "expense")], "strong")
    assert [(row["record_id"], row["amount"]) for row in result] == [("expense", "-70.0")]
    assert result[0]["offset_group"] == ""
    assert len(audit_rows) == 2

def test_full_refund_removes_both_records():
    result, audit_rows = settle_refund_relations(
        [expense("expense", -100), refund("refund", 100, "expense", "strong")],
        [relation("refund", "expense")],
        "strong",
    )
    assert result == []
    assert {row["record_id"] for row in audit_rows} == {"expense", "refund"}

def test_deleted_expense_relation_is_rebound_to_kept_expense():
    relations, pending, audit_rows = resolve_refund_relations(
        [expense("dropped", -100), refund("refund", 100, "dropped", "strong")],
        [expense("kept", -100), refund("refund", 100, "dropped", "strong")],
        {"dropped": "kept"},
    )
    assert [(pair.refund_id, pair.expense_id) for pair in relations] == [("refund", "kept")]
    assert pending == []
    assert audit_rows[0]["reconcile_status"] == "refund_rebound_after_dedup"
```

- [ ] **Step 2: 验证测试确实失败**

Run: `PYTHONPATH=src pytest tests/test_refund_reconcile.py -v`

Expected: FAIL，原因是 `ft.refund_reconcile` 尚不存在。

- [ ] **Step 3: 实现最小结算器**

```python
@dataclass(frozen=True)
class RefundRelation:
    refund_id: str
    expense_id: str
    strength: str
    rule_hint: str

def resolve_refund_relations(source_rows, kept_rows, canonical_ids):
    """从退款行的 merge_refund_into 建立规范化关系，并返回失效/冲突审计。"""

def settle_refund_relations(rows, relations, confidence):
    """删除退款、保留净额消费或删除全额消费，并返回双边审计行。"""
```

实现必须：解析传递式 canonical ID；拒绝重绑后多目标、方向/金额/币种/账户不匹配的关系；对部分退款清空消费的 `offset_*` 字段并设 `proposed_action=leave_as_is`；对 locked 关系端不结算。

- [ ] **Step 4: 验证结算器测试通过**

Run: `PYTHONPATH=src pytest tests/test_refund_reconcile.py -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/ft/refund_reconcile.py tests/test_refund_reconcile.py
git commit -m "feat: 增加退款关系结算器"
```

### Task 2: 将 strong 退款结算接入 reconcile

**Files:**
- Modify: `src/ft/reconcile.py:502-997`
- Test: `tests/test_reconcile.py`

**Interfaces:**
- Consumes: `dedup_with_pairs()` 返回的 `(keep_row, remove_row)` 对，以及 Task 1 的 `resolve_refund_relations` / `settle_refund_relations`。
- Produces: state 新字段 `refund_auto_audit_rows`、`refund_pending_record_ids`；无 pending 时将结算结果写入正式 records。

- [ ] **Step 1: 写失败集成测试**

```python
def test_reconcile_auto_settles_strong_full_refund_after_dedup(tmp_env):
    day_path = write_records(tmp_env, [
        expense_row("bank_expense", -10, strength="strong"),
        refund_row("refund", 10, "bank_expense", strength="strong"),
        duplicate_expense_row("wechat_expense", -10),
    ])
    do_reconcile(month="2026-06")
    assert read_rows(day_path) == []
    audit_rows = read_latest_audit(tmp_env)
    assert {row["reconcile_status"] for row in audit_rows} >= {"refund_full_auto"}

def test_reconcile_rebinds_refund_when_dedup_drops_its_original_expense(tmp_env):
    day_path = write_records(tmp_env, [
        expense_row("bank_expense", -10, strength="strong"),
        duplicate_expense_row("wechat_expense", -10),
        refund_row("refund", 10, "bank_expense", strength="strong"),
    ])
    do_reconcile(month="2026-06")
    assert read_rows(day_path) == []
    assert "refund_rebound_after_dedup" in audit_statuses(tmp_env)
```

- [ ] **Step 2: 验证集成测试失败**

Run: `PYTHONPATH=src pytest tests/test_reconcile.py -k 'strong_full_refund or rebinds_refund' -v`

Expected: FAIL，退款记录仍存在或目标 ID 悬空。

- [ ] **Step 3: 按去重后顺序接入结算器**

在 `_prepare_reconcile_state()` 中由 `pairs` 构建 `{remove_id: keep_id}`，以去重前 `scoped_active` 读取原始退款关系，再将两端规范化到 `kept`。将 strong 关系结算为新的 `kept` 集和 `refund_auto_audit_rows`，将 weak/冲突/locked 关系的相关记录 ID 写入 `refund_pending_record_ids`。在 `do_reconcile()` 所有无 pending 与 mixed pending 分支中使用结算后的保留集，并将退款审计行传给 `_write_audit()` 或 `proposed_audit.csv`。

- [ ] **Step 4: 验证定向和完整 reconcile 测试**

Run: `PYTHONPATH=src pytest tests/test_reconcile.py tests/test_reconcile_pending.py -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/ft/reconcile.py tests/test_reconcile.py tests/test_reconcile_pending.py
git commit -m "feat: reconcile 自动核销强退款"
```

### Task 3: weak 退款 pending 与人工结算

**Files:**
- Modify: `src/ft/ai_apply.py:84-146`
- Modify: `src/ft/reconcile.py:600-823`
- Test: `tests/test_ai_apply.py`
- Test: `tests/test_reconcile_pending.py`

**Interfaces:**
- Consumes: pending `edited.csv` 中退款行的 `decision_action=merge_refund_into:<expense_id>`。
- Produces: `apply_reconcile_working_rows()` 对退款合并返回跨文件正确分组的净额行和双边 audit；weak 关系单独也会创建 pending。

- [ ] **Step 1: 写失败测试**

```python
def test_weak_refund_without_mirror_candidate_creates_pending(tmp_env):
    write_records(tmp_env, [expense_row("expense", -100, strength="weak"), refund_row("refund", 30, "expense", strength="weak")])
    do_reconcile(month="2026-06")
    rows = read_pending_rows(tmp_env)
    assert {row["record_id"] for row in rows} == {"expense", "refund"}

def test_continue_reconcile_applies_confirmed_weak_partial_refund(tmp_env):
    session = create_weak_refund_pending(tmp_env, expense_amount=-100, refund_amount=30)
    confirm_merge(session, refund_id="refund", expense_id="expense")
    continue_reconcile()
    assert read_records(tmp_env) == [("expense", "-70.0")]

def test_continue_reconcile_keeps_rejected_weak_refund_unchanged(tmp_env):
    session = create_weak_refund_pending(tmp_env, expense_amount=-100, refund_amount=30)
    reject_merge(session, reason="商户无法确认")
    continue_reconcile()
    assert read_records(tmp_env) == [("expense", "-100.0"), ("refund", "30.0")]
```

- [ ] **Step 2: 验证测试失败**

Run: `PYTHONPATH=src pytest tests/test_reconcile_pending.py tests/test_ai_apply.py -k 'weak_refund or confirmed_weak' -v`

Expected: FAIL，弱退款不会创建 pending，或 continue 保留退款原行。

- [ ] **Step 3: 实现 pending 与人工结算**

在 `_should_enter_reconcile_pending()` 中加入未结算 weak 退款关系；在 `_create_reconcile_pending_session()` 把 `refund_pending_record_ids` 与现有镜像待审 ID 合并并传递闭包关联。让 `apply_reconcile_working_rows()` 收集确认的 `merge_refund_into` 动作，调用 Task 1 的结算器，将净额消费写入消费的 `record_file`，并从退款文件移除退款；保持 `leave_as_is` 的退款链完全不变。

- [ ] **Step 4: 验证人工核销、回归和账本校验**

Run: `PYTHONPATH=src pytest tests/test_ai_apply.py tests/test_reconcile.py tests/test_reconcile_pending.py -v`

Expected: PASS。

Run: `PYTHONPATH=src pytest -q`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add src/ft/ai_apply.py src/ft/reconcile.py tests/test_ai_apply.py tests/test_reconcile_pending.py
git commit -m "feat: 支持弱退款人工核销"
```

### Task 4: 更新用户流程文档

**Files:**
- Modify: `docs/import-reconcile-flow.md:188-367`
- Modify: `README.md:92-190`
- Test: `tests/test_cli.py`

**Interfaces:**
- Consumes: Task 2、Task 3 的真实命令行为和 audit 状态。
- Produces: 与实际 reconcile 顺序、strong/weak 处理、重绑和 pending 决策一致的中文文档。

- [ ] **Step 1: 写文档断言或命令帮助测试**

```python
def test_reconcile_help_describes_refund_settlement(capsys):
    cli.main(["reconcile", "--help"])
    assert "退款核销" in capsys.readouterr().out
```

- [ ] **Step 2: 验证测试失败**

Run: `PYTHONPATH=src pytest tests/test_cli.py::test_reconcile_help_describes_refund_settlement -v`

Expected: FAIL，帮助文本尚未说明退款核销。

- [ ] **Step 3: 更新帮助与流程文档**

说明固定顺序为“去重后重绑、再结算退款”；明确 strong 自动、weak pending、部分净额化、全额删除、拒绝保留、以及 audit 的双边追溯。不要把 `proposed_action` 误写为已经执行的最终决定。

- [ ] **Step 4: 验证文档相关测试**

Run: `PYTHONPATH=src pytest tests/test_cli.py -v`

Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add README.md docs/import-reconcile-flow.md src/ft/cli.py tests/test_cli.py
git commit -m "文档: 更新退款核销流程"
```

## 计划自审

- 规格中的 strong 自动、weak pending、全额删除、部分净额化、关系重绑、冲突降级、locked 保护、跨文件写回和审计均有对应任务与测试。
- 所有生产改动均位于失败测试之后；没有省略接口、验证命令或验收条件。
- 不新增 records schema，不改 convert 候选规则，范围符合设计规格。

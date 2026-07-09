# Convert Refund Fact Preservation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change `convert` refund handling from eager in-convert netting to fact-preserving relation output, so `output.csv` keeps both expense/refund rows plus structured relation fields for later reconcile/apply. Reconcile should later remove only cross-source duplicate mirror records, while preserving refund rows and refund relation fields in the final ledger layer.

**Architecture:** Keep existing refund detection and `tracking_pairs` generation in `src/ft/convert.py`, but stop collapsing strong pairs into netted output rows. Instead, generate stable `record_id`s, write relation metadata (`offset_group`, `offset_role`, `offset_strength`, `proposed_action`, etc.) onto output rows, and let later reconcile/apply consume those relations. Preserve weak pending behavior while making the main output a semi-finished fact ledger.

**Tech Stack:** Python, pytest, existing `ft.convert`, `ft.ai_apply`, `ft.reconcile` pipeline

---

## File Structure

- Modify: `finance-tracker/src/ft/convert.py`
  - Stop eager refund netting in main output rows
  - Add record ID generation and offset relation field injection
  - Extend output CSV schema
  - Keep pending generation compatible with the new output semantics
- Modify: `finance-tracker/src/ft/ai_apply.py`
  - Verify/adjust application of convert-originated `merge_refund_into` and `net_with` relations
- Modify: `finance-tracker/tests/test_convert.py`
  - Rewrite refund output assertions from “row removed/netted” to “facts preserved + relation fields present”
- Modify: `finance-tracker/tests/test_convert_pending.py`
  - Ensure weak refund pending behavior still works with preserved fact rows
- Modify: `finance-tracker/tests/test_ai_apply.py`
  - Add coverage for applying convert-emitted refund relation actions

No new production modules are required. Keep the first implementation inside existing convert/apply files.

### Task 1: Add failing tests for fact-preserving convert output

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Add focused tests for strong refund output preservation**

Inside the existing refund-related test classes in `finance-tracker/tests/test_convert.py`, add tests that assert:

```python
    def test_支付宝强退款_convert输出保留消费和退款事实(self):
        rows, tracking_pairs = _read_alipay_raw(str(sample_csv))
        expenses = [r for r in rows if r["category"] == "expense"]
        incomes = [r for r in rows if r["category"] == "income"]
        assert len(expenses) >= 1
        assert len(incomes) >= 1
        target_refunds = [r for r in incomes if r.get("offset_role") == "refund"]
        assert target_refunds
        assert any(r.get("proposed_action", "").startswith("merge_refund_into:") for r in target_refunds)
        assert all(r.get("offset_strength") == "strong" for r in target_refunds)

    def test_工行借记卡强退款_convert输出不再直接净额化(self):
        lines = [
            "2026-01-05", "09:00:00", "-19.90", "快捷支付", "支付宝（中国）网络技术有限公司",
            "2026-01-05", "10:00:00", "+19.90", "退款", "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        assert len(records) == 2
        expense = next(r for r in records if r["category"] == "expense")
        refund = next(r for r in records if r["category"] == "income")
        assert expense["offset_role"] == "expense"
        assert refund["offset_role"] == "refund"
        assert refund["offset_strength"] == "strong"
        assert refund["proposed_action"] == f"merge_refund_into:{expense['record_id']}"
        assert expense["offset_group"] == refund["offset_group"]
```

- [ ] **Step 2: Add focused test for weak refund preservation + pending compatibility**

Add a test like:

```python
    def test_弱退款_convert输出保留事实且标记weak关系(self):
        lines = [
            "2026-01-05", "09:00:00", "-100.00", "快捷支付", "支付宝（中国）网络技术有限公司",
            "2026-01-05", "09:10:00", "-100.00", "网上银行", "支付宝（中国）网络技术有限公司",
            "2026-01-05", "10:00:00", "+50.00", "退款", "支付宝（中国）网络技术有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=False)
        refund = next(r for r in records if r["category"] == "income")
        assert refund["offset_role"] == "refund"
        assert refund["offset_strength"] == "weak"
        assert refund["proposed_action"].startswith("merge_refund_into:")
        assert any(pair["pending_required"] is True for pair in tracking_pairs)
```

- [ ] **Step 3: Run the focused tests to verify they fail**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "convert输出保留消费和退款事实 or 不再直接净额化 or 标记weak关系"
```

Expected: FAIL because current convert pipeline still removes/nets strong refund rows instead of preserving them with relation fields.

### Task 2: Add relation fields and stable IDs to convert output rows

**Files:**
- Modify: `finance-tracker/src/ft/convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Add stable record-id helper and refund relation annotator**

In `finance-tracker/src/ft/convert.py`, add helpers near refund helpers:

```python
def _assign_record_ids(rows: list[dict], *, prefix: str = "c") -> list[dict]:
    assigned = []
    for idx, row in enumerate(rows, 1):
        assigned.append({**row, "record_id": row.get("record_id") or f"{prefix}_{idx:06d}"})
    return assigned


def _annotate_refund_relations(rows: list[dict], tracking_pairs) -> list[dict]:
    by_signature = {}
    for row in rows:
        key = (
            row.get("date"), row.get("amount"), row.get("counterparty", ""),
            row.get("description", ""), row.get("category"), row.get("payment_method", ""),
        )
        by_signature.setdefault(key, []).append(row)

    for group_idx, pair in enumerate(tracking_pairs, 1):
        expense = pair["expense"]
        refund = pair["refund"]
        group_id = f"refund_{group_idx:06d}"
        expense_key = (...)
        refund_key = (...)
        expense_row = by_signature[expense_key].pop(0)
        refund_row = by_signature[refund_key].pop(0)
        expense_row["offset_group"] = group_id
        expense_row["offset_role"] = "expense"
        expense_row["offset_strength"] = pair.get("match_strength", "")
        expense_row["offset_source"] = pair.get("source_refund_signal", "")
        expense_row["offset_rule_hint"] = pair.get("rule_hint", "")
        expense_row["offset_match_type"] = pair.get("match_type", "")
        expense_row["proposed_action"] = expense_row.get("proposed_action", "leave_as_is")
        refund_row["offset_group"] = group_id
        refund_row["offset_role"] = "refund"
        refund_row["offset_strength"] = pair.get("match_strength", "")
        refund_row["offset_source"] = pair.get("source_refund_signal", "")
        refund_row["offset_rule_hint"] = pair.get("rule_hint", "")
        refund_row["offset_match_type"] = pair.get("match_type", "")
        refund_row["proposed_action"] = f"merge_refund_into:{expense_row['record_id']}"
    return rows
```

Use exact tuple fields that match the row shape in this codebase; do not add fuzzy matching here.

- [ ] **Step 2: Stop returning netted/consumed refund output rows for convert-facing flows**

Refactor `convert.py` so refund pairing still produces `tracking_pairs`, but the rows used for `output.csv` preserve both sides of the fact rows. The easiest first implementation is:

- keep existing source parsers and `tracking_pairs`
- build a new `prepared_rows_for_output` list from the raw fact rows before refund collapse
- assign `record_id`s to those rows
- annotate relations from `tracking_pairs`
- use this fact-preserving row list for output generation and pending generation

Do **not** remove the old pairing helpers entirely in this task; just separate:
- relation detection
- output projection

- [ ] **Step 3: Extend `_build_output_row()` and `_write_output_csv()` schema**

Add these output columns:

```python
"record_id", "offset_group", "offset_role", "offset_strength",
"offset_source", "offset_rule_hint", "offset_match_type", "proposed_action"
```

Ensure rows without offset relations write empty strings and default `proposed_action="leave_as_is"`.

- [ ] **Step 4: Run the focused tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "convert输出保留消费和退款事实 or 不再直接净额化 or 标记weak关系"
```

Expected: PASS.

### Workflow note: AI consumes refund relation fields at reconcile time

Before implementation, keep this workflow invariant in mind:

- `convert` writes `proposed_action`, `offset_group`, `offset_strength`, and related fields into the semi-finished fact ledger
- these fields are **not** meant to trigger immediate default AI review right after each single-source convert
- the default consumer of these fields is the later **reconcile-stage** AI review, after multiple imported sources have been merged into one review surface
- convert pending should remain only for cases where current convert semantics already require local blocking review; do not redesign this task into “every convert always launches AI”

This means the implementation should preserve complete facts now and defer cross-source refund arbitration to reconcile.

### Task 3: Keep pending behavior while switching to fact-preserving output

**Files:**
- Modify: `finance-tracker/src/ft/convert.py`
- Modify: `finance-tracker/tests/test_convert_pending.py`
- Test: `finance-tracker/tests/test_convert_pending.py`

- [ ] **Step 1: Add failing pending test for preserved refund rows**

Add or update a pending test to assert:

- `proposed_output.csv` contains both expense and refund fact rows
- weak refund rows still appear in `ai_working.csv`
- strong refund rows do not need to be sent for AI review

Structure the assertion around current pending fixture helpers in `tests/test_convert_pending.py`.

- [ ] **Step 2: Adjust `_build_convert_ai_rows()` to consume fact-preserving prepared rows**

Update `convert.py` so:

- all prepared fact rows become base rows in `ai_working.csv` only if that matches current session semantics, or keep only output rows as base rows if needed for minimal change
- weak refund pairs still get AI rows with the same `ai_group`
- no duplicate logical rows are emitted accidentally

Recommended minimal rule:
- `proposed_output.csv` always reflects fact-preserving rows
- `ai_working.csv` continues to contain only rows needed for weak decisions, plus any always-present base rows required by the existing session model

- [ ] **Step 3: Run pending tests**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert_pending.py -q
```

Expected: PASS.

### Task 4: Make apply consume convert-originated refund relations

**Files:**
- Modify: `finance-tracker/src/ft/ai_apply.py`
- Modify: `finance-tracker/tests/test_ai_apply.py`
- Test: `finance-tracker/tests/test_ai_apply.py`

- [ ] **Step 1: Add failing tests for relation application on preserved fact rows**

Add tests to `tests/test_ai_apply.py` asserting that when rows contain:

- one expense row with `record_id=c_000001`
- one refund row with `proposed_action=merge_refund_into:c_000001`

then `apply_convert_working_rows()` produces the same final netted result as the old eager convert flow.

Also add a `net_with:` case if that action is reachable in convert-originated rows.

- [ ] **Step 2: Ensure `apply_convert_working_rows()` treats convert-originated `proposed_action` the same as AI-edited actions**

If needed, normalize `proposed_action` into `ai_action` when building working rows, or extend apply logic so convert-emitted actions are consumed without requiring manual AI edits first.

Prefer the smallest change that preserves current pending behavior.

- [ ] **Step 3: Run AI apply tests**

Run:

```bash
PYTHONPATH=src pytest tests/test_ai_apply.py -q
```

Expected: PASS.

### Task 5: Preserve refund facts while allowing reconcile to drop cross-source duplicates

**Files:**
- Modify: `finance-tracker/src/ft/reconcile.py`
- Modify: `finance-tracker/tests/test_convert.py`
- Modify: `finance-tracker/tests/test_ai_apply.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Ensure final ledger semantics keep refund rows**

Adjust expectations and implementation so refund-related relations are preserved in the ledger rows after convert/reconcile preparation. Do not design this feature to physically delete refund facts when a relation exists.

- [ ] **Step 2: Keep duplicate-removal responsibility scoped to cross-source mirror rows**

When later touching reconcile logic, ensure only duplicate mirror observations across sources are removed from the final ledger layer. Refund rows themselves remain as business facts.

- [ ] **Step 3: Add or update regression tests**

Add assertions that:

- refund facts survive
- refund relation fields survive
- duplicate cross-source mirror rows can still be marked/dropped by reconcile

### Task 6: Full convert regression and real-bill verification

**Files:**
- Modify: `finance-tracker/src/ft/convert.py`
- Test: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert_pending.py`
- Test: `finance-tracker/tests/test_ai_apply.py`

- [ ] **Step 1: Run full convert-related tests**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q
PYTHONPATH=src pytest tests/test_convert_pending.py -q
PYTHONPATH=src pytest tests/test_ai_apply.py -q
```

Expected: PASS.

- [ ] **Step 2: Replay representative bills and inspect the new output semantics**

Run a replay script that converts representative Alipay / WeChat / ICBC debit samples and inspect:

- `output.csv` now contains both expense and refund rows
- refund rows carry `proposed_action`
- strong refund facts are preserved instead of being physically removed
- the final relation metadata is stable and parseable

Example shape (adapt exact paths to local fixtures or `.ft/bills` files already used in this repo):

```bash
PYTHONPATH=src python - <<'PY'
from ft.convert import _prepare_convert_rows
for source, path, password in [
    ('icbc-debit', '.ft/bills/工商银行历史明细（申请单号：26061301103655542828）密码013958.pdf', '013958'),
]:
    rows, bill_type, tracking_pairs = _prepare_convert_rows(path, source, password)
    print(source, bill_type, len(rows), len(tracking_pairs))
    for row in rows[:10]:
        print(row.get('record_id'), row['category'], row.get('offset_role'), row.get('offset_strength'), row.get('proposed_action'))
PY
```

Expected:
- fact rows are preserved
- relation fields are populated for refund pairs
- no eager netting remains in the convert-facing output rows

## Plan Self-Review

### Spec coverage
- Convert output becomes a semi-finished fact ledger: Task 2
- Refund facts remain visible for reconcile: Task 2 + Task 5
- Strong/weak become relation-confidence rather than eager apply: Task 1 + Task 2
- Pending remains available for weak pairs: Task 3
- Final netting still possible via apply: Task 4

### Placeholder scan
- No `TODO`, `TBD`, or vague placeholders remain
- Every task names exact files, commands, and expected outcomes
- Code snippets are schematic where exact tuple fields depend on current row shape, but implementation target functions are explicit and bounded

### Type consistency
- `offset_role`: `expense | refund | offset_income`
- `offset_strength`: `strong | weak | ""`
- `proposed_action`: `leave_as_is | merge_refund_into:<record_id> | net_with:<record_id> | drop`
- Helper names are consistent: `_assign_record_ids`, `_annotate_refund_relations`

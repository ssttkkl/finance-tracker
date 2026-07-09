# ICBC Debit Refund Detection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a zero-miss, net-safe ICBC debit refund pipeline that recognizes `退款` / `退货` / `撤销交易`, auto-applies only the safe same-cluster refund cases, and preserves correct debit-account balances and expense statistics.

**Architecture:** Keep the existing ICBC debit PDF row parser in `src/ft/convert.py`, but split candidate handling into two paths: `撤销交易` continues through reversal pairing, while `退款` / `退货` enter a new refund pairing path with debit-specific cluster safety checks. Reuse the existing refund-tracking shape so pending handling and refund CSV output remain consistent with other bill types.

**Tech Stack:** Python, pytest, existing `ft.convert` parsing and refund pairing pipeline

---

## File Structure

- Modify: `finance-tracker/src/ft/convert.py`
  - Add ICBC debit refund candidate detection
  - Add ICBC debit refund/account cluster helpers
  - Add debit-specific refund pairing flow after row parsing
  - Keep `撤销交易` on the existing reversal path
- Modify: `finance-tracker/tests/test_convert.py`
  - Add focused row-parser tests for refund candidate tagging
  - Add strong/weak tests for debit refund pairing
  - Add regression tests ensuring `利息` / `还款` / `基金赎回` stay out of refund handling

No new production files are required. Keep the implementation inside `convert.py` to match the current importer layout.

### Task 1: Add failing tests for ICBC debit refund candidate detection

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing tests for refund/reversal candidate tagging**

Add these tests near the existing ICBC debit parser tests in `finance-tracker/tests/test_convert.py`:

```python
    def test_工行借记卡_退款标记为_refund_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "退款", "1614", "+19.90",
            "405.84", "支付宝（中国）网络技术有限公司", "2155****0690", "快捷支付",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "refund"
        assert rec["_is_refund"] is True

    def test_工行借记卡_退货标记为_refund_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "退货", "0200", "+100.00",
            "1076.16", "中国银联无卡快捷支付业务专户", "3602****5565", "网上银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "refund"
        assert rec["_is_refund"] is True

    def test_工行借记卡_撤销交易标记为_reversal_candidate(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "撤销交易", "1614", "+761.08",
            "33628.24", "黄文龙", "3799****9166", "手机银行",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["category"] == "income"
        assert rec["_debit_offset_type"] == "reversal"
        assert rec["_is_reversal"] is True
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_退款标记为_refund_candidate or 工行借记卡_退货标记为_refund_candidate or 工行借记卡_撤销交易标记为_reversal_candidate"
```

Expected: FAIL because `_parse_icbc_debit_row()` does not yet tag debit offset candidates.

- [ ] **Step 3: Implement minimal ICBC debit offset tagging in `_parse_icbc_debit_row()`**

In `finance-tracker/src/ft/convert.py`, extend `_parse_icbc_debit_row()` so that it tags rows by summary before returning:

```python
    debit_offset_type = ""
    if summary == "撤销交易":
        debit_offset_type = "reversal"
    elif summary in {"退款", "退货"}:
        debit_offset_type = "refund"
```

Then attach the flags into the returned dict:

```python
        "_debit_offset_type": debit_offset_type,
        "_is_refund": debit_offset_type == "refund",
        "_is_reversal": debit_offset_type == "reversal",
```

Do not tag `利息`, `还款`, `基金购买`, `基金赎回`, or `银联消费`.

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_退款标记为_refund_candidate or 工行借记卡_退货标记为_refund_candidate or 工行借记卡_撤销交易标记为_reversal_candidate"
```

Expected: PASS.

### Task 2: Add failing tests for zero-miss exclusion boundaries

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing tests ensuring non-refund incomes stay out**

Add these tests:

```python
    def test_工行借记卡_利息不进入退款链路(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "利息", "1614", "+0.25",
            "998.87", "（空）", "（空）", "批量业务",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["_debit_offset_type"] == ""
        assert rec["_is_refund"] is False
        assert rec["_is_reversal"] is False

    def test_工行借记卡_基金赎回不进入退款链路(self):
        row = [
            "2026-01-05\n20:32:09", "1614020101021984636", "活期", "00000",
            "人民币", "钞", "基金赎回", "1614", "+10000.00",
            "98270.93", "中国工商银行股份有限公司基金快速赎回", "0200****6428", "业务资金清算专户",
        ]
        from ft.convert import _parse_icbc_debit_row
        rec = _parse_icbc_debit_row(row)
        assert rec is not None
        assert rec["_debit_offset_type"] == ""
        assert rec["_is_refund"] is False
        assert rec["_is_reversal"] is False
```

- [ ] **Step 2: Run the tests to verify they fail before implementation**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_利息不进入退款链路 or 工行借记卡_基金赎回不进入退款链路"
```

Expected: FAIL until the default `_debit_offset_type` fields are present on all debit rows.

- [ ] **Step 3: Ensure all ICBC debit rows get explicit default flags**

In `_parse_icbc_debit_row()`, make sure rows that are not refund/reversal candidates still return:

```python
        "_debit_offset_type": "",
        "_is_refund": False,
        "_is_reversal": False,
```

with the tagged values only overriding these defaults for `退款` / `退货` / `撤销交易`.

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_利息不进入退款链路 or 工行借记卡_基金赎回不进入退款链路"
```

Expected: PASS.

### Task 3: Add failing tests for debit refund auto-pairing strong/weak behavior

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing tests for same-cluster strong refund pairing**

Add these tests inside the ICBC debit test area:

```python
    def test_工行借记卡_支付宝退款_唯一候选自动核销(self):
        rows = [
            {
                "date": "2026-01-01 10:00:00", "amount": -19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 11:00:00", "amount": 19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "退款",
                "category": "income", "payment_method": "快捷支付", "_debit_offset_type": "refund",
                "_is_refund": True, "_is_reversal": False,
            },
        ]
        from ft.convert import _pair_icbc_debit_offsets
        records, tracking_pairs = _pair_icbc_debit_offsets(rows)
        assert len(records) == 0
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False

    def test_工行借记卡_同类多候选退款_最近归并直过(self):
        rows = [
            {
                "date": "2026-01-01 10:00:00", "amount": -15.80, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 10:30:00", "amount": -19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 11:00:00", "amount": 19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "退款",
                "category": "income", "payment_method": "快捷支付", "_debit_offset_type": "refund",
                "_is_refund": True, "_is_reversal": False,
            },
        ]
        from ft.convert import _pair_icbc_debit_offsets
        records, tracking_pairs = _pair_icbc_debit_offsets(rows)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False
        assert tracking_pairs[0]["candidate_count"] == 2
        assert tracking_pairs[0]["expense"]["amount"] == -19.90
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_支付宝退款_唯一候选自动核销 or 工行借记卡_同类多候选退款_最近归并直过"
```

Expected: FAIL because `_pair_icbc_debit_offsets()` does not exist yet.

- [ ] **Step 3: Implement `_pair_icbc_debit_offsets()` and minimal debit cluster helpers**

In `finance-tracker/src/ft/convert.py`, add:

```python
def _icbc_debit_refund_cluster(counterparty: str, description: str) -> str:
    text = f"{counterparty or ''} {description or ''}"
    if "支付宝（中国）网络技术有限公司" in text:
        return "alipay_refund"
    if "财付通" in text or "深圳市财付通支付科技有限公司" in text:
        return "tenpay_refund"
    if "中国银联无卡快捷支付业务专户" in text or "银联无卡支付业务" in text:
        return "unionpay_refund"
    if "京东商城平台商户" in text or "网银在线（北京）科技有限公司" in text:
        return "jd_refund"
    if "淘宝平台商户" in text:
        return "taobao_refund"
    return ""


def _icbc_debit_account_cluster(rec: dict) -> str:
    payment_method = rec.get("payment_method", "")
    return f"icbc_debit_{payment_method or 'unknown'}"
```

Then implement `_pair_icbc_debit_offsets(rows)` by:

- splitting rows into `refunds`, `reversals`, `others`, and `expenses`
- sending `reversals` through the existing `_pair_reversals()`
- sending `refunds` through debit-specific pairing logic
- tagging debit refund matches `strong` only when all candidates stay in one refund cluster and one account cluster, and amount does not overshoot
- choosing the nearest amount-covering candidate when multiple safe candidates exist

Keep the output shape aligned with existing `tracking_pairs`.

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_支付宝退款_唯一候选自动核销 or 工行借记卡_同类多候选退款_最近归并直过"
```

Expected: PASS.

### Task 4: Add failing tests for unsafe debit refund cases staying weak

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing weak-case tests**

Add these tests:

```python
    def test_工行借记卡_跨类型候选退款_保持弱置信(self):
        rows = [
            {
                "date": "2026-01-01 10:00:00", "amount": -19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 10:30:00", "amount": -19.90, "currency": "CNY",
                "counterparty": "财付通", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 11:00:00", "amount": 19.90, "currency": "CNY",
                "counterparty": "支付宝（中国）网络技术有限公司", "description": "退款",
                "category": "income", "payment_method": "快捷支付", "_debit_offset_type": "refund",
                "_is_refund": True, "_is_reversal": False,
            },
        ]
        from ft.convert import _pair_icbc_debit_offsets
        records, tracking_pairs = _pair_icbc_debit_offsets(rows)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "weak"
        assert tracking_pairs[0]["pending_required"] is True

    def test_工行借记卡_退款冲超候选_不自动核销(self):
        rows = [
            {
                "date": "2026-01-01 10:00:00", "amount": -10.00, "currency": "CNY",
                "counterparty": "财付通", "description": "消费",
                "category": "expense", "payment_method": "快捷支付", "_debit_offset_type": "",
                "_is_refund": False, "_is_reversal": False,
            },
            {
                "date": "2026-01-01 11:00:00", "amount": 19.90, "currency": "CNY",
                "counterparty": "财付通", "description": "退款",
                "category": "income", "payment_method": "快捷支付", "_debit_offset_type": "refund",
                "_is_refund": True, "_is_reversal": False,
            },
        ]
        from ft.convert import _pair_icbc_debit_offsets
        records, tracking_pairs = _pair_icbc_debit_offsets(rows)
        assert len(tracking_pairs) == 0
        assert len([r for r in records if r["category"] == "income"]) == 1
```

- [ ] **Step 2: Run the tests to verify they fail before safety logic is complete**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_跨类型候选退款_保持弱置信 or 工行借记卡_退款冲超候选_不自动核销"
```

Expected: FAIL until weak-path classification and overshoot handling are complete.

- [ ] **Step 3: Finish debit refund weak-path safety checks**

In `_pair_icbc_debit_offsets()` ensure:

- cross-cluster candidate sets produce `match_strength="weak"`
- cross-account-cluster candidate sets produce `match_strength="weak"`
- overshoot candidates are excluded from matching entirely
- unmatched refunds remain income rows rather than being dropped

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "工行借记卡_跨类型候选退款_保持弱置信 or 工行借记卡_退款冲超候选_不自动核销"
```

Expected: PASS.

### Task 5: Wire the debit refund pipeline into real raw parsing and replay verification

**Files:**
- Modify: `finance-tracker/src/ft/convert.py`
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert_pending.py`
- Test: `finance-tracker/tests/test_ai_apply.py`

- [ ] **Step 1: Replace the ICBC debit post-processing path**

In `_read_icbc_debit_raw()` replace the current single line:

```python
    records, rev_pairs = _pair_reversals(records)
    return records, "icbc_debit", rev_pairs
```

with a call to the new combined debit handler:

```python
    records, tracking_pairs = _pair_icbc_debit_offsets(records)
    return records, "icbc_debit", tracking_pairs
```

Ensure the new helper still includes reversal matches in `tracking_pairs`.

- [ ] **Step 2: Run the full convert-related test files**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q
PYTHONPATH=src pytest tests/test_convert_pending.py -q
PYTHONPATH=src pytest tests/test_ai_apply.py -q
```

Expected: PASS.

- [ ] **Step 3: Replay the real ICBC debit PDF and inspect the result mix**

Run:

```bash
PYTHONPATH=src python - <<'PY'
from ft.convert import _read_icbc_debit_raw
path = '/Users/huangwenlong/.ft/bills/工商银行历史明细（申请单号：26061301103655542828）密码013958.pdf'
records, bill_type, tracking_pairs = _read_icbc_debit_raw(path, '013958')
print('bill_type=', bill_type)
print('records=', len(records))
print('pairs=', len(tracking_pairs))
print('strong_pairs=', sum(1 for p in tracking_pairs if p.get('match_strength') == 'strong'))
print('weak_pairs=', sum(1 for p in tracking_pairs if p.get('match_strength') == 'weak'))
for pair in tracking_pairs[:20]:
    print(pair.get('match_strength', ''), pair.get('match_type', ''), pair['expense']['date'], pair['expense']['counterparty'], '=>', pair['refund']['date'], pair['refund']['counterparty'])
PY
```

Expected:
- `退款` / `退货` / `撤销交易` all enter some offset path
- `利息` / `还款` / `基金赎回` remain outside refund pairing
- safe same-cluster refund cases increase `strong`
- unsafe cross-cluster or ambiguous cases remain `weak`

- [ ] **Step 4: Stop after replay and report the observed rule coverage**

Summarize which real refund domains were upgraded to `strong`, which remained `weak`, and whether any new refund-like pattern appeared that should update the spec.

## Plan Self-Review

### Spec coverage
- Zero-miss candidate recognition for `退款` / `退货` / `撤销交易`: Task 1 and Task 5
- Exclusion of non-refund incomes: Task 2
- Net-safe same-cluster auto-pairing: Task 3
- Weak behavior for cross-cluster / overshoot cases: Task 4
- Real-bill replay verification: Task 5

### Placeholder scan
- No `TODO`, `TBD`, or vague placeholders remain
- Every task includes exact file paths, code snippets, commands, and expected results

### Type consistency
- `'_debit_offset_type'`: `"" | "refund" | "reversal"`
- `'_is_refund'`: `True | False`
- `'_is_reversal'`: `True | False`
- helper names are consistent: `_pair_icbc_debit_offsets`, `_icbc_debit_refund_cluster`, `_icbc_debit_account_cluster`

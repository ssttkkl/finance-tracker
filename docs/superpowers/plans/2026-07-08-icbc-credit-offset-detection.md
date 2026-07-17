# ICBC Credit Net-Safe Refund Auto-Apply Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a net-safe ICBC credit-card refund auto-apply pipeline that keeps zero-miss offset detection, auto-applies same-cluster merchant refunds, and only sends cross-cluster or unsafe cases to pending.

**Architecture:** Keep the current ICBC credit offset detection in `src/ft/convert.py`, but replace the old “candidate_count must equal 1” strong rule with a cluster-based safety rule. Merchant refunds will become `strong` when all candidates fall into the same offset cluster and account cluster, the refund amount does not overshoot, and the chosen expense is the nearest amount-covering candidate; benefit/cashback/fee offsets remain `keep_as_offset_income`.

**Tech Stack:** Python, pytest, existing `ft.convert` refund pairing pipeline

---

## File Structure

- Modify: `finance-tracker/src/ft/convert.py`
  - Add ICBC credit refund cluster helpers
  - Add account-cluster helper for ICBC credit refund evaluation
  - Relax ICBC merchant refund strong classification from unique-only to same-cluster near-match
  - Preserve fallback and dirty-text weak behavior
- Modify: `finance-tracker/tests/test_convert.py`
  - Add focused tests for same-cluster auto-apply
  - Add negative tests for cross-cluster and overshoot protection
  - Add regression coverage for real bill patterns (`中国铁路网络有限公司`, 京东, 美团, 自助侠)

No new production files are required. Keep the implementation inside `convert.py` to match the current importer design.

### Task 1: Add failing tests for net-safe same-cluster strong classification

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing tests for same-cluster multi-candidate strong behavior**

Add these tests inside `TestIcbcRefundPairing` in `finance-tracker/tests/test_convert.py`:

```python
    def test_铁路退款_同类多候选按最近归并直过(self):
        lines = [
            "2026-01-01",
            "08:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-01",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "120.00",
            "人民币",
            "120.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-01",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "退货",
            "支付宝-中国铁路网络有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False
        assert tracking_pairs[0]["candidate_count"] == 2
        assert tracking_pairs[0]["expense"]["date"] == "2026-01-01 09:00:00"

    def test_京东退款_同类多候选按最近且可覆盖金额归并直过(self):
        lines = [
            "2026-01-02",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "20.00",
            "人民币",
            "20.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-02",
            "09:30:00",
            "622599000000001200",
            "借",
            "人民币",
            "80.00",
            "人民币",
            "80.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-02",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "50.00",
            "人民币",
            "50.00",
            "退货",
            "京东支付-京东商城业务",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False
        assert tracking_pairs[0]["candidate_count"] == 2
        assert tracking_pairs[0]["expense"]["amount"] == -80.0
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "铁路退款_同类多候选按最近归并直过 or 京东退款_同类多候选按最近且可覆盖金额归并直过"
```

Expected: FAIL because current ICBC merchant refund logic still forces `candidate_count != 1` to remain weak.

- [ ] **Step 3: Implement minimal cluster helpers and relax unique-only strong gating**

In `finance-tracker/src/ft/convert.py`, add helpers near the ICBC credit refund helpers:

```python
def _icbc_credit_offset_cluster(value: str, description: str) -> str:
    text = f"{value or ''} {description or ''}"
    if "中国铁路网络有限公司" in text:
        return "railway_travel"
    if any(token in text for token in ("京东", "网银在线")):
        return "ecommerce_jd"
    if any(token in text for token in ("美团", "北京象鲜科技有限公司")):
        return "local_life_meituan"
    if "自助侠" in text:
        return "device_service"
    if any(token in text for token in ("携程", "去哪儿")):
        return "rideshare_travel"
    return ""


def _icbc_credit_account_cluster(payment_method: str, card_number: str) -> str:
    card_tail = (card_number or "").strip()
    if card_tail:
        return f"icbc_credit_card_{card_tail}"
    return f"icbc_credit_channel_{payment_method or 'unknown'}"
```

Then update `_classify_refund_match(...)` for ICBC merchant refunds so that:

```python
    if ref.get("_refund_signal") == "icbc_credit_return" and ref.get("offset_type") == "merchant_refund":
        trusted = ref.get("_icbc_refund_merchant_trusted", False)
        if not trusted:
            return "weak", True
        if rule_hint not in {"refund_raw_cp_match", "refund_cp_match"}:
            return "weak", True
        if not ref.get("_icbc_refund_same_cluster", False):
            return "weak", True
        if not ref.get("_icbc_refund_same_account_cluster", False):
            return "weak", True
```

Do not keep the `candidate_count != 1` hard restriction.

- [ ] **Step 4: Run the tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "铁路退款_同类多候选按最近归并直过 or 京东退款_同类多候选按最近且可覆盖金额归并直过"
```

Expected: PASS.

### Task 2: Add failing tests for cross-cluster and overshoot protections

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write the failing negative tests for unsafe auto-apply**

Add these tests inside `TestIcbcRefundPairing`:

```python
    def test_跨消费类型候选_保持弱置信待审(self):
        lines = [
            "2026-01-03",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "中国铁路网络有限公司",
            "",
            "2026-01-03",
            "09:10:00",
            "622599000000001200",
            "借",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-03",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "100.00",
            "人民币",
            "100.00",
            "退货",
            "退款",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "weak"
        assert tracking_pairs[0]["pending_required"] is True

    def test_退款金额冲超候选_保持弱置信待审(self):
        lines = [
            "2026-01-04",
            "09:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "20.00",
            "人民币",
            "20.00",
            "消费",
            "京东支付-京东商城业务",
            "",
            "2026-01-04",
            "10:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "50.00",
            "人民币",
            "50.00",
            "退货",
            "京东支付-京东商城业务",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 0
        incomes = [r for r in records if r["category"] == "income"]
        assert len(incomes) == 1
```

- [ ] **Step 2: Run the negative tests to verify failures before implementation**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "跨消费类型候选_保持弱置信待审 or 退款金额冲超候选_保持弱置信待审"
```

Expected: At least the cross-cluster case FAILS before cluster safety flags are implemented.

- [ ] **Step 3: Implement cluster-safety flags on the selected ICBC refund candidate**

In `_pair_refunds(...)`, after selecting `best`, compute safety flags for ICBC merchant refunds across all candidates:

```python
        if ref.get("_refund_signal") == "icbc_credit_return":
            candidate_expenses = [expenses[c["expense_index"]] for c in candidates]
            offset_clusters = {
                _icbc_credit_offset_cluster(exp.get("counterparty", ""), exp.get("description", ""))
                for exp in candidate_expenses
            }
            account_clusters = {
                _icbc_credit_account_cluster(exp.get("payment_method", ""), exp.get("card_number", ""))
                for exp in candidate_expenses
            }
            ref["_icbc_refund_same_cluster"] = len(offset_clusters) == 1 and "" not in offset_clusters
            ref["_icbc_refund_same_account_cluster"] = len(account_clusters) == 1
```

Keep the existing basic constraints so overshoot still blocks candidate collection.

- [ ] **Step 4: Run the negative tests to verify they pass**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "跨消费类型候选_保持弱置信待审 or 退款金额冲超候选_保持弱置信待审"
```

Expected: PASS.

### Task 3: Add regression coverage for real-pattern relaxed strong cases

**Files:**
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert.py`

- [ ] **Step 1: Write focused regression tests for real refund domains**

Add these tests inside `TestIcbcRefundPairing`:

```python
    def test_自助侠重复退款_同类近邻可自动核销(self):
        lines = [
            "2026-01-05",
            "08:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "2.00",
            "人民币",
            "2.00",
            "消费",
            "财付通-自助侠",
            "",
            "2026-01-05",
            "08:10:00",
            "622599000000001200",
            "借",
            "人民币",
            "2.00",
            "人民币",
            "2.00",
            "消费",
            "财付通-自助侠",
            "",
            "2026-01-05",
            "09:00:00",
            "379983032529166",
            "贷",
            "人民币",
            "0.70",
            "人民币",
            "0.70",
            "退货",
            "财付通-自助侠",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False
        assert tracking_pairs[0]["candidate_count"] == 2

    def test_美团平台退款_同类多候选可自动核销(self):
        lines = [
            "2026-01-06",
            "12:00:00",
            "622599000000001200",
            "借",
            "人民币",
            "20.00",
            "人民币",
            "20.00",
            "消费",
            "美团支付-北京象鲜科技有限公司",
            "",
            "2026-01-06",
            "12:05:00",
            "622599000000001200",
            "借",
            "人民币",
            "人民币",
            "40.00",
            "消费",
            "美团支付-北京象鲜科技有限公司",
            "",
            "2026-01-06",
            "12:20:00",
            "379983032529166",
            "贷",
            "人民币",
            "5.00",
            "人民币",
            "5.00",
            "退货",
            "美团支付-北京象鲜科技有限公司",
        ]
        from ft.convert import _parse_icbc_lines
        records, tracking_pairs = _parse_icbc_lines(lines, is_credit=True)
        assert len(tracking_pairs) == 1
        assert tracking_pairs[0]["match_strength"] == "strong"
        assert tracking_pairs[0]["pending_required"] is False
```

- [ ] **Step 2: Run the real-pattern tests to verify failures before implementation**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "自助侠重复退款_同类近邻可自动核销 or 美团平台退款_同类多候选可自动核销"
```

Expected: FAIL while the unique-only strong restriction still exists.

- [ ] **Step 3: Fix the test fixture typo and ensure parser-compatible inputs**

In the `美团平台退款` test, ensure the expense amount block is valid and matches parser expectations exactly:

```python
            "借",
            "人民币",
            "40.00",
            "人民币",
            "40.00",
            "消费",
```

Then keep the rest of the test unchanged.

- [ ] **Step 4: Run the real-pattern tests to verify they pass after implementation**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q -k "自助侠重复退款_同类近邻可自动核销 or 美团平台退款_同类多候选可自动核销"
```

Expected: PASS.

### Task 4: Run full verification and real-bill replay

**Files:**
- Modify: `finance-tracker/src/ft/convert.py`
- Modify: `finance-tracker/tests/test_convert.py`
- Test: `finance-tracker/tests/test_convert_pending.py`
- Test: `finance-tracker/tests/test_ai_apply.py`

- [ ] **Step 1: Run the full convert test file**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert.py -q
```

Expected: PASS.

- [ ] **Step 2: Run the pending and AI regression tests**

Run:

```bash
PYTHONPATH=src pytest tests/test_convert_pending.py -q
PYTHONPATH=src pytest tests/test_ai_apply.py -q
```

Expected: PASS.

- [ ] **Step 3: Replay the real ICBC credit PDFs and inspect the new strong/weak mix**

Run:

```bash
PYTHONPATH=src python - <<'PY'
from pathlib import Path
from ft.convert import _read_icbc_raw
base = Path('.ft/bills')
for name, pwd in [
    ('202606130112316491068950-20260613_001密码349448.pdf', '349448'),
    ('202606130112316491068950-20260613_002密码349448.pdf', '349448'),
]:
    records, bill_type, tracking_pairs = _read_icbc_raw(str(base / name), pwd)
    print(name)
    print('bill_type=', bill_type)
    print('pairs=', len(tracking_pairs))
    print('strong_pairs=', sum(1 for p in tracking_pairs if p.get('match_strength') == 'strong'))
    print('weak_pairs=', sum(1 for p in tracking_pairs if p.get('match_strength') == 'weak'))
    for pair in tracking_pairs[:10]:
        print(pair.get('match_strength'), pair.get('rule_hint'), pair.get('candidate_count'), pair['expense']['counterparty'], pair['refund']['counterparty'])
PY
```

Expected:
- Same-cluster railway / JD / Meituan / 自助侠 refunds increase in `strong`
- Cross-cluster, fallback, and dirty-text cases remain `weak`
- Benefit/cashback/fee rows are still preserved as `offset_income`

- [ ] **Step 4: Stop and review the replay outcome before any broader rollout logic changes**

Review the printed output and confirm the relaxed `strong` cases are concentrated in same-cluster refund domains rather than noisy fallback paths.

## Plan Self-Review

### Spec coverage
- Zero-miss offset recognition stays intact: existing parser behavior + Task 4 replay verification
- Net-safe merchant strong classification: Task 1 and Task 2
- Same-cluster near-match auto-apply for real domains: Task 3
- Cross-cluster / overshoot protections: Task 2
- Real-bill verification: Task 4

### Placeholder scan
- No `TODO`, `TBD`, or vague placeholders remain
- Every task includes exact file paths, code snippets, commands, and expected results

### Type consistency
- `offset_type`: `merchant_refund | benefit_rebate | campaign_cashback | fee_reversal`
- `match_strength`: `strong | weak`
- `pending_required`: `True | False`
- Helper names are consistent across tasks: `_icbc_credit_offset_cluster`, `_icbc_credit_account_cluster`

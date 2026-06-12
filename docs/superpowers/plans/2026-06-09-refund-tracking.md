# 退款追踪 CSV 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** convert 阶段的退款配对生成 `_refunds.csv` 追踪文件。

**Architecture:** `_pair_refunds` / `_pair_ccb_refunds` 返回值从 `list[dict]` 改为 `(records, tracking_pairs)`；raw reader 透传 tracking_pairs；do_convert 汇总后写 `_refunds.csv`。

**Tech Stack:** Python 3.11+, uv, pytest

---

### Task 1: _pair_refunds 增加追踪输出（支付宝/微信/ICBC信用卡共用）

**Files:**
- Modify: `src/ft/convert.py` — `_pair_refunds` (lines 141-227)

- [ ] **Step 1: 改 _pair_refunds 返回值**

在函数开头初始化 `tracking_pairs = []`。

在全额配对决策处（line 208: `consumed[best_idx] = True`）添加追踪记录：

```python
if exact_amt:
    consumed[best_idx] = True
    tracking_pairs.append({
        "expense": dict(expenses[best_idx]),
        "refund": dict(ref),
        "match_type": "full",
    })
```

在部分配对决策处（line 209-210: `remaining[best_idx] = ...`）添加追踪记录：

```python
else:
    # 记录原始金额快照
    original_amount = -remaining[best_idx]  # 调整前的原始金额
    remaining[best_idx] = round(remaining[best_idx] - ref_amt, 2)
    tracking_pairs.append({
        "expense": {**expenses[best_idx], "amount": original_amount},
        "refund": dict(ref),
        "match_type": "partial",
    })
```

返回值改为：`return result, tracking_pairs`

- [ ] **Step 2: 更新调用方 _read_alipay_raw**

Line 292: `return _pair_refunds(expenses, refunds, raw)` →
`records, tracking_pairs = _pair_refunds(expenses, refunds, raw); return records, tracking_pairs`

- [ ] **Step 3: 更新调用方 _read_wechat_raw**

Line 376: `return _pair_refunds(expenses, refunds, raw)` →
`records, tracking_pairs = _pair_refunds(expenses, refunds, raw); return records, tracking_pairs`

- [ ] **Step 4: 更新调用方 _read_icbc_raw**

ICBC 退款配对在 line 562-568，更新返回值：
```python
return records, bill_type, tracking_pairs  # 原来返回 (records, bill_type)
```

- [ ] **Step 5: 运行测试确认不破坏现有逻辑**

```bash
cd ~/Projects/finance-tracker && uv run pytest -v 2>&1 | tail -15
```
Expected: 只改返回值签名，调用方未更新会导致失败。等 Task 3 一起修。

---

### Task 2: _pair_ccb_refunds 增加追踪输出

**Files:**
- Modify: `src/ft/importers/ccb_debit.py` — `_pair_ccb_refunds`, `read_ccb_debit`

- [ ] **Step 1: 改 _pair_ccb_refunds 返回值**

在函数开头初始化 `tracking_pairs = []`。

在配对成功处（line 154: `paired_exp.add(best); paired_ref.add(ri)`）添加：

```python
if abs(exp_amt - ref_amt) < 0.005:
    paired_exp.add(best)
    paired_ref.add(ri)
    tracking_pairs.append({
        "expense": dict(expenses[[e[0] for e in expenses].index(best)][1]),
        "refund": dict(ref),
        "match_type": "full",
    })
```

返回值改为：`return result, tracking_pairs`

- [ ] **Step 2: 更新 read_ccb_debit**

Line 77: `records = _pair_ccb_refunds(records)` →
`records, tracking_pairs = _pair_ccb_refunds(records)`

返回值改为：`return records, tracking_pairs`

---

### Task 3: do_convert 汇总并写 _refunds.csv

**Files:**
- Modify: `src/ft/convert.py` — `do_convert` (lines 749-833)

- [ ] **Step 1: 更新各 raw reader 调用点收集 tracking_pairs**

```python
if source == "icbc":
    rows, bill_type, tracking_pairs = _read_icbc_raw(path, password)
elif source == "icbc-debit":
    rows, bill_type = _read_icbc_debit_raw(path, password)
    tracking_pairs = []
elif source == "alipay":
    rows, tracking_pairs = _read_alipay_raw(path)
    bill_type = "alipay"
elif source == "wechat":
    rows, tracking_pairs = _read_wechat_raw(path)
    bill_type = "wechat"
elif source == "ccb-debit":
    rows, tracking_pairs = read_ccb_debit(path)
    bill_type = "ccb_debit"
```

- [ ] **Step 2: 在主 CSV 写完后，写 _refunds.csv**

```python
    # 写退款追踪 CSV
    if tracking_pairs:
        refund_output = output.replace(".csv", "_refunds.csv")
        refund_rows = _build_refund_tracking_rows(tracking_pairs, rules, default_action, bill_type)
        with open(refund_output, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["date", "amount", "currency", "counterparty",
                             "description", "category", "account_name", "source",
                             "platform", "bill_source", "refund_status"])
            writer.writerows(refund_rows)
        print(f"✅ 退款追踪 {len(tracking_pairs)} 对 → {refund_output}")
```

- [ ] **Step 3: 实现 _build_refund_tracking_rows**

```python
def _build_refund_tracking_rows(tracking_pairs, rules, default_action, bill_type):
    """将 tracking_pairs 转成 11 列追踪 CSV 行（每对两行）"""
    rows = []
    for pair in tracking_pairs:
        exp = pair["expense"]
        ref = pair["refund"]
        
        # 消费行
        exp_status = "已全额退款" if pair["match_type"] == "full" else \
                     f"已部分退款(净额{round(exp['amount'] + ref['amount'], 2)})"
        exp_acct = _route_account(exp, rules, default_action, bill_type)
        exp_source = _infer_payment_source(bill_type, exp.get("counterparty", ""), exp.get("description", ""))
        rows.append([
            exp["date"], exp["amount"], exp.get("currency", "CNY"),
            exp.get("counterparty", ""), exp.get("description", ""),
            "expense", exp_acct, exp_source,
            exp.get("platform", ""), bill_type, exp_status,
        ])
        
        # 退款行
        ref_acct = _route_account(ref, rules, default_action, bill_type)
        ref_source = _infer_payment_source(bill_type, ref.get("counterparty", ""), ref.get("description", ""))
        rows.append([
            ref["date"], ref["amount"], ref.get("currency", "CNY"),
            ref.get("counterparty", ""), ref.get("description", ""),
            "income", ref_acct, ref_source,
            ref.get("platform", ""), bill_type, "退款核销",
        ])
    return rows


def _route_account(rec, rules, default_action, bill_type):
    """路由单条 rec 到账户名（复用 do_convert 中的映射逻辑）"""
    from .mapping import match_payment_method
    card_num = rec.get("card_number", "")
    if card_num:
        match = match_payment_method(rules, f"{bill_type}_{card_num}", "*")
    else:
        match = None
    if not match:
        match = match_payment_method(rules, bill_type, rec.get("payment_method", ""))
    if match:
        return match["account"]
    return "未知"
```

- [ ] **Step 4: 运行全量测试**

```bash
cd ~/Projects/finance-tracker && uv run pytest -v 2>&1 | tail -10
```

- [ ] **Step 5: E2E 测试**

```bash
# 支付宝
uv run ft convert <alipay_csv> -s alipay -o /tmp/test_alipay.csv
ls -la /tmp/test_alipay_refunds.csv

# 建行
uv run ft convert ~/Downloads/ccb_bills/hqmx_20260609191736.xls -s ccb-debit -o /tmp/test_ccb.csv
ls -la /tmp/test_ccb_refunds.csv
```

- [ ] **Step 6: 提交**

```bash
git add -A && git commit -m "feat: add refund tracking CSV (_refunds.csv)"
```

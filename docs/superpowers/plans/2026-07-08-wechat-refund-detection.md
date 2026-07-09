# 微信退款识别与配对重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把微信退款配对从“主要靠 cp 撞候选”改成“状态入口 + 原消费业务键主导 candidate”，覆盖真实微信账单中出现的全部退款场景。

**Architecture:** 保留 `src/ft/convert.py` 现有 `_pair_refunds()` 主体与 pending 分流框架，在微信分支补充原始字段、微信业务键 helper、微信专用 candidate 收集，以及红包/转账弱信号降级。测试先覆盖自助侠、美团、京东、互联互通、品牌别名与社交退款，再回放真实微信账单验证 strong/weak 变化。

**Tech Stack:** Python、pytest、openpyxl、现有 `ft.convert` 退款配对链路

---

## File map

- Modify: `src/ft/convert.py`
  - 保留微信原始字段
  - 新增微信业务键 helper
  - 新增微信 candidate 收集逻辑
  - 调整退款强弱判定
- Modify: `tests/test_convert.py`
  - 增加微信退款强/弱规则测试
- Verify: `tests/test_convert_pending.py`
  - 确认 weak 微信退款仍进入 pending
- Verify: `docs/superpowers/specs/2026-07-08-wechat-refund-detection-design.md`
  - 对照 spec 检查实现覆盖

### Task 1: 先写微信退款入口与强弱测试

**Files:**
- Modify: `tests/test_convert.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: 增加自助侠设备号部分退款测试**

```python
def test_微信自助侠设备号部分退款_识别为强匹配(self):
    path = str(TEST_DIR / "wechat_refund_zizhuxia_device.xlsx")
    _make_wechat_xlsx([
        ["2025-05-27 21:49:25", "自助侠", "充电柜-1017122_2", "支出", "2.00", "零钱", "已退款(¥0.73)", "商户消费", "4200002659202505274772023434", "010233939681236096"],
        ["2025-05-28 06:06:37", "自助侠", "自助侠", "收入", "0.73", "零钱", "已退款¥0.73", "自助侠-退款", "50301903272025052895854168505", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert len(records) == 1
    assert records[0]["amount"] == -1.27
    assert tracking_pairs[0]["rule_hint"] == "refund_wechat_device_key"
    assert tracking_pairs[0]["match_strength"] == "strong"
    assert tracking_pairs[0]["pending_required"] is False
```

- [ ] **Step 2: 增加美团平台退款按订单号锁定测试**

```python
def test_微信美团平台退款_按订单号唯一锁定原消费(self):
    path = str(TEST_DIR / "wechat_refund_meituan_order.xlsx")
    _make_wechat_xlsx([
        ["2025-12-02 09:41:13", "美团", "瑞幸咖啡-美团App-25120211100400001300774750489312", "支出", "17.80", "工商银行信用卡(9166)", "已全额退款", "商户消费", "4200002957202512028946944728", "20251202094109U95283610624414069"],
        ["2025-12-02 18:41:26", "美团", "麻小磊串串麻辣烫-美团App-25120211100400001300859984400312", "支出", "9.90", "工商银行信用卡(9166)", "已全额退款", "商户消费", "4200002875202512020751494795", "20251202184122U54819688981374182"],
        ["2025-12-09 19:59:48", "美团平台商户", "美团平台商户", "收入", "9.90", "工商银行信用卡(9166)", "已全额退款", "美团平台商户-退款", "50103805552025120937120252155", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert len(records) == 1
    assert records[0]["description"] == "瑞幸咖啡-美团App-25120211100400001300774750489312"
    assert tracking_pairs[0]["rule_hint"] == "refund_wechat_meituan_order"
    assert tracking_pairs[0]["match_strength"] == "strong"
```

- [ ] **Step 3: 增加互联互通钱包充值部分退款测试**

```python
def test_微信互联互通钱包充值部分退款_按稳定描述强匹配(self):
    path = str(TEST_DIR / "wechat_refund_wallet_token.xlsx")
    _make_wechat_xlsx([
        ["2025-09-08 20:05:19", "互联互通", "钱包充值", "支出", "2.00", "建设银行储蓄卡(2820)", "已退款(¥0.70)", "商户消费", "4200002846202509086533217395", "8004166849525251"],
        ["2025-09-09 04:01:03", "互联互通", "互联互通", "收入", "0.70", "建设银行储蓄卡(2820)", "已退款¥0.70", "互联互通-退款", "50100204552025090915297649813", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert len(records) == 1
    assert records[0]["amount"] == -1.3
    assert tracking_pairs[0]["rule_hint"] == "refund_wechat_desc_token"
    assert tracking_pairs[0]["match_strength"] == "strong"
```

- [ ] **Step 4: 增加品牌别名退款测试**

```python
def test_微信品牌别名退款_仍识别为强匹配(self):
    path = str(TEST_DIR / "wechat_refund_brand_alias.xlsx")
    _make_wechat_xlsx([
        ["2024-12-26 14:55:45", "UNIQLO", "优衣库商品", "支出", "79.00", "工商银行信用卡(1200)", "已全额退款", "商户消费", "4200002363202412263911689588", "ZFDD02024122629136430461"],
        ["2024-12-26 14:58:08", "优衣库", "优衣库", "收入", "79.00", "工商银行信用卡(1200)", "已全额退款", "优衣库-退款", "50302801902024122666359503558", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert records == []
    assert tracking_pairs[0]["rule_hint"] == "refund_wechat_brand_alias"
    assert tracking_pairs[0]["match_strength"] == "strong"
```

- [ ] **Step 5: 增加红包退款继续 weak 测试**

```python
def test_微信红包退款_继续走弱信号待审(self):
    path = str(TEST_DIR / "wechat_refund_red_packet_weak.xlsx")
    _make_wechat_xlsx([
        ["2025-05-15 17:09:34", "发给是我小转转啊", "/", "支出", "50.00", "零钱", "已全额退款", "微信红包（单发）", "100003980125051500055211649566164214", "1000039801202505157184950651034"],
        ["2025-05-16 17:09:37", "/", "/", "收入", "50.00", "零钱", "已全额退款", "微信红包-退款", "1000039801202505157184950651034", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert records == []
    assert tracking_pairs[0]["match_strength"] == "weak"
    assert tracking_pairs[0]["pending_required"] is True
```

- [ ] **Step 6: 增加转账退款继续 weak 测试**

```python
def test_微信转账退款_继续走弱信号待审(self):
    path = str(TEST_DIR / "wechat_refund_transfer_weak.xlsx")
    _make_wechat_xlsx([
        ["2026-03-07 04:11:13", "是我小转转啊", "转账备注:微信转账", "支出", "60.00", "建设银行储蓄卡(2820)", "已全额退款", "转账", "53010002371104202603070433707100", "1000050001202603070820865004483"],
        ["2026-03-08 04:11:14", "/", "转账备注:微信转账", "收入", "60.00", "建设银行储蓄卡(2820)", "已全额退款", "转账-退款", "132100005020107202603080012202175009214", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert records == []
    assert tracking_pairs[0]["match_strength"] == "weak"
    assert tracking_pairs[0]["pending_required"] is True
```

- [ ] **Step 7: 运行新增微信测试，确认先失败或暴露缺口**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py -k "微信自助侠设备号部分退款 or 微信美团平台退款 or 微信互联互通钱包充值部分退款 or 微信品牌别名退款 or 微信红包退款 or 微信转账退款" -v`
Expected: 至少新增测试失败，说明实现尚未更新。

### Task 2: 重建微信 candidate 识别入口

**Files:**
- Modify: `src/ft/convert.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: 扩展微信测试账单 helper 支持交易单号与商户单号**

```python
def _make_wechat_xlsx(rows: list[list[str]], path: str):
    import openpyxl
    wb = openpyxl.Workbook()
    ws = wb.active
    header = ["交易时间", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态"]
    optional_headers = ["交易类型", "交易单号", "商户单号"]
    max_cols = max((len(r) for r in rows), default=len(header))
    if max_cols > len(header):
        header.extend(optional_headers[: max_cols - len(header)])
    ws.append(header)
    for r in rows:
        ws.append(r)
    wb.save(path)
```

- [ ] **Step 2: 在 `_read_wechat_raw()` 中保留微信原始字段**

```python
txn_type = vals[h["交易类型"]] if "交易类型" in h else ""
txn_id = vals[h["交易单号"]] if "交易单号" in h else ""
merchant_order_id = vals[h["商户单号"]] if "商户单号" in h else ""
```

并在写入 `raw.append({...})` 时增加：

```python
"txn_type": txn_type,
"txn_id": txn_id,
"merchant_order_id": merchant_order_id,
```

- [ ] **Step 3: 新增微信业务键 helper**

```python
def _wechat_device_key(description: str) -> str:
    import re
    description = (description or "").strip()
    match = re.search(r"((?:充电柜|充电插座)-[A-Za-z0-9_\-]+)", description)
    return match.group(1) if match else ""


def _wechat_meituan_order_key(description: str) -> str:
    import re
    description = (description or "").strip()
    patterns = [
        r"美团订单-(\d{20,})",
        r"-美团App-(\d{20,})",
        r"-美团微信小程序-(\d{20,})",
    ]
    for pattern in patterns:
        match = re.search(pattern, description)
        if match:
            return match.group(1)
    return ""


def _wechat_meituan_cashier_key(description: str) -> str:
    import re
    description = (description or "").strip()
    match = re.search(r"(美团收银\d+)", description)
    return match.group(1) if match else ""


def _wechat_stable_refund_token(description: str) -> str:
    description = (description or "").strip()
    tokens = {
        "钱包充值",
        "寄件",
        "预付费充电订单",
        "自助机押金",
        "订单付款",
        "存包柜预付费",
        "先乘车后付款",
        "转账备注:微信转账",
    }
    return description if description in tokens else ""
```

- [ ] **Step 4: 新增微信品牌归一化 helper**

```python
def _wechat_refund_brand_aliases(value: str) -> set[str]:
    value = (value or "").strip()
    aliases = {value} if value else set()
    pairs = [
        ("麦当劳", "北京麦当劳食品有限公司"),
        ("UNIQLO", "优衣库"),
        ("luckin coffee", "瑞幸咖啡"),
        ("广州骑安", "滴滴"),
        ("立普世", "立普世咖啡"),
    ]
    for a, b in pairs:
        if value in {a, b}:
            aliases.update({a, b})
    return aliases
```

- [ ] **Step 5: 新增微信社交退款 helper**

```python
def _wechat_is_social_refund(*, expense: dict, refund: dict) -> bool:
    text = " ".join([
        expense.get("txn_type", ""),
        expense.get("description", ""),
        refund.get("txn_type", ""),
        refund.get("description", ""),
    ])
    return any(token in text for token in ("红包", "转账"))
```

- [ ] **Step 6: 新增微信专用 candidate 收集 helper**

```python
def _collect_wechat_refund_candidates(expenses: list, ref: dict, consumed: list[bool], remaining: list[float], ref_amt: float):
    matches: dict[int, str] = {}
    ref_desc = ref.get("description", "")
    ref_cp = ref.get("counterparty", "")

    def try_add(expense_index: int, rule_hint: str):
        if consumed[expense_index]:
            return
        exp = expenses[expense_index]
        if not _refund_matches_basic_constraints(exp, ref, ref_amt, remaining[expense_index]):
            return
        matches.setdefault(expense_index, rule_hint)

    ref_aliases = _wechat_refund_brand_aliases(ref_cp) | _wechat_refund_brand_aliases(ref_desc)
    for i, exp in enumerate(expenses):
        exp_device = _wechat_device_key(exp.get("description", ""))
        exp_meituan_order = _wechat_meituan_order_key(exp.get("description", ""))
        exp_meituan_cashier = _wechat_meituan_cashier_key(exp.get("description", ""))
        exp_token = _wechat_stable_refund_token(exp.get("description", ""))
        exp_aliases = _wechat_refund_brand_aliases(exp.get("counterparty", "")) | _wechat_refund_brand_aliases(exp.get("description", ""))

        if ref.get("txn_type") == "自助侠-退款" and exp_device:
            try_add(i, "refund_wechat_device_key")
            continue
        if "美团" in ref.get("txn_type", "") and (exp_meituan_order or exp_meituan_cashier):
            try_add(i, "refund_wechat_meituan_order" if exp_meituan_order else "refund_wechat_meituan_cashier")
            continue
        if ref.get("txn_type") == "互联互通-退款" and exp_token == "钱包充值":
            try_add(i, "refund_wechat_desc_token")
            continue
        if ref_aliases and exp_aliases and ref_aliases & exp_aliases:
            try_add(i, "refund_wechat_brand_alias")

    candidates = []
    for i, rule_hint in matches.items():
        exp = expenses[i]
        exact_amt = abs(remaining[i] - ref_amt) < 0.01
        candidates.append({
            "expense_index": i,
            "exact_amt": exact_amt,
            "desc_match": False,
            "expense_date": exp["date"],
            "rule_hint": rule_hint,
        })
    return candidates
```

- [ ] **Step 7: 在 `_pair_refunds()` 中优先运行微信 candidate 收集**

```python
candidates = _collect_order_based_refund_candidates(expenses, ref, consumed, remaining, ref_amt)
if not candidates and _refund_source_signal(ref) == "wechat_status":
    candidates = _collect_wechat_refund_candidates(expenses, ref, consumed, remaining, ref_amt)
```

- [ ] **Step 8: 在 `_classify_refund_match()` 中把微信社交退款降级为 weak**

```python
if _refund_source_signal(ref) == "wechat_status" and _wechat_is_social_refund(expense=expense, refund=ref):
    return "weak", True
```

- [ ] **Step 9: 运行 Task 1 测试确认通过**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py -k "微信自助侠设备号部分退款 or 微信美团平台退款 or 微信互联互通钱包充值部分退款 or 微信品牌别名退款 or 微信红包退款 or 微信转账退款" -v`
Expected: PASS

### Task 3: 验证京东拆分退款与真实样本回放行为

**Files:**
- Modify: `tests/test_convert.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: 增加京东拆分退款测试**

```python
def test_微信京东拆分退款_连续冲减同一原消费(self):
    path = str(TEST_DIR / "wechat_refund_jd_split.xlsx")
    _make_wechat_xlsx([
        ["2024-11-11 01:15:51", "京东", "京东-订单编号299561054326", "支出", "557.92", "零钱", "已退款(¥470.72)", "商户消费", "42000000000000000001", "4061882411110115470131313588"],
        ["2024-11-11 01:16:12", "京东商城平台商户", "京东商城平台商户", "收入", "341.30", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111145407841691", ""],
        ["2024-11-11 01:16:17", "京东商城平台商户", "京东商城平台商户", "收入", "32.56", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111115414528395", ""],
        ["2024-11-11 01:16:25", "京东商城平台商户", "京东商城平台商户", "收入", "96.86", "零钱", "已退款¥470.72", "京东商城平台商户-退款", "50300801362024111115462617037", ""],
    ], path)
    from ft.convert import _read_wechat_raw
    records, tracking_pairs = _read_wechat_raw(path)
    assert len(records) == 1
    assert records[0]["amount"] == -87.2
    assert len(tracking_pairs) == 3
    assert all(p["match_strength"] == "strong" for p in tracking_pairs)
```

- [ ] **Step 2: 运行京东拆分测试确认通过**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py -k "微信京东拆分退款" -v`
Expected: PASS

- [ ] **Step 3: 用真实微信账单回放 convert 结果**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 - <<'PY'
from pathlib import Path
from collections import Counter
from ft.convert import _read_wechat_raw
files = sorted(Path('/Users/huangwenlong/.ft/bills').glob('*微信*.xlsx'))
all_pairs = []
for path in files:
    _, tracking_pairs = _read_wechat_raw(str(path))
    all_pairs.extend(tracking_pairs)
print('tracking_pairs', len(all_pairs))
print('strength', Counter(p.get('match_strength') for p in all_pairs))
print('rule_hint', Counter(p.get('rule_hint') for p in all_pairs).most_common())
PY`
Expected: weak 数明显下降，红包/转账仍保留为 weak。

### Task 4: 验证 pending 与回归测试

**Files:**
- Verify: `tests/test_convert_pending.py`
- Verify: `tests/test_ai_apply.py`
- Verify: `docs/superpowers/specs/2026-07-08-wechat-refund-detection-design.md`

- [ ] **Step 1: 运行微信 convert / pending 回归测试**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py tests/test_convert_pending.py -v`
Expected: PASS

- [ ] **Step 2: 运行最终回归命令**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py tests/test_convert_pending.py tests/test_ai_apply.py -v`
Expected: PASS

- [ ] **Step 3: 手动对照 spec 检查实现覆盖**

确认：
- 退款入口仍由微信状态字段驱动
- 自助侠设备号场景已覆盖
- 美团平台订单号 / 收银号场景已覆盖
- 京东拆分退款能连续冲减
- 互联互通 `钱包充值` 场景已覆盖
- 红包退款 / 转账退款仍为 weak
- `_pair_refunds()` 主体和 pending 框架未被重写

- [ ] **Step 4: 整理结果并准备交付**

输出内容：
- 修改文件列表
- 微信退款规则变化
- 真实样本回放结果
- 测试命令与结果
- 保留为 weak 的场景说明
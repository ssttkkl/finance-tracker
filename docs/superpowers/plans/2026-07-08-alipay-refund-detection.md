# 支付宝退款识别重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把支付宝退款入口改成“结构化强信号优先、description 弱信号进 AI”，覆盖三份真实支付宝账单中出现的退款形态。

**Architecture:** 在 `src/ft/convert.py` 中增加支付宝退款信号分类 helper，重建 `_read_alipay_raw()` 的退款候选识别入口。保留现有 `_pair_refunds()` 与 pending 分流框架，只通过 `_refund_signal` 的强弱来决定自动直过还是进入 AI 审查。

**Tech Stack:** Python、pytest、现有 `ft.convert` 退款配对链路

---

## File map

- Modify: `src/ft/convert.py`
  - 新增支付宝退款信号 helper
  - 调整 `_read_alipay_raw()` 的 `_refund_signal` 写入和 `refunds` 收集条件
  - 调整 `_refund_signal_is_strong()` 识别新的强信号
- Modify: `tests/test_convert.py`
  - 补充支付宝强信号 / 弱信号测试
- Verify: `tests/test_convert_pending.py`
  - 确认 pending 行为仍成立

### Task 1: 先写支付宝退款入口测试

**Files:**
- Modify: `tests/test_convert.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: 增加“状态退款成功 + 非退款分类”测试**

```python
def test_退款成功_非退款分类_仍识别为退款(self):
    csv_path = str(TEST_DIR / "alipay_refund_status_strong.csv")
    _make_alipay_csv([
        ["2026-01-21 21:13:04", "交通出行", "高德打车", "高德打车订单", "支出", "22.00", "工行信用卡(1200)", "交易成功"],
        ["2026-01-21 21:13:48", "交通出行", "高德打车", "退款-高德打车订单", "不计收支", "5.29", "工行信用卡(1200)", "退款成功"],
    ], csv_path)
    from ft.convert import _read_alipay_raw
    records, tracking_pairs = _read_alipay_raw(csv_path)
    assert len(records) == 1
    assert records[0]["amount"] == -16.71
    assert tracking_pairs[0]["match_strength"] == "strong"
```

- [ ] **Step 2: 增加“退款分类 + 不计收支 + 交易成功”测试**

```python
def test_退款分类且不计收支_交易成功_仍识别为退款(self):
    csv_path = str(TEST_DIR / "alipay_refund_category_nocount.csv")
    _make_alipay_csv([
        ["2026-01-01 12:00:00", "投资理财", "蚂蚁财富", "基金买入", "不计收支", "100.00", "建行储蓄卡(2820)", "交易成功"],
        ["2026-01-02 09:00:00", "退款", "蚂蚁财富", "买入退款", "不计收支", "100.00", "建行储蓄卡(2820)", "交易成功"],
    ], csv_path)
    from ft.convert import _read_alipay_raw
    records, tracking_pairs = _read_alipay_raw(csv_path)
    assert records == []
    assert tracking_pairs[0]["source_refund_signal"] == "alipay_category_nocount"
    assert tracking_pairs[0]["match_strength"] == "strong"
```

- [ ] **Step 3: 保留并校验 description-only 弱信号测试**

```python
def test_退款_交易分类非退款_按说明兜底(self):
    ...
    assert tracking_pairs[0]["source_refund_signal"] == "alipay_desc"
    assert tracking_pairs[0]["match_strength"] == "weak"
    assert tracking_pairs[0]["pending_required"] is True
```

- [ ] **Step 4: 运行针对性测试，确认先失败或暴露缺口**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py -k "alipay_refund_status_strong or alipay_refund_category_nocount or 退款_交易分类非退款_按说明兜底" -v`
Expected: 至少新增测试失败，说明实现尚未更新。

### Task 2: 重建支付宝退款信号入口

**Files:**
- Modify: `src/ft/convert.py`
- Test: `tests/test_convert.py`

- [ ] **Step 1: 新增支付宝退款信号 helper**

```python
def _alipay_refund_signal(*, txn_type: str, txn_status: str, direction: str, description: str) -> str:
    if txn_status == "退款成功":
        return "alipay_status"
    if txn_type == "退款" and direction == "不计收支":
        return "alipay_category_nocount"
    if "退款" in description:
        return "alipay_desc"
    return ""
```

- [ ] **Step 2: 在 `_read_alipay_raw()` 中改用 helper 写入 `_refund_signal`**

```python
refund_signal = _alipay_refund_signal(
    txn_type=txn_type,
    txn_status=txn_status,
    direction=direction,
    description=enriched_desc,
)
```

- [ ] **Step 3: 调整 `refunds` 收集条件**

```python
refunds = [r for r in raw if r["amount"] > 0 and r.get("_refund_signal")]
```

- [ ] **Step 4: 把 `alipay_category_nocount` 加入强信号集合**

```python
return _refund_source_signal(ref) in {
    "alipay_status",
    "alipay_category_nocount",
    "wechat_status",
    "icbc_credit_return",
    "ccb_debit_desc",
}
```

- [ ] **Step 5: 运行 Task 1 测试确认通过**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py -k "alipay_refund_status_strong or alipay_refund_category_nocount or 退款_交易分类非退款_按说明兜底" -v`
Expected: PASS

### Task 3: 验证 pending 与正式输出行为未回退

**Files:**
- Modify: `tests/test_convert.py`
- Verify: `tests/test_convert_pending.py`

- [ ] **Step 1: 运行支付宝 convert / pending 回归测试**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py tests/test_convert_pending.py -v`
Expected: PASS（若环境无额外依赖问题）

- [ ] **Step 2: 检查强信号不进 pending、弱信号仍进 pending**

```python
assert tracking_pairs[0]["match_strength"] == "strong"
assert tracking_pairs[0]["pending_required"] is False

assert tracking_pairs[0]["match_strength"] == "weak"
assert tracking_pairs[0]["pending_required"] is True
```

- [ ] **Step 3: 手动回顾 `src/ft/convert.py` 中支付宝分支**

确认：
- 关闭类仍先跳过
- description 只产生 `alipay_desc`
- `_pair_refunds()` 主体未被重写
- `do_convert()` pending/正式输出逻辑未受影响

### Task 4: 完成文档与最终验证

**Files:**
- Verify: `docs/superpowers/specs/2026-07-08-alipay-refund-detection-design.md`
- Verify: `docs/superpowers/plans/2026-07-08-alipay-refund-detection.md`

- [ ] **Step 1: 对照 spec 检查实现覆盖**

检查项：
- 强信号：`退款成功`
- 强信号：`分类=退款 且 不计收支`
- 弱信号：description 退款语义
- 排除项：关闭类 / 支出

- [ ] **Step 2: 运行最终命令**

Run: `cd "/Users/huangwenlong/finance-tracker" && PYTHONPATH=src python3 -m pytest tests/test_convert.py tests/test_convert_pending.py tests/test_ai_apply.py -v`
Expected: PASS

- [ ] **Step 3: 整理结果并准备交付**

输出内容：
- 修改文件列表
- 核心规则变化
- 运行过的测试与结果
- 未覆盖项（如 0 元退款审计）

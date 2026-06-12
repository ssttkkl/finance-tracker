# ICBC 退款匹配修复模式

> 日期: 2026-06-13
> 相关: `_normalize_counterparty()` 与 ICBC 退款配对冲突

## 问题

`_normalize_counterparty()` 在 record 创建时即运行，改变了 expense 和 refund 的 `counterparty` 字段。这破坏了 ICBC 退款配对的两个环节：

### 1. 退款检测（sentinel）
ICBC 解析器通过 `counterparty == "退货"` 检测退款记录。但 `_normalize_counterparty("退货", description, "icbc")` 可能将 "退货" 归一化为品牌名（如 "退货" + "拼多多支付-商户名" → `_infer_platform` 命中 "拼多多" → cp="拼多多"），导致退款检测失败。

### 2. 退款匹配
`_pair_refunds` 通过 `_counterparty_matches(exp_cp, ref_cp)` 匹配。但 expense 和 refund 的 counterparty 经过不同的归一化路径后不一致：

```
expense: "财付通-新渔阳滑雪场" → strip payment prefix → "新渔阳滑雪场" → brand match → "美团"
refund:  r["description"] = "财付通-新渔阳滑雪场" → normalize → "拼多多支付-橙予进口专营店" → brand match → "拼多多"
```

「美团」vs「拼多多」— 无法匹配。

## 修复（三部分）

### Part A: 存储原始 cp + refund flag

在 `_parse_icbc_lines` 的 credit 分支中，调用 `_normalize_counterparty` **之前**检查 `counterparty` 原始值：

```python
normalized_cp, enriched_desc = _normalize_counterparty(counterparty, description[:80], "icbc")
rec = {
    ...
    "counterparty": normalized_cp,
    "description": enriched_desc[:80],
    ...
    "_raw_cp": counterparty,  # ← 保存归一化前的原始值
}
if counterparty == "退货":
    rec["_is_refund"] = True  # ← 在归一化前做标记
records.append(rec)
```

**关键**：`_is_refund` 必须在 `_normalize_counterparty` 之前检查。归一化后 counterparty 已被改写，无法判断原值。

### Part B: 退款匹配改用 flag

将退款检测从字符串比较改为 flag 检查：

```python
# 改前
refunds = [r for r in records if r["amount"] > 0 and r.get("counterparty", "") == "退货"]

# 改后
refunds = [r for r in records if r["amount"] > 0 and r.get("_is_refund")]
```

### Part C: `_pair_refunds` 中 fallback 到 `_raw_cp`

修改 `convert.py` 中的 `_pair_refunds` 函数，在 normalized cp 匹配失败后尝试用 `_raw_cp` 二次匹配：

```python
# 改前
if not _counterparty_matches(exp["counterparty"], ref["counterparty"]):
    continue

# 改后
if not _counterparty_matches(exp["counterparty"], ref["counterparty"]):
    raw_cp = exp.get("_raw_cp", "")
    if not raw_cp or not _counterparty_matches(raw_cp, ref["counterparty"]):
        continue
```

**为什么是 expense 的 `_raw_cp`？** 因为在 `_parse_icbc_lines` 的 refund 处理阶段（line 718），refund 的 counterparty 已被设为原始 description（即未经归一化的商户名），所以要用 expense 的 raw cp 来匹配。

### Part D（可选）：配对后归一化 tracking pair

在 `_pair_refunds` 返回后，对 tracking pair 中的 counterparty 做归一化：

```python
records, tracking_pairs = _pair_refunds(expenses, refunds, records)
for pair in tracking_pairs:
    for key in ("expense", "refund"):
        raw = pair[key].get("counterparty", "")
        desc = pair[key].get("description", "")
        new_cp, new_desc = _normalize_counterparty(raw, desc, "icbc")
        pair[key]["counterparty"] = new_cp
        pair[key]["description"] = new_desc
```

## 文件分布

| 代码位置 | 改动 |
|---------|------|
| `convert.py:_parse_icbc_lines` (credit 分支) | 加 `_raw_cp` + `_is_refund` flag |
| `convert.py:_parse_icbc_lines` (refund 处理) | 退款检测用 `_is_refund`，配对后 normalize tracking pair |
| `convert.py:_pair_refunds` | expense 匹配加 `_raw_cp` fallback |

## 测试

涉及测试文件：`tests/test_convert.py`

| 测试名 | 验证点 |
|--------|--------|
| `test_全额退款_双向核销` | 退款 ¥600 + 消费 ¥600 全部消失，tracking_pairs 正确 |
| `test_icbc_卡号_通过核销保留` | 卡号通过 tracking pairs 保留 |
| `test_退款行platform跟随counterparty更新` | 退款 tracking pair 中 counterparty 归一化正确（如「拼多多」） |
| `test_platform_一致性` | tracking rows 中支出和退款 counterparty 一致 |

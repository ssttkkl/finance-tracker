# Merge Dedup Design

**Date**: 2026-06-07
**Status**: Draft

## Overview

`ft merge` 拼接多个 CSV 后跨源去重，输出两份文件：去重后的干净 CSV 和被删除的重复记录 CSV。

## Architecture

新增 `src/ft/dedup.py`，包含核心去重函数 `dedup()`。`merge.py` 的 `do_merge` 拼接 CSV 后调用 `dedup()`，不再做简单精确匹配去重。`load.py` 的 `do_load` 不再去重，直接入库。

CSV 新增第10列 `bill_source`，由 convert 阶段写入，merge 阶段读取：

| 转换器 | bill_source |
|---|---|
| 支付宝 | `alipay` |
| 微信 | `wechat` |
| 工行信用卡 | `icbc_credit` |
| 工行借记卡 | `icbc_debit` |
| 其他银行 | `bank` |

```python
do_merge(csv_files, output_dir):
    拼接所有 CSV
    kept, removed = dedup(records)  # 内部按 bill_source 分类
    写 output_dir/merged.csv
    写 output_dir/removed.csv
    打印统计到 stderr
```

## Source Classification

### Source Groups

`dedup()` 按 `bill_source` 字段将记录归入三个组：

| 组 | bill_source 值 | 优先级 |
|---|---|---|
| 支付宝 | `alipay` | 1 (最高) |
| 微信 | `wechat` | 2 |
| 银行 | `icbc_credit`, `icbc_debit`, `bank` 等 | 3 (最低)|

支付宝/微信之间不匹配（不同支付渠道），银行之间也不匹配（不同卡）。

## Algorithm: Two-Pass

### Step 1: Grouping

按 `(date 截断到分钟, amount, currency)` 分组。截断规则：取 `YYYY-MM-DD HH:MM`，忽略秒。

### Step 2: Cross-Source Matching

对每个组 G_t，候选人池 = G_t 的高优先级记录 + G_{t-1} 的高优先级记录（处理跨分钟边界）。

对每条低优先级记录 r：
- 遍历候选人池中每条记录 c
- 满足 `|r.time - c.time| ≤ 5s` 且 `cross_verify(r, c)` → 候选匹配
- 多个候选匹配时，选时间差最小的
- 标记 r 为"去除"

`cross_verify(a, b)` — 至少一条通过：

1. **platform 匹配**：双方非空且 `a.platform == b.platform`
2. **counterparty 双向子串**：双方非空且 `a in b` 或 `b in a`
3. **description 双向子串**：双方非空且 `a in b` 或 `b in a`

全不通过 → 不匹配。所有交叉验证字段为空 → 不匹配。

### Step 3: Output

去重后的记录按 `date` 升序写入 `merged.csv`（10 列：原 9 列 + `bill_source`）。

## Removed CSV Format

11 列（原 9 列 + `bill_source` + `dedup_status`），每对重复两条相邻：

```
date,amount,currency,counterparty,description,category,account_name,source,platform,bill_source,dedup_status
2026-01-01 13:00:03,-30,CNY,麦当劳,,expense,支付宝余额,支付宝,麦当劳,alipay,保留
2026-01-01 13:00:04,-30,CNY,麦当劳,,expense,工行信用卡(1200),支付宝,麦当劳,icbc_credit,去除
```

`dedup_status` 取值：`保留` / `去除`。组内按日期升序，组间按日期升序。

## Files

| File | Change |
|---|---|---|
| `src/ft/dedup.py` | **New**. Core dedup logic |
| `src/ft/merge.py` | Modify `do_merge` to call `dedup()` |
| `src/ft/convert.py` | Add `bill_source` column to CSV output |
| `tests/test_dedup.py` | **New**. 10 test cases |

## Test Cases

| # | Scenario | Expected |
|---|---|---|
| 1 | 不同时间/金额 | Both kept |
| 2 | Same amount, time diff > 5s | Both kept |
| 3 | Same amount, ≤5s, platform match | Bank removed |
| 4 | Same amount, ≤5s, counterparty substring | Bank removed |
| 5 | Same amount, ≤5s, all cross-verify fail | Both kept |
| 6 | Cross-minute boundary (12:59:58 vs 13:00:02) | Bank removed |
| 7 | Multiple matches, pick closest time | Closest matched, others kept |
| 8 | Same source (bank vs bank) | Both kept |
| 9 | All cross-verify fields empty | Both kept |
| 10 | WeChat vs bank | WeChat kept, bank removed |

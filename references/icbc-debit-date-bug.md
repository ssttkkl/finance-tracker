# ICBC 借记卡日期 Bug 调试追踪

**发现日期**: 2026-06-13
**影响范围**: `_parse_icbc_lines` 借记卡分支（`is_credit=False`），位于 `src/ft/convert.py`
**受影响历史数据**: 1,204 条 icbc_debit 记录（日期全是 00:00:00，真实时间在 description 中）

## 现象

工行借记卡（`icbc_debit`）的所有记录日期被归一化为当天 `00:00:00`：

```
2023-06-13 00:00:00,-17.0,CNY,深圳市财付通支付,17:25:13,expense,工行借记卡,银行卡,icbc_debit
```

对比信用卡（`icbc_credit`）记录日期正常：

```
2024-11-19 18:32:24,-41.76,CNY,XSOLLA *MAHJONGSOUL,,expense,工行信用卡(1200),银行卡,icbc_credit
```

## 根因

### Bug 1：日期硬编码 `00:00:00`（第 762 行）

```python
# 借记卡分支 — 原代码
"date": f"{date} 00:00:00",
```

对比信用卡分支（第 596-697 行）正确提取了时间：

```python
# 信用卡分支 — 正确
current_time = lines[i+1].strip()
...
"date": f"{current_date} {current_time}",
```

### Bug 2：时间行被 description 误捕获（第 738 行）

ICBC 借记卡 PDF 文本格式（mutool 输出）：

```
2023-06-13         ← 日期行（查找 11 行内的日期行，得到 date="2023-06-13"）
17:25:13           ← 时间行（8 字符，≤10，不在排除列表 → 被 description 捕获！）
161402******4636
活期
...
```

description 提取逻辑（第 736-742 行）在 `date_line_idx+1` 到金额行之间查找 ≤10 字符的短字符串作为摘要。时间字符串 `17:25:13` 完全匹配此条件。

## 修复（2026-06-13）

**文件**: `src/ft/convert.py`

### 修改 1：提取时间（第 731-737 行，在日期查找后新增）

```python
# 提取时间（日期下行）
time_str = "00:00:00"
if date_line_idx + 1 < len(lines):
    time_candidate = lines[date_line_idx + 1].strip()
    if re.match(r"^\d{2}:\d{2}:\d{2}$", time_candidate):
        time_str = time_candidate
```

### 修改 2：description 排除时间模式（第 744 行）

```python
# 原条件
if s and len(s) <= 10 and s not in (...):

# 修改后
if s and len(s) <= 10 and s not in (...) \
        and not re.match(r"^\d{2}:\d{2}:\d{2}$", s):
```

### 修改 3：日期拼接使用提取的时间（第 770 行）

```python
# 原
"date": f"{date} 00:00:00",

# 修改后
"date": f"{date} {time_str}",
```

## 验证

```bash
cd ~/.hermes/skills/finance/finance-tracker
.venv/bin/python -m pytest tests/ -x -q  # 286 passed
```

单笔验证：

```python
lines = ['2023-06-13', '17:25:13', '161402******4636', ...]
records, _ = _parse_icbc_lines(lines, is_credit=False)
assert records[0]['date'] == '2023-06-13 17:25:13'
assert records[0]['description'] != '17:25:13'
```

## 历史数据修复

需从原始 PDF 重新转换工行借记卡账单才能恢复时间。`ft verify --fix` 重新计算快照，但时间数据在 CSV 中已丢失，无法从快照恢复。

## 误判澄清

建行借记卡（ccb_debit）也有 886 条 00:00:00 记录，但那是 **数据源限制**：CCB XLS 格式 `row[4]` 只存 `YYYYMMDD`（无时分秒），代码中 `date = f"{date_raw[:4]}-{date_raw[4:6]}-{date_raw[6:8]} 00:00:00"` 是正确行为，不是 bug。

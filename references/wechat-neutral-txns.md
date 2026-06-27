# WeChat 中性交易（零钱提现/充值/零钱通存取/理财通）

## 问题背景

微信导出账单（XLSX）中，`收/支`（direction）列对零钱提现、充值、零钱通存取、理财通等交易标记为 `/`（中性交易），而非"支出"或"收入"。

原始 `_read_wechat_raw` 的跳过逻辑（`convert.py` 第 547 行）：
```python
elif direction != "收入":
    continue
```
导致所有中性交易被静默跳过。

## 修复方案

在 `convert.py` 的 `_read_wechat_raw` 函数中：

### ① 变量提取提前
将原先放在 if/elif 链**之后**的变量提取移到链**之前**：

```python
# 原位置（第 572-577 行，在 if/elif 之后）：
payment_method = vals[h["支付方式"]] if "支付方式" in h else ""
counterparty = vals[h["交易对方"]] if "交易对方" in h else ""
desc = vals[h["商品"]] if "商品" in h else ""
txn_type = vals[h["交易类型"]] if "交易类型" in h else ""
date_raw = vals[h["交易时间"]] if "交易时间" in h else ""
date_str = date_raw[:19].replace("/", "-")
```

移到 `amount = float(...)` 解析之后、`if direction:` 之前。

**为什么要提前：** 原代码把这些变量定义在 if/elif 链之后 75 行的地方，新增的中性交易分支（需要引用 `txn_type`）无法访问这些变量。变量定义应该始终放在它被第一次引用的位置**之前**，而不是放在某个隐含"肯定不会用到的"位置。

### ② 增加中性交易分支

```python
if direction == "支出":
    amount = -amount
elif direction == "收入":
    pass
elif txn_type in ("零钱提现", "充值", "零钱通存取", "理财通"):
    # 中性交易，按金额正负判断方向
    if amount > 0:
        category = "income"
    else:
        category = "expense"
        amount = -amount
    if not desc or desc in ("/", "-"):
        desc = txn_type
    normalized_cp, enriched_desc = _normalize_counterparty(counterparty, desc[:80], "wechat")
    raw.append({
        "date": date_str,
        "amount": round(amount, 2),
        "payment_method": payment_method,
        "counterparty": normalized_cp,
        "description": enriched_desc[:80],
        "category": category,
        "status": status,
    })
    continue
else:
    continue
```

### ③ 删除重复赋值

原 if/elif 链之后的赋值行（`payment_method = ...`、`counterparty = ...` 等）需要删除，因为已经提前提取。

## 后果

**修复前**：零钱提现 ≈224.53、充值等完全丢失，导致微信零钱余额与记录不符。
**修复后**：中性交易被正确记录为 income/expense，零钱提现显示如：
```
2023-06-27 13:33:09,224.53,CNY,建设银行(2820),零钱提现,income,建行储蓄卡(2820),微信,wechat
```

## 验证方法

```bash
cd ~/Downloads
python3 -c "
from ft.convert import _read_wechat_raw
rows, pairs = _read_wechat_raw('微信支付账单流水文件(...).xlsx')
found = [r for r in rows if '提现' in str(r) or '充值' in str(r)]
print(f'Found {len(found)} neutral txns')
for f in found:
    print(f['date'], f['amount'], f['counterparty'], f['description'], f['category'])
"
```

修复后总数比修复前多出中性交易的数量。

## 关键教训

**变量定义顺序决定代码可维护性。** `txn_type`、`payment_method`、`counterparty`、`desc`、`date_raw`、`date_str` 应该始终在第一次使用它们的位置**之前**定义。把它们放在分支链之后 75 行是一种"它永远走不到那里才会用到"的隐含假设，这种假设约束了后续修改代码的能力。所有从行数据中提取的字段应该在解析完 `amount` 后立即全部提取，然后再判断如何处理。

# 转换器静默丢弃与修复方案

## 背景

finance-tracker 的转换器（`convert.py`）存在多处静默跳过数据的逻辑，用 `continue` 或 `return None` 静默丢弃非预期数据。用户要求「禁止任何静默丢弃行为，非预期数据必须马上中断」。

## 修复清单

### `_read_alipay_raw`（支付宝）

| 行号 | 原行为 | 修复 |
|------|--------|------|
| `if len(row) < 7: continue` | 列数不足时静默跳过 | 改为 `raise ValueError`，包含 row 内容和格式变更提示 |
| `except ValueError: continue` | 金额无法解析时静默跳过 | 改为 `raise ValueError`，包含 amount_str、date、direction |
| `if amount == 0: continue` | 0 元交易（会员卡抵扣、积分兑换等）静默跳过 | 保留为 0 金额记录，category 按 `收/支` 方向判断 |
| `else: continue`（未知方向） | 未知收/支方向静默跳过 | 改为 `raise ValueError`，包含 direction、date、type |

**注意**：0 元交易的 category 需要在多个地方跳过覆盖：
1. 在 `amount == 0` 分支中设置 category
2. 后续的 `category = "expense" if amount < 0 else "income"` 需要用 `if amount != 0:` 包裹跳过
3. 否则 0 元支出会被覆盖为 income（因为 `0.0 < 0` 为 False）

### `_read_wechat_raw`（微信）

| 行号 | 原行为 | 修复 |
|------|--------|------|
| `elif direction != "收入": continue` | 中性交易（方向= `/`）静默跳过 | 改为 `elif direction == "收入": pass` + `elif txn_type in ("零钱提现", "充值", "零钱通存取", "理财通"):` 处理中性交易 |

**关键注意点**：txn_type 变量在修复前位于 if/elif 链**之后**（原第 575 行），但中性交易分支需要引用它。修复时必须将 `payment_method`、`counterparty`、`desc`、`txn_type`、`date_raw`、`date_str` 的提取移到 if/elif 链**之前**。

### `_parse_icbc_debit_row`（工行借记卡 PDF）

| 行号 | 原行为 | 修复 |
|------|--------|------|
| `if not dm: return None` | 无日期时静默丢弃 | 改为 `raise ValueError` |
| `if len(row) < 13: return None` | 列数不足时静默丢弃 | 改为 `raise ValueError` |
| `if not amt_m: return None` | 金额无法解析时静默丢弃 | 改为 `raise ValueError` |

## 设计原则

- **阻断绝不要静默**：任何非预期数据格式、未知枚举值、解析失败都应抛异常，而不是 `continue` 或 `return None`
- **异常消息要可操作**：包含足够上下文（文件名、行内容、字段值、预期格式）让用户能直接判断问题根源
- **0 元交易例外**：0 元非资金变动类交易（会员卡抵扣、权益兑换等）保留记录但金额为 0 - 这是**合法的有记录价值的交易**，不抛异常也不静默丢弃
- **影响范围透明**：修改转换器后，用旧文件重新转换验证行数变化，确认修复后的差异
- **变量定义顺序**：`txn_type`、`payment_method`、`counterparty`、`desc` 等应在 if/elif 判断**之前**就提取好，否则新添加的分支无法引用它们

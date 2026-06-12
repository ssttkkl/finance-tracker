# 建行储蓄卡 XLS 转换器设计

> 2026-06-09 | ft convert 新增 `ccb-debit` 源

## 输入

建行个人活期账户交易明细 XLS（`xlrd` 解析），9 列：

| 列 | 名称 | 示例 |
|---|---|---|
| 0 | 序号 | 1 |
| 1 | 摘要 | 消费 / 充值 / 消费退货 / 无卡自助交易 / 银联入账 … |
| 2 | 币别 | 人民币元 |
| 3 | 钞汇 | 钞 |
| 4 | 交易日期 | YYYYMMDD |
| 5 | 交易金额 | -13.99 / 100.00 |
| 6 | 账户余额 | 1,879.94 |
| 7 | 交易地点/附言 | 始终为 `***` |
| 8 | 对方账号与户名 | `账号/名称`，名称可能脱敏（`***咖啡`） |

卡号从第 2 行提取（`卡号/账号:xxxxxxxxxxxx0523`）。

## 模块

`src/ft/importers/ccb_debit.py` — 纯函数：

```python
def read_ccb_debit(path: str) -> list[dict]
```

返回 rec dict 列表，交给 `do_convert` 统一写 CSV。

## rec dict 字段

遵循其他转换器（alipay/wechat/icbc）的 dict 结构：

| 字段 | 来源 | 说明 |
|---|---|---|
| `date` | col[4] | `YYYY-MM-DD 00:00:00` |
| `amount` | col[5] | float，保留符号 |
| `currency` | col[2] | `人民币元`→CNY，`美元`→USD |
| `card_number` | 表头卡号 | 末 4 位，用于 mapping 路由 |
| `counterparty` | col[8] `/` 后部分 | 商家名 / 脱敏名 |
| `description` | col[1] 摘要 | 消费/充值/消费退货…（交易类型信息） |
| `category` | amount 符号 | `<0`→expense，`>0`→income |
| `payment_method` | — | `建行储蓄卡({末4位})` |
| `platform` | counterparty | 调用 `_infer_platform()` 推断 |

## 退款配对

「消费退货」（amount>0）与对应原消费配对核销：

**匹配条件**（必须全部满足）：
1. 金额绝对值精确相等
2. 退款日期 ≥ 消费日期，且 ≤ 消费日后 30 天
3. 附加信号：脱敏名末位字符匹配（如「于震」`[*]震` ↔ `***震`）

**配对结果**：
- 全额退款 → 删除两条记录
- 部分退款 → 调整原消费净额
- 孤退款 → 保留为 income

配对算法复用 `_pair_refunds()`，退款记录以 category=income, counterparty=对方户名, description=消费退货 传入。

## 接入 do_convert

`source` 参数新增 `ccb-debit`：

```python
elif source == "ccb-debit":
    rows = read_ccb_debit(path)
    bill_type = "ccb_debit"
```

mapping 规则：
- 优先：`ccb_debit_{末4位}` → 具体卡账户
- 回退：`ccb_debit` → 通用建行储蓄卡账户

`_infer_payment_source("ccb_debit", ...)` 返回 `"建行储蓄卡"`（需新增类型判断）。

## 测试

TDD，12 个用例（`tests/test_ccb_debit.py`）：

| # | 场景 |
|---|---|
| 1 | 基本消费解析 |
| 2 | 充值 / 缴费 |
| 3 | 无卡自助交易 |
| 4 | 有卡自助消费 |
| 5 | 银联入账（收入） |
| 6 | 转账支取 |
| 7 | 证转银 / 银转证 |
| 8 | 利息存入 |
| 9 | 消费退货全额配对（同天） |
| 10 | 消费退货全额配对（不同天，30 天内） |
| 11 | 消费退货部分配对 |
| 12 | 孤退款保留 |
| 13 | 多币种（USD） |
| 14 | 卡号提取 |
| 15 | counterparty 脱敏名处理 |

## 非目标

- 不涉及信用卡（建行信用卡走不同格式）
- 不对脱敏 counterparty 做语义还原
- 不修改其他转换器逻辑

# Data Model: 标准记录类型

## `record_type`

现金流水上的正式标准记录类型，类型值是小写稳定协议值：

`consumption`、`refund`、`reversal`、`transfer_reversal`、`withdrawal_in`、`withdrawal_out`、`transfer_in`、`transfer_out`、`repayment`、`income`、`investment_in`、`investment_out`、`interest`、`fee`、`fx_in`、`fx_out`、`other`。

约束：

- 不允许空值。
- 不修改 `amount`、`category` 或 `source_payload` 的原始语义。
- `repayment` 不是 `transfer_in` 或 `transfer_out` 的别名。
- `refund` 只表示消费退款；一般撤销或冲正使用 `reversal`，P2P 转账、红包和群收款的退回使用 `transfer_reversal`。
- `withdrawal_in` 和 `withdrawal_out` 分别表示提现入账和提现出账，不是普通 `transfer_in` 或 `transfer_out` 的别名。
- `other` 是未知来源语义的可见兜底，不是历史兼容读取路径。

## `cash_transactions`

在现有 `category` 后新增：

| 字段 | 类型 | 可空 | 说明 |
|---|---|---|---|
| `record_type` | `String(32)` | 否 | 导入时生成的标准记录类型 |

`source_payload` 继续保留 `txn_type`、方向、`summary`、退款信号、原始对手方等字段。`record_type` 是可查询正式字段，来源快照是审计证据，两者不互相替代。

## 分类输入

纯分类函数读取：

- `source_type` / `bill_source`
- `txn_type`、`_alipay_direction`、`_wechat_direction`
- `summary`、退款相关信号、`offset_type`
- `note`、`counterparty`、`location`、`acct_name_raw`
- `amount` 和既有 `category` 仅用于方向分支和兜底，不单独证明业务语义

## 读取合同

现金流水详细读取结果增加 `record_type`；旧的 `_record_type` 仍表示账户类型，不得复用或覆盖。

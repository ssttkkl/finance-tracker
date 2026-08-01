# Data Model: 工行退款摘要关系配对

## Cash fact

现有 `cash_transactions` 表不变。导入后字段语义如下：

| 字段 | 类型/来源 | 约束 |
|---|---|---|
| `source_type` | 文本，取解析行 `bill_source` | 工行信用卡为 `icbc_credit`，借记卡为 `icbc_debit` |
| `record_id` | 文本，取账单行稳定标识 | 与 `source_type` 共同用于幂等 |
| `counterparty` | 文本，规范化对手方 | 同一原始对手方经过同一规则后结果相同 |
| `note` | 文本，保留业务描述 | 不承载工行退款信号 |
| `source_payload` | JSON 来源行快照 | 必须包含 `bill_source`、`summary`、`refund_signal`（没有信号时为空字符串）和原始对手方字段 |

## Structured refund signal

| 正式渠道 | 正式信号 | 触发条件 |
|---|---|---|
| `icbc_credit` | `icbc_credit_return` | `source_payload.summary == "退货"` 且 `source_payload.bill_source == "icbc_credit"` |
| `icbc_debit` | `icbc_debit_return` | `source_payload.summary == "退货"` 且 `source_payload.bill_source == "icbc_debit"` |

任何其他摘要、渠道或信号组合都不是本 Feature 的工行退款信号。

## Relation

沿用现有 `transaction_relations`：

- `kind=refund_offset`；
- 主端为负金额消费，次端为正金额退货；
- 自动确认仍要求现有金额、币种、时间窗口、对手方和一对一占用规则；
- 原始现金流水保持不变，收支投影消费关系结果。

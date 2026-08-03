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

## Transfer seed signal

Phase C 的**转账出账种子**仍使用现有 `FactView`，不新增持久化字段。种子候选必须自身命中来源专用信号；可识别的信号包括提现族、结构化 `summary=转账`、`转出到银行卡`、`转账到银行卡`、`转账支取`、`无卡自助`/`无卡付`/`无卡支付`、`银转证`、`银行转证券`和明确还款信号。普通备注或对手方文本中的裸“转账”不构成来源信号。银行入账类 `电子汇入`、`银联入账`只用于识别对侧候选，不能单独使普通消费成为转账出账种子。全量扫描和显式 `seed_ids` 使用相同闸门。

## Relation

沿用现有 `transaction_relations`：

- `kind=refund_offset`；
- 主端为负金额消费，次端为正金额退货；
- 自动确认仍要求现有金额、币种、时间窗口、对手方和一对一占用规则；
- 原始现金流水保持不变，收支投影消费关系结果。

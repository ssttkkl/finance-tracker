# 数据模型：工行卡退货退款信号

本功能不改变数据库 schema。字段随既有现金事实的 JSON `source_payload` 保存，并由仓储映射到 `FactView.raw_payload`。

| 键 | 类型 | 新记录语义 | 允许缺失 | 约束 |
|----|------|------------|----------|------|
| `bill_source` | string | `icbc_credit` 或 `icbc_debit` | 否 | 必须与 `refund_signal` 使用同一来源。 |
| `summary` | string | 原生账单摘要；退款行值为 `退货` | 可缺失 | 仅为审计与新信号证据，不改变展示字段。 |
| `refund_signal` | string | 工行信用卡为 `icbc_credit_return`；借记卡为 `icbc_debit_return` | 可缺失 | 仅由对应来源的 `summary=退货` 生成。 |

退款种子谓词：

```text
fact 是现金事实
AND fact.amount > 0
AND raw_payload.bill_source IN {"icbc_credit", "icbc_debit"}
AND raw_payload.refund_signal == {
  "icbc_credit": "icbc_credit_return",
  "icbc_debit": "icbc_debit_return",
}[raw_payload.bill_source]
```

缺失、非字符串或未知值均为 false。`summary=退货` 只在解析阶段生成正式信号；关系层不把只有 `summary` 的历史快照当作兼容输入。不读取遗留 `offset_source`；P2P 排除仍只根据文本执行。

## 写入与回滚

- 新导入：在既有 `StatementImportService` 事务内，源行与新的 JSON 键原子写入。
- 历史记录：不读取、不回填、不迁移；受影响账单重新导入。
- 回滚：代码回滚后，新键作为 JSON 额外字段被旧版本忽略；不会影响金额、余额或既有关系。

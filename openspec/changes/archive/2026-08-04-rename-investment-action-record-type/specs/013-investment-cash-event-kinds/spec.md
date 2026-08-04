## ADDED Requirements

### Requirement: 投资事件使用记录类型与记录子类型
系统 MUST 将投资事件的正式业务类型持久化为 `record_type`，不得再暴露或写入 `action` 字段。系统 MUST 为每条投资事件持久化 `record_subtype`；`record_type` 与 `record_subtype` 共同构成用于查询、审计和关系匹配的规范语义，来源行快照仍完整保留原生字段。

#### Scenario: 读取已迁移的投资事件
- **WHEN** 调用方读取一条投资事件
- **THEN** 响应包含 `record_type` 与 `record_subtype`，不包含 `action`，且资产组成、精确十进制金额、币种、业务行标识和来源行快照与迁移前同一经济事实一致

### Requirement: 投资经济事实的范围约束
系统 MUST 只允许以下 `record_type` 与 `record_subtype` 组合：`funding(external|subaccount)`、`trade(security|fx|repo)`、`income(dividend_cash|dividend_stock|interest|reward)`、`expense(commission|tax|interest|handling_fee|penalty)`、`reversal(expense_tax|expense_interest|expense_commission|funding_withdrawal)`、`subscription(ipo_debit|ipo_refund)`、`adjustment(fx_net|manual|unclassified)` 与 `snapshot(cash|position)`。方向 MUST 由 `from_ticker`、`from_amount`、`to_ticker` 与 `to_amount` 表达，不得另设入金或出金一级类型。只有 `funding(external)` 可进入外部资金调拨候选；无法从结构化来源字段安全归类的历史现金变化 MUST 使用 `adjustment(unclassified)`。

#### Scenario: 导入非出入金现金变化
- **WHEN** 导入来源语义为利息、税费、外汇净额、奖励或出金退款的投资事件
- **THEN** 系统必须分别写为 `income(interest)`、`expense(tax|interest|commission|handling_fee|penalty)`、`adjustment(fx_net)`、`income(reward)` 或 `reversal(funding_withdrawal)`，并保留能够解释规范化决定的原生来源字段

### Requirement: 双后端兼容的投资事件字段迁移
系统 MUST 在 SQLite 与 PostgreSQL 中以等价的字段名称、记录类型、记录子类型、精确十进制金额、币种、工作区隔离和幂等身份保存投资事件。重复导入相同 `source_type` 与 `record_id` 时 MUST 不创建重复事件，也不得改变既有规范化结果。

#### Scenario: 重复导入已迁移来源行
- **WHEN** 在任一正式后端重复导入同一来源行
- **THEN** 系统返回幂等结果，已有 `record_type`、`record_subtype`、来源行快照和投资快照均不变

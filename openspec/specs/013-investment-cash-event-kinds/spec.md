# Investment cash event kinds

## Purpose
| `deposit` / `withdraw` | True customer funding; internal transfers (P1) | 本能力的行为契约由迁移后的需求与场景持续维护。

## Requirements

### Requirement: 历史行为基线
系统 MUST 保持迁移前规格文件记录的可观察行为、错误边界和非目标。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：WHEN 该能力被修改，THEN 变更必须同步更新本能力的需求与场景。
- THEN 系统满足该条件，并保留可复核的验证证据。

### Requirement: 投资事件使用记录类型与记录子类型
系统 MUST 将投资事件的正式业务类型持久化为 `record_type`，不得再暴露或写入 `action` 字段。系统 MUST 为每条投资事件持久化 `record_subtype`；`record_type` 与 `record_subtype` 共同构成用于查询、审计和关系匹配的规范语义，来源行快照仍完整保留原生字段。

#### Scenario: 读取已迁移的投资事件
- **WHEN** 调用方读取一条投资事件
- **THEN** 响应包含 `record_type` 与 `record_subtype`，不包含 `action`，且资产组成、精确十进制金额、币种、业务行标识和来源行快照与迁移前同一经济事实一致

### Requirement: 出入金记录类型的范围约束
系统 MUST 只允许 `record_type=deposit` 或 `record_type=withdraw` 搭配 `record_subtype=external_funding` 或 `record_subtype=subaccount_transfer`。利息、税费和佣金 MUST 使用 `record_type=fee` 及相应子类型；外汇净额、奖励和出金冲回 MUST 分别使用 `fx_adjustment(net_cash_adjustment)`、`reward(cash_reward)` 和 `withdrawal_reversal(withdrawal_refund)`，且不得进入外部出入金候选。无法从结构化来源字段安全归类的历史现金变化 MUST 使用 `cash_adjustment(unclassified)`，不得保留为 `deposit` 或 `withdraw`。

#### Scenario: 导入非出入金现金变化
- **WHEN** 导入来源语义为利息、税费、外汇净额、奖励或出金冲回的投资事件
- **THEN** 系统不得将其记录为 `deposit` 或 `withdraw`，并保留能够解释规范化决定的来源行快照

### Requirement: 双后端兼容的投资事件字段迁移
系统 MUST 在 SQLite 与 PostgreSQL 中以等价的字段名称、记录类型、记录子类型、精确十进制金额、币种、工作区隔离和幂等身份保存投资事件。重复导入相同 `source_type` 与 `record_id` 时 MUST 不创建重复事件，也不得改变既有规范化结果。

#### Scenario: 重复导入已迁移来源行
- **WHEN** 在任一正式后端重复导入同一来源行
- **THEN** 系统返回幂等结果，已有 `record_type`、`record_subtype`、来源行快照和投资快照均不变

## Source
完整迁移来源与原始验证证据：[013-investment-cash-event-kinds/spec.md](../../changes/archive/2026-08-01-013-investment-cash-event-kinds/legacy/013-investment-cash-event-kinds/spec.md)。
本文件是 OpenSpec 的行为导向投影；实现细节、研究记录和历史任务保留在对应 change 的 `legacy/` 目录。

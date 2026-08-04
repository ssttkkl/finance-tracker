## ADDED Requirements

### Requirement: 导入时规范化投资事件记录类型
系统 MUST 在写入投资事件前，根据来源的结构化交易类型、方向和字段将其规范化为 `record_type` 与 `record_subtype`。导入器 MUST 只负责这种归一，不得把券商名称、银行名称或账单文本规则传递到后续资金调拨扫描器。

#### Scenario: 相同规范语义跨来源导入
- **WHEN** 两个不同导入渠道分别提供语义相同的外部入金来源行
- **THEN** 两条投资事件均为 `record_type=deposit` 和 `record_subtype=external_funding`，并各自保留完整来源行快照与独立幂等身份

### Requirement: 导入时拒绝混淆出入金与现金调整
系统 MUST 对无法安全归类为外部出入金、投资账户内部调拨或已支持的非出入金记录类型的来源行失败关闭。系统不得仅因金额正负将其猜测为 `deposit` 或 `withdraw`。

#### Scenario: 来源类型不支持的现金变化
- **WHEN** 导入器无法从来源的结构化字段确定一笔现金变化的业务语义
- **THEN** 导入失败并提供可操作错误，不写入投资事件或投资快照

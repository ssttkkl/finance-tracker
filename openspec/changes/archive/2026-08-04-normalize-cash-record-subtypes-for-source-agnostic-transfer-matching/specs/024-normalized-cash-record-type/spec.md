## ADDED Requirements

### Requirement: 导入时确定标准记录子类型

系统 MUST 在每条现金流水导入时持久化非空 `record_subtype`，并以 `record_type` 与 `record_subtype` 的合法组合表达资金移动语义。`transfer_in` 和 `transfer_out` MUST 使用 `ordinary_transfer`、`cross_border_remittance` 或 `internal_account_transfer`；`fx_in` 和 `fx_out` MUST 使用 `currency_exchange`；`repayment` MUST 使用 `credit_repayment`；`withdrawal_in` 和 `withdrawal_out` MUST 使用 `withdraw_to_bank`；其余标准记录类型 MUST 使用 `not_applicable`。系统 MUST 拒绝不合法组合，且 SQLite 与 PostgreSQL 的约束和导入结果必须等价。

#### Scenario: 工行跨境汇款在导入时确定子类型
- **WHEN** 工行借记卡来源摘要明确为跨境汇款且方向为出账
- **THEN** 系统 MUST 导入为 `record_type=transfer_out` 与 `record_subtype=cross_border_remittance`，不得导入为换汇类型

#### Scenario: 工银亚洲子账号不在导入期推断内部调拨
- **WHEN** 工银亚洲活期账户业务行的对方账号为完整子账号或可严格还原的掩码子账号
- **THEN** 系统 MUST 导入为来源直接确定的转账 `record_type` 与 `ordinary_transfer`；是否属于本人内部调拨只能由关系层的显式账户别名和目标账户归属决定

#### Scenario: 明确购汇保持换汇子类型
- **WHEN** 来源原生字段明确表达购汇、结售汇或汇兑
- **THEN** 系统 MUST 按方向导入为 `fx_out` 或 `fx_in`，并将 `record_subtype` 保存为 `currency_exchange`

#### Scenario: 非资金移动记录使用明确不适用值
- **WHEN** 一条消费或收入现金流水完成导入
- **THEN** 系统 MUST 将 `record_subtype` 保存为 `not_applicable`，不得使用空字符串表示未分类

### Requirement: 导入时规范化可匹配对方账号

系统 MUST 将 `counterparty_account` 保存为导入期可匹配的规范账号，而非原始账单字符串副本。完整未掩码账号 MUST 仅移除账号格式分隔符；仅含尾号的值 MUST 保留尾号；非账号文本或无法可靠识别的掩码 MUST 保存为空字符串。工银亚洲完整币种子账号及其扩展账号 MUST 保留完整数字值，不得改写其末位；只有掩码前缀、连续掩码宽度、尾号和当前账单账户严格吻合时，才可由该本账号还原完整对方账号。原始账号 MUST 完整保留在 `source_payload` 对应原始列，系统 MUST NOT 将规范化值或文件元数据写入该快照。

#### Scenario: 工银亚洲完整币种子账号保留
- **WHEN** 工银亚洲业务行的原始对方账号为完整币种子账号或其扩展账号
- **THEN** 导入的 `counterparty_account` MUST 仅移除格式字符而保留其原有数字，同时 `source_payload` MUST 保留未经改写的原始账号

#### Scenario: 工银亚洲严格还原掩码对方账号
- **WHEN** 本账单账户为完整数字账号，工银亚洲来源对方账号的前缀、连续掩码宽度和尾号与其严格吻合
- **THEN** 系统 MUST 由本账单账户还原完整 `counterparty_account`，且 `source_payload` MUST 保留原始掩码账号

#### Scenario: 无法验证的掩码对方账号不伪造匹配值
- **WHEN** 来源仅提供无法与当前工银亚洲账单账户严格验证的含掩码符号对方账号
- **THEN** 系统 MUST 将 `counterparty_account` 保存为空字符串，且不得从对方名称、账户名称或尾号猜测补齐

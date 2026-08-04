## MODIFIED Requirements

### Requirement: 基于标准字段的转账关系匹配

系统 MUST 仅使用现金流水的 `record_type`、`record_subtype`、`account_id`、`counterparty_account`、`counterparty_account_attrs`、`currency`、精确 Decimal 金额、`occurred_at` 与当前工作区显式登记的 `account_aliases` 扫描普通转账、跨境汇款和内部账户调拨。该扫描 MUST NOT 判断 `source_type`、来源快照、账单文本、对方名称或账户类型。所有读取 `counterparty_account` 的候选筛选和长窗口分配路径 MUST 同时消费并验证 `counterparty_account_attrs`；本人账户标识与完整、尾号、掩码或重建对方账号的归属不唯一时，系统 MUST 不自动确认关系。

#### Scenario: 同字段事实跨来源得到相同配对结果

- **WHEN** 两组现金流水具有相同的规范字段、对方账号属性、账户别名与时间金额条件但来自不同导入渠道
- **THEN** 系统 MUST 生成相同的转账候选和确认结果，不得因导入渠道或账单文本不同而改变结果

#### Scenario: 跨币种跨境汇款慢到账

- **WHEN** `cross_border_remittance` 转出以带合法属性的对方账号唯一归属到一条反向转入账户，双方币种不同且时间差不超过 7 天
- **THEN** 系统 MUST 不比较金额并创建 `accepted transfer_pair(cross_currency_remittance)`

#### Scenario: 唯一目标的最近一对一分配

- **WHEN** 多笔具有唯一对方账号目标的转出在 7 天内竞争多个同一目标账户的合格转入
- **THEN** 系统 MUST 按时间差、转出业务行标识、转入业务行标识稳定排序全部合法边，并仅在两端均未占用时确认最近一对；任何转入不得被重复分配

#### Scenario: 同一账户跨币种调拨

- **WHEN** 一笔带合法属性的对方账号唯一指向当前 `account_id`，且合格转出与转入币种不同、时间差不超过 7 天
- **THEN** 系统 MUST 创建 `accepted transfer_pair(currency_exchange)`，且该关系不得计入外部收支

#### Scenario: 无属性账号不进入长窗口匹配

- **WHEN** 一笔转出只有非空 `counterparty_account`，但 `counterparty_account_attrs` 为空或非法
- **THEN** 系统 MUST 不根据字符串长度、掩码符号或导入渠道解析目标，也不得为该记录启用唯一账号目标的长窗口匹配

## ADDED Requirements

### Requirement: 基于标准字段的转账关系匹配

系统 MUST 仅使用现金流水的 `record_type`、`record_subtype`、`account_id`、`counterparty_account`、`currency`、精确 Decimal 金额、`occurred_at` 与当前工作区显式登记的 `account_aliases` 扫描普通转账、跨境汇款和内部账户调拨。该扫描 MUST NOT 判断 `source_type`、来源快照、账单文本、对方名称或账户类型。本人账户标识与规范对方账号的归属不唯一时，系统 MUST 不自动确认关系。

#### Scenario: 同字段事实跨来源得到相同配对结果
- **WHEN** 两组现金流水具有相同的规范字段、账户别名与时间金额条件但来自不同导入渠道
- **THEN** 系统 MUST 生成相同的转账候选和确认结果，不得因导入渠道或账单文本不同而改变结果

#### Scenario: 普通同币种转账自动确认
- **WHEN** `ordinary_transfer` 的转出与不同账户的转入方向相反、币种相同、金额绝对值精确相等，且双方在既有强时间窗口内唯一并满足对方账号归属
- **THEN** 系统 MUST 创建 `accepted transfer_pair(ordinary_transfer)`

#### Scenario: 跨币种跨境汇款慢到账
- **WHEN** `cross_border_remittance` 转出以规范对方账号唯一归属到一条反向转入账户，双方币种不同且时间差不超过 7 天
- **THEN** 系统 MUST 不比较金额并创建 `accepted transfer_pair(cross_currency_remittance)`

#### Scenario: 唯一目标的最近一对一分配
- **WHEN** 多笔具有唯一对方账号目标的转出在 7 天内竞争多个同一目标账户的合格转入
- **THEN** 系统 MUST 按时间差、转出业务行标识、转入业务行标识稳定排序全部合法边，并仅在两端均未占用时确认最近一对；任何转入不得被重复分配

#### Scenario: 同一账户跨币种调拨
- **WHEN** 一笔具有唯一对方账号目标的转出与转入归属同一 `account_id`、币种不同且时间差不超过 7 天
- **THEN** 系统 MUST 创建 `accepted transfer_pair(currency_exchange)`，且该关系不得计入外部收支

### Requirement: 转账关系子类型语义

系统 MUST 将同币种普通或跨境资金转移关系保存为 `ordinary_transfer`，将跨币种跨境汇款关系保存为 `cross_currency_remittance`，将明确换汇或同一规范账户跨币种调拨保存为 `currency_exchange`，并将还款关系保存为 `credit_repayment`。系统 MUST 根据配对两端币种确定跨币种汇款关系子类型，而不得仅凭任一来源的账单文本推断。

#### Scenario: 同币种跨境汇款保持普通转账关系
- **WHEN** 两端均为 `cross_border_remittance` 或其合格反向转入且币种相同、金额精确相等
- **THEN** 系统 MUST 创建 `transfer_pair(ordinary_transfer)`，不得标记为换汇

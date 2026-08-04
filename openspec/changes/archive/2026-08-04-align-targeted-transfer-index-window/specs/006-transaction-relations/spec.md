## ADDED Requirements

### Requirement: 长窗口转账候选索引覆盖业务时间窗

系统 MUST 对唯一对方账号目标的转账关系，以有界且可索引的候选查询完整覆盖最多 7 天的精确时间窗口。候选索引可以因日期分桶读取边界日，但系统 MUST 在生成关系前以 `occurred_at` 的精确时间差排除超过 7 天的记录；不得因索引范围短于业务时间窗遗漏合法关系。

#### Scenario: 跨越三个自然日的同币种跨境汇款

- **WHEN** `cross_border_remittance` 转出以唯一对方账号目标指向转入账户，双方同币种、金额绝对值精确相等、时间差小于 7 天但发生日期相隔三个自然日
- **THEN** 系统 MUST 创建 `accepted transfer_pair(ordinary_transfer)`

#### Scenario: 索引边界外的转入不配对

- **WHEN** 唯一对方账号目标的转出和转入时间差超过 7 天
- **THEN** 系统 MUST 不创建该两条流水之间的转账配对关系

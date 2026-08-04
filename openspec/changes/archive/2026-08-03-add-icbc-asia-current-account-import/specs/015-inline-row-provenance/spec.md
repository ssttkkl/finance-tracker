## MODIFIED Requirements

### Requirement: 账单行溯源与正式事实同在一行
系统 MUST 作为账本所有者，在每条本变更发布后的账单派生正式事实同一行保存导入渠道、业务行键、完整的来源行快照和正式字段。来源行快照 `source_payload` MUST 是来源账单中该业务行全部原始列名和值的 JSON 表示：不得遗漏来源列、不得因值为空而省略列、不得混入解析、账户映射、关系检查、格式规范化或兼容性字段。来源账单的无标题列 MUST 使用空字符串 `""` 作为 JSON 键并保留其原始值；若任一列名重复（包括多个无标题列），系统 MUST 拒绝导入，不能编造列名。正式字段 `counterparty`、`counterparty_account`、`note`、`record_type`、`account_name`、`source_type` 和 `record_id` 可以由来源行派生，但不得反写或补充进 `source_payload`。对无法以唯一列结构表达的 PDF 业务行，系统 MUST 保存解析器可归属该行的全部原始表格单元或原始文本单元，且不得保存推断值。

`counterparty_account` MUST 保存来源直接提供的对方账号、卡号、掩码账号或账户标识；来源未提供或无法可靠识别时 MUST 为空字符串。它不得保存本账户的账号、映射账户名或推断出的对侧账户。该字段与 `source_payload` 中的对应原始值同时存在时，前者用于受控查询，后者用于审计。

历史事实在本变更前已经丢失原始列时，系统 MUST 保留既有来源快照和事实，不得伪造完整来源行；迁移只能从已有可确定值回填 `counterparty_account`。

#### Scenario: 支付宝完整来源行与对方账号
- **WHEN** 用户导入一行同时包含 `对方账号`、备注和空值列的支付宝账单
- **THEN** 新建现金流水的 `source_payload` MUST 与该行全部原始表头和值完全一致，`counterparty_account` MUST 等于原始 `对方账号`，且快照中不得出现 `account_name`、`record_type`、`source_type` 或映射结果

#### Scenario: 微信提现到账卡
- **WHEN** 用户导入一行交易类型为 `零钱提现`、支付方式直接表示到账卡的微信账单
- **THEN** 现金流水 MUST 将该到账卡保存为 `counterparty_account`，同时来源行快照 MUST 保留原始 `支付方式` 值而非被路由后的支付方式

#### Scenario: 缺少对方账号的来源行
- **WHEN** 用户导入不提供对方账号的账单行
- **THEN** 系统 MUST 保存完整来源行快照并将 `counterparty_account` 保存为空字符串，不得以本账户、账户映射或文本猜测填充该列

#### Scenario: 无标题来源列
- **WHEN** 用户导入一行包含唯一无标题来源列且该列有原始值的账单
- **THEN** 系统 MUST 使用 `""` 作为该列在 `source_payload` 中的键并保留原始值，不得删除、重命名或以派生字段替代该列

#### Scenario: 双后端等价
- **WHEN** 同一来源账单分别导入 SQLite 和 PostgreSQL 工作区
- **THEN** 两个后端的来源行快照、`counterparty_account`、幂等结果和正式金额 MUST 等价，允许代理键和时间戳字面差异

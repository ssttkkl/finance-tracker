## MODIFIED Requirements

### Requirement: 账单行溯源与正式事实同在一行
系统 MUST 作为账本所有者，在每条本变更发布后的账单派生正式事实同一行保存导入渠道、业务行键、完整的来源行快照和正式字段。来源行快照 `source_payload` MUST 是来源账单中该业务行全部原始列名和值的 JSON 表示：不得遗漏来源列、不得因值为空而省略列、不得混入解析、账户映射、关系检查、格式规范化或兼容性字段。正式字段 `counterparty`、`counterparty_account`、`note`、`record_type`、`account_name`、`source_type` 和 `record_id` 可以由来源行派生，但不得反写或补充进 `source_payload`。对无法以唯一列结构表达的 PDF 业务行，系统 MUST 保存解析器可归属该行的全部原始表格单元或原始文本单元，且不得保存推断值。

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

#### Scenario: 双后端等价
- **WHEN** 同一来源账单分别导入 SQLite 和 PostgreSQL 工作区
- **THEN** 两个后端的来源行快照、`counterparty_account`、幂等结果和正式金额 MUST 等价，允许代理键和时间戳字面差异

### Requirement: 关系匹配读事实行快照
系统 MUST 作为对账用户，从事实 `source_payload`（或已提升列）读取 hard-key 与日期几何所需的原始字段；种子仅为 `seed_fact_ids`。关系匹配可以读取 `counterparty_account` 作为附加证据，但不得修改来源行快照，也不得依赖曾被禁止写入快照的派生字段。

#### Scenario: 完整快照支持关系检查
- **WHEN** 已导入的来源行包含关系检查所需的原始支付方式、日期或账号信息
- **THEN** 关系检查 MUST 从完整来源行快照或 `counterparty_account` 获得该信息，且不得重新解析来源文件或依赖独立 raw 表

#### Scenario: 历史快照保持可审计
- **WHEN** 升级前事实缺少原始列，无法重建完整来源行
- **THEN** 迁移 MUST 保留该事实及其既有来源快照，关系检查不得把迁移生成的值描述为原始来源字段

### Requirement: 投资账单原始动作溯源与受控重建
系统 MUST 为每条由投资账单导入的来源业务行在 `source_payload` 保存可归属的原始动作、旗标和备注字段，不得以归一后的 `record_type`、`record_subtype` 或中间 `DEPOSIT` / `WITHDRAW` 值替换原始动作。由账单汇总生成的 `snapshot` 没有来源动作时，快照 MUST 保存可归属的原始汇总单元，不得伪造 `action_raw`。对于东方证券 PDF，来源行快照 MUST 保留“银行转证券”“证券转银行”“OTC资金划入”“OTC资金划出”“利息归本”与“股息红利差异扣税”中的原始动作。用户明确授权重建特定账本时，系统 MUST 先创建可恢复备份，再从原始账单重新发布投资事件；不得以直接更新既有正式事实伪造来源行或改变其语义。

#### Scenario: 东方证券相同归一动作保留不同原始语义
- **WHEN** 同一份东方证券 PDF 同时包含“银行转证券”“OTC资金划入”“利息归本”或“股息红利差异扣税”
- **THEN** 每条新投资事件的 `source_payload` MUST 保留对应原始动作，且其正式分类分别为 `funding(external)`、`funding(subaccount)`、`income(interest)` 与 `expense(tax)`

#### Scenario: 受控清空后重导投资账单
- **WHEN** 用户明确授权重建指定真实账本的投资事件
- **THEN** 系统 MUST 在可恢复备份成功且迁移、导入和读模型验证通过后发布重建结果；任一步失败 MUST 保留或恢复备份，且不得发布部分投资事件或资金调拨关系

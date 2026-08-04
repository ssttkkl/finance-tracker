## MODIFIED Requirements

### Requirement: 账单行溯源与正式事实同在一行

系统 MUST 作为账本所有者，在每条本变更发布后的账单派生正式事实同一行保存导入渠道、业务行键、完整的来源行快照和正式字段。来源行快照 `source_payload` MUST 是来源账单中该业务行全部原始列名和值的 JSON 表示：不得遗漏来源列、不得因值为空而省略列、不得混入解析、账户映射、关系检查、格式规范化或兼容性字段。来源账单的无标题列 MUST 使用空字符串 `""` 作为 JSON 键并保留其原始值；若任一列名重复（包括多个无标题列），系统 MUST 拒绝导入，不能编造列名。正式字段 `counterparty`、`counterparty_account`、`counterparty_account_attrs`、`note`、`record_type`、`record_subtype`、`account_name`、`source_type` 和 `record_id` 可以由来源行派生，但不得反写或补充进 `source_payload`。对无法以唯一列结构表达的 PDF 业务行，系统 MUST 保存解析器可归属该行的全部原始表格单元或原始文本单元，且不得保存推断值。

`counterparty_account` MUST 保存从来源直接提供的对方账号、卡号、掩码账号或账户标识导出的规范表示，不得因表示被掩码、包含非数字字符或当前无法匹配而清空可识别值。`counterparty_account_attrs` MUST 是非空 JSON 数组列，并以规范顺序保存 `full`、`tail`、`masked` 和 `reconstructed` 中适用的属性：完整来源标识使用 `full`，仅有独立 4 位尾号时使用 `tail`，来源值包含显式掩码时使用 `masked`；掩码值仅在与同一账单直接提供的参照账号满足严格长度、前缀、掩码范围和可见尾部校验时才可重建，并同时使用 `masked` 与 `reconstructed`。来源未提供账号或只提供空值标记时，账号 MUST 为空字符串且属性 MUST 为空数组。新导入的非空账号若无法生成合法属性，系统 MUST 拒绝整批导入。

支付宝 MUST 从每条实际导入行的 `对方账号` 提取账号；微信 MUST 仅把 `零钱提现` 直接提供的到账卡视为对方账号；建行借记卡 MUST 从 `对方账号与户名` 拆出账号部分；工行借记卡和工银亚洲 MUST 读取各自的独立对方账号列；工行信用卡 MUST 仅从来源结构明确标记为转账且可归属到同一业务行的账号单元提取。普通支付方式、本账户账号、映射账户名、对方名称、商户文本或其他推断出的对侧账户不得写入该列。对应未经改写的原始账号值 MUST 保留在 `source_payload`。

历史事实在本变更前已经丢失原始列时，系统 MUST 保留既有来源快照和事实，不得伪造完整来源行。迁移 MUST 为已有可确定账号回填属性，并可从完整来源行补回来源专用对方账号；无法证明表示类型的历史非空账号 MUST 保留原值并使用空属性，因而不得作为账号匹配证据。

#### Scenario: 支付宝掩码对方账号

- **WHEN** 用户导入一行 `对方账号` 为掩码邮箱、手机号或平台账号的支付宝资金流水
- **THEN** 新建现金流水的 `counterparty_account` MUST 保留该掩码表示，`counterparty_account_attrs` MUST 为 `["masked"]`，且 `source_payload` MUST 与全部原始表头和值完全一致

#### Scenario: 微信提现到账卡

- **WHEN** 用户导入一行交易类型为 `零钱提现`、支付方式直接表示到账卡的微信账单
- **THEN** 现金流水 MUST 保存规范尾号和 `["tail"]`，同时来源行快照 MUST 保留原始 `支付方式` 值而非被路由后的支付方式

#### Scenario: 银行账单保留完整、尾号和掩码表示

- **WHEN** 建行借记卡、工行借记卡或工行信用卡的可归属对方账号分别提供完整账号、独立 4 位尾号或掩码账号
- **THEN** 现金流水 MUST 保留对应规范表示并分别生成 `["full"]`、`["tail"]` 或 `["masked"]`，不得因当前没有本人账户别名而丢弃账号

#### Scenario: 工银亚洲严格重建掩码账号

- **WHEN** 工银亚洲对方账号与同一账单直接提供的参照账号满足严格重建条件
- **THEN** 现金流水 MUST 保存重建后的完整规范账号和 `["masked", "reconstructed"]`，来源行快照 MUST 继续保存原始掩码值

#### Scenario: 缺少对方账号的来源行

- **WHEN** 用户导入不提供对方账号或只提供 `/`、`-`、`（空）` 等来源空值标记的账单行
- **THEN** 系统 MUST 保存完整来源行快照，将 `counterparty_account` 保存为空字符串并将 `counterparty_account_attrs` 保存为空数组，不得以本账户、账户映射或文本猜测填充

#### Scenario: 无标题来源列

- **WHEN** 用户导入一行包含唯一无标题来源列且该列有原始值的账单
- **THEN** 系统 MUST 使用 `""` 作为该列在 `source_payload` 中的键并保留原始值，不得删除、重命名或以派生字段替代该列

#### Scenario: 双后端等价

- **WHEN** 同一来源账单分别导入 SQLite 和 PostgreSQL 工作区
- **THEN** 两个后端的来源行快照、`counterparty_account`、`counterparty_account_attrs`、精确 Decimal 金额、币种、幂等结果和工作区隔离 MUST 等价，允许代理键和时间戳字面差异

### Requirement: 关系匹配读事实行快照

系统 MUST 作为对账用户，从事实 `source_payload` 或已提升列读取退款 hard-key 与日期几何所需的原始字段；种子仅为 `seed_fact_ids`。转账、提现、还款、跨境汇款和内部账户调拨的关系匹配 MUST 只读取 `record_type`、`record_subtype`、`account_id`、`counterparty_account`、`counterparty_account_attrs`、币种、金额、时间和显式账户别名，不得读取来源行快照或任何账单原文。任何读取 `counterparty_account` 的匹配路径 MUST 同时消费属性；属性缺失、未知、重复、顺序非法或与账号表示矛盾时，系统 MUST 不使用账号证据，且不得根据长度、掩码符号、导入渠道或来源快照补造属性。关系匹配不得修改来源行快照或正式账号字段。

#### Scenario: 完整快照支持非转账关系检查

- **WHEN** 已导入来源行包含退款 hard-key 或日期几何所需的原始字段
- **THEN** 关系检查 MUST 从完整来源行快照或对应已提升列获得信息，且不得重新解析来源文件或依赖独立 raw 表

#### Scenario: 对方账号属性控制转账匹配

- **WHEN** 转账关系读取一条非空 `counterparty_account` 的事实
- **THEN** 系统 MUST 按同一事实的合法 `counterparty_account_attrs` 解释该值；缺少合法属性时 MUST 忽略账号证据而不读取来源字段猜测

#### Scenario: 历史快照保持可审计

- **WHEN** 升级前事实缺少原始列，无法重建完整来源行或证明账号属性
- **THEN** 迁移 MUST 保留该事实及其既有来源快照，关系检查不得把迁移生成的值描述为原始来源字段，也不得使用无属性账号匹配

#### Scenario: 对方账号只匹配显式本人标识

- **WHEN** 转账关系读取一条带有合法对方账号字段的事实，但当前工作区没有与候选账户绑定的本人账户标识
- **THEN** 系统 MUST 不从账户名称、来源映射、备注或其他事实猜测目标账户，并保持标准字段候选行为

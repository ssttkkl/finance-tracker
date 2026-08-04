## MODIFIED Requirements

### Requirement: 工银亚洲账户路由与业务行幂等

系统 MUST 优先从工银亚洲文件级 `銀行賬號`（兼容 `银行账号`）提取本账户标识及尾号；只有该字段缺失或不含可用账户标识时，才可以从 `下掛賬戶` 提取。本账户尾号存在时，系统 MUST 用 `icbc_asia_current_account_<尾号>` 加 `*` 规则优先路由账户；两个字段均未提供可用账户标识时，系统 MUST 以 `icbc_asia_current_account` 和账单内工银亚洲活期账户标识路由；不得接受整文件账户覆盖。系统 MUST 以选定的来源账户标识（如有）、已解析的账本账户和除导出序号外的原始交易内容确定 `source_type=icbc_asia_current_account` 的业务行键，使重叠导出的同一业务行不重复入账。若同一文件中有多条除导出序号外完全相同的交易行，系统 MUST 失败关闭，不得静默跳过其中任意一条。

#### Scenario: 银行账号优先于下挂账户

- **WHEN** 一份账单同时提供尾号不同的 `銀行賬號` 和 `下掛賬戶`
- **THEN** 系统 MUST 使用 `銀行賬號` 的尾号匹配账户映射，并以该完整账户标识参与业务行键计算

#### Scenario: 重叠导出不重复入账

- **WHEN** 同一账户的两份重叠导出文件仅交易序号不同，且包含相同的业务行
- **THEN** 第二次导入 MUST 跳过已存在的业务行，不得重复发布现金流水

#### Scenario: 账户映射未命中

- **WHEN** 可用的账单账户尾号和工银亚洲活期账户标识均未命中 `~/.ft/mapping.yaml`
- **THEN** 系统 MUST 整批失败，不得写入未知账户或部分事实

#### Scenario: 双后端等价

- **WHEN** 将同一份工银亚洲账单分别导入 SQLite 与 PostgreSQL 工作区
- **THEN** 两个后端的正式金额、币种、业务行键、`source_payload`、`counterparty_account` 和幂等结果 MUST 等价，允许代理键和时间戳字面差异

## ADDED Requirements

### Requirement: 自动识别必须先使用解析器的快速格式探测

现金账单自动识别 MUST 要求每个内置候选解析器提供 `can_parse` 格式探测。探测 MUST 只读取受控的文件签名、表头或 PDF 首页标识，MUST NOT 生成正式流水、执行完整文件解析、加载账户映射或写入存储。

自动识别 MUST 先调用候选解析器的格式探测；只有恰好一个解析器返回匹配时，才对该解析器执行一次完整解析。零个或多个解析器匹配时 MUST 返回 `import_channel_unrecognized`，且 MUST NOT 通过依次执行多个完整解析来选择渠道。

#### Scenario: 唯一解析器匹配后只完整解析一次

- **WHEN** 使用者选择一份格式明确符合单个现金账单解析器的文件
- **THEN** 系统 MUST 先完成候选解析器的 `can_parse` 探测
- **AND** 系统 MUST 只调用匹配解析器一次完整解析，并继续返回既有导入渠道和来源账户分组
- **AND** 其他候选解析器 MUST NOT 被调用完整解析

#### Scenario: 未加密工行信用卡 PDF 扫描

- **WHEN** 使用者选择一份未加密且首页格式明确的工行信用卡 PDF
- **THEN** 工行信用卡解析器的 `can_parse` MUST 返回匹配，工行借记卡解析器的 `can_parse` MUST 不匹配
- **AND** 系统 MUST 只执行一次工行信用卡完整解析，识别为 `icbc_credit` 并允许页面进入账户映射

#### Scenario: 未加密工行借记卡 PDF 扫描

- **WHEN** 使用者选择一份未加密且首页格式明确的工行借记卡 PDF
- **THEN** 工行借记卡解析器的 `can_parse` MUST 返回匹配，工行信用卡解析器的 `can_parse` MUST 不匹配
- **AND** 系统 MUST 只执行一次工行借记卡完整解析，识别为 `icbc_debit` 并允许页面进入账户映射

#### Scenario: 零个或多个解析器匹配

- **WHEN** 没有解析器匹配，或多个解析器同时匹配同一份文件
- **THEN** 系统 MUST 返回 `import_channel_unrecognized`
- **AND** 系统 MUST NOT 执行任何候选解析器的完整解析

#### Scenario: 探测或解析遇到密码保护

- **WHEN** 账单 PDF 需要密码，或提供的密码无法解锁文件
- **THEN** 系统 MUST 返回对应的密码错误结果
- **AND** 系统 MUST NOT 为同一份文件继续执行其他候选解析器的完整解析
- **AND** 页面 MUST 清除扫描中的忙碌状态并保留重新选择或重试入口

#### Scenario: 唯一匹配后的正式解析失败

- **WHEN** 格式探测唯一匹配，但该解析器的完整解析失败
- **THEN** 系统 MUST 返回既有的可操作解析失败或来源账户识别错误
- **AND** 系统 MUST NOT 把失败转换为另一个候选解析器的完整解析尝试

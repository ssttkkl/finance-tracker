## ADDED Requirements

### Requirement: 导入关系决定与现金写入保持同一确认事务

现金账单确认导入 MUST 在完成业务行幂等写入后，在同一个 Application Service 事务中校验并应用本次关系决定，再刷新受影响的收支投影。CLI 与 Web MUST 使用相同的关系规划和应用合同；任一流水、关系、账户映射或投影步骤失败时，整次确认 MUST 回滚本次确认产生的变化。

#### Scenario: Web 确认自动退款配对

- **WHEN** Web 预览展示由结构化平台退款证据产生的自动 `refund_offset`，且使用者确认导入
- **THEN** 系统 MUST 在同一确认事务中写入新现金流水并应用该关系
- **AND** 确认后的关系结果 MUST 与 CLI 使用同一标准化账单导入后的关系结果等价

#### Scenario: 关系应用失败

- **WHEN** 现金流水可以写入但关系端点冲突、关系决定无效或收支投影校验失败
- **THEN** 系统 MUST 回滚本次确认新增或更新的现金流水、关系、账户映射和投影
- **AND** 不得返回导入成功或留下半完成的关系图

#### Scenario: 重复确认

- **WHEN** 使用者以相同幂等键重复确认相同账单和关系决定
- **THEN** 系统 MUST 返回同一确认结果而不得重复写入流水或关系
- **AND** 既有关系和收支投影 MUST 保持不变

### Requirement: 无法唯一归属的组合支付按入口一致地逐行跳过

支付宝来源行如果同时包含多个真实资金账户、且来源只提供合计金额而无法安全拆分，系统 MUST 将该行标记为 `import_composite_payment_unresolved` 并作为可计数白名单跳过。Web 和 CLI MUST 对其余有效行继续导入；其他来源账户无法识别或账户映射错误 MUST 继续使整批失败。文件全部业务行均为该白名单错误时，系统 MUST 返回零新增的明确结果，不得创建流水。

#### Scenario: CLI 跳过支付宝组合支付

- **WHEN** CLI 导入的支付宝账单包含一条无法唯一归属的组合支付和至少一条可映射业务行
- **THEN** CLI MUST 导入可映射业务行并报告组合支付跳过数量
- **AND** 被跳过行不得创建现金流水或来源行快照

#### Scenario: CLI 文件全部为组合支付

- **WHEN** CLI 导入的支付宝账单所有业务行均为 `import_composite_payment_unresolved`
- **THEN** CLI MUST 返回成功的零新增结果并明确报告跳过数量
- **AND** 账户映射、现金流水、交易关系和收支投影 MUST 保持不变

### Requirement: 导入时间与预览确认使用同一规范表示

账单解析器 MUST 在导入边界把来源时间规范化为带 UTC offset 的 `occurred_at`；无 offset 时间 MUST 按来源渠道声明的时区解释，带 offset 时间 MUST 保持其绝对时刻。预览、CLI 导入和确认重算 MUST 使用同一规范时间，来源行快照仍保留原始时间值。

#### Scenario: 无 offset 的支付宝时间

- **WHEN** 支付宝账单提供无 offset 的中国本地时间
- **THEN** 预览事实和实际现金流水 MUST 表示同一个按中国来源时区解释后的 UTC 时刻
- **AND** 确认不得因为 naive 与 aware 表示差异返回陈旧错误

### Requirement: 关系派生元数据与来源行快照分离保存

账单导入 MUST 将 `source_payload` 限定为原始来源行快照，并将 `offset_role`、`offset_group`、`offset_strength`、`offset_match_type` 和 `offset_rule_hint` 等关系派生值保存到独立的关系派生元数据中。现金流水从数据库重载后，关系规划 MUST 能读取这些派生值；重复导入同一业务行时不得创建第二条流水，且允许只更新变化的关系派生元数据。

#### Scenario: 微信退款角色在重载后仍可用

- **WHEN** 微信转换器为同一订单的消费行和退款行生成 `offset_role`，并把结果写入现金流水
- **THEN** 原始来源行快照 MUST 不包含这些派生字段
- **AND** 从 SQLite 或 PostgreSQL 重载流水后，退款规划 MUST 仍能按该角色识别正确的消费对侧

#### Scenario: 重新导入只刷新派生元数据

- **WHEN** 同一工作区再次导入相同业务行，原始来源快照和业务行标识未变但关系派生元数据发生变化
- **THEN** 系统 MUST 更新该流水的关系派生元数据而不创建重复流水
- **AND** 既有来源行快照 MUST 保持原值

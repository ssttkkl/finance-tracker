## Purpose
User description: "SQLite 与 PostgreSQL 都作为正式运行时后端，由 FT_DATABASE_URL 显式选择且功能基本等价。 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: Explicitly Select Either Runtime Database
系统 MUST 作为 Finance Tracker 用户，我希望只通过 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite，随后使用同一套 CLI 命令完成账户、现金、转账、投资、查询和账单导入，而无需理解或切换另一套产品行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Preserve Financial and Audit Semantics Across Backends
系统 MUST 作为需要可信账务结果的用户，我希望相同输入在 PostgreSQL 和 SQLite 中产生相同的正式事实、修订、 投影和查询结果，使数据库选择不改变金额、币种、时间、幂等或审计语义。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: Operate and Validate Both Backends Deliberately
系统 MUST 作为维护者，我希望 schema 初始化、workspace provisioning、文档和测试都明确覆盖 PostgreSQL 与 SQLite，使未来持久化变更不能只在一个后端通过后被误判为完成。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: 系统 MUST 接受 PostgreSQL 和文件型 SQLite 的 `FT_DATABASE_URL` 作为正式运行时配置。
- - **FR-002**: `FT_DATABASE_URL` MUST 是唯一后端选择机制；系统 MUST NOT 自动探测、静默回退、双写、
- - **FR-003**: `FT_WORKSPACE_ID` MUST 在两个后端上保持相同的必填、隔离和未知 workspace 错误合同。
- - **FR-004**: 当前账户、现金、余额校准、转账、投资、查询、报告、转换和账单导入产品能力 MUST 在
- - **FR-005**: 相同有效输入 MUST 在两个后端产生等价的正式事实、来源关系、追加式修订、投影和规范化
- - **FR-006**: 两个后端 MUST 对金额使用精确十进制语义，拒绝非有限值、超过 18 位小数或超出支持范围
- - **FR-007**: 时间输入、UTC 保存、Asia/Shanghai 解释和日/月分桶 MUST 在两个后端保持等价。
- - **FR-008**: 写入、导入和投影更新 MUST 在两个后端保持事务原子性；异常和业务拒绝不得发布部分状态。
- - **FR-009**: 重复导入、provider 记录去重和幂等键 MUST 在两个后端产生相同结果。
- - **FR-010**: schema 初始化与版本检查 MUST 通过同一迁移入口支持两个后端，并达到同一逻辑 schema head。
- - **FR-011**: persistence adapter MAY 使用方言专用 SQL 或锁策略，但 MUST 将差异限制在适配器边界内，
- - **FR-012**: SQLite MUST 启用外键、WAL 和约 5 秒的有界锁等待；等待超时 MUST 映射为稳定、可操作的
- - **FR-013**: 系统 MUST 为所有持久化相关 Application Service 与 CLI 流程提供同一 SQLite/真实
- - **FR-014**: 文档、示例、CLI help 和配置错误 MUST 明确说明两个受支持后端、文件型 SQLite 持久化、
- - **FR-015**: 本 feature MUST NOT 恢复 CSV/YAML/Git 运行时账本，不得实现 PostgreSQL 与 SQLite 之间的
- - **FR-016**: 新建 SQLite 数据库及辅助文件 SHOULD 使用 owner-only 权限；既有文件权限过宽时系统
- - **FR-017**: 数据库配置、连接、schema、workspace 和锁错误 MUST 只输出脱敏连接摘要；任何错误、

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 当前受支持的 CLI/Application Service 契约矩阵在 SQLite 和真实 PostgreSQL 上均 100% 通过。
- - **SC-002**: 对相同确定性场景，两个后端的规范化事实、来源关系、修订、投影和查询结果比较差异为零。
- - **SC-003**: 两个后端的事务失败注入、重复导入和并发投影场景 100% 不产生部分状态、重复事实或丢失更新。
- - **SC-004**: 空 PostgreSQL 数据库和空 SQLite 文件均可从同一迁移入口达到当前 head，并成功 provision
- - **SC-005**: 缺失、非法、不受支持或不可用数据库配置的测试 100% 明确失败，且没有自动 fallback、
- - **SC-006**: 存储相关测试清单中，SQLite 集成覆盖和真实 PostgreSQL 集成覆盖均无未解释跳过项。
- - **SC-007**: README、CLI help、quickstart、constitution 和产品路线对受支持数据库及非目标的陈述零矛盾。
- - **SC-008**: SQLite 锁竞争、权限过宽和数据库连接失败测试 100% 产生约定的等待、警告或脱敏错误，

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

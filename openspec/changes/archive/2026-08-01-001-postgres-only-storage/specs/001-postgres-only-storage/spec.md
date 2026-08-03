## Purpose
User description: "产品早期快速开发阶段不需要兼容、迁移和回滚；彻底删除本地 CSV/YAML/Git 存储，以不留历史包袱为原则。 本能力的行为契约由迁移后的需求与场景持续维护。

## ADDED Requirements

### Requirement: 所有运行入口只使用 PostgreSQL
系统 MUST 作为开发者和产品使用者，我希望 CLI、Web、Worker 和 MCP 从同一个 workspace 数据库读取和写入， 使系统只有一种运行方式，不再因 backend 选择产生分叉行为。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 删除全部 legacy 存储表面
系统 MUST 作为维护者，我希望本地 repository、backend 选择、Git 账本事务和迁移兼容层从产品与代码库中消失， 使后续功能不再承担双实现、双测试和旧配置维护成本。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 原始账单直接进入统一导入流程
系统 MUST 作为用户，我仍希望导入当前 parser 已支持的银行、支付平台和券商 CSV、XLS/XLSX 或 PDF 文件，但这些 文件只作为原始输入，解析、审查和提交后的正式事实必须进入 PostgreSQL。

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：执行该用户故事的独立测试，结果符合迁移前的验收口径。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 功能需求基线
系统 MUST 保持以下迁移前功能需求；后续行为变更必须通过 OpenSpec change 明确修改。

- - **FR-001**: PostgreSQL MUST 是 CLI、Web、Worker 和 MCP 唯一受支持的运行时事实源。
- - **FR-002**: 系统 MUST 删除 `local|postgres` backend 选择以及任何隐式 local 默认值或运行时文件回退。
- - **FR-003**: 系统 MUST 删除 CSV/YAML/Git 账本 repository、当前快照和 Git 事务后端。
- - **FR-004**: 系统 MUST 删除面向旧本地账本的 inspect、import、verify、shadow comparison、cutover 和 export-to-local 兼容流程。
- - **FR-005**: 现有本地账本数据 MUST 被视为可丢弃开发数据；系统 MUST NOT 读取、迁移或自动删除它。
- - **FR-006**: 依赖 Git 文件账本语义且没有独立产品价值的命令 MUST 被删除，不保留兼容别名。
- - **FR-007**: CSV、JSON、YAML、XLS/XLSX 和 PDF MAY 作为原始导入输入，但 MUST NOT 成为运行时账本、
- - **FR-008**: 原始输入的内容摘要、来源标识、解析结果、正式提交和后续修订 MUST 在 PostgreSQL 中可追溯。
- - **FR-009**: 缺少数据库、schema 或 workspace 配置时，系统 MUST 失败关闭并提供直接错误，不得创建本地存储。
- - **FR-010**: 所有写入 MUST 使用数据库事务保证原子性；失败事务不得发布部分正式事实。
- - **FR-011**: workspace 隔离 MUST 保持；调用方不得在 repository 操作中覆盖已绑定 workspace。
- - **FR-012**: 未发布的 PostgreSQL schema 和 migration history MAY 重建为一个干净基线，不要求兼容旧开发数据库。
- - **FR-013**: 旧 backend 配置、命令、代码、测试、依赖和文档 MUST 在同一 feature 中删除，不得留下不可达兼容层。
- - **FR-014**: 本 feature MUST NOT 引入本地数据迁移、运行时回滚或双写阶段。
- - **FR-015**: 本 feature MUST NOT 同时引入财富报告、Web 认证、关系审查列表、Connector、AI 或 MCP 新能力。
- - **FR-016**: 依赖本地凭据、mapping 或事件 CSV 的 Connector sync 和文件型 reconcile MUST 从当前
- - **FR-017**: 系统 MUST 保留账户、现金、转账、投资手工写入、查询和当前 statement parser 矩阵的
- - **FR-018**: 金额和数量 MUST 以有限 `Decimal`/`NUMERIC(38,18)` 保存，持久化和中间计算 MUST NOT
- - **FR-019**: 已被正式事实引用的账户 MUST NOT 被硬删除；用户 MUST 先停用账户，只有已停用且未被任何
- - **FR-020**: 账户重命名后，既有正式事实、来源关系、修订和投影 MUST 仍通过稳定 account ID 归属于
- - **FR-021**: provider 已含 offset 的时间 MUST 按该 offset 解析；无 offset 的现有中国账单时间 MUST
- - **FR-022**: 导入的内容摘要、大小和解析结果 MUST 来自同一次不可变源文件捕获；PDF
- - **FR-023**: 并发请求 MUST 使用隔离的 database session/UoW；投影读改写 MUST 串行化，不得
- - **FR-024**: 会被同名多币种账户匹配的写命令 MUST 显式指定币种；转账源金额和跨币种目标
- - **FR-025**: 投资投影 MUST 从到账资产中扣除以到账资产计价的佣金，且 MUST 拒绝将同一
- - **FR-026**: 所有被 application service 拒绝的 CLI 写命令 MUST 以非零状态退出，不能只打印错误后

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：对该能力进行修改时，验证结果 MUST 覆盖迁移后的功能需求清单。
- THEN 系统满足该条件，并保留可复核的验证证据。
### Requirement: 可度量验收结果
系统 MUST 继续满足以下可度量结果；它们是迁移后的验收回归基线。

- - **SC-001**: 100% 的受支持运行入口在不存在本地账本目录的环境中完成正常场景，并使用同一 workspace 数据。
- - **SC-002**: 项目配置和运行入口中不存在可成功选择的 local backend；所有旧配置均明确失败。
- - **SC-003**: 执行全部受支持写入流程后，不产生新的 CSV 账本、YAML 快照或 Git 账本事务。
- - **SC-004**: 代码库中对 legacy local runtime、迁移服务和 shadow comparison 的可执行引用为零。
- - **SC-005**: 当前受支持的原始账单格式全部可进入统一导入流程，成功提交只产生 PostgreSQL 正式事实。
- - **SC-006**: 数据库事务失败注入场景 100% 不产生部分正式事实。
- - **SC-007**: 全新数据库可以从一个干净 schema 基线重复初始化，并通过全部存储集成测试。
- - **SC-008**: README、CLI help、顶层产品方案、财富报告设计和实现状态文档对运行时存储的陈述零矛盾。
- - **SC-009**: CLI help 中不存在 `commit`、`status`、`reset`、`migrate`、`append`、文件型 `reconcile`、
- - **SC-010**: 账户重命名和“有事实账户删除失败”测试 100% 保持正式事实、来源关系与投影不变。
- - **SC-011**: 金额 scale 超限和非法时间输入 100% 在事务提交前失败，合法时间 UTC 往返与
- - **SC-012**: 源文件替换、重叠账单、PDF 解密失败和提取超时的测试 100% 不产生错配来源、
- - **SC-013**: 并发 UoW/投影、同名多币种账户、非正转账、卖出佣金和成本币种冲突的回归测试
- - **SC-014**: 投资账户默认现金估值、重复 provider ID、全重叠批次目标账户、活跃空账户删除拒绝和

#### Scenario: 验收场景 1
- GIVEN 迁移前规格所描述的有效业务上下文。
- WHEN 执行以下验收条件：运行该能力的验收矩阵时，结果 MUST 满足迁移后的成功标准。
- THEN 系统满足该条件，并保留可复核的验证证据。

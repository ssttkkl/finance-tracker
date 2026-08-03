# Feature Specification: PostgreSQL-Only Runtime Storage

**Feature Branch**: `refactor/web`

**Created**: 2026-07-17

**Status**: Complete

**Input**: User description: "产品早期快速开发阶段不需要兼容、迁移和回滚；彻底删除本地 CSV/YAML/Git 存储，以不留历史包袱为原则。"

## Clarifications

### Session 2026-07-17

- Q: 是否需要保留本地账本兼容、数据迁移或运行时回滚？ → A: 不需要；当前数据可丢弃，直接删除 legacy 存储与兼容层。
- Q: 是否同时禁止 CSV 等文件格式？ → A: 只禁止其作为存储和兼容层；当前业务确实需要的原始账单输入格式可以保留。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 所有运行入口只使用 PostgreSQL (Priority: P1)

作为开发者和产品使用者，我希望 CLI、Web、Worker 和 MCP 从同一个 workspace 数据库读取和写入，
使系统只有一种运行方式，不再因 backend 选择产生分叉行为。

**Why this priority**: 单一事实源是后续财富报告、Web 写入和异步任务共享业务规则的前提。

**Independent Test**: 在不存在本地账本目录的干净环境中配置一个 workspace，运行全部受支持查询和
写入入口；确认结果互相可见，且没有创建任何 CSV 账本、YAML 快照或 Git 事务。

**Acceptance Scenarios**:

1. **Given** 数据库和 workspace 已配置，**When** 用户通过任一受支持入口查询或写入，**Then**
   操作只作用于该 workspace 的 PostgreSQL 事实源。
2. **Given** 数据库 URL、schema 或 workspace 缺失，**When** 普通命令或服务启动，**Then** 系统
   明确失败，不创建本地目录、不读取旧账本，也不回退到文件存储。
3. **Given** 同一 workspace 先通过一个入口写入，**When** 另一个入口查询，**Then** 它立即看到
   同一正式事实和修订结果。

---

### User Story 2 - 删除全部 legacy 存储表面 (Priority: P2)

作为维护者，我希望本地 repository、backend 选择、Git 账本事务和迁移兼容层从产品与代码库中消失，
使后续功能不再承担双实现、双测试和旧配置维护成本。

**Why this priority**: 仅把 PostgreSQL 设为默认但继续保留旧实现，仍会留下长期分支和误用入口。

**Independent Test**: 检查所有配置、命令、运行时入口、测试和文档；确认不存在可执行的 local backend、
本地账本迁移路径或声称支持 CSV/YAML/Git 运行时的内容。

**Acceptance Scenarios**:

1. **Given** 用户提供旧的 `backend=local`、账本目录或 Git 账本配置，**When** 应用启动，**Then**
   系统拒绝未知/废弃配置，不进入兼容模式。
2. **Given** 用户查看 CLI help 和项目文档，**When** 搜索存储方式，**Then** 只看到 PostgreSQL
   运行时，不看到迁移、切换或本地 fallback 指引。
3. **Given** 旧的 `commit/status/reset` 命令只服务于 Git 文件账本，**When** feature 完成，**Then**
   这些命令不再作为受支持产品能力存在。
4. **Given** 代码或测试仍引用本地运行时 repository，**When** 执行完成检查，**Then** feature 不得
   被声明完成。

---

### User Story 3 - 原始账单直接进入统一导入流程 (Priority: P3)

作为用户，我仍希望导入当前 parser 已支持的银行、支付平台和券商 CSV、XLS/XLSX 或 PDF 文件，但这些
文件只作为原始输入，解析、审查和提交后的正式事实必须进入 PostgreSQL。

**Why this priority**: 删除文件存储不能误伤真实的数据获取方式，但中间文件不能重新演化成第二账本。

**Independent Test**: 导入每种当前受支持的原始账单格式，验证原始文件标识和解析结果进入统一导入
流程，正式提交只产生数据库事实，不生成可运行的文件账本。

**Acceptance Scenarios**:

1. **Given** 一个受支持的原始账单文件，**When** 用户导入并确认，**Then** 系统保留来源摘要和
   原始记录关系，正式事实只写入 PostgreSQL。
2. **Given** 一个转换后的 CSV 中间产物，**When** 导入结束，**Then** 它不被注册为运行时账本、
   当前快照或事务日志。
3. **Given** 原始输入解析或校验失败，**When** 用户查看结果，**Then** 数据库不产生部分正式事实，
   且错误可定位到来源文件和记录。

### Edge Cases

- 没有数据库连接、schema 未初始化或 workspace 不存在时，所有运行入口失败关闭。
- 用户目录中仍存在旧 `~/.ft` 文件时，应用不得读取、迁移或自动删除这些文件。
- 环境变量或配置文件继续声明 `local` backend 时，系统不得忽略该值并假装成功启动。
- 原始 CSV 与正式数据库事实名称相似时，必须通过来源类型和状态明确区分，不能把中间文件当成账本。
- 数据库事务中途失败时不得产生部分正式事实；这是当前事务正确性，不是旧存储回滚兼容。
- 旧 migration、shadow comparison 或 export-to-local 测试仍通过时，说明 legacy 能力尚未删除，feature 未完成。
- 删除旧 schema 与迁移历史后，全新数据库必须能从单一基线重复初始化。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: PostgreSQL MUST 是 CLI、Web、Worker 和 MCP 唯一受支持的运行时事实源。
- **FR-002**: 系统 MUST 删除 `local|postgres` backend 选择以及任何隐式 local 默认值或运行时文件回退。
- **FR-003**: 系统 MUST 删除 CSV/YAML/Git 账本 repository、当前快照和 Git 事务后端。
- **FR-004**: 系统 MUST 删除面向旧本地账本的 inspect、import、verify、shadow comparison、cutover 和 export-to-local 兼容流程。
- **FR-005**: 现有本地账本数据 MUST 被视为可丢弃开发数据；系统 MUST NOT 读取、迁移或自动删除它。
- **FR-006**: 依赖 Git 文件账本语义且没有独立产品价值的命令 MUST 被删除，不保留兼容别名。
- **FR-007**: CSV、JSON、YAML、XLS/XLSX 和 PDF MAY 作为原始导入输入，但 MUST NOT 成为运行时账本、
  中间事实源、当前快照或回退存储；本 feature 的 required matrix 只包含开始时已有 parser 的格式。
- **FR-008**: 原始输入的内容摘要、来源标识、解析结果、正式提交和后续修订 MUST 在 PostgreSQL 中可追溯。
- **FR-009**: 缺少数据库、schema 或 workspace 配置时，系统 MUST 失败关闭并提供直接错误，不得创建本地存储。
- **FR-010**: 所有写入 MUST 使用数据库事务保证原子性；失败事务不得发布部分正式事实。
- **FR-011**: workspace 隔离 MUST 保持；调用方不得在 repository 操作中覆盖已绑定 workspace。
- **FR-012**: 未发布的 PostgreSQL schema 和 migration history MAY 重建为一个干净基线，不要求兼容旧开发数据库。
- **FR-013**: 旧 backend 配置、命令、代码、测试、依赖和文档 MUST 在同一 feature 中删除，不得留下不可达兼容层。
- **FR-014**: 本 feature MUST NOT 引入本地数据迁移、运行时回滚或双写阶段。
- **FR-015**: 本 feature MUST NOT 同时引入财富报告、Web 认证、关系审查列表、Connector、AI 或 MCP 新能力。
- **FR-016**: 依赖本地凭据、mapping 或事件 CSV 的 Connector sync 和文件型 reconcile MUST 从当前
  产品入口移除；它们只能在后续具备 PostgreSQL-native 状态与审计模型的独立 feature 中重新引入。
- **FR-017**: 系统 MUST 保留账户、现金、转账、投资手工写入、查询和当前 statement parser 矩阵的
  核心产品能力；原始 statement MUST 直接进入 PostgreSQL import use case，不再以 converted CSV
  `append` 作为正式提交路径；导入 MUST 显式指定目标账户，不从本地 mapping 猜测。投资账户自身的
  `currency` MUST 至少作为该账户的现金币种参与组合估值，不能被当作需要行情的证券 ticker。
- **FR-018**: 金额和数量 MUST 以有限 `Decimal`/`NUMERIC(38,18)` 保存，持久化和中间计算 MUST NOT
  舍入或经过 float；超过 18 位小数的输入 MUST 明确拒绝，只有展示输出 MAY 按现有币种规则舍入。
- **FR-019**: 已被正式事实引用的账户 MUST NOT 被硬删除；用户 MUST 先停用账户，只有已停用且未被任何
  事实引用的空账户才可删除，且删除失败不得改变事实或投影。
- **FR-020**: 账户重命名后，既有正式事实、来源关系、修订和投影 MUST 仍通过稳定 account ID 归属于
  同一账户，不得依赖旧名称字符串维持关系。
- **FR-021**: provider 已含 offset 的时间 MUST 按该 offset 解析；无 offset 的现有中国账单时间 MUST
  按 workspace `Asia/Shanghai` 解释，正式事实统一保存为 UTC `timestamptz`，查询再按 workspace 时区分桶。
- **FR-022**: 导入的内容摘要、大小和解析结果 MUST 来自同一次不可变源文件捕获；PDF
  解密与文本提取只能在权限受限的临时目录中进行，不得在原账单旁生成明文 sidecar；密码
  MUST NOT 通过 CLI 参数值或子进程参数暴露。同一 provider 记录标识在重叠账单或同一文件中只能
  投影一次；每个 import batch MUST 独立保留其显式目标账户，即使该批次全部记录都与旧批次重叠。
- **FR-023**: 并发请求 MUST 使用隔离的 database session/UoW；投影读改写 MUST 串行化，不得
  因后写覆盖丢失已提交的事实增量。
- **FR-024**: 会被同名多币种账户匹配的写命令 MUST 显式指定币种；转账源金额和跨币种目标
  金额 MUST 大于零，不得以 truthiness 替换用户显式输入的零值。
- **FR-025**: 投资投影 MUST 从到账资产中扣除以到账资产计价的佣金，且 MUST 拒绝将同一
  ticker 的不同成本币种直接相加。未显式时间 MUST 按 workspace `Asia/Shanghai` 生成。
- **FR-026**: 所有被 application service 拒绝的 CLI 写命令 MUST 以非零状态退出，不能只打印错误后
  返回成功；正常业务拒绝与输入解析异常在脚本调用中必须同样可检测。

### Key Entities

- **Workspace**: 唯一运行时账本边界；所有正式事实、来源记录和审计事件均归属于一个 workspace。
- **Source Artifact**: 用户提供的原始输入文件及其内容摘要；它是来源证据，不是正式账本。
- **Formal Fact**: 账户、现金交易、投资事件、快照等正式财务事实，只存在于 PostgreSQL。
- **Revision**: 正式事实的追加式审计记录，包含变更前后值、操作者、原因和时间。
- **Import Batch**: 一次原始输入解析与提交尝试，负责把来源记录与正式事实关联起来。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% 的受支持运行入口在不存在本地账本目录的环境中完成正常场景，并使用同一 workspace 数据。
- **SC-002**: 项目配置和运行入口中不存在可成功选择的 local backend；所有旧配置均明确失败。
- **SC-003**: 执行全部受支持写入流程后，不产生新的 CSV 账本、YAML 快照或 Git 账本事务。
- **SC-004**: 代码库中对 legacy local runtime、迁移服务和 shadow comparison 的可执行引用为零。
- **SC-005**: 当前受支持的原始账单格式全部可进入统一导入流程，成功提交只产生 PostgreSQL 正式事实。
- **SC-006**: 数据库事务失败注入场景 100% 不产生部分正式事实。
- **SC-007**: 全新数据库可以从一个干净 schema 基线重复初始化，并通过全部存储集成测试。
- **SC-008**: README、CLI help、顶层产品方案、财富报告设计和实现状态文档对运行时存储的陈述零矛盾。
- **SC-009**: CLI help 中不存在 `commit`、`status`、`reset`、`migrate`、`append`、文件型 `reconcile`、
  CSV snapshot `verify --fix` 或 local-backed Connector sync。
- **SC-010**: 账户重命名和“有事实账户删除失败”测试 100% 保持正式事实、来源关系与投影不变。
- **SC-011**: 金额 scale 超限和非法时间输入 100% 在事务提交前失败，合法时间 UTC 往返与
  Asia/Shanghai 日/月分桶测试全部通过。
- **SC-012**: 源文件替换、重叠账单、PDF 解密失败和提取超时的测试 100% 不产生错配来源、
  重复正式事实、原目录明文 sidecar 或暴露密码的子进程参数。
- **SC-013**: 并发 UoW/投影、同名多币种账户、非正转账、卖出佣金和成本币种冲突的回归测试
  全部通过。
- **SC-014**: 投资账户默认现金估值、重复 provider ID、全重叠批次目标账户、活跃空账户删除拒绝和
  投资 CLI 业务失败退出码的回归测试全部通过。

## Assumptions

- 产品尚未上线，现有本地账本和开发数据库数据均可丢弃。
- 不存在需要兼容的外部用户、稳定公开 API 或生产数据库。
- “无迁移”同时包括不保留 Phase 2 的本地账本迁移服务、shadow comparison 和 cutover 流程。
- “无回滚”指不提供旧存储运行时回退；数据库事务自身的失败原子性和正式财务审计仍是强约束。
- 原始账单文件格式是数据输入边界，不属于被删除的本地存储 backend。
- JSON/YAML 不属于 feature 开始时的 statement parser 矩阵；FR-007 只允许未来 parser 把文件作为输入，
  不要求本 feature 新增这些 provider/format。
- 未来进入真实用户数据阶段时，将通过 constitution amendment 重新引入备份、schema 演进和灾难恢复要求。

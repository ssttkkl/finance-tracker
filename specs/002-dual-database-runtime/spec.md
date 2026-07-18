# Feature Specification: PostgreSQL and SQLite Runtime Parity

**Feature Branch**: `refactor/web`

**Created**: 2026-07-18

**Status**: Draft

**Input**: User description: "SQLite 与 PostgreSQL 都作为正式运行时后端，由 FT_DATABASE_URL 显式选择且功能基本等价。"

## Clarifications

### Session 2026-07-18

- Q: SQLite 是仅用于开发测试，还是与 PostgreSQL 一样作为正式运行时后端？ → A: 两者都是正式运行时
  后端，由 `FT_DATABASE_URL` 显式选择且功能基本等价。
- Q: SQLite 写锁冲突如何处理？ → A: 启用 WAL、外键和约 5 秒有界等待；超时后返回稳定的数据库繁忙
  错误，不自动重放 Application Service。
- Q: SQLite 文件权限过宽时是否阻断？ → A: 新文件尽量使用 owner-only 权限；既有文件权限过宽时
  明确警告并给出修复建议，但不阻断启动。
- Q: 数据库 URL 在错误和日志中如何脱敏？ → A: 只显示 dialect 与最小脱敏连接摘要，不显示密码、
  查询参数或完整 SQLite 路径。
- Q: 双后端验证覆盖到什么范围？ → A: 所有持久化相关 Application/CLI 合同运行共享 SQLite/真实
  PostgreSQL 矩阵；纯领域和 parser 测试只运行一次。

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Explicitly Select Either Runtime Database (Priority: P1)

作为 Finance Tracker 用户，我希望只通过 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite，随后使用同一套
CLI 命令完成账户、现金、转账、投资、查询和账单导入，而无需理解或切换另一套产品行为。

**Why this priority**: 显式选择且两种后端都能完成当前核心工作流，是双数据库支持的最小可用闭环。

**Independent Test**: 分别从空 PostgreSQL 数据库和空 SQLite 文件初始化同名 workspace，执行当前支持的
核心 CLI 工作流；两个环境都成功，且每个进程只连接 URL 指定的数据库。

**Acceptance Scenarios**:

1. **Given** 一个有效的 PostgreSQL URL、当前 schema 和已存在 workspace，**When** 用户运行任一当前
   受支持 CLI 命令，**Then** 命令只读取或写入该 PostgreSQL 数据库。
2. **Given** 一个有效的文件型 SQLite URL、当前 schema 和已存在 workspace，**When** 用户运行同一
   CLI 命令，**Then** 命令只读取或写入该 SQLite 文件，用户可见结果与 PostgreSQL 场景等价。
3. **Given** 缺失、格式错误或不是 PostgreSQL/SQLite 的 URL，**When** 应用启动，**Then** 系统在执行
   业务操作前明确失败，且不尝试其他后端。
4. **Given** 已选择的数据库不可达、只读、锁定、schema 过期或 workspace 不存在，**When** 用户运行
   命令，**Then** 系统明确失败，不创建其他数据库、不切换后端，也不发布部分正式事实。

---

### User Story 2 - Preserve Financial and Audit Semantics Across Backends (Priority: P2)

作为需要可信账务结果的用户，我希望相同输入在 PostgreSQL 和 SQLite 中产生相同的正式事实、修订、
投影和查询结果，使数据库选择不改变金额、币种、时间、幂等或审计语义。

**Why this priority**: 双后端只有在账务结果等价时才是真正兼容；仅能连接 SQLite 但结果分叉不可接受。

**Independent Test**: 对两个全新数据库运行同一组确定性账户、现金、转账、投资、导入、重命名、停用和
失败注入场景，比较规范化后的事实、来源关系、修订、投影、报告和错误合同。

**Acceptance Scenarios**:

1. **Given** 相同 workspace、业务输入和受控时间，**When** 两个后端分别完成同一成功工作流，**Then**
   除数据库生成的内部标识与物理存储细节外，规范化业务结果完全相同。
2. **Given** 相同的非法金额、币种冲突、重复导入或业务拒绝，**When** 两个后端分别处理，**Then**
   它们以相同错误类别和非零 CLI 状态失败，并保持相同的持久化前后状态。
3. **Given** 写入中途发生异常，**When** 事务退出，**Then** 两个后端都不留下部分事实、部分投影或
   错误的 import batch 状态。
4. **Given** 两个独立请求更新同一 workspace 投影，**When** 后端允许操作完成，**Then** 已提交增量
   不会因后写覆盖而丢失；SQLite 可以串行化或明确返回可重试的锁错误，但不得产生错误账务结果。

---

### User Story 3 - Operate and Validate Both Backends Deliberately (Priority: P3)

作为维护者，我希望 schema 初始化、workspace provisioning、文档和测试都明确覆盖 PostgreSQL 与
SQLite，使未来持久化变更不能只在一个后端通过后被误判为完成。

**Why this priority**: 没有持续的双后端门禁，功能等价会随着后续 schema 和查询变更快速退化。

**Independent Test**: 从干净数据库执行迁移、provisioning、CLI quickstart、完整契约矩阵和失败场景；
SQLite 自动化矩阵与真实 PostgreSQL 矩阵均有独立结果，任一失败都会阻断完成声明。

**Acceptance Scenarios**:

1. **Given** 一个空 PostgreSQL 数据库或空 SQLite 文件，**When** 运行同一 schema 初始化入口，**Then**
   两者都达到同一逻辑 schema head，并能 provision workspace。
2. **Given** 一项涉及模型、repository、查询、事务或迁移的变更，**When** 执行验证，**Then** 同一契约
   矩阵必须分别在 SQLite 和真实 PostgreSQL 上通过。
3. **Given** 用户阅读 README、CLI help 和 quickstart，**When** 选择任一后端，**Then** 能找到准确的
   URL、初始化、限制和故障排查说明，且不会看到自动回退、双写或隐式迁移承诺。

### Edge Cases

- `sqlite+pysqlite:///:memory:` 仅用于显式的短生命周期测试，不作为需要跨进程保留账务数据的正式配置；
  常规 SQLite 运行必须使用文件型 URL。
- SQLite 文件父目录不存在、路径不可写、文件被锁或位于不支持可靠锁语义的共享文件系统时，系统必须
  给出可操作错误，不得退回 PostgreSQL、内存数据库或文件账本。
- SQLite 文件或辅助文件权限允许同机其他用户读取时，系统必须警告并给出 owner-only 修复建议；
  警告不得包含完整文件路径，也不得在未获用户指示时自动修改既有文件权限。
- PostgreSQL 与 SQLite 的自动生成标识、时间默认值或错误原文不同，比较时必须使用稳定的业务字段与
  统一错误类别，不能把方言文案误当成业务合同。
- SQLite 可以采用单写者串行化，PostgreSQL 可以支持更高并发；吞吐和锁等待属于允许的运行差异，
  丢失更新、部分提交或不同账务结果不属于允许差异。
- 一个数据库已初始化而另一个没有时，应用不得复制数据、推断用户意图或同时连接两个后端。
- 旧 CSV/YAML/Git 文件账本仍不是 SQLite 支持的一部分，不能以 SQLite 兼容名义恢复。
- 跨 PostgreSQL 与 SQLite 的数据搬运、同步、备份互转和灾难恢复不在本 feature 范围内。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 MUST 接受 PostgreSQL 和文件型 SQLite 的 `FT_DATABASE_URL` 作为正式运行时配置。
- **FR-002**: `FT_DATABASE_URL` MUST 是唯一后端选择机制；系统 MUST NOT 自动探测、静默回退、双写、
  shadow compare 或根据文件是否存在改变后端。
- **FR-003**: `FT_WORKSPACE_ID` MUST 在两个后端上保持相同的必填、隔离和未知 workspace 错误合同。
- **FR-004**: 当前账户、现金、余额校准、转账、投资、查询、报告、转换和账单导入产品能力 MUST 在
  PostgreSQL 与 SQLite 上使用相同 Application Service 和 CLI 合同。
- **FR-005**: 相同有效输入 MUST 在两个后端产生等价的正式事实、来源关系、追加式修订、投影和规范化
  查询结果。
- **FR-006**: 两个后端 MUST 对金额使用精确十进制语义，拒绝非有限值、超过 18 位小数或超出支持范围
  的值；持久化和中间计算 MUST NOT 经过二进制浮点。
- **FR-007**: 时间输入、UTC 保存、Asia/Shanghai 解释和日/月分桶 MUST 在两个后端保持等价。
- **FR-008**: 写入、导入和投影更新 MUST 在两个后端保持事务原子性；异常和业务拒绝不得发布部分状态。
- **FR-009**: 重复导入、provider 记录去重和幂等键 MUST 在两个后端产生相同结果。
- **FR-010**: schema 初始化与版本检查 MUST 通过同一迁移入口支持两个后端，并达到同一逻辑 schema head。
- **FR-011**: persistence adapter MAY 使用方言专用 SQL 或锁策略，但 MUST 将差异限制在适配器边界内，
  并保持 Application Service、领域对象和 CLI 行为不分叉。
- **FR-012**: SQLite MUST 启用外键、WAL 和约 5 秒的有界锁等待；等待超时 MUST 映射为稳定、可操作的
  数据库繁忙错误。系统 MUST NOT 自动重放 Application Service，也不得因锁超时或并发写入产生丢失
  更新或重复事实。
- **FR-013**: 系统 MUST 为所有持久化相关 Application Service 与 CLI 流程提供同一 SQLite/真实
  PostgreSQL 契约矩阵；mocks 或仅 SQLite 测试不能替代 PostgreSQL 证据，纯领域与 parser 测试不要求
  按数据库重复运行。
- **FR-014**: 文档、示例、CLI help 和配置错误 MUST 明确说明两个受支持后端、文件型 SQLite 持久化、
  允许的运行差异，以及不支持自动回退、双写和隐式跨后端迁移。
- **FR-015**: 本 feature MUST NOT 恢复 CSV/YAML/Git 运行时账本，不得实现 PostgreSQL 与 SQLite 之间的
  数据迁移、同步或复制，也不得夹带新的 Web、Worker、MCP 或财富分析能力。
- **FR-016**: 新建 SQLite 数据库及辅助文件 SHOULD 使用 owner-only 权限；既有文件权限过宽时系统
  MUST 警告并提供修复建议，但 MUST NOT 自动修改权限或阻断启动。
- **FR-017**: 数据库配置、连接、schema、workspace 和锁错误 MUST 只输出脱敏连接摘要；任何错误、
  日志或 CLI 输出 MUST NOT 包含数据库密码、URL 查询参数或完整 SQLite 文件路径。

### Key Entities

- **Runtime Database Selection**: 由单个 `FT_DATABASE_URL` 表示的一次性后端选择；只指向 PostgreSQL
  或文件型 SQLite，不携带 fallback 顺序。
- **Workspace**: 两个后端共同的账本隔离边界；所有正式事实、来源、修订和投影归属于一个 workspace。
- **Logical Schema Baseline**: 两个后端共享的实体、约束、关系和迁移 head；允许物理类型或方言实现不同。
- **Parity Contract**: 对同一业务场景定义输入、规范化结果、错误类别和状态不变量的跨后端验证合同。
- **Operational Difference**: 不改变财务或用户可见业务语义的后端差异，例如部署、吞吐、锁等待和
  数据库生成的内部元数据。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 当前受支持的 CLI/Application Service 契约矩阵在 SQLite 和真实 PostgreSQL 上均 100% 通过。
- **SC-002**: 对相同确定性场景，两个后端的规范化事实、来源关系、修订、投影和查询结果比较差异为零。
- **SC-003**: 两个后端的事务失败注入、重复导入和并发投影场景 100% 不产生部分状态、重复事实或丢失更新。
- **SC-004**: 空 PostgreSQL 数据库和空 SQLite 文件均可从同一迁移入口达到当前 head，并成功 provision
  workspace 与完成 quickstart。
- **SC-005**: 缺失、非法、不受支持或不可用数据库配置的测试 100% 明确失败，且没有自动 fallback、
  第二数据库连接或 legacy 文件账本写入。
- **SC-006**: 存储相关测试清单中，SQLite 集成覆盖和真实 PostgreSQL 集成覆盖均无未解释跳过项。
- **SC-007**: README、CLI help、quickstart、constitution 和产品路线对受支持数据库及非目标的陈述零矛盾。
- **SC-008**: SQLite 锁竞争、权限过宽和数据库连接失败测试 100% 产生约定的等待、警告或脱敏错误，
  且日志与 CLI 捕获中不存在密码、查询参数或完整 SQLite 路径。

## Assumptions

- “功能基本等价”覆盖当前已交付的 CLI、Application Service、财务语义、审计与错误合同，不要求两个
  数据库具备相同吞吐、锁实现、部署模型或原始数据库错误文案。
- SQLite 正式运行使用本地文件持久化；内存 SQLite 只用于显式短生命周期测试。
- 现有 PostgreSQL 数据保留在原数据库中，本 feature 不负责复制到 SQLite；现有 SQLite 测试数据也
  不承诺迁移到 PostgreSQL。
- 项目仍处于未上线开发阶段，可以在本 feature 内重建尚未发布的迁移基线，但最终 head 必须同时支持
  两个后端。
- 当前 PostgreSQL adapter 中已通过 SQLite 快速测试的共享 SQLAlchemy 模型和 repository 是可复用起点，
  但只有正式运行时验证和跨后端契约完成后，SQLite 才算受支持。

### Out of Scope

- PostgreSQL 与 SQLite 之间的数据导入、导出、迁移、同步、双写或自动故障切换。
- 恢复旧 CSV/YAML/Git backend、旧本地账本或 shadow comparison。
- 为 SQLite 承诺 PostgreSQL 等级的并发吞吐、远程访问、高可用或多进程写扩展能力。
- 新增 Web、Worker、MCP、Connector、财富归因或其他与双数据库等价无关的产品能力。

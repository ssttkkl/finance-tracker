<!--
Sync Impact Report
- Version change: 3.0.0 -> 3.1.0
- Modified principles: none
- Modified sections:
  - 交付与评审流程 — 补充 Spec 演进与 artifact 对齐要求
- Added sections:
  - Spec 演进策略 — 默认 Flow-Forward、活跃 Living、实现期 Flow-Back
- Removed sections: none
- Templates/commands:
  - ✅ AGENTS.md / CLAUDE.md — 增加 Spec 演进操作规则
  - ✅ docs/README.md — Spec Kit 索引说明演进约定
  - ✅ .specify/templates/* — 无结构性变更；Constitution Check 与现有门禁仍适用
  - ✅ .agents/skills/speckit-*/SKILL.md — 无需改名或路径调整
- Follow-up TODOs:
  - ⚠ README.md — 保持描述当前已交付运行时行为；与 feature 交付同步
  - ⚠ docs/productization-wealth-report-design.md — 后续财富 feature 完成后同步基线
  - ⚠ specs/001-postgres-only-storage — 继续作为已完成历史 feature 保留，不回写新需求
-->

# Finance Tracker Constitution

## Core Principles

### I. 财务正确性与可审计性

所有金额计算 MUST 使用精确十进制语义，币种、精度和舍入规则 MUST 显式定义；不得用二进制浮点
或隐式单位转换承载账务结果。导入、转换、核销、迁移和同步 MUST 保留来源、关键决策与可复核证据，
不得静默丢弃、重复记账或覆盖历史事实。相同输入的重复处理 MUST 幂等；无法证明安全的异常路径
MUST 失败关闭并给出可操作错误。任何汇总或快照 MUST 能追溯到原始记录和确定性规则。

### II. Spec Kit 规格驱动

领域行为、Bug 修复、跨模块重构、公共接口、财务规则、数据模型、持久化、迁移以及 CLI/Web/
Worker/MCP 能力的变更，MUST 由一个目标单一的 Spec Kit feature 驱动。`spec.md` 定义需求和验收，
`plan.md` 及设计产物定义技术决策，`tasks.md` 定义执行顺序与状态；实现不得引入 artifacts 中没有的
行为。实施中发现的新事实 MUST 先回写正确 artifact 并重新检查一致性。每个 feature MUST 可独立
测试、可审查、可回滚；过大范围 MUST 拆分。

### III. 测试先行与验证证据

所有可执行行为、财务计算、数据与迁移、兼容性和接口变更 MUST 先有能因目标行为缺失而失败的
自动化测试，再写最小实现并使其通过。测试 MUST 覆盖正常、边界、错误、重复执行和恢复路径；
涉及存储或外部边界时 MUST 包含相称的集成测试。交付前 MUST 运行受影响测试、完整测试套件及
项目提供的类型检查、lint 和构建，并检查最终 diff。无法运行的验证 MUST 报告准确原因、风险和
补跑命令，不得用推测代替证据。

### IV. 显式数据库选择与行为等价

PostgreSQL 与 SQLite MUST 都是正式支持的运行时事实源；每个进程 MUST 仅通过
`FT_DATABASE_URL` 显式选择其中一个后端，不得自动探测、静默回退、双写或 shadow compare。
两个后端 MUST 对相同 Application Service、CLI 契约、财务语义、金额精度、事务原子性、幂等、
来源审计和 workspace 隔离提供等价结果。数据库方言、并发能力、部署方式和性能等不可避免的运行差异
MUST 在 feature artifacts 与操作文档中明确列出，但不得成为账务结果或用户可见业务行为分叉的理由。
所有持久化、schema 或查询变更 MUST 同时提供 SQLite 自动化集成证据与真实 PostgreSQL 集成证据。
跨后端数据复制或迁移不是隐式运行时职责；若产品需要，MUST 由独立 feature 定义可审计、可恢复的
显式流程。CSV/YAML/Git 文件账本和其他 legacy backend 仍不得作为运行时事实源或数据库回退。

### V. 清晰边界与最小复杂度

领域规则 MUST 与 CLI、Web、数据库和第三方供应商边界解耦，通过明确接口传递数据。新增抽象、
依赖或基础设施 MUST 对应当前 spec 的具体需求，并在 plan 中说明为何现有结构不足；不得为假设中的
未来场景预建框架。修改 MUST 聚焦当前 feature，复用现有模式，不得夹带无关重构。边界处 MUST
验证输入、显式处理错误，并避免把凭据、隐私数据或原始财务数据写入不受控日志与仓库产物。

## 工程约束

- 运行时基线为 Python 3.11+；依赖与命令以 `pyproject.toml` 和 `uv` 工作流为准。
- PostgreSQL 与 SQLite 是 CLI、Web、Worker 和 MCP 正式支持的运行时事实源；调用方必须通过
  `FT_DATABASE_URL` 显式选择一个后端。任何新能力不得依赖或扩展 CSV/YAML/Git 文件账本 backend。
- CSV、JSON、YAML 和 PDF 只可作为当前产品明确需要的原始账单输入格式；不得作为 repository、当前
  快照、事务日志、运行时 backend、迁移载体或兼容回退。原始文件的身份、摘要、导入状态和审计
  关系 MUST 由当前显式选择的数据库管理。
- PostgreSQL 与 SQLite MUST 共享同一逻辑 schema 基线与迁移入口；方言专用实现 MUST 局限在
  persistence adapter 内，并由等价性测试证明不会改变领域结果、审计关系或错误合同。
- 未发布的 schema 和 Alembic 历史 MAY 在 feature 中重建为干净基线；测试数据 MUST 可重复生成并
  去标识化。不得为可丢弃开发数据引入隐式跨后端迁移或长期兼容层。
- 金额、币种、时间、账户身份和外部记录 ID 的语义 MUST 在 spec 与数据模型中明确，不得依赖字符串
  猜测或隐式默认值。
- 凭据、Token、账户隐私和原始账单 MUST 保持在受控存储中；测试夹具与日志 MUST 去标识化。
- 文档、示例、迁移说明和 CLI help MUST 与已交付行为同步。
- Feature 目录采用 sequential 编号（`specs/00N-short-name/`）；`.specify/feature.json` 指向当前活跃
  feature。Complete feature 的目录 MUST 保留为历史记录，除非用户明确批准归档删除。

## Spec 演进策略

本仓库采用 Spec Kit 的三种 artifact 演进模型；选择规则如下，且 MUST 保持 artifacts 与代码一致。

### 默认：Flow-Forward

新能力、新边界、目标已变或既有 feature 已 Complete 时，MUST 新建 `specs/00N-...` 目录并走完整
specify → clarify → plan → tasks → analyze → implement → converge 流程。已完成 feature（例如
`001-postgres-only-storage`）MUST 只读保留，不得回写新需求。新 feature 若扩展或取代先前工作，
SHOULD 在 Context 中 cross-link 相关目录（Supersedes / Extends）。

### 活跃 feature：Living Spec

同一目标、同一活跃 feature 内的范围、验收或财务语义变化时，MUST 先修订当前 `spec.md`，再同步
`plan.md` / `tasks.md` 及适用设计产物，并在恢复实施前运行 `$speckit-analyze`。大改 artifacts 前
SHOULD 使用干净工作树或独立分支，使生成物 diff 可审查。替换派生产物前 MUST 保留仍有效的关键
技术决策与理由。

### 实现期：Flow-Back 纪律

实施或评审中的发现可先落在最近正确的 artifact 或代码，但 MUST 立刻判断它改变的是行为、策略、
任务还是仅实现细节，并回写所有与之冲突的 artifacts。不得让 code 或仅 `tasks.md` 成为唯一事实源
而 `spec.md` 过期。主 session 负责回写；implementer 发现缺口 MUST 停下来返回主 session，不得静默
扩大范围。

### 项目文件刷新

升级 Spec Kit 共享项目文件时，MUST 先备份 `.specify/memory/constitution.md` 与已定制
templates/skills，再执行约定的 `specify init --here --force ...`，随后恢复并复核定制。`specs/`
不属于模板包，不得用升级覆盖 feature artifacts。

## 交付与评审流程

每个受约束变更 MUST 依次完成 specify、clarify、plan、tasks、analyze、implement 和 converge，并
按上节选择 Flow-Forward 或 Living Spec。用户可见产品方向使用 gstack 产品挑战，跨模块或高风险
技术方案使用 gstack 架构挑战；采纳结论 MUST 回写 Spec Kit artifacts。所有代码变更 MUST 通过
gstack code review；Web 行为变更 MUST 通过浏览器 QA。发布、合并、部署和线上验证只在获得用户明确
授权后执行。任何 CRITICAL/HIGH 规格分析问题、阻断性 review finding、失败测试或未解释的
constitution 违例都会阻断下一阶段。

## Governance

本 constitution 高于 feature spec、plan、tasks 和临时会话指令中的工程约定。修订 MUST 使用
`$speckit-constitution`，记录理由、同步影响和迁移要求，并按语义化版本管理：删除或重定义原则升
MAJOR，新增原则或实质扩展升 MINOR，澄清文字升 PATCH。每个 plan MUST 在研究前与设计后执行
Constitution Check；`$speckit-analyze` 和代码评审 MUST 把违反 MUST 的问题视为阻断项。例外必须
由用户明确批准、写入 plan 的 Complexity Tracking，并包含到期或消除路径。

**Version**: 3.1.0 | **Ratified**: 2026-07-17 | **Last Amended**: 2026-07-21

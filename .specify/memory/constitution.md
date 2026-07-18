<!--
Sync Impact Report
- Version change: 1.0.0 -> 2.0.0
- Modified principles:
  - IV. 兼容、迁移与可回滚演进 -> IV. 单一事实源与零历史包袱
- Modified sections:
  - 工程约束 — PostgreSQL 直接成为唯一运行时事实源，删除 legacy backend 与迁移兼容层
- Added sections: none
- Removed sections: none
- Templates/commands:
  - ✅ .specify/templates/plan-template.md — Constitution Check 可承载破坏性替换与旧代码删除门禁
  - ✅ .specify/templates/spec-template.md — 现有场景、边界和成功标准足以描述不兼容替换
  - ✅ .specify/templates/tasks-template.md — 已要求行为变更、数据和接口测试先行
  - ✅ .agents/skills/speckit-*/SKILL.md — 均以 constitution 为最高项目约束，无存储默认值冲突
  - ✅ docs/productization-refactor-plan.md — 顶层路线改为 PostgreSQL-only，并引用财富报告设计
  - ✅ docs/productization-wealth-report-design.md — Phase 1/2 基线和 PostgreSQL-only 决策已同步
  - ⚠ README.md — 保持描述当前已交付行为；待 postgres-only feature 实施完成后同步改写
- Follow-up TODOs:
  - ✅ specs/001-postgres-only-storage 已完成旧后端、迁移兼容层和相关文档包袱删除
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

### IV. 单一事实源与零历史包袱

项目当前处于未上线的快速开发阶段，legacy 运行时、未发布 CLI 契约和本地开发数据不享有兼容、
迁移或回滚承诺。PostgreSQL MUST 是唯一运行时事实源；CSV/YAML/Git 账本 backend、双后端选择、
shadow compare、旧数据迁移和文件账本回退 MUST 在同一 feature 中删除，不得以“兼容”为理由长期
保留不可达代码、抽象、配置、测试或文档。破坏性替换 MUST 在 spec 中明确范围，并以全新数据库
基线和可重复测试数据验证；现有本地数据视为可丢弃开发数据，应用不得读取、迁移或自动删除它。
该原则不削弱财务审计：正式 PostgreSQL 数据的来源、修订、金额精度和可追溯性仍 MUST 满足原则 I。
当项目明确进入需要保护真实用户持久化数据的阶段时，MUST 再次修订 constitution，恢复 schema
演进、备份、迁移和灾难恢复门禁。

### V. 清晰边界与最小复杂度

领域规则 MUST 与 CLI、Web、数据库和第三方供应商边界解耦，通过明确接口传递数据。新增抽象、
依赖或基础设施 MUST 对应当前 spec 的具体需求，并在 plan 中说明为何现有结构不足；不得为假设中的
未来场景预建框架。修改 MUST 聚焦当前 feature，复用现有模式，不得夹带无关重构。边界处 MUST
验证输入、显式处理错误，并避免把凭据、隐私数据或原始财务数据写入不受控日志与仓库产物。

## 工程约束

- 运行时基线为 Python 3.11+；依赖与命令以 `pyproject.toml` 和 `uv` 工作流为准。
- PostgreSQL 是 CLI、Web、Worker 和 MCP 唯一受支持的运行时事实源。postgres-only feature 完成前，
  任何新能力不得依赖或扩展现有 CSV/YAML/Git backend。
- CSV、JSON、YAML 和 PDF 只可作为当前产品明确需要的原始账单输入格式；不得作为 repository、当前
  快照、事务日志、运行时 backend、迁移载体或兼容回退。原始文件的身份、摘要、导入状态和审计
  关系 MUST 由 PostgreSQL 管理。
- 未发布的 schema 和 Alembic 历史 MAY 在 feature 中重建为干净基线；不得为可丢弃开发数据保留旧
  schema、迁移链或适配层。测试数据 MUST 可重复生成并去标识化。
- 金额、币种、时间、账户身份和外部记录 ID 的语义 MUST 在 spec 与数据模型中明确，不得依赖字符串
  猜测或隐式默认值。
- 凭据、Token、账户隐私和原始账单 MUST 保持在受控存储中；测试夹具与日志 MUST 去标识化。
- 文档、示例、迁移说明和 CLI help MUST 与已交付行为同步。

## 交付与评审流程

每个受约束变更 MUST 依次完成 specify、clarify、plan、tasks、analyze、implement 和 converge。
用户可见产品方向使用 gstack 产品挑战，跨模块或高风险技术方案使用 gstack 架构挑战；采纳结论
MUST 回写 Spec Kit artifacts。所有代码变更 MUST 通过 gstack code review；Web 行为变更 MUST
通过浏览器 QA。发布、合并、部署和线上验证只在获得用户明确授权后执行。任何 CRITICAL/HIGH
规格分析问题、阻断性 review finding、失败测试或未解释的 constitution 违例都会阻断下一阶段。

## Governance

本 constitution 高于 feature spec、plan、tasks 和临时会话指令中的工程约定。修订 MUST 使用
`$speckit-constitution`，记录理由、同步影响和迁移要求，并按语义化版本管理：删除或重定义原则升
MAJOR，新增原则或实质扩展升 MINOR，澄清文字升 PATCH。每个 plan MUST 在研究前与设计后执行
Constitution Check；`$speckit-analyze` 和代码评审 MUST 把违反 MUST 的问题视为阻断项。例外必须
由用户明确批准、写入 plan 的 Complexity Tracking，并包含到期或消除路径。

**Version**: 2.0.0 | **Ratified**: 2026-07-17 | **Last Amended**: 2026-07-17

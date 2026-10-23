# 文档索引

当前行为以 [`openspec/project-context.md`](../openspec/project-context.md)、`openspec/specs/` 和实现为准；尚未同步的变更另查 active change。本目录保留流程概览、业务规则说明、接口与数据参考、运行配置及 UI 约束，不再维护已退役实现的架构分析或逐步开发日志。现金导入后端已完成，UI 留后续独立任务；后端接口可用不代表现有客户端已接通。

运行时通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或文件型 SQLite。两者共享应用服务和数据合同；不得回退、不双写、不隐式迁移。迁移版本以 Alembic 当前 head 和 [`SCHEMA_REVISION`](../src/ft/adapters/relational/runtime.py) 为准，不在文档中另存版本副本。SQLite 写入繁忙时应重试，目录或文件权限错误须先修正，不能改用另一数据库掩盖失败。

## 当前能力基线

- 运行时、账户与账本：`runtime-database`、`multi-currency-accounts`、`ledger-records`
- 现金导入、分类与关系：`statement-import`、`cash-record-classification`、`transaction-relations`
- 投资事件、对账单导入、估值与同步：`investment-event-model`、`investment-statement-import`、`portfolio-valuation`、`investment-connector-sync`

当前规格和 active changes 分别通过 `openspec list --specs` 与 `openspec list` 查看。

## 使用与架构

| 文档 | 说明 |
|---|---|
| [项目说明](../README.md) | 环境准备、后端、Compose 客户端、API 与开发验证 |
| [Compose 功能地图](feature-map.md) | Compose 页面、实现证据与当前 QA 覆盖 |
| [导入、关系与同步流程](import-flow.md) | 当前主要导入和数据提交流程 |
| [现金账单导入流程](cash-import-flow.md) | 现金导入主阅读入口：业务、后端执行、接口与数据衔接、局部重建及失败恢复 |
| [UI 设计约束](ui-design-rules.md) | Compose UI 实现与审查规则 |
| [设计系统组件登记](design-system/registry.yaml) | 当前共享展示辅助项 |

## 现金账单导入阅读顺序

先完整阅读 [现金账单导入流程](cash-import-flow.md)。业务部分解释用户的一次导入与后续补处理，技术部分解释后端如何承载这些规则；两部分在同一条链路中衔接。需要精确规则或参数时，再查以下参考。

| 参考文档 | 只在这里维护的细节 |
|---|---|
| [业务规则与设计取舍](cash-import-redesign.md) | 字段白名单、流水与关系状态、来源身份和无编号匹配、维护规则、账单样本依据 |
| [接口、数据与客户端交互参考](cash-import-redesign-contracts.md) | HTTP 请求／响应、逻辑表结构、详细 UI 合同、合成场景及协议示例 |
| [临时存储运行配置](cash-import-r2-staging.md) | R2／内存后端、TTL、清扫、权限与发布限制 |

详细参考不另写一套完整流程；实施计划、审查和测试日志仍保留在对应 OpenSpec 变更中。后续 UI 合同不是当前客户端完成状态，入口覆盖以 [功能地图](feature-map.md) 为准。

## OpenSpec

[业务能力规格导航](../openspec/specs/README.md) 按模块列出当前主规格。`openspec/changes/` 保存 active changes，完成变更归档于 `openspec/changes/archive/`。迁移信息见 [`openspec/MIGRATION.md`](../openspec/MIGRATION.md)，工程约定见 [`openspec/project-context.md`](../openspec/project-context.md) 与根目录 [`AGENTS.md`](../AGENTS.md)。

## 解析与行情参考

供应商账单格式、解析器行为和行情适配细节见 [references/](../references/README.md)。参考资料补充特定来源细节；当前行为仍以代码、测试和对应规格为准。

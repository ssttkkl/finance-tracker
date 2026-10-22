# 文档索引

当前行为以 [`openspec/project-context.md`](../openspec/project-context.md)、`openspec/specs/` 和实现为准。本目录提供运行说明、架构梳理与 UI 约束，不维护平行的行为规格或历史路线。

运行时通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或文件型 SQLite。两者共享应用服务和数据合同；不自动回退、不双写、不隐式迁移。迁移版本以 Alembic 当前 head 和 `SCHEMA_REVISION` 为准；当前仓库值为 `20261018_37`。

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
| [现金账单导入后端架构](cash-import-backend-architecture.md) | Compose 多文件现金导入的服务链路、问题与重构建议 |
| [投资管理架构](investment-management-architecture.md) | 手工写入、账单导入、连接器同步、事件查询、组合估值和现金—投资资金关系的当前模型与 API 契约 |
| [现金导入临时存储配置](cash-import-r2-staging.md) | R2 与内存暂存后端的配置和边界 |
| [UI 设计约束](ui-design-rules.md) | Compose UI 实现与审查规则 |
| [设计系统组件登记](design-system/registry.yaml) | 当前共享展示辅助项 |

## OpenSpec

[业务能力规格导航](../openspec/specs/README.md) 按模块列出当前主规格。`openspec/changes/` 保存 active changes，完成变更归档于 `openspec/changes/archive/`。迁移信息见 [`openspec/MIGRATION.md`](../openspec/MIGRATION.md)，工程约定见 [`openspec/project-context.md`](../openspec/project-context.md) 与根目录 [`AGENTS.md`](../AGENTS.md)。

## 解析与行情参考

供应商账单格式、解析器行为和行情适配细节见 [references/](../references/README.md)。参考资料补充特定来源细节；当前行为仍以代码、测试和对应规格为准。

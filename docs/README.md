# 文档索引

当前行为以 [`openspec/project-context.md`](../openspec/project-context.md)、`openspec/specs/` 和实现为准。本目录提供运行说明、架构梳理与 UI 约束，不维护平行的行为规格或历史路线。

运行时通过 `FT_DATABASE_URL` 显式选择 PostgreSQL 或文件型 SQLite。两者共享应用服务和数据合同；不得回退、不双写、不隐式迁移。迁移版本以 Alembic 当前 head 和 `SCHEMA_REVISION` 为准；当前仓库 schema 值为 `20261021_40`。SQLite 写入繁忙时应重试，目录或文件权限错误须先修正，不能改用另一数据库掩盖失败。

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
| [现金导入流程重设计](cash-import-redesign.md) | 尚未实施的设计提议：固定字段、草稿流水、联合确认及局部投影维护 |
| [现金导入用户流程、接口与数据契约](cash-import-redesign-contracts.md) | 重设计配套提议：统一预览流水列表、前后端协议、表结构约束及端到端场景 |
| [现金导入临时存储配置](cash-import-r2-staging.md) | R2 与内存暂存后端的配置和边界 |
| [UI 设计约束](ui-design-rules.md) | Compose UI 实现与审查规则 |
| [设计系统组件登记](design-system/registry.yaml) | 当前共享展示辅助项 |

## OpenSpec

[业务能力规格导航](../openspec/specs/README.md) 按模块列出当前主规格。`openspec/changes/` 保存 active changes，完成变更归档于 `openspec/changes/archive/`。迁移信息见 [`openspec/MIGRATION.md`](../openspec/MIGRATION.md)，工程约定见 [`openspec/project-context.md`](../openspec/project-context.md) 与根目录 [`AGENTS.md`](../AGENTS.md)。

## 解析与行情参考

供应商账单格式、解析器行为和行情适配细节见 [references/](../references/README.md)。参考资料补充特定来源细节；当前行为仍以代码、测试和对应规格为准。

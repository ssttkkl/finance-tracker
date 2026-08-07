# 文档索引

可执行行为的唯一事实源是 [`openspec/project-context.md`](../openspec/project-context.md) 与 `openspec/specs/<capability>/spec.md`。本目录只保存使用说明、顶层路线和冻结的产品决策记录。

运行时：`FT_DATABASE_URL` 选择 **PostgreSQL 或文件型 SQLite** 之一——不得自动回退（no fallback）、不得双写（dual-write）、不得隐式迁移（implicit migration）。
SQLite 遇到繁忙、读写权限或 schema 错误时会直接报告，不会静默改用其他存储后端。

## 当前基线

- **运行时 / 账户 / 账本**：`runtime-database`、`multi-currency-accounts`、`ledger-records`
- **现金导入 / 分类 / 关系**：`statement-import`、`cash-record-classification`、`transaction-relations`
- **投资事件 / 文件导入**：`investment-event-model`、`investment-statement-import`
- **估值 / 连接器同步**：`portfolio-valuation`、`investment-connector-sync`
- **Alembic / `SCHEMA_REVISION` head**：`20260729_11`
- **财富归因内核**（Phase 3 内核，已落地）：`wealth-attribution`（无专用 CLI/Web）
- **收支账本 Web**：`cash-ledger-browser`（收支投影、稳定分页和证据详情）

active change 通过 `openspec list` 查看。`cash-ledger-browser` 只包含收支账本；投资事件、持仓和估值的 Web 展示规划见 `investment-ledger-browser` active change，完成归档前不属于当前主规格。

收支账本的生产预览需在 `npm run build` 时设置 `VITE_FT_API_ORIGIN`，随后才运行 `npm run start`；预览
服务器不会重新读取该变量。具体命令见 [项目说明](../README.md#收支账本-web)。

## 使用与结构

| 文档 | 说明 |
|---|---|
| [项目说明](../README.md) | 安装、CLI、导入、同步、验证 |
| [收支账本 Web 规格](../openspec/specs/cash-ledger-browser/spec.md) | 只读收支投影浏览与本机双进程运行形态 |
| [投资账本 active change](../openspec/changes/investment-ledger-browser/proposal.md) | 尚未实现的投资事件与持仓浏览规划 |
| [导入 / 关系 / 同步流程](import-flow.md) | 事务语义与命令（015 后） |
| [显式 CSV 导出格式](export-csv-format.md) | 只读预览，非账本 |
| [数据库表结构](database-schema.md) | ORM + Alembic 速查（含 `sync_cursors`） |

## 路线与产品决策

| 文档 | 说明 |
|---|---|
| [产品化重构顶层路线](productization-refactor-plan.md) | 阶段依赖与完成门槛 |
| [Phase 2 Web 单一 Spec 交接](phase2-web-spec-handoff.md) | 020 创建前输入；**非实施权威** |
| [财富解释与趋势对比设计](productization-wealth-report-design.md) | 已批准决策输入；**非**实施权威 |
| [财富报告线框](productization-wealth-report-wireframe.html) | 线框参考 |

## OpenSpec

`openspec/specs/` 保存当前能力主规格，`openspec/changes/` 保存 active change，完成后归档到
`openspec/changes/archive/`。迁移清单和每个旧 feature 的完整产物见 [`openspec/MIGRATION.md`](../openspec/MIGRATION.md)。约定见 [`openspec/project-context.md`](../openspec/project-context.md) 与根目录 [AGENTS.md](../AGENTS.md)。

已完成能力（节选）：

- [运行时数据库](../openspec/specs/runtime-database/spec.md)、[账本记录](../openspec/specs/ledger-records/spec.md)
- [账单导入](../openspec/specs/statement-import/spec.md)、[交易关系](../openspec/specs/transaction-relations/spec.md)
- [投资事件模型](../openspec/specs/investment-event-model/spec.md)、[投资组合估值](../openspec/specs/portfolio-valuation/spec.md)
- [财富归因](../openspec/specs/wealth-attribution/spec.md)、[收支账本浏览](../openspec/specs/cash-ledger-browser/spec.md)

完整列表以 `openspec/` 目录和 `openspec list --specs` 为准；本索引不维护平行任务清单。

## 解析与行情参考

供应商格式与适配细节：[references/](../references/README.md)。

## 已移除的过时文档

下列路径**不再维护**（曾描述文件账本、旧三步 reconcile、或已吸收的 phase 笔记）：

- `docs/import-reconcile-flow.md` → 由 [import-flow.md](import-flow.md) 取代
- `docs/unified-csv-format.md` → 由 [export-csv-format.md](export-csv-format.md) 取代
- `docs/phase1-application-services.md` / `docs/phase2-postgresql-storage.md`（仓库中已不存在；内容由 constitution + specs 吸收）

考古用 Git history，勿把旧流程当操作说明。

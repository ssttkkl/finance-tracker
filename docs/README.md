# 文档索引

可执行行为的唯一事实源是 [`.specify/memory/constitution.md`](../.specify/memory/constitution.md) 与 `specs/<feature>/{spec,plan,tasks}.md`。本目录只保存使用说明、顶层路线和冻结的产品决策记录。

运行时：`FT_DATABASE_URL` 选择 **PostgreSQL 或文件型 SQLite** 之一——不得自动回退（no fallback）、不得双写（dual-write）、不得隐式迁移（implicit migration）。
SQLite 遇到繁忙、读写权限或 schema 错误时会直接报告，不会静默改用其他存储后端。

## 当前基线

- **现金 / 导入 / 关系**：`002`–`008`
- **投资文件导入与 schema 收口**：`009`–`016`（含行幂等、内联溯源、bigint PK）
- **估值**：`017-asset-valuation-quote`
- **连接器同步**：`018-investment-connector-sync`
- **Alembic / `SCHEMA_REVISION` head**：`20260729_11`
- **财富归因内核**（Phase 3 内核，已落地）：`003-wealth-attribution-core`（无专用 CLI/Web）
- **收支账本 Web**：`020-cash-ledger-browser-web`（收支投影、稳定分页和证据详情）

活跃 feature 指针见 `.specify/feature.json`。`020-cash-ledger-browser-web` 只包含收支账本；投资账本视图、持仓和持仓估值留给 `022-investment-ledger-browser-web`。

收支账本的生产预览需在 `npm run build` 时设置 `VITE_FT_API_ORIGIN`，随后才运行 `npm run start`；预览
服务器不会重新读取该变量。具体命令见 [项目说明](../README.md#收支账本-web)。

## 使用与结构

| 文档 | 说明 |
|---|---|
| [项目说明](../README.md) | 安装、CLI、导入、同步、验证 |
| [收支账本 Web 规格](../specs/020-cash-ledger-browser-web/spec.md) | 只读收支投影浏览与本机双进程运行形态 |
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

## Spec Kit

`specs/` 使用 sequential Flow-Forward：`specs/00N-short-name/`。Complete 历史只读；Living Spec 仅改当前活跃目录。约定见 constitution 与根目录 [AGENTS.md](../AGENTS.md)。

已完成 Phase 1 相关（节选）：

- [001](../specs/001-postgres-only-storage/spec.md) … [016](../specs/016-bigint-surrogate-ids/spec.md) schema/导入链
- [017-asset-valuation-quote](../specs/017-asset-valuation-quote/spec.md)
- [018-investment-connector-sync](../specs/018-investment-connector-sync/spec.md)
- [003-wealth-attribution-core](../specs/003-wealth-attribution-core/spec.md)（Phase 3 内核）

完整列表以 `specs/` 目录为准；本索引不维护平行任务清单。

## 解析与行情参考

供应商格式与适配细节：[references/](../references/README.md)。

## 已移除的过时文档

下列路径**不再维护**（曾描述文件账本、旧三步 reconcile、或已吸收的 phase 笔记）：

- `docs/import-reconcile-flow.md` → 由 [import-flow.md](import-flow.md) 取代
- `docs/unified-csv-format.md` → 由 [export-csv-format.md](export-csv-format.md) 取代
- `docs/phase1-application-services.md` / `docs/phase2-postgresql-storage.md`（仓库中已不存在；内容由 constitution + specs 吸收）

考古用 Git history，勿把旧流程当操作说明。

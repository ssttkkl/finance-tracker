# 文档索引

可执行行为的唯一事实源是 `.specify/memory/constitution.md` 与
`specs/<feature>/{spec,plan,tasks}.md`。本目录只保存当前使用说明、顶层路线和冻结的产品决策记录。

运行时仅支持 PostgreSQL 与文件型 SQLite，且 `FT_DATABASE_URL` 只选择其中之一：no fallback、no
dual-write、no implicit migration。SQLite 的 `storage.busy`、permission 和 schema 故障说明见项目说明与
002 feature contracts；诊断不得暴露凭据或完整文件路径。

## 当前基线

- [项目说明](../README.md)：PostgreSQL 与文件 SQLite 配置、命令与验证。
- [账单直接导入流程](import-reconcile-flow.md)：原始文件到正式 relational facts 的事务链路。
- [显式 CSV 导出格式](unified-csv-format.md)：只读检查/交换用途，不是运行时账本。
- [Phase 1 历史记录](phase1-application-services.md)：已被当前 application 边界吸收。
- [Phase 2 历史记录](phase2-postgresql-storage.md)：PostgreSQL-only 历史收口；当前运行时由 002 feature 定义。

## 路线与产品决策

- [产品化重构顶层路线](productization-refactor-plan.md)
- [财富解释与趋势对比设计](productization-wealth-report-design.md)
- [财富报告线框](productization-wealth-report-wireframe.html)

## Spec Kit

`specs/` 使用 sequential Flow-Forward：`specs/00N-short-name/`。默认新能力开新目录；已
Complete 的 feature 只读保留。当前活跃 feature 的同一目标变更用 Living Spec 改该目录内
artifacts；实现发现冲突时 Flow-Back 回写并重新 analyze。约定见
[constitution](../.specify/memory/constitution.md) 与仓库根 `AGENTS.md`。

- [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md)（Complete，历史）
- [002-dual-database-runtime](../specs/002-dual-database-runtime/spec.md)
- [003-wealth-attribution-core](../specs/003-wealth-attribution-core/spec.md)
- [004-mapping-import-open-currency](../specs/004-mapping-import-open-currency/spec.md)
- [005-multi-currency-accounts](../specs/005-multi-currency-accounts/spec.md)
- 后续能力继续新建独立 feature；本目录不维护平行任务清单。

解析器格式与行情适配细节见 [references 索引](../references/README.md)。

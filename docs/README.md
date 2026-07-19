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

- [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md)
- [002-dual-database-runtime](../specs/002-dual-database-runtime/spec.md)
- 后续财富归因和财富报告 Web 各自创建独立 feature；本目录不维护平行任务清单。

解析器格式与行情适配细节见 [references 索引](../references/README.md)。

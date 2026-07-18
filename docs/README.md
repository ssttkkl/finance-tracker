# 文档索引

可执行行为的唯一事实源是 `.specify/memory/constitution.md` 与
`specs/<feature>/{spec,plan,tasks}.md`。本目录只保存当前使用说明、顶层路线和冻结的产品决策记录。

## 当前基线

- [项目说明](../README.md)：PostgreSQL-only 配置、命令与验证。
- [账单直接导入流程](import-reconcile-flow.md)：原始文件到 PostgreSQL facts 的事务链路。
- [显式 CSV 导出格式](unified-csv-format.md)：只读检查/交换用途，不是运行时账本。
- [Phase 1 历史记录](phase1-application-services.md)：已被当前 application 边界吸收。
- [Phase 2 历史记录](phase2-postgresql-storage.md)：曾经的双 backend 实验，已被 PostgreSQL-only 基线取代。

## 路线与产品决策

- [产品化重构顶层路线](productization-refactor-plan.md)
- [财富解释与趋势对比设计](productization-wealth-report-design.md)
- [财富报告线框](productization-wealth-report-wireframe.html)

## Spec Kit

- [001-postgres-only-storage](../specs/001-postgres-only-storage/spec.md)
- 后续财富归因和财富报告 Web 各自创建独立 feature；本目录不维护平行任务清单。

解析器格式与行情适配细节见 [references 索引](../references/README.md)。

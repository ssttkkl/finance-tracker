# 提案：PostgreSQL-Only Runtime Storage

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 80/80；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/001-postgres-only-storage/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `001-postgres-only-storage`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/001-postgres-only-storage/spec.md`、`legacy/001-postgres-only-storage/plan.md`、`legacy/001-postgres-only-storage/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "产品早期快速开发阶段不需要兼容、迁移和回滚；彻底删除本地 CSV/YAML/Git 存储，以不留历史包袱为原则。 本能力的行为契约由迁移后的需求与场景持续维护。

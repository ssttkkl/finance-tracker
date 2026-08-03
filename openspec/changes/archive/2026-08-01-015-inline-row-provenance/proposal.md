# 提案：功能规格：正式事实结构清理（内联溯源 + 去掉冗余表/列）

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Draft`，任务完成度为 49/49；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/015-inline-row-provenance/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `015-inline-row-provenance`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/015-inline-row-provenance/spec.md`、`legacy/015-inline-row-provenance/plan.md`、`legacy/015-inline-row-provenance/tasks.md` 及其同目录的其他产物。

## Purpose

- 问：导入溯源链在文件 digest 不再作去重后还有何用？ → 答：行级业务标识 + 原始 payload 仍被业务使用；希望与行数据放在一起。 本能力的行为契约由迁移后的需求与场景持续维护。

# 提案：功能规格：整数代理主键（Bigint Surrogate IDs）

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Draft`，任务完成度为 29/29；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/016-bigint-surrogate-ids/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `016-bigint-surrogate-ids`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/016-bigint-surrogate-ids/spec.md`、`legacy/016-bigint-surrogate-ids/plan.md`、`legacy/016-bigint-surrogate-ids/tasks.md` 及其同目录的其他产物。

## Purpose

- 问：采用哪一档？ → 答：**D2** 完整整数代理主键 + 对外业务键。 本能力的行为契约由迁移后的需求与场景持续维护。

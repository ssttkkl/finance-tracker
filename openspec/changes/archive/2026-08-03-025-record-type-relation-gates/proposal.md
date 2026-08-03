# 提案：关系配对使用正式记录类型

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `已完成`，任务完成度为 43/43；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/025-record-type-relation-gates/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `025-record-type-relation-gates`

## Impact

- PR28 已同步包含正式记录类型闸门、关系匹配实现和回归测试；本次只将其规格资产迁移到 OpenSpec，不重复修改运行时代码。
- 迁移来源：`legacy/025-record-type-relation-gates/spec.md`、`legacy/025-record-type-relation-gates/plan.md`、`legacy/025-record-type-relation-gates/tasks.md` 及其同目录的其他产物。

## Purpose

现金导入已经在 `cash_transactions.record_type` 保存标准记录类型，但关系配对仍在部分 Phase 中通过 `summary`、交易描述或退款词重新判断“这是退款/转账/还款”。这会让导入分类与关系分类出现分歧，也会把普通收入或消费误放入候选池。 本能力的行为契约由迁移后的需求与场景持续维护。

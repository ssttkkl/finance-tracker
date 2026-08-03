# 提案：Transaction Relations

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 83/83；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/006-transaction-relations/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `006-transaction-relations`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/006-transaction-relations/spec.md`、`legacy/006-transaction-relations/plan.md`、`legacy/006-transaction-relations/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "重新设计账单导入后的去重、退款核销、转账配对、跨平台消费合并逻辑。核心原则：所有导入产生的原始事实都保留，不因配对/去重/退款核销而物理删除或改写原始记录；系统只追加记录事实之间的关系与判断证据/状态，再由报表和派生投影读取这些关系来避免重复统计或计算净额。配对规则可参考 main 分支当前实现。 本能力的行为契约由迁移后的需求与场景持续维护。

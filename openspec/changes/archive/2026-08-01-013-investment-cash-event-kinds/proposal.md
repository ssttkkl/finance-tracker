# 提案：Investment cash event kinds

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete (living with 011 branch)`，任务完成度为 0/0；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/013-investment-cash-event-kinds/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `013-investment-cash-event-kinds`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/013-investment-cash-event-kinds/spec.md`、`legacy/013-investment-cash-event-kinds/plan.md`、`legacy/013-investment-cash-event-kinds/tasks.md` 及其同目录的其他产物。

## Purpose

| `deposit` / `withdraw` | True customer funding; internal transfers (P1) | 本能力的行为契约由迁移后的需求与场景持续维护。

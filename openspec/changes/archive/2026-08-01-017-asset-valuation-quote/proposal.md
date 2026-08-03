# 提案：实时资产估值与持仓市值

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 30/30；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/017-asset-valuation-quote/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `017-asset-valuation-quote`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/017-asset-valuation-quote/spec.md`、`legacy/017-asset-valuation-quote/plan.md`、`legacy/017-asset-valuation-quote/tasks.md` 及其同目录的其他产物。

## Purpose

1. **计价币种估值**：各持仓按各自计价（行情）货币估值，**不**折算； 本能力的行为契约由迁移后的需求与场景持续维护。

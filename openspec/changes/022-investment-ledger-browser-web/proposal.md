# 提案：投资账本浏览 Web

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Draft`，任务完成度为 0/0；迁移后定位为active change，仍需继续实现和验证。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/022-investment-ledger-browser-web/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `022-investment-ledger-browser-web`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/022-investment-ledger-browser-web/spec.md`、`legacy/022-investment-ledger-browser-web/plan.md`、`legacy/022-investment-ledger-browser-web/tasks.md` 及其同目录的其他产物。

## Purpose

本 feature 在 `020-cash-ledger-browser-web` 完成后扩展同一只读 Web/API 运行形态。它交付投资 本能力的行为契约由迁移后的需求与场景持续维护。

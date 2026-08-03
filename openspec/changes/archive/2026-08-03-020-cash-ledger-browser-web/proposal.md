# 提案：收支账本浏览 Web

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Living Spec 更新中（2026-08-01；本轮回写模态证据抽屉；此前于 2026-07-30 收敛并按用户授权合并 021 审计工作台规范）`，任务完成度为 171/185；迁移后定位为active change，仍需继续实现和验证。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/020-cash-ledger-browser-web/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `020-cash-ledger-browser-web`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/020-cash-ledger-browser-web/spec.md`、`legacy/020-cash-ledger-browser-web/plan.md`、`legacy/020-cash-ledger-browser-web/tasks.md` 及其同目录的其他产物。

## Purpose

**Context**：本 feature 原本把全部现金流水直接展示为“消费账本”，只附加关系摘要。实施后的业务复核 本能力的行为契约由迁移后的需求与场景持续维护。

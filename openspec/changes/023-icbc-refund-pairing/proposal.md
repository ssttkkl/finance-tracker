# 提案：工行退款摘要关系配对

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Draft`，任务完成度为 43/45；迁移后定位为active change，仍需继续实现和验证。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/023-icbc-refund-pairing/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `023-icbc-refund-pairing`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/023-icbc-refund-pairing/spec.md`、`legacy/023-icbc-refund-pairing/plan.md`、`legacy/023-icbc-refund-pairing/tasks.md` 及其同目录的其他产物。

## Purpose

用户反馈工行信用卡账单中 `summary=退货` 的收入行没有配对，且 PDF 中相同对手方被导入成不同名称。 本能力的行为契约由迁移后的需求与场景持续维护。

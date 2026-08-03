# 提案：Mapping Import & Open Currency

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 26/26；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/004-mapping-import-open-currency/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `004-mapping-import-open-currency`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/004-mapping-import-open-currency/spec.md`、`legacy/004-mapping-import-open-currency/plan.md`、`legacy/004-mapping-import-open-currency/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "恢复 master 兼容的账单导入：按 ~/.ft/mapping.yaml 从账单内支付方式/卡号推断每行账户；ft import 不允许 --account。同时移除 CLI/领域层币种白名单，支持任意币种（含 JPY）。 本能力的行为契约由迁移后的需求与场景持续维护。

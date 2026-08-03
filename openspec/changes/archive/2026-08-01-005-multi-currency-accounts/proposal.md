# 提案：Multi-Currency Accounts

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 33/33；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/005-multi-currency-accounts/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `005-multi-currency-accounts`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/005-multi-currency-accounts/spec.md`、`legacy/005-multi-currency-accounts/plan.md`、`legacy/005-multi-currency-accounts/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "目前的账户体系规定一个账户只能持有一个币种；物理世界一张卡有多币种会被建模成多个账户。修改建模为一个账户可持有多个币种，为后续换汇、跨币种转账做支持。不保留兼容逻辑和数据迁移代码，实现完成后一次性迁移现有数据。 本能力的行为契约由迁移后的需求与场景持续维护。

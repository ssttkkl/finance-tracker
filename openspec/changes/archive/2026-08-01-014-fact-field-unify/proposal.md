# 提案：Fact Field Unification

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 29/29；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/014-fact-field-unify/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `014-fact-field-unify`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/014-fact-field-unify/spec.md`、`legacy/014-fact-field-unify/plan.md`、`legacy/014-fact-field-unify/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "统一消费账户记录（cash_transactions）与投资账户记录（investment_events）的建模风格与字段名；业务含义相同的字段使用相同名称。对齐表结构（正式列 + 可选 payload），保留两张事实表，不合并为单 ledger 表。背景：消费侧为宽表；投资侧为窄表 + JSON payload，且 `kind` 列实际存 action，与历史文档漂移。 本能力的行为契约由迁移后的需求与场景持续维护。

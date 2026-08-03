# 提案：导入时生成现金流水标准记录类型

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Draft`，任务完成度为 25/25；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/024-normalized-cash-record-type/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `024-normalized-cash-record-type`

## Impact

- PR28 已同步包含 `record_type` 导入字段、数据库迁移、实现和回归测试；本次只将其规格资产迁移到 OpenSpec，不重复修改运行时代码。
- 迁移来源：`legacy/024-normalized-cash-record-type/spec.md`、`legacy/024-normalized-cash-record-type/plan.md`、`legacy/024-normalized-cash-record-type/tasks.md` 及其同目录的其他产物。

## Purpose

用户要求在导入账单原始记录时增加标准化记录类型，先支持关系配对器按类型筛选；不提供历史数据兼容逻辑，修改后用 `.ft/bills` 重建新数据库。 本能力的行为契约由迁移后的需求与场景持续维护。

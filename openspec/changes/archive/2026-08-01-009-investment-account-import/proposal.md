# 提案：Investment Account Import

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 155/155；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/009-investment-account-import/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `009-investment-account-import`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/009-investment-account-import/spec.md`、`legacy/009-investment-account-import/plan.md`、`legacy/009-investment-account-import/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "从 main 恢复投资事件领域模型与文件/手动导入。main 已有完整投资体系（SWAP/DEPOSIT/WITHDRAW/DIVIDEND/CHECKIN、多券商解析），但产品化迁移过程中仅保留了 DFZQ 单一 PoC；本 feature 将其恢复到 PostgreSQL-only + 双 DB + 关系架构中，覆盖多券商 PDF/CSV 解析与投资事件领域模型。买入卖出统一用 SWAP 表示（现金↔资产交换），手续费通过 commission 字段记录。 本能力的行为契约由迁移后的需求与场景持续维护。

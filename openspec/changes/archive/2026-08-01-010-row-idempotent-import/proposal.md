# 提案：Row-Level Idempotent Import (Incremental)

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 24/24；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/010-row-idempotent-import/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `010-row-idempotent-import`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/010-row-idempotent-import/spec.md`、`legacy/010-row-idempotent-import/plan.md`、`legacy/010-row-idempotent-import/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "009 就改成「仅业务行幂等 + 重叠文件可增量」。这个开个新spec做吧，把消费账本和投资账本都改掉" — change cash and investment statement import idempotency from file-level source_digest short-circuit to **row-level source_identity only**, so overlapping files apply only new business rows (incremental). Supersedes digest-as-primary-idempotency in 007 and 009. Keep import batches / raw files as job/audit metadata, not ledger truth. Dual-backend required. 本能力的行为契约由迁移后的需求与场景持续维护。

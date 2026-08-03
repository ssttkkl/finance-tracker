# 提案：Relations Kind Decouple

## Why

迁移现有 feature 规格，使其由 OpenSpec 管理，同时保留原始需求、技术方案、任务和验证证据。
该 feature 的原始状态为 `Complete`，任务完成度为 38/38；迁移后定位为已完成历史 feature，作为只读归档保留。

## What Changes

- 将可观察行为整理为 OpenSpec `Requirement` 与 `Scenario`。
- 将原始历史产物完整保存在本 change 的 `legacy/008-relations-kind-decouple/` 目录。
- 让后续行为变更通过 `proposal.md`、delta spec、`design.md` 和 `tasks.md` 管理。

## Capabilities

- **Modified Capabilities**: `008-relations-kind-decouple`

## Impact

- 影响本仓库的规格目录、Agent 工作流和验证文档，不改变产品运行时代码。
- 迁移来源：`legacy/008-relations-kind-decouple/spec.md`、`legacy/008-relations-kind-decouple/plan.md`、`legacy/008-relations-kind-decouple/tasks.md` 及其同目录的其他产物。

## Purpose

User description: "关系识别 Kind 竖切解耦：将 payment_mirror / transfer_pair / refund_offset 拆为独立 RulePack，共享最薄 core；合法跨 kind 依赖仅通过 pipeline 的 MatchContext；Phase A→D 固定；Diamond 作为 refund 子能力只读 accepted 边。目标：三 kind 行为可独立演进；Step A 零业务语义变更；词表清理（强/软排除等）后续 feature。非目标：通用规则引擎、改审查 API 契约、改 006/007 验收语义。 本能力的行为契约由迁移后的需求与场景持续维护。

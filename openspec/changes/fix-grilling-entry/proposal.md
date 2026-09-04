## Why

仓库只安装了 `mattpocock/skills` 的 `grill-me` 用户入口，没有同时安装它委托调用的 `grilling` skill，因此需求澄清门禁引用了一个不存在的入口。结果是 Codex 只能读到一行委托文本，无法执行实际的逐轮澄清。

## What Changes

- 将上游 `grilling` skill 与现有 `grill-me` 入口一起纳入仓库技能目录。
- 将 `grill-me` 的委托文本同步到上游当前写法，并补齐 Codex 可发现的 `grilling` 元数据。
- 同步个人 Codex skill 安装目录，使当前及后续会话都能加载该入口。
- 修正 Agent 工作流文案，明确 `grill-me` 是用户入口、`grilling` 是其依赖的实际澄清 skill。

## Capabilities

### New Capabilities

无。本次只修复 Agent skill 的安装完整性和调用入口，不改变 Finance Tracker 的产品行为。

### Modified Capabilities

无。

## Impact

- 受影响文件：`.agents/skills/grill-me/`、`.agents/skills/grilling/`、`AGENTS.md` 及对应 OpenSpec 记录。
- 个人安装副本：`~/.codex/skills/grill-me/`、`~/.codex/skills/grilling/`。
- 不修改账本代码、数据库、API、依赖或财务数据。
- 回滚方式：删除新增的 `grilling` skill，恢复 `grill-me` 委托文本和 `AGENTS.md` 的原文；个人安装副本同样恢复即可。

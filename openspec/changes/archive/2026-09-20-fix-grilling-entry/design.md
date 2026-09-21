## Context

上游 `mattpocock/skills` 将 `grill-me` 定义为显式调用的用户入口，并让它调用名为 `grilling` 的 model-invoked primitive。当前仓库只保留了前者。Codex skill 的 skill 名称由 `SKILL.md` 的 `name` 和目录共同决定；缺少 `grilling/SKILL.md` 时，`/grilling` 不是可解析入口。

## Goals / Non-Goals

**Goals:**

- 保持上游的两个 skill 名称、职责边界和调用关系。
- 让仓库安装内容与个人 Codex 安装内容都包含 `grilling`。
- 让 Agent 工作流文档不再把缺失的依赖误报为运行时工具缺失。

**Non-Goals:**

- 不重写上游的 grilling 访谈方法。
- 不把 `grill-me` 改成隐式调用，继续由 `agents/openai.yaml` 的 `allow_implicit_invocation: false` 保留用户显式启动需求澄清的门禁。
- 不实现 shell 命令或新的外部服务。

## Decisions

- 采用上游 `grilling` 原文和元数据：这样可避免仓库复制一套与上游漂移的访谈逻辑。
- 同时保留 `grill-me` 和 `grilling` 两个目录：`grill-me` 是门禁要求的显式入口，`grilling` 是它的运行依赖，不能只保留其一。
- 将“缺少实际 skill”定位为安装完整性问题，而不是在 Agent 内部用人工推测替代 skill。`grill-me` 的 `SKILL.md` 委托给 `grilling`，显式调用约束由其 `agents/openai.yaml` 的 `allow_implicit_invocation: false` 表达。

## Risks / Trade-offs

- [上游内容未来更新] → 本次固定采用当前上游内容，并在仓库中保留来源与同步边界；后续更新时需同步两个目录。
- [当前会话已加载旧 skill 清单] → 个人安装副本立即补齐；入口发现通常在新会话或 skill 清单刷新后生效。

## Migration Plan

无运行时迁移。提交仓库 skill 文件后，刷新 Codex skill 清单；若需回滚，删除新增依赖并恢复委托文案。

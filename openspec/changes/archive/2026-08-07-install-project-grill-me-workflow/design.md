## Context

参见 `proposal.md` 的动机。当前仓库已将 `.agents/skills/openspec-*` 作为项目内工作流技能目录，但缺少项目内的 `grill-me` 副本；根目录 `AGENTS.md` 负责定义所有变更的前置门禁。

## Goals / Non-Goals

**Goals:**

- 在项目内固定 `grill-me` 的技能文件和展示元数据，使代理可从当前仓库读取同一版本。
- 把 `/grilling` 需求澄清会接入现有 OpenSpec 工作流，并要求澄清结论可追溯到 change artifact 或任务记录。
- 保持项目现有 OpenSpec、测试和外部写授权门禁不变。

**Non-Goals:**

- 不把技能改造成自动触发；`allow_implicit_invocation` 继续为 `false`。
- 不同步 `grill-me` 仓库的其他技能，不引入新的运行时依赖或安装脚本。
- 不修改业务规格、代码、数据库、测试或发布流程。

## Decisions

1. **项目路径使用 `.agents/skills/grill-me/`。**
   - 选择原因：与仓库现有项目技能目录一致，且不污染用户级 `~/.codex/skills`。
   - 备选方案：只保留用户级安装；无法保证协作者和自动化环境得到同一技能，因此不采用。

2. **复制上游技能的两个文件，不扩展技能内容。**
   - `SKILL.md` 保留上游的名称、描述和手动调用约定；`agents/openai.yaml` 保留展示信息及禁止隐式调用策略。
   - 选择原因：项目只需要固定来源和启用门禁，不应在本次变更中重新设计技能行为。

3. **在 `AGENTS.md` 中定义前置澄清门禁。**
   - 每项变更在规划或实施前运行 `/grilling`；至少明确目标、范围、非目标、具体内容、验收标准和关键风险后才允许继续。
   - 如果仍存在会改变范围、语义或验收的歧义，必须暂停并请求决策；已明确的结果写入 OpenSpec artifact 或任务记录。
   - 选择原因：`AGENTS.md` 是仓库变更流程的唯一规则，能够覆盖代码、文档、配置、规格和技能文件变更。

## Risks / Trade-offs

- **[技能内容可能与上游更新脱节]** → 在项目内记录来源路径；后续需要升级时单独提出变更并重新审查差异。
- **[澄清门禁增加小改动的沟通成本]** → 允许对明确的小改动快速完成简短 `/grilling`，但不取消门禁或省略范围和验收结论。
- **[代理误以为技能可隐式触发]** → 保留 `allow_implicit_invocation: false`，并在 `AGENTS.md` 明确要求手动运行 `/grilling`。

## Migration Plan

实施时添加项目技能文件并更新 `AGENTS.md`；无需数据库、依赖或运行时迁移。回滚时删除 `.agents/skills/grill-me/`，恢复 `AGENTS.md` 的对应段落，并保留 change 记录作为审计证据。

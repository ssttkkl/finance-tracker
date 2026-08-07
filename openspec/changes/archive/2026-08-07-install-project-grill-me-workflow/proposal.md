## Why

项目当前只有用户级的 `grill-me` 技能副本，仓库本身无法保证后续变更使用同一套需求澄清规则。将技能纳入项目并把需求澄清设为 `AGENTS.md` 的前置门禁，可以在实施前统一明确变更范围、具体内容与验收边界。

## What Changes

- 在 `.agents/skills/grill-me/` 纳入 `grill-me` 的项目副本，包括 `SKILL.md` 与 `agents/openai.yaml`。
- 更新根目录 `AGENTS.md`：每项变更在规划或实施前都必须运行 `/grilling`，持续澄清直到目标、范围、非目标、具体内容、验收标准和关键风险明确。
- 当需求仍有范围、语义或验收歧义时，暂停实施并请求必要决策；澄清结果写入对应的 OpenSpec artifact 或任务记录。
- 不改变财务领域行为、运行时依赖、数据库、公共 API 或现有业务规格。

## Capabilities

### New Capabilities

无。本变更只涉及项目工作流、文档和技能文件，`.openspec.yaml` 使用 `skip_specs: true`。

### Modified Capabilities

无。本变更不修改任何业务能力的规格要求。

## Impact

- 受影响文件：根目录 `AGENTS.md`、`.agents/skills/grill-me/SKILL.md`、`.agents/skills/grill-me/agents/openai.yaml` 以及本 change 的记录文件。
- 运行时系统、数据库、依赖和用户数据不受影响。
- 后续变更增加一次人工/代理需求澄清环节；回滚时删除项目技能目录并恢复 `AGENTS.md` 的原有约定即可。

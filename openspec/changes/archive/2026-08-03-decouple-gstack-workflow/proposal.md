## Why

当前 A 类变更保留了 OpenSpec、测试和审查门禁，但将产品、工程、设计和开发者体验复核绑定到仓库外的 gstack。缺少该工具会阻断不依赖其实现细节的工作，也使仓库无法独立复现完整交付流程。

本次变更将历史 A 类阶段流程恢复为仓库规则：保留规格、计划、任务一致性、受控实施、审查、测试与 QA、发布和反思；由执行变更的 AI 按风险运行实际验证并回写证据；将 gstack 和 Hallmark 降为可选辅助，而非完成前提。

## What Changes

- 重写 `AGENTS.md`：A 类变更采用八阶段完整流程，并按产品、工程、设计、开发者体验和安全视角触发复核。
- 移除 gstack 的安装前置、强制命令和唯一流程所有权；审查与 QA 的完成合同、回写要求和风险门禁保持不变。
- 在 `AGENTS.md` 写明由执行变更的 AI 按 A/B/C 等级选择、执行并回写 OpenSpec、Python、Web 和差异检查。
- 更新共享项目上下文，明确审查和 QA 不依赖特定外部 Skill。

**BREAKING**：后续变更不再以调用 gstack 命令作为完成证据；执行变更的 AI 必须按 AGENTS 定义的风险门禁运行检查并记录审查与验证事实。

## Capabilities

### New Capabilities

无。本次只调整仓库工作流、验证工具与项目文档，不改变 Finance Tracker 的用户可见行为。

### Modified Capabilities

无。

## Impact

- 受影响文件：`AGENTS.md`、`openspec/project-context.md`。
- 不修改账本领域代码、数据模型、持久化、接口、迁移或现有产品规格。
- 已完成变更的归档记录保持历史事实，不批量改写其中的 gstack 证据。
- 回滚方式：恢复上述工作流文件；不涉及数据库或正式账本事实。

## 归档后修订（2026-08-03）

八阶段不再只作为 A 类的完整流程。A、B、C 均须按同一八阶段组织任务和证据，差异只在每阶段的最低动作与复核强度；B/C 可以合并轻量动作，但不能跳过阶段或省略不适用理由。

A 类 UI 变更新增硬门禁：计划阶段必须使用 `$hallmark` 设计并产出当前 change 下的 `prototype/index.html`，作为后续任务拆分、设计复核和实施的 HTML demo 证据。

所有 A/B/C UI 变更的审查阶段都必须运行 `$hallmark audit`。审计 finding 按严重级别回写 `tasks.md`，critical 与 major finding 修复后重新审计。

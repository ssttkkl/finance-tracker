## 1. 变更设计

- [x] 1.1 确认此变更使用 `skip_specs: true`，并在 proposal、design 中记录范围、非目标、替代方案、风险和回滚方式。

## 2. 仓库自有流程

- [x] 2.1 将 `AGENTS.md` 改为仓库自有流程：保留思考、计划、任务一致性、受控实施、审查、测试与 QA、发布、反思八阶段，移除 gstack 安装和命令前置。
- [x] 2.2 在 `AGENTS.md` 定义产品、工程、设计、开发者体验和安全的条件性复核视角、统一输出合同，以及由 AI 执行和回写 A/B/C 验证证据的规则。
- [x] 2.3 更新 `openspec/project-context.md`，使审查与验证证据不依赖 gstack 或 Hallmark 的特定调用；领域与工具 Skill 保留为可选辅助。

## 3. 验证与归档

- [x] 3.1 运行 OpenSpec 校验、健康检查、相关文档测试、相称构建和 `git diff --check`；记录不适用的 Web、双后端、性能和安全检查及其理由。

  验证记录（2026-08-03 16:05 CST，`HEAD`/比较基线均为 `3ed6dbafa42ebaff9d3721c0a259b92031418cad`）：

  - `openspec validate --all --strict`：26 项通过，0 项失败。
  - `openspec doctor`：仓库根目录和 OpenSpec 根目录正常。
  - `uv run pytest -q tests/test_runtime_docs.py`：5 项通过。
  - `uv run python -m compileall -q src tests scripts`：通过。
  - `git diff --check`：通过。
  - Web、SQLite/PostgreSQL 契约矩阵、性能和安全专项检查：不适用。本次只修改工作流与 OpenSpec 文档，没有产品 Web、持久化、性能路径或安全边界改动。
  - 未执行旧/新流程迁移验证和完整产品回归：用户明确要求「不用迁移验证」；残余风险是未来 Agent 对新规则的实际遵循尚未由真实产品变更验证。补跑方式是在下一次对应风险的 A 类产品变更中，按 `AGENTS.md` 执行完整适用测试和复核。

- [x] 3.2 以独立复核视角检查最终 diff、A 类流程和文档回写；将 finding 与处理结论记录在本文件。

  独立复核记录（2026-08-03 16:07 CST，范围：`AGENTS.md`、`openspec/project-context.md` 与本 change 的 artifacts）：

  - 已覆盖风险：gstack/Hallmark 被误保留为完成前提；八阶段流程或产品、工程、设计、开发者体验、安全复核视角缺失；门禁脚本替代风险判断；外部写授权范围缩窄；不适用检查未被记录。
  - Finding（HIGH，已采纳）：初版把发布和外部写授权仅放在 A 类阶段，可能使 B/C 变更失去明确约束。已在 `AGENTS.md` 的「发布与外部写授权」独立章节恢复全等级规则。
  - Finding（MEDIUM，已采纳）：初版只有复核名称，没有保留开放式产品探讨及各评审视角的挑战问题。已在 A 类第 1、5 阶段补充问题清单，作为原先产品、工程、设计、开发者体验和安全评审能力的仓库规则替代。
  - 结论：无未解决的阻断性 finding；不修改领域代码、持久化、公开接口或既有归档，且没有提交、推送、PR 或其他外部写操作。

- [x] 3.3 完成所有任务后运行 `$openspec-archive-change`，同步变更状态但不创建提交、推送或 PR。

## 归档后修订（2026-08-03）

- [x] 将八阶段改为 A/B/C 共用骨架，并在 `AGENTS.md` 增加阶段强度矩阵；B/C 的轻量化只减少每阶段深度，不跳过阶段、测试先行、审查、验证证据或外部写授权。
- [x] 为 A 类 UI 增加 Hallmark HTML 原型门禁：第 2 阶段产出 `prototype/index.html`，在任务拆分、设计复核和实施前完成状态与响应式检查。
- [x] 为所有 A/B/C UI 变更增加 Hallmark 审计门禁：第 5 阶段对最终 UI 运行 `$hallmark audit`，记录按严重级别排序的 finding，并在 critical 或 major finding 修复后重新审计。

- [x] 使用 `writing-great-skills` 收敛 `AGENTS.md`：以单一事实源移除跨文件重复的分级、八阶段和财务/数据库规则；保留可检查的 A/B/C、Hallmark、审查、验证与授权完成条件。

  验证记录（2026-08-03 16:44 CST，`HEAD`：`6852db4f83d81cb12e6d805d27809c2f72f0920b`，比较基线：`origin/refactor/web` 的 `93998b2c3f205a58ae9c1e02154cd8bf3dded1ce`）：

  - `openspec validate --all --strict`：25 项通过，0 项失败。
  - `openspec doctor`：仓库根目录和 OpenSpec 根目录正常。
  - `uv run pytest -q tests/test_runtime_docs.py`：5 项通过。
  - `git diff --check`：通过。
  - Hallmark 实际原型与 audit、Web QA、SQLite/PostgreSQL 契约矩阵、性能与安全专项检查：不适用。本次只收敛流程与上下文文档，不改变产品 UI、持久化、性能路径或安全边界；按用户要求不执行旧/新流程迁移验证和完整产品回归。残余风险是未来真实产品变更尚未验证新文本的可执行性；在下一次相应风险的 A 类变更中按 `AGENTS.md` 补跑适用流程。

  独立文档复核（2026-08-03 16:44 CST，范围：`AGENTS.md`、`openspec/project-context.md` 和本 change 的 artifacts）：

  - 已覆盖风险：A/B/C 强度或八阶段被删除；Hallmark 原型或 UI audit 门禁被弱化；外部写授权和验证证据失去唯一位置；财务、持久化与安全规则从文档中消失；gstack 再次成为依赖。
  - Finding：无 critical、major 或阻断性 finding。`AGENTS.md` 保留即时执行规则，`project-context.md` 保留跨变更工程原则；二者通过单向引用消除重复。

  归档后修订验证（2026-08-03）：`openspec validate --all --strict` 通过 25 项；`openspec doctor` 正常；`uv run pytest -q tests/test_runtime_docs.py` 通过 5 项；`git diff --check` 通过。此次仅修改流程文档，没有产品 UI 或迁移行为，因此不适用 Hallmark 实际原型或 audit、Web QA、双后端、性能和安全专项检查。

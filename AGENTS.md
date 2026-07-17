# AI 工作流

本仓库采用 **Spec Kit + gstack**。Spec Kit 是规格、方案、任务和实施的唯一主流程；
gstack 只承担产品与架构挑战、代码评审、Web QA 和发布验证。

除“适用范围”明确列出的例外外，以下门禁均为强约束。任务完成前持续推进；只有缺少关键决策、
外部授权或必需工具时才停下询问，不要反复询问是否继续。

## 工具与项目产物

- 本机或 Agent 环境必须提供 Spec Kit CLI `specify`，且版本必须与
  `.specify/init-options.json` 的 `speckit_version` 一致。当前基线为 `0.12.17`。
- Codex 必须能发现 `.agents/skills/speckit-*`；gstack 必须安装在开发者本机或 Agent 环境中。
- 缺少当前阶段所需的 CLI 或 skill 时，停止该阶段，报告缺失项和官方安装方式；不得跳过门禁或
  用临时自创流程代替。
- 必须提交 Spec Kit 的项目产物：`.specify/`、`.agents/skills/speckit-*` 和 `specs/`。
- Spec Kit CLI 与 gstack 的源码、插件及依赖必须安装在仓库外；不得复制、vendoring、以 Git
  submodule 引入或以 symlink 暴露到本仓库。
- 更新 Spec Kit 项目文件前，先备份 `.specify/memory/constitution.md` 和已定制的模板/skills，
  再执行：

  ```bash
  specify init --here --force --integration codex --integration-options="--skills" --script sh
  ```

  初始化后必须恢复并复核仓库定制，不能让升级覆盖 constitution 或测试门禁。

## 适用范围

以下工作必须走完整 Spec Kit 主流程：

- 新增或修改领域行为、用户可见行为、CLI、Web、Worker 或 MCP 能力；
- Bug 修复、跨模块重构、公共接口或依赖边界变更；
- 财务计算、币种与精度规则、数据模型、持久化、迁移、兼容性或审计规则变更。

仅文案、注释、拼写、格式化或不改变行为的文档维护可跳过 feature artifacts；仍须检查 diff，
运行与改动相称的静态校验，并在交付时说明验证证据。

## 唯一事实源

- `.specify/memory/constitution.md` 定义项目不可妥协的工程原则。
- 每个变更的 `specs/<feature>/spec.md` 定义“做什么和为什么”，`plan.md` 定义技术方案，
  `tasks.md` 定义执行顺序与完成状态。
- 代码和测试是上述 artifacts 的实现。发现需求或方案错误时，先更新对应 artifact，再继续实施。
- gstack 的评审结论必须回写到 `spec.md`、`plan.md` 或 `tasks.md`；不得另建一套可独立演进的
  需求、方案或任务事实源。
- 一个 feature 必须目标单一、可独立测试、可回滚；范围过大时先拆成多个 feature。

## 执行流程

### 1. 规格与产品挑战

1. 阅读 constitution、现有代码、测试、相关文档和当前 feature artifacts。
2. 使用 `$speckit-specify` 创建或更新 `spec.md`。
3. 使用 `$speckit-clarify` 消除会影响范围、验收、财务语义、数据模型、迁移或兼容性的歧义。
4. 对用户可见能力、产品方向或明显范围取舍，运行 gstack `plan-ceo-review`；把采纳的结论回写
   `spec.md`，重新检查验收场景。

规格必须包含可独立验证的用户场景、边界/失败场景、明确的非目标和可度量成功标准。涉及金额、
币种、持久化或迁移时，还必须写明精度、舍入、幂等、来源追踪、兼容与回滚口径。

### 2. 技术方案与架构挑战

1. 使用 `$speckit-plan` 生成 `plan.md`、`research.md`、`data-model.md`、`contracts/` 和
   `quickstart.md` 中适用的产物。
2. 对跨模块、公共接口、财务计算、数据模型、持久化、迁移、安全、性能或部署拓扑变更，运行
   gstack `plan-eng-review`；把采纳的结论回写 `plan.md` 及相关设计产物。
3. 再次执行 plan 中的 Constitution Check。存在未获明确批准的 constitution 违例时不得进入任务拆分。

### 3. 任务拆分与一致性门禁

1. 使用 `$speckit-tasks` 生成按依赖排序、可执行的 `tasks.md`。
2. 任务必须覆盖规格中的每项需求与验收场景。所有可执行行为、财务逻辑、数据、迁移和接口变更
   都必须先安排失败测试，再安排最小实现和验证。
3. 使用 `$speckit-analyze` 检查 spec、plan 和 tasks 的一致性与覆盖率。
4. 存在 CRITICAL 或 HIGH 问题时，回到对应 artifact 修正并重新 analyze；不得直接实施。

### 4. 实施

1. 在独立 feature 分支或 worktree 中使用 `$speckit-implement` 按 tasks 顺序实施。
2. 先运行新增测试确认其因目标行为缺失而失败，再小步实现并转绿；每完成一项立即更新 task 状态。
3. 不扩大 feature 范围，不顺手重构无关代码。实施发现的新需求或架构决策必须先回写 artifacts。
4. 完成后使用 `$speckit-converge` 对照 spec、plan、tasks 和代码；若追加任务，继续实施直至收敛。

### 5. 评审与验证

1. 所有代码变更必须运行 gstack `review`。阻断性 finding 必须修复并重新评审；若 finding 反映规格
   或方案缺口，先更新对应 Spec Kit artifact。
2. 运行受影响测试、完整测试套件、类型检查、lint 和构建中项目实际提供的命令。
3. Web 行为或交互变更必须运行 gstack `qa`，覆盖主流程、错误/空状态和相关回归；修复后重新 QA。
4. 检查最终 diff、未跟踪文件和 tasks 完成状态后，才能声明完成。

未运行的验证必须明确列出：未验证内容、原因、风险和开发者可执行的准确命令。不得把推测写成
通过结论。

### 6. 发布

只有用户明确要求创建 PR、发布或部署时，才进入外部写操作：

1. 使用 gstack `ship` 完成发布前测试、最终 review、提交、推送和 PR。
2. 用户明确授权合并与部署后，使用 `land-and-deploy` 等待 CI/部署并验证生产健康。
3. 需要上线后观察时使用 `canary`；发现回归立即停止扩大发布并报告回滚路径。

没有发布授权时，交付到“本地实现与验证完成”，不得自行推送、建 PR、合并或部署。

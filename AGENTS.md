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

## 中文术语与文案规范

新增或修改文档、代码注释、docstring 或程序文案时，必须同时使用项目级
`$domain-glossary` 与 `$chinese-documentation`。程序文案包括 CLI 帮助与错误信息、日志提示、
交互提示和 UI 文本；不得因改动短小或只涉及一行注释而跳过。

按以下顺序执行：

1. 先使用 `$domain-glossary`：读取 `DOMAIN_GLOSSARY.md`、相关 feature artifacts 和代码上下文，
   识别同义词、歧义词与领域术语。使用已有规范术语；出现新概念、语义变化或未决歧义时，先更新
   `DOMAIN_GLOSSARY.md`，写明定义、应避免的别名和必要的概念关系。
2. 再使用 `$chinese-documentation`：检查并改写机翻味、欧化句式、逐词直译和过度翻译，复核
   中英文空格、全半角标点、首次出现的中英对照及全文一致性。中文应按目标读者可理解的业务含义
   表达，不得把仓库内部英文术语机械地逐词翻译后直接暴露给用户。
3. 交付前按两个 skill 的检查项复核本次新增或修改的全部适用文本；发现术语问题时，先修正词表，
   再同步修正文档、注释和程序文案。

代码标识符、数据库字段、API 字段、CLI 参数、枚举值、协议字面量和用户输入中的原文引用保持原样，
并使用反引号标识；只规范它们周围的说明文字。领域术语按语境翻译，不做全局字符串替换。例如
`leg` 不得默认译为“腿”：投资事件中的 `cash leg` 写作“现金部分”，`security leg` 写作
“证券部分”，swap 的 `from` / `to` 写作“付出资产”/“换入资产”，面向用户的 `open-leg pending`
写作“待配对关系”或结合上下文写作“未配对的一笔”。内部状态名首次出现时可写成
“待配对关系（`open-leg pending`）”。

`spec.md`、`plan.md` 和 `tasks.md` 仍是需求、方案与任务的事实源；`DOMAIN_GLOSSARY.md` 只约束命名
和面向读者的解释。若术语分歧反映业务语义变化，先按 Spec 演进策略更新对应 artifact，再更新词表。

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
- `.specify/feature.json` 的 `feature_directory` 指向当前活跃 feature；sequential 编号目录
  `specs/00N-short-name/` 为默认布局。

## Spec 演进策略

对应 Spec Kit evolving-specs 的三种模型，本仓库按以下规则选择（细节以 constitution 为准）：

1. **Flow-Forward（默认）**  
   新能力、新边界，或既有 feature 已 Complete：用 `$speckit-specify` 新建 `specs/00N-...`，
   走完整主流程。Complete 历史目录只读保留，不回写新需求；新目录在 Context 中 cross-link
   Supersedes / Extends 关系。

2. **Living Spec（当前活跃 feature）**  
   同一目标内改范围或验收：先改当前 `spec.md`（`$speckit-clarify` 或显式编辑），再同步
   `plan.md` / `tasks.md`，运行 `$speckit-analyze` 后再实施。大改前优先干净工作树或独立分支。

3. **Flow-Back（实现/评审发现）**  
   先落最近正确的 artifact，再对齐整套 `spec` / `plan` / `tasks`。implementer 发现缺口必须返回
   主 session 回写；不得只改代码或只改 tasks 而让 spec 过期。

升级 Spec Kit 项目文件仍按「工具与项目产物」中的备份 → `specify init --force` → 恢复复核流程；
不得用升级覆盖 `specs/`。

## 执行流程

### 1. 规格与产品挑战

1. 阅读 constitution、现有代码、测试、相关文档和当前 feature artifacts。
2. 使用 `$speckit-specify` 创建或更新 `spec.md`。
3. 使用 `$speckit-clarify` 消除会影响范围、验收、财务语义、数据模型、迁移或兼容性的歧义。
4. 对用户可见能力、产品方向或明显范围取舍，运行 gstack `plan-ceo-review`；把采纳的结论回写
   `spec.md`，重新检查验收场景。

规格必须包含可独立验证的用户场景、边界/失败场景、明确的非目标和可度量成功标准。涉及金额、
币种、持久化或迁移时，还必须写明精度、舍入、幂等、来源追踪、兼容与回滚口径。
涉及数据库行为时，还必须分别定义 PostgreSQL 与 SQLite 的等价行为、允许的运行差异，以及禁止的
自动回退、双写和隐式跨后端迁移。

### 2. 技术方案与架构挑战

1. 使用 `$speckit-plan` 生成 `plan.md`、`research.md`、`data-model.md`、`contracts/` 和
   `quickstart.md` 中适用的产物。
2. 对跨模块、公共接口、财务计算、数据模型、持久化、迁移、安全、性能或部署拓扑变更，运行
   gstack `plan-eng-review`；把采纳的结论回写 `plan.md` 及相关设计产物。
3. 再次执行 plan 中的 Constitution Check。存在未获明确批准的 constitution 违例时不得进入任务拆分。

持久化方案必须包含 PostgreSQL/SQLite schema、事务、并发、查询和错误合同的差异清单，并说明如何
通过共享 Application Service 与后端测试矩阵证明用户可见行为基本等价。

### 3. 任务拆分与一致性门禁

1. 使用 `$speckit-tasks` 生成按依赖排序、可执行的 `tasks.md`。
2. 任务必须覆盖规格中的每项需求与验收场景。所有可执行行为、财务逻辑、数据、迁移和接口变更
   都必须先安排失败测试，再安排最小实现和验证。
   持久化相关任务必须同时安排 SQLite 集成测试和真实 PostgreSQL 集成测试。
3. 使用 `$speckit-analyze` 检查 spec、plan 和 tasks 的一致性与覆盖率。
4. 存在 CRITICAL 或 HIGH 问题时，回到对应 artifact 修正并重新 analyze；不得直接实施。

### 4. 实施

1. 主 session 负责完成规格、澄清、方案、任务拆分和一致性门禁，但不得直接实施产品代码。
2. 只有 `spec.md`、`plan.md`、`tasks.md` 均已就绪，Constitution Check 已通过，且
   `$speckit-analyze` 不存在未解决的 CRITICAL 或 HIGH 问题时，主 session 才能进入实施阶段。
3. 进入实施阶段后，实施必须在独立 feature 分支或 worktree 中使用 `$speckit-implement` 按 tasks
   顺序执行。阶段交接只以 Spec Kit artifacts 为依据，不得依赖未写入 artifacts 的对话决策。
4. 当前实施环境无法执行 `$speckit-implement` 时，必须停止并报告缺失项；不得跳过实施门禁或以
   临时自创流程代替。
5. 先运行新增测试确认其因目标行为缺失而失败，再小步实现并转绿；每完成一项立即更新 task 状态。
6. 不扩大 feature 范围，不顺手重构无关代码。实施发现的新需求或架构决策必须先返回主 session，
   由主 session 回写 artifacts 后才能继续。
7. 完成后使用 `$speckit-converge` 对照 spec、plan、tasks 和代码；若追加任务，继续实施直至收敛。

### 5. 评审与验证

1. 所有代码变更必须运行 gstack `review`。阻断性 finding 必须修复并重新评审；若 finding 反映规格
   或方案缺口，先更新对应 Spec Kit artifact。
2. 运行受影响测试、完整测试套件、类型检查、lint 和构建中项目实际提供的命令。
   存储行为变更必须在 SQLite 与真实 PostgreSQL 上运行同一契约矩阵；任一后端缺少证据均不得声明完成。
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

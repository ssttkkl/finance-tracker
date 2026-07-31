# AI 工作流

本仓库采用 **Spec Kit + gstack + Hallmark**。开发流程遵循「思考 → 计划 → 构建 → 审查 → 测试 → 发布 → 反思」：Spec Kit 是规格、方案、任务和实施的唯一主流程；gstack 提供阶段性的产品、架构、质量与发布专业能力；Hallmark 负责 UI 设计纪律与界面质量。`AGENTS.md` 是本文件的符号链接，不得将其替换为独立副本。

除“适用范围”明确列出的例外外，以下门禁均为强约束。任务完成前持续推进；只有缺少关键决策、外部授权或当前阶段的必需工具时才停下询问，不要反复询问是否继续。

## 工具、安装与能力边界

- 本机或 Agent 环境必须提供 Spec Kit CLI `specify`，且版本必须与 `.specify/init-options.json` 的 `speckit_version` 一致。当前基线为 `0.12.17`。
- `$speckit-*` 与 Hallmark 均是 AI 编码助手通过 Skill 工具调用的工作流技能，不是 shell CLI 或 `specify` 的同名子命令。`$speckit-specify`、`$speckit-clarify`、`$speckit-plan`、`$speckit-tasks`、`$speckit-analyze`、`$speckit-implement`、`$speckit-converge` 和 `$hallmark` 必须通过 Skill 工具执行；不得尝试或要求执行 `specify analyze`、`specify converge`、`hallmark audit` 等同名 shell 命令。若 Skill 不可用，应报告缺少对应 Skill，不得以 shell 命令失败作为缺失证据。
- Hallmark 的 `audit`、`redesign`、`study` 是通过 `$hallmark` 传入的技能动词。例如执行设计审计时调用 `$hallmark audit <target>`；不得把 `hallmark` 当作需要安装或探测的独立终端命令。
- `specify` CLI 只用于其实际提供的初始化、版本查询和项目脚本前置检查等操作；必须先以 `specify --help` 或项目脚本确认可用子命令，不能从 Skill 名称推断 CLI 能力。
- Codex 必须能发现 `.agents/skills/speckit-*` 和项目级 `.agents/skills/hallmark/`。Hallmark 及其必需参考资料应作为项目技能提交；不得提交其运行记录、缓存、凭据或浏览器状态。
- gstack 必须通过团队模式安装在开发者或 Agent 环境中。gstack 源码、插件和依赖必须在仓库外；不得复制、vendoring、以 Git submodule 引入或以 symlink 暴露到本仓库。
- gstack 团队模式使用 `gstack-team-init required` 生成仓库内 `.claude/` 引导配置；变更前先审阅差异。团队模式不得覆盖本文件的 Spec Kit 主线、回写协议、验证门禁或外部写授权。
- 缺少当前阶段明确要求的 CLI 或 skill 时，停止该阶段，报告缺失项和官方安装方式；按需可选的 gstack skill 缺失不阻断 Spec Kit 主线。
- 必须提交 Spec Kit 的项目产物：`.specify/`、`.agents/skills/speckit-*` 和 `specs/`。
- 更新 Spec Kit 项目文件前，先备份 `.specify/memory/constitution.md` 和已定制的模板/skills，再执行：

  ```bash
  specify init --here --force --integration codex --integration-options="--skills" --script sh
  ```

  初始化后必须恢复并复核仓库定制，不能让升级覆盖 constitution 或测试门禁。
- 对真实 Web 交互、认证页面、视觉检查或浏览器 QA，优先使用 gstack `/browse`；不得使用 `mcp__claude-in-chrome__*`。仓库文件、公开资料和 API 文档等只读检索仍使用适合的现有工具。
- gstack `/autoplan` 与 `/spec` 只能提供探索或草稿输入，不能替代 `$speckit-*` 主线，也不能覆盖 `spec.md`、`plan.md` 或 `tasks.md`。

## 中文术语与文案规范

新增或修改文档、代码注释、docstring 或程序文案时，必须同时使用项目级 `$domain-glossary` 与 `$chinese-documentation`。程序文案包括 CLI 帮助与错误信息、日志提示、交互提示和 UI 文本；不得因改动短小或只涉及一行注释而跳过。

按以下顺序执行：

1. 先使用 `$domain-glossary`：读取 `DOMAIN_GLOSSARY.md`、相关 feature artifacts 和代码上下文，识别同义词、歧义词与领域术语。使用已有规范术语；出现新概念、语义变化或未决歧义时，先更新 `DOMAIN_GLOSSARY.md`，写明定义、应避免的别名和必要的概念关系。
2. 再使用 `$chinese-documentation`：检查并改写机翻味、欧化句式、逐词直译和过度翻译，复核中英文空格、全半角标点、首次出现的中英对照及全文一致性。中文应按目标读者可理解的业务含义表达，不得把仓库内部英文术语机械地逐词翻译后直接暴露给用户。
3. 交付前按两个 skill 的检查项复核本次新增或修改的全部适用文本；发现术语问题时，先修正词表，再同步修正文档、注释和程序文案。

代码标识符、数据库字段、API 字段、CLI 参数、枚举值、协议字面量和用户输入中的原文引用保持原样，并使用反引号标识；只规范它们周围的说明文字。领域术语按语境翻译，不做全局字符串替换。例如 `leg` 不得默认译为“腿”：投资事件中的 `cash leg` 写作“现金部分”，`security leg` 写作“证券部分”，swap 的 `from` / `to` 写作“付出资产”/“换入资产”，面向用户的 `open-leg pending` 写作“待配对关系”或结合上下文写作“未配对的一笔”。内部状态名首次出现时可写成“待配对关系（`open-leg pending`）”。

`spec.md`、`plan.md` 和 `tasks.md` 仍是需求、方案与任务的事实源；`DOMAIN_GLOSSARY.md` 只约束命名和面向读者的解释。若术语分歧反映业务语义变化，先按 Spec 演进策略更新对应 artifact，再更新词表。

## 变更分级与适用范围

开始实施前，主 session 必须按风险选择路径。分级降低不必要的流程成本，不降低财务正确性、数据安全、公共契约和可回滚性的要求；实施中命中升级条件时，立即停止轻量路径并切换到 A 类完整 Feature。

| 等级 | 适用条件 | 必需流程 | 最小验证 |
|------|----------|----------|----------|
| A：完整 Feature | 新领域能力、领域规则变化、完整页面、跨模块重构、公共接口或依赖边界、财务计算、币种与精度、数据模型、持久化、迁移、兼容性、审计、安全敏感变更 | 完整 Spec Kit：`specify → clarify → plan → tasks → analyze → implement → converge` | 受影响测试、完整回归、类型检查、lint、构建、`git diff --check`、gstack `/review`，以及适用的 `/qa`、Hallmark `audit`、双后端契约矩阵、`/benchmark`、`/cso` |
| B：轻量 Feature | 在既有架构中增加局部能力；不改变领域规则、金额语义、持久化、权限、路由、公共契约或依赖；影响范围明确 | 在当前活跃 feature 的 `tasks.md` 或独立轻量变更记录中写明目标、非目标、验收、受影响文件和风险判断；测试先行、最小实现、更新验证证据 | 受影响测试、类型检查或构建、`git diff --check`、范围化 gstack `/review`；真实 Web 交互按范围运行 `/qa`，UI 或样式改动按范围运行 Hallmark `audit` |
| C：局部 Bug 修复 | 根因明确、恢复既定行为、可由单个或少量回归测试证明；不涉及金额、持久化、权限、公共契约、路由或跨模块边界 | 复现缺陷、先写失败回归测试、最小修复；在当前任务记录或提交说明中标明根因与验证 | 新增回归测试、受影响测试、`git diff --check`、短范围 gstack `/review`；真实 Web 交互缺陷运行针对性 `/qa` |

以下任一条件强制升级为 A 类：根因不明或同一修复连续失败 2 次；涉及金额、币种、精度、余额、关系匹配、数据写入、SQLite/PostgreSQL、迁移、权限、敏感数据、外部回调、公共 API/CLI/SDK、路由、新依赖、超过 3 个独立模块，或需求解释发生变化。

仅文案、注释、拼写、格式化或不改变行为的文档维护不属于 A/B/C 类；仍须检查 diff，运行与改动相称的静态校验，并在交付时说明验证证据。

## 唯一事实源与回写协议

- `.specify/memory/constitution.md` 定义项目不可妥协的工程原则。
- A 类完整 Feature 的 `specs/<feature>/spec.md` 定义“做什么和为什么”，`plan.md` 定义技术方案，`tasks.md` 定义执行顺序与完成状态；B/C 类以当前 `tasks.md`、独立轻量变更记录或提交说明保存最小事实源。
- 代码和测试是相应 artifacts 或轻量记录的实现。发现需求或方案错误时，先更新对应记录，再继续实施。
- 一个 feature 必须目标单一、可独立测试、可回滚；范围过大时先拆成多个 feature。
- `.specify/feature.json` 的 `feature_directory` 指向当前活跃 feature；sequential 编号目录 `specs/00N-short-name/` 为默认布局。
- 采纳 gstack 或 Hallmark 的结论后，必须记录来源 skill、采纳或拒绝结论、理由和正式产物的落点。CEO、工程、安全、设计与发布类建议即使拒绝也应留下简短理由。

| 结论类别 | 必须回写的正式产物 |
|----------|--------------------|
| 用户价值、范围、非目标、验收与边界 | A 类为 `spec.md`；B/C 类为最小变更记录 |
| 架构、数据流、接口、安全、性能、部署、测试或 UI 设计策略 | A 类为 `plan.md`、`research.md`、`data-model.md`、`contracts/` 或适用设计产物；B/C 类仅在风险判断需要时记录 |
| 缺陷、实施补漏、验证动作、发布准备 | A/B 类为 `tasks.md` 或独立变更记录；C 类可在提交说明记录 |

gstack 临时报告、截图、设计稿、浏览记录和学习记忆，以及 Hallmark 的预检缓存、设计记录和预览文件，均只能作为辅助证据，不能成为长期唯一事实源。评审、测试或设计审计发现规格或方案缺口时，A 类必须按 Flow-Back 更新最近正确的 artifact；B/C 类必须更新最小变更记录或在升级后补齐完整 artifacts，再修复实现。

## Spec 演进策略

A 类完整 Feature 按 Spec Kit evolving-specs 的三种模型选择；B/C 类仅在最小 artifacts 范围内保留目标、
风险与验证证据，不得借轻量路径改变需求、架构或领域语义。

1. **Flow-Forward（A 类默认）**：新能力、新边界，或既有 feature 已 Complete：用 `$speckit-specify` 新建 `specs/00N-...`，走完整主流程。Complete 历史目录只读保留，不回写新需求；新目录在 Context 中 cross-link Supersedes / Extends 关系。
2. **Living Spec（A 类当前活跃 feature）**：同一目标内改范围、验收或领域语义：先改当前 `spec.md`（`$speckit-clarify` 或显式编辑），再同步 `plan.md` / `tasks.md`，运行 `$speckit-analyze` 后再实施。大改前优先干净工作树或独立分支。
3. **Flow-Back（全部等级）**：先判断发现是否只影响局部实现、任务记录、需求或技术策略。A 类按完整 artifact 链路回写；B/C 类至少回写最小变更记录和验证证据。发现需求、架构、领域语义或风险升级时，立即切换为 A 类并对齐整套 `spec` / `plan` / `tasks`。

升级 Spec Kit 项目文件仍按「工具、安装与能力边界」中的备份 → `specify init --force` → 恢复复核流程；不得用升级覆盖 `specs/`。

## 执行流程

以下第 1 至 3 节是 A 类完整 Feature 的强制主流程。B/C 类不生成完整 artifacts，也不调用完整
Spec Kit 链路；必须遵守「变更分级与适用范围」中的最小记录、测试、审查、升级和外部授权要求。

### 1. 思考：A 类规格与产品挑战

1. 阅读 constitution、现有代码、测试、相关文档和当前 feature artifacts。
2. 使用 `$speckit-specify` 创建或更新 `spec.md`。
3. 使用 `$speckit-clarify` 消除会影响范围、验收、财务语义、数据模型、迁移或兼容性的歧义。
4. 问题定义不清、存在产品假设或范围混乱时，使用 gstack `/office-hours`；对用户可见能力、产品方向或明显范围取舍，运行 `plan-ceo-review`。采纳结论后回写 `spec.md`，重新检查验收场景。
5. 根因未定位的已知问题使用 `/investigate`；它不能替代规格分析或“先改再看”的试错。
6. 新页面、视觉方向或设计系统探索可使用 `/design-consultation`、`/design-shotgun` 或 Hallmark `study`；结论必须先落入正式规格，不能直接生成生产实现。

规格必须包含可独立验证的用户场景、边界/失败场景、明确的非目标和可度量成功标准。涉及金额、币种、持久化或迁移时，还必须写明精度、舍入、幂等、来源追踪、兼容与回滚口径。涉及数据库行为时，还必须分别定义 PostgreSQL 与 SQLite 的等价行为、允许的运行差异，以及禁止的自动回退、双写和隐式跨后端迁移。

### 2. 计划：A 类技术、设计与架构挑战

1. 使用 `$speckit-plan` 生成 `plan.md`、`research.md`、`data-model.md`、`contracts/` 和 `quickstart.md` 中适用的产物。
2. 对跨模块、公共接口、财务计算、数据模型、持久化、迁移、安全、性能或部署拓扑变更，运行 gstack `plan-eng-review`；把采纳的结论回写 `plan.md` 及相关设计产物。
3. 用户界面、交互或视觉层级变更运行 `plan-design-review`；API、CLI、SDK、安装流程或开发者文档变更运行 `plan-devex-review`；认证授权、敏感数据、外部回调、上传或供应链风险运行 `/cso`。
4. 新页面、重大视觉改造或设计系统变更还必须在 `spec.md` 明确受众、核心任务、信息架构、真实内容或素材边界和可访问性目标。使用 Hallmark 前先读取现有 `design.md`、字体、调色板、间距、动效依赖和框架信号，优先复用已有设计系统。
5. Hallmark 提出的宏观结构、主题令牌、交互状态、视觉审计和响应式策略，必须回写 `plan.md` 或设计产物。建议新建 `design.md`、`tokens.css`、`.hallmark/` 或预览文件时，先在任务拆分前确认其符合现有设计系统和提交策略。
6. 再次执行 plan 中的 Constitution Check。存在未获明确批准的 constitution 违例时不得进入任务拆分。

持久化方案必须包含 PostgreSQL/SQLite schema、事务、并发、查询和错误合同的差异清单，并说明如何通过共享 Application Service 与后端测试矩阵证明用户可见行为基本等价。

### 3. 计划：A 类任务拆分与一致性门禁

1. 使用 `$speckit-tasks` 生成按依赖排序、可执行的 `tasks.md`。
2. 任务必须覆盖规格中的每项需求与验收场景。所有可执行行为、财务逻辑、数据、迁移和接口变更都必须先安排失败测试，再安排最小实现和验证。持久化相关任务必须同时安排 SQLite 集成测试和真实 PostgreSQL 集成测试。
3. UI 任务应明确页面或组件范围、拟修改/创建的文件、真实素材来源、设计令牌、可访问性、8 种组件状态、响应式宽度和设计审计步骤。删除多个组件、页面或生产文件必须先取得用户对文件级计划的明确确认。
4. 真实浏览器 QA 因工作树存在本会话已验证的未提交变更而无法启动时，若这些变更已完成相称测试、`git diff --check` 通过，且使用者已明确授权本地提交，自动按逻辑单元提交后继续 QA；不得暂存、丢弃或提交来源不明、未验证或未获授权的变更。该授权仅限本地提交，不包含推送、创建 PR 或合并。
5. `tasks.md` 必须包含适用的发布门禁任务，并将它作为 PR 准入的唯一门禁清单：所有 feature 均须安排 `$speckit-analyze`、`$speckit-converge`、受影响测试、完整回归、构建和 `git diff --check`；所有代码变更须安排 gstack `/review`；Web 行为变更须安排 gstack `/qa`；UI、样式或交互变更须安排 Hallmark `audit`；持久化变更须安排 SQLite 与真实 PostgreSQL 契约矩阵。每项验证任务必须明确适用条件、命令或 skill、验收范围和证据记录位置。不适用项必须保留任务并写明不适用理由，不得静默删除。
6. 使用 `$speckit-analyze` 检查 spec、plan 和 tasks 的一致性与覆盖率。存在 CRITICAL 或 HIGH 问题时，回到对应 artifact 修正并重新 analyze；不得直接实施。

### 4. 构建：A 类受控实施与 B/C 类最小修复

1. A 类主 session 负责完成规格、澄清、方案、任务拆分和一致性门禁，但不得直接实施产品代码；B/C 类主 session 可在最小记录与失败测试就绪后直接执行局部实现。
2. A 类只有在 `spec.md`、`plan.md`、`tasks.md` 均已就绪、Constitution Check 已通过，且 `$speckit-analyze` 不存在未解决的 CRITICAL 或 HIGH 问题时，主 session 才能进入实施阶段。
3. A 类实施必须在独立 feature 分支或 worktree 中使用 `$speckit-implement` 按 tasks 顺序执行。B/C 类优先在独立分支或 worktree 中实施；当前环境已隔离且变更局部时，可不调用 `$speckit-implement`，但必须遵循最小记录、测试先行和升级条件。阶段交接只以已记录的决策为依据，不得依赖未写入记录的对话决策。
4. 当前实施环境无法执行 `$speckit-implement` 时，A 类必须停止并报告缺失项；B/C 类不得以此跳过测试、审查、最小记录或升级门槛。
5. A/B/C 类均先运行新增测试确认其因目标行为缺失而失败，再小步实现并转绿；每完成一项立即更新适用任务或最小记录。
6. Hallmark 只在适用的 UI 变更范围内使用。A 类页面设计应明确宏观结构、导航/页脚、令牌和内容素材；组件必须覆盖默认、悬停、聚焦、激活、禁用、加载、错误、成功 8 种状态。B/C 类按受影响组件或状态验证，不得引入临时内联颜色或字体；所有新增颜色和字体必须引用集中声明的命名令牌。
7. 禁止编造指标、客户背书、评价、图片或案例，禁止重新绘制浏览器、手机或 IDE 等伪界面框架。保留既有路由、组件归属、文案意图、品牌和信息架构；删除多个组件、页面或生产文件前必须获得明确确认。
8. `/careful`、`/freeze`、`/guard` 可在数据库、迁移、生产诊断或范围易扩散操作时提供局部保护，但不能替代 worktree、Spec Kit tasks、测试先行或 Flow-Back。根因未知、同一问题两次修复仍失败、出现数据/权限/迁移风险，或需要扩大到任务外模块时，停止普通修复并运行 `/investigate`。高风险或有争议实现可使用 `/codex` 获取独立意见。
9. 不扩大既定变更范围，不顺手重构无关代码。A 类实施发现的新需求或架构决策必须先返回主 session，由主 session 回写完整 artifacts 后才能继续；B/C 类发现此类变化时必须升级为 A 类。
10. A 类完成后使用 `$speckit-converge` 对照 spec、plan、tasks 和代码；若追加任务，继续实施直至收敛。B/C 类完成后复核最小记录、回归测试、受影响验证和未解决风险。

### 5. 开发后验证（一）：按分级执行代码与设计审查

1. A 类在构建后、提交或进入发布准备前必须运行完整 gstack `/review`。B 类运行范围化 `/review`；C 类运行短范围 `/review`，至少核对根因、失败回归测试、最小 diff 和升级条件。阻断性 finding 必须修复并重新评审；若 finding 反映规格或方案缺口，A 类先更新对应 Spec Kit artifact，B/C 类先更新最小记录或升级。
2. A 类 UI/Web 变更在同一开发后验证阶段运行 `/design-review`，开发者体验变更运行 `/devex-review`，安全敏感变更运行 `/cso`；高风险变更或评审结论冲突时使用 `/codex` 获取第二意见。B/C 类只在变更实际覆盖相应范围时运行这些审查。
3. A 类完成 UI 设计后在同一阶段运行 Hallmark `audit`。B 类 UI、样式或交互变更按受影响组件或页面运行 Hallmark `audit`；C 类纯逻辑或非视觉修复不适用。`audit` 只输出按影响排序的设计问题，不直接修改；必要修复按分级回写后再实施。
4. A 类 Hallmark 审计与 gstack 设计评审至少检查信息层级、结构差异、真实内容、令牌一致性、可访问性和交互状态。页面在 320、375、414、768 px 宽度不得出现横向滚动、文本溢出或两行可点击文本；标题保持正体，支持 `prefers-reduced-motion`，焦点状态必须清晰可见。B/C 类仅检查受影响的同类风险；Hallmark 自检未通过时先修复再交付。

### 6. 开发后验证（二）：按分级执行行为、界面与回归测试

1. A 类在代码审查后、提交或进入发布准备前，运行受影响测试、完整测试套件、类型检查、lint 和构建中项目实际提供的命令。存储行为变更必须在 SQLite 与真实 PostgreSQL 上运行同一契约矩阵；任一后端缺少证据均不得声明完成。B/C 类运行新增回归测试、受影响测试和相称的类型检查或构建；命中升级条件时改按 A 类执行。
2. A 类 Web 行为或交互变更必须在同一开发后验证阶段运行 gstack `/qa`，覆盖主流程、错误/空状态和相关回归；修复后重新 QA。B 类按受影响用户流程运行 `/qa`；C 类仅对真实 Web 交互缺陷运行针对性 `/qa`。仅需报告问题且未获修复授权时使用 `/qa-only`。
3. A 类 UI 改动的 QA 必须覆盖 320、375、414、768 px 响应式检查、键盘操作、错误/空状态和相关回归；认证页面使用 `/setup-browser-cookies` 后再进行授权范围内的测试。B/C 类只覆盖受影响控件、相关键盘路径及可能受影响的响应式宽度。
4. A 类规格中有可度量性能目标、性能敏感路径或性能回归时运行 `/benchmark`。B/C 类只在变更实际影响已定义的性能敏感路径时运行。
5. A 类检查最终 diff、未跟踪文件和 tasks 完成状态后，才能声明该提交已完成开发后验证。验证任务从 `[ ]` 改为 `[X]` 时，必须在任务下记录实际执行的命令或 skill、结果、验证的 HEAD、比较基线（适用时）、执行时间和未解决风险；不得以计划、推测或旧结果代替实际证据。B/C 类在最小记录或提交说明中记录实际命令、结果和未解决风险。当前 HEAD、目标分支基线、依赖或运行环境发生影响验证的变化时，必须重新执行受影响验证。
6. 发布阶段以当前变更等级的记录为唯一 PR 准入清单：A 类要求全部适用 `tasks.md` 门禁为 `[X]`；B/C 类要求最小验证记录完整且与当前待推送提交对应。无论等级，比较基线必须与 PR 目标分支一致，且不得存在未说明的不适用项。`/ship` 不得另设或重复一套门禁。
7. 当待推送提交、目标分支基线和运行环境均未改变时，发布阶段复用已记录的开发后验证证据，不因创建或更新 PR 重复执行 `/review`、`/qa` 或同一测试。新增提交、目标分支同步、依赖或部署配置变化，或审查发现新风险时，必须从受影响验证项开始重新执行。

### 7. 发布：授权、交付与观察

只有用户明确要求创建 PR、发布或部署时，才进入外部写操作。授权按动作分别确认，技能默认行为不得扩大授权：

| 动作 | 最低授权 | 对应 gstack 技能 | 未获授权时 |
|------|----------|-----------------|------------|
| 本地提交 | 明确要求提交 | `/ship` 的本地准备部分 | 保持本地工作树与验证证据，不提交 |
| 推送、创建 PR | 明确要求推送或创建 PR | `/ship` | 不推送、不建 PR |
| 合并 | 明确授权具体 PR 合并 | `/land-and-deploy` | 只报告可合并状态与风险 |
| 部署 | 明确授权目标环境部署 | `/land-and-deploy` | 不触发部署 |
| 灰度扩大、流量切换、回滚 | 明确授权，既定紧急 runbook 除外 | `/canary` | 只观察、报告信号和提出建议 |
| 第三方资源、外部文档或公告 | 明确授权目标系统与操作范围 | `/setup-deploy`、`/document-release` | 仅生成草稿或建议 |

首次或部署配置变化时使用 `/setup-deploy`。发布后需要观察时使用 `/canary`；发现回归立即停止扩大发布并报告回滚路径。发布准备、回滚信息和验证证据必须回写 `tasks.md` 或 `plan.md` 的适用位置。

### 8. 反思：持续改进闭环

1. 在发布、里程碑或固定节奏后按需运行 `/retro`，可配合 `/health`、`/benchmark`、`/devex-review` 和 `/learn`；小型纯文案或低风险维护不强制复盘。
2. `/context-save` 与 `/context-restore` 只用于会话交接辅助。交接前必须先将关键决策、任务状态和验证证据落入 Spec Kit artifacts。
3. 本 feature 的未完成验证或缺陷回写当前 `tasks.md`；当前目标的需求或设计修正走 Living Spec 或 Flow-Back；新的独立改进走 Flow-Forward，创建新的 `specs/00N-...` feature。
4. 可迁移的长期规则经人工判断后更新 constitution、`AGENTS.md`、词表或测试策略；不得只保存在 gstack `/learn` 或 Hallmark 运行记录中。

## gstack（必需的全局安装）

每次开始 AI 辅助工作前，先确认 gstack 已安装：

```bash
test -d ~/.claude/skills/gstack/bin && echo "GSTACK_OK" || echo "GSTACK_MISSING"
```

若输出 `GSTACK_MISSING`，立即停止，不得跳过所需技能或绕过 gstack 错误。提示开发者执行：

```bash
git clone --depth 1 https://github.com/garrytan/gstack.git ~/.claude/skills/gstack
cd ~/.claude/skills/gstack && ./setup --team
```

完成后重启 AI 编码工具。gstack 文件路径使用全局路径 `~/.claude/skills/gstack/...`。

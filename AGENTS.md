# Finance Tracker Agent 工作流

本仓库采用 **OpenSpec + gstack + Hallmark**。OpenSpec 管理行为规格、变更提案、设计、任务和归档；gstack 提供产品、架构、质量与发布能力；Hallmark 负责 UI 设计纪律与界面质量。`CLAUDE.md` 是指向本文件的符号链接，修改 Agent 约定时只编辑 `AGENTS.md`。

## 工具、安装与能力边界

- 本机或 Agent 环境必须提供 OpenSpec CLI，当前迁移基线为 `1.7.0`；运行时需要 Node.js `20.19.0` 或更高版本。
- 安装或升级 OpenSpec：

  ```bash
  npm install -g @fission-ai/openspec@latest
  openspec --version
  openspec update
  ```

- 在仓库根目录初始化 Codex 集成：`openspec init --tools codex`。Codex 使用 skills，不生成命令文件；本仓库的 `.codex/skills` 指向 `.agents/skills`。
- 当前可用的项目级 OpenSpec skills 是 `$openspec-explore`、`$openspec-propose`、`$openspec-apply-change`、`$openspec-update-change`、`$openspec-sync-specs` 和 `$openspec-archive-change`。CLI 验证使用 `openspec validate`，健康检查使用 `openspec doctor`。
- OpenSpec 的 CLI 命令运行在终端，`$openspec-*` 通过 Codex skill 调用；不要把 skill 名称当成 shell 子命令。
- gstack 必须全局安装在仓库外。每次开始 AI 辅助工作前确认：

  ```bash
  test -d ~/.claude/skills/gstack/bin && echo "GSTACK_OK" || echo "GSTACK_MISSING"
  ```

  若缺失，停止当前阶段并提示开发者按 gstack 官方方式安装；不要复制、vendoring、submodule 或 symlink 引入仓库。
- 真实 Web 交互、认证页面、视觉检查或浏览器 QA 优先使用 gstack `/browse`；不要使用 `mcp__claude-in-chrome__*`。
- `openspec/`、`.agents/skills/openspec-*`、`openspec/specs/` 和 `openspec/changes/` 是必须提交的项目产物。不得提交 OpenSpec 运行缓存、凭据或浏览器状态。

## 中文术语与文案规范

新增或修改文档、代码注释、docstring 或程序文案时，必须按以下顺序使用项目级 `$domain-glossary` 与 `$chinese-documentation`：

1. `$domain-glossary` 先读取 `DOMAIN_GLOSSARY.md`、相关 OpenSpec 主规格、active changes 和代码上下文，识别同义词、歧义词与领域术语。新概念或语义变化先更新词表，再写文档和文案。
2. `$chinese-documentation` 检查中英文空格、全半角标点、首次出现的中英对照、机翻味和欧化句式。
3. 交付前按两个 skill 的检查项复核本次新增或修改的全部适用文本。

代码标识符、数据库字段、API 字段、CLI 参数、枚举值、协议字面量和用户输入中的原文引用保持原样，并使用反引号；只规范它们周围的说明文字。`leg` 按业务语境写成**现金部分**、**证券部分**、**付出资产**、**换入资产**或具体流水，不逐词翻译。

## 变更分级

分级降低不必要的流程成本，但不降低财务正确性、数据安全、公共契约和可回滚性的要求。实施中命中升级条件时，立即切换到 A 类。

| 等级 | 适用条件 | 必需流程 | 最小验证 |
|------|----------|----------|----------|
| A：完整变更 | 新领域能力、领域规则、跨模块重构、公共接口或依赖边界、财务计算、币种与精度、数据模型、持久化、迁移、兼容性、审计、安全敏感变更 | 完整 OpenSpec：`explore → propose → validate → apply → archive` | 受影响测试、完整回归、类型检查、lint、构建、`git diff --check`、gstack `/review`，以及适用的 `/qa`、Hallmark `audit`、双后端契约矩阵、`/benchmark`、`/cso` |
| B：轻量变更 | 既有架构内的局部能力，不改变领域规则、金额语义、持久化、权限、路由、公共契约或依赖 | 小型 OpenSpec change；至少记录目标、非目标、验收、受影响文件、风险和证据 | 受影响测试、类型检查或构建、`git diff --check`、范围化 gstack `/review`；Web/UI 按影响运行 `/qa` 或 Hallmark `audit` |
| C：局部缺陷修复 | 根因明确、恢复既定行为、可由少量回归测试证明 | 复现缺陷、先写失败回归测试、最小修复 | 新增回归测试、受影响测试、相称的类型检查或构建、`git diff --check`、短范围 gstack `/review` |

以下任一条件强制升级为 A 类：根因不明或同一修复连续失败 2 次；涉及金额、币种、精度、余额、关系匹配、数据写入、SQLite/PostgreSQL、迁移、权限、敏感数据、外部回调、公共 API/CLI/SDK、路由、新依赖、超过 3 个独立模块，或需求解释发生变化。分类存疑时选择更高等级。

## 唯一事实源与 OpenSpec 回写协议

- `openspec/project-context.md` 定义跨变更的工程原则。
- `openspec/specs/<capability>/spec.md` 定义当前能力的行为、需求和场景，是主规格事实源。
- `openspec/changes/<name>/` 定义未完成变更；`proposal.md` 说明为什么和范围，`specs/` 保存 delta，`design.md` 说明如何实现，`tasks.md` 说明执行顺序。
- `openspec/changes/archive/YYYY-MM-DD-<name>/` 保留已完成变更的完整审计记录。归档前必须把 delta 同步到主规格，不能把未完成工作伪装成完成。
- 旧规格迁移记录见 `openspec/MIGRATION.md`；每个迁移 change 的 `legacy/` 保存原始技术方案、任务、合同、研究和检查清单。`legacy/` 是历史证据，不是新变更的唯一事实源。
- 发现需求、领域语义、架构、数据库、接口或风险变化时，先更新对应 OpenSpec artifact，再继续实施。不要让代码、聊天记录或临时报告成为唯一事实源。
- gstack、Hallmark、QA 或安全审查的采纳/拒绝结论，必须回写 `proposal.md`、`design.md`、`tasks.md` 或 `openspec/project-context.md` 的相称位置，并记录理由。

## OpenSpec 演进策略

1. **新能力**：使用 `$openspec-explore`（可选）和 `$openspec-propose <description>` 创建独立 change；完成后通过 `$openspec-archive-change` 归档。
2. **当前 active change**：范围、验收或财务语义变化时，先运行 `$openspec-update-change`，同步 proposal、delta spec、design 和 tasks，再恢复实现。
3. **行为变化**：delta spec 使用 `ADDED`、`MODIFIED`、`REMOVED` 或 `RENAMED`；`MODIFIED` 必须包含完整更新后的 requirement 和场景。
4. **纯实现细节**：只需修改 design、tasks 或代码，不要无理由复制整份主规格；如果实现发现需求变化，回写到 spec 后再继续。
5. **验证与归档**：实现完成后运行 `openspec validate --all --strict`、`openspec doctor`、受影响测试和完整回归；只有任务、测试和审查证据齐全时才归档。

## A 类实施流程

1. 阅读 `openspec/project-context.md`、相关主规格、active changes、`DOMAIN_GLOSSARY.md`、代码、测试和文档。
2. 使用 `$openspec-explore` 明确代码现状、范围和风险；需要正式工作时使用 `$openspec-propose`。
3. 检查 proposal、delta specs、design 和 tasks；涉及金额、数据库、迁移、公共契约、安全或兼容性时，必须显式记录精度、舍入、幂等、来源追踪、事务、回滚和 PostgreSQL/SQLite 差异。
4. 进入实施前运行 `openspec validate --all --strict`，不存在 ERROR；任务必须先安排因目标行为缺失而失败的测试，再安排最小实现。
5. 使用 `$openspec-apply-change` 按 tasks 顺序实施。每完成一项立即更新 tasks；遇到需求或架构变化先暂停并回写 artifact。
6. 实施完成后使用 `$openspec-archive-change`，由 archive 流程同步 delta、保留 change 历史并更新主规格。

B/C 类可以在当前隔离 worktree 中直接完成局部实现，但仍须遵守测试先行、OpenSpec 最小记录、审查和升级条件。当前环境无法执行必须的 OpenSpec skill 时，停止该阶段并报告缺失项；不要用未经记录的手工流程替代。

## 财务、数据库与安全门禁

- 所有金额和数量使用 `Decimal`/`NUMERIC(38,18)`；禁止 float 承载账务结果。精度超限、非法时间、币种冲突和无法验证的关系必须在事务提交前失败。
- 导入、转换、关系配对、投影和同步必须保留来源行快照、业务行标识和可复核证据；重复输入必须幂等，失败不得发布部分正式事实。
- 每个进程只通过 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite；不得自动回退、双写或隐式跨后端迁移。
- PostgreSQL 与 SQLite 的持久化变更必须使用同一 Application Service 和同一用户可见合同，分别提供 SQLite 集成证据与真实 PostgreSQL 集成证据。
- 领域规则与 CLI、Web、数据库和外部数据源边界解耦；新增依赖、抽象和基础设施必须在 design 中说明当前需求和替代方案。
- 不把凭据、Token、账户隐私、真实账单或未经去标识化的样本写入日志、测试夹具或仓库产物。

## 开发后验证

- A 类必须运行：OpenSpec `validate`、`doctor`、受影响测试、完整测试套件、项目实际提供的类型检查、lint、构建、`git diff --check`、完整 gstack `/review`，以及适用的 `/qa`、Hallmark `audit`、`/cso`、`/benchmark`。
- B/C 类至少运行新增回归测试、受影响测试、相称的类型检查或构建、`git diff --check` 和范围化 gstack `/review`；真实 Web 缺陷运行针对性 `/qa`，UI 或样式改动运行 Hallmark `audit`。
- Web 变更的 QA 覆盖主流程、错误/空状态、键盘操作及受影响的响应式宽度；UI 变更检查焦点、禁用、加载、错误、成功等状态，以及 320、375、414、768 px 宽度无横向滚动或文本溢出。
- 验证任务完成后，在当前 change 的 `tasks.md` 下记录实际命令或 skill、结果、当前 `HEAD`、比较基线、执行时间和未解决风险。当前 HEAD、目标分支、依赖或环境变化后，必须重新运行受影响验证。
- 不适用的检查必须保留在任务记录中并说明原因，不得静默删除。

## 发布与外部写授权

只有用户明确要求创建 PR、发布或部署时，才执行对应外部写操作；授权按动作分别计算：

- 本地提交：需要明确要求提交。
- 推送或创建 PR：需要明确要求推送或创建 PR。
- 合并、部署、流量切换或回滚：需要明确授权具体目标和环境。
- 第三方资源、外部文档或公告：需要明确授权目标系统与范围。

没有授权时保持工作树和验证证据，不提交、不推送、不建 PR、不合并、不部署。`openspec archive` 只整理仓库内规格记录，不代表已获发布授权。

## 其他安全规则

- 删除或覆盖文件前先确认精确目标；优先使用可恢复的移动操作，不对工作区、仓库根目录或宽泛目录执行递归删除。
- 不扩大既定变更范围，不顺手重构无关代码。删除多个页面、组件或生产文件前必须取得用户对文件级计划的明确确认。
- 根因未知、同一问题两次修复仍失败、出现数据/权限/迁移风险，或需要扩大到任务外模块时，运行 gstack `/investigate` 并回写结论。

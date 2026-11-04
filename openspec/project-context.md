# Finance Tracker 项目上下文

本文是 OpenSpec 变更共享的工程约束。它约束所有 `openspec/changes/`，不替代具体能力的主规格；能力行为以
`openspec/specs/` 为准，原始迁移证据保存在对应 change 的 `legacy/` 目录。

跨前后端的异常、输入、写入、模型和复杂度取向由 [产品编码规范](../docs/shared/product-coding-rules.md) 维护；本文件保留财务、安全与运行约束，变更流程和验证强度由 [AGENTS.md](../AGENTS.md) 维护。

## 不可妥协的原则

### 财务正确性与可审计性

- 金额、数量、汇率和成本必须使用精确十进制语义；币种、精度和舍入规则必须显式定义，不能用二进制浮点承载账务结果。
- 导入、转换、关系配对、投影、迁移和同步必须保留来源、关键决策和可复核证据；不能静默丢弃、重复记账或覆盖历史事实。
- 相同输入的重复处理必须幂等；无法证明安全的异常路径必须失败关闭并提供可操作错误。
- 每个汇总或快照都必须能追溯到原始账本记录和确定性规则。

### 测试先行与验证证据

测试先行、各级验证范围及交付证据按 [AGENTS.md](../AGENTS.md#审查验证与证据) 执行；如何用行为和故障注入验证不变量，见 [产品编码规范](../docs/shared/product-coding-rules.md#6-用行为和证据验证)。

### 显式数据库选择与等价行为

- PostgreSQL 与 SQLite 都是正式支持的运行时事实源；调用方必须通过 `FT_DATABASE_URL` 显式选择一个后端。
- 不得自动探测、静默回退、双写或隐式跨后端迁移。相同 Application Service、CLI 合同、财务语义、精度、幂等、审计和工作区隔离必须产生等价的用户可见结果。
- 所有持久化、schema 或查询变更必须同时提供 SQLite 自动化集成证据和真实 PostgreSQL 集成证据；方言差异只能存在于 persistence adapter。
- 上述“不得自动探测”适用于运行时和应用代码；测试代码不得自动启动、探测或切换数据库。完成开发和 SQLite 回归后，执行者必须人工准备本地专用 PostgreSQL 测试库，优先使用 Docker PostgreSQL 或本机 `psql` 可连接的数据库，并通过 `FT_TEST_POSTGRES_URL` 显式配置。测试库名称必须以 `_test` 结尾；未配置或不可连接时，PostgreSQL 矩阵属于未完成，不得宣称双后端验证通过。

### 边界、安全与最小复杂度

- 领域规则必须与 CLI、Web、数据库和第三方数据源边界解耦，通过明确接口传递数据。
- 产品面向个人记账；具体输入边界、并发取舍、抽象及依赖规则见 [产品编码规范](../docs/shared/product-coding-rules.md)。
- 凭据、Token、账户隐私和原始账单只能存放在受控位置；测试夹具和日志必须去标识化。
- CSV、JSON、YAML 和 PDF 只能作为产品明确支持的原始输入或显式导出，不能成为运行时事实源、迁移载体或隐式回退。

## 变更流程

变更分级、升级条件、七阶段强度、UI 原型与审计、审查、验证证据和外部写授权以根目录 `AGENTS.md` 为唯一来源。本文件只定义跨变更工程原则；每个 OpenSpec change 必须按其风险遵守这些原则并在相应 artifact 中留下证据。

## OpenSpec 工作约定

- `openspec/specs/` 是当前行为的唯一规格事实源；按 `<module>/<capability>/spec.md` 组织；从 [业务能力导航](specs/README.md) 定位当前合同，不再使用 sequential feature 目录作为运行入口。
- `openspec/changes/<name>/` 是未完成变更，至少包含 `proposal.md`、`specs/<module>/<capability>/spec.md`、`design.md`（适用时）和 `tasks.md`。
- `openspec/changes/archive/YYYY-MM-DD-<name>/` 是已完成变更的审计记录；归档前必须把 delta 同步到主规格，不能把未完成工作伪装成完成。
- 主规格写行为合同：使用 `### Requirement:` 和 `#### Scenario:`，每条 requirement 至少有一个可验证场景，使用 `MUST` 或 `SHALL` 表示规范性要求。
- 设计细节进入 `design.md`，执行顺序进入 `tasks.md`；不要把内部类名、框架选择或逐行实现步骤塞进主规格。
- 每次变更完成后运行 `openspec validate --all --strict` 和 `openspec doctor`；实现、Web、UI、数据库和安全风险仍按本文件及根目录 `AGENTS.md` 运行相称的审查、测试和 QA。审查工具可以替换，但范围、finding、采纳/拒绝理由和验证证据必须回写正式 artifact。

## 运行基线

- 生产运行时为 Kotlin/JVM 21；构建、测试与部署以 `compose/` Gradle 工程、`Dockerfile` 和 `render.yaml` 为准。Python 只保留与后端无关的开发工具。
- PostgreSQL 与 SQLite 由 `FT_DATABASE_URL` 显式选择；任何新能力不得依赖文件账本 backend。
- 账户、币种、时间、来源和业务行标识的语义必须在相关 OpenSpec 主规格中明确，不能依赖字符串猜测或隐式默认值。

# Finance Tracker

多账户、多币种个人财务工具：把消费、储蓄与投资放进同一套可审计账本，并提供投资组合估值与周期表现。

项目由 Kotlin/Ktor 后端和 Compose Multiplatform 客户端组成。后端是金额、余额、权限、来源、关系和审计的唯一事实源；Compose 的 Web、Android、iOS 目标通过同一套 HTTP API 访问它。

现金收支的关系约束、派生业务标签和稳定收支引用已接入后端与 Compose。当前行为以 [交易关系规格](openspec/specs/cash-ledger/transaction-relations/spec.md) 和 [功能地图](docs/feature-map.md) 为准；本工作树的界面修复范围与验证见 [UI 审查修复](openspec/changes/archive/2026-10-31-fix-cash-ui-audit-findings/tasks.md)。

Kotlin 后端是唯一生产入口，启动时会为空的 PostgreSQL／SQLite 数据库创建 `20261030_53` 基线 schema；非空库只校验版本。部署与运行参数见 [后端说明](compose/backend/README.md)。

## 运行边界

运行时通过 `FT_DATABASE_URL`（或 Render 的 `DATABASE_URL`）显式选择一个事实源——PostgreSQL 或文件型 SQLite。二者共享 Application Service、财务语义与同一份 Kotlin 基线 schema；不得自动回退（no fallback）、不得双写（dual-write）、不得从旧版本隐式迁移。CSV、XLS、PDF 只作导入输入或预览，不是账本。

空库会在启动时建到当前基线；已有库不会自动改表，也不会创建 workspace 或切换数据库。库不可达、schema 版本不是 `20261030_53` 或残缺时直接失败。SQLite 使用 WAL、外键与有界写锁等待；数据库繁忙、读写权限或 schema 故障会返回脱敏的 `storage.*` 错误码。

## 环境准备

需要 JDK 21、Node.js 20.19+（Compose Web 本地预览与 QA）、Docker（Render 镜像或本地容器），以及 PostgreSQL 或本地可写 SQLite 路径。Python 只用于与后端无关的开发工具（UI token、Web 静态构建、HTTP 验证），不是运行依赖。

```bash
export FT_DATABASE_URL='postgresql://localhost/finance_tracker'
```

SQLite 示例：

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////absolute/path/finance-tracker.db"
```

空数据库在首次启动时自动建到当前基线；已在 `20261030_53` 的数据库只校验。不会自动从旧版本升级，也不会回退到其他数据库。初始化 workspace 仍需显式执行后端提供的管理操作；服务端不会自动创建 workspace。

## 启动后端与 Compose

构建并启动后端：

```bash
(cd compose && ./gradlew :backend:installDist --no-configuration-cache)
FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db' \
FT_IMPORT_STAGING_BACKEND=memory FT_IMPORT_STAGING_ALLOW_MEMORY=1 \
FT_HOST=127.0.0.1 FT_KOTLIN_PORT=18240 FT_WEB_ORIGIN=http://127.0.0.1:5173 \
./compose/backend/build/install/backend/bin/backend
```

生产部署使用 `Dockerfile` 和 `render.yaml`：Render 注入 `PORT`、`FT_DATABASE_URL`、`FT_WEB_ORIGIN` 与导入暂存配置，健康检查路径为 `/health`。Render 的实际创建与发布需要单独授权。

现金导入的临时会话默认最多保存 128 MiB 序列化内容，其中解析行最多 25 MiB；本地内存模式合计最多 256 MiB。这些限制与上传大小及 JVM 峰值内存不同，大批导入还需要容纳计划对象和编码缓冲；部署前按实际批次验证运行内存。超限及配置说明见[现金导入暂存](docs/import/cash-import-flow.md#53-临时内容和长期数据)。

Compose Multiplatform 是默认客户端工作流：

```bash
python3 scripts/generate_ui_tokens.py
(cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache)
(cd compose && ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache)
(cd compose && ./gradlew :androidApp:assembleDebug --no-configuration-cache)
(cd compose && ./gradlew :shared:iosSimulatorArm64Test :shared:linkDebugFrameworkIosSimulatorArm64 --no-configuration-cache)
node compose/qa/web/scripts/start-compose-demo.mjs
node compose/qa/web/scripts/start-compose-offline-demo.mjs
```

Compose Web 后端连接预览使用 `node compose/qa/web/scripts/start-compose-demo.mjs`；无 API 后端演示使用 `node compose/qa/web/scripts/start-compose-offline-demo.mjs`。Compose Web QA 工具位于 `compose/qa/web/`。Android/iOS 演示方式见 [`compose/README.md`](compose/README.md)，历史 UI 设计资料见 [`design/README.md`](design/README.md)。

本地 Compose 调试通过 `FT_API_PROXY_ORIGIN` 配置 API 代理；生产目标只接受 HTTPS。具体 target 和运行参数见 [`compose/README.md`](compose/README.md)。

## API 能力

所有 `/api/v1` 业务请求都使用 `Authorization: Bearer <session-token>`，当前 workspace 和角色由服务端会话解析。Compose 通过平台实现的 `TokenStore` 保存会话令牌；账单密码只在当前导入请求内存中存在。

| 能力 | API 入口 | 客户端承载 |
|---|---|---|
| 登录、注册、workspace、成员 | `/api/v1/auth/*` | Compose Multiplatform |
| 账户管理 | `POST /api/v1/accounts`、`/api/v1/accounts/by-id/{account_id}`；写请求携带幂等键和所需修订 | Compose 查询已接入，独立编辑及导入退出补偿待接入 |
| 收支浏览、详情与证据 | `/api/v1/cash-projections`、`/api/v1/cash-records/{transaction_id}`、`/api/v1/evidence/*` | Compose 已接正式／草稿浏览、默认开启的零元筛选、详情与来源证据；零元条件同步影响列表、计数、汇总和分页 |
| 手工流水、余额校准、转账 | `/api/v1/cashflow/*` 或 `POST /api/v1/cash-records` | 既有入口保留，不等同于新维护计划 |
| 现金账单解析、统一预览和确认 | `/api/v1/cash-import/*` | Compose 已接会话、来源账户映射、分页核对、确认收据与错误诊断；功能演示验收仍在进行 |
| 正式编辑、草稿补全与当前关联维护 | `/api/v1/cash-mutations/*`、`PATCH /api/v1/cash-records/{transaction_id}/draft` | Compose 已接草稿补全、明确转正式、公共字段编辑与当前关系维护；联合方案改选仍不可提交 |
| 分类与删除 | `/api/v1/cash-projections/*` 分类／删除命令、`DELETE /api/v1/cash-records/{transaction_id}` | 既有入口须按当前版本及目标范围合同验收 |
| 投资查询与事件 | `/api/v1/investment-events`、`/api/v1/investment-portfolio` | Compose Multiplatform |
| 报表与查询 | `/api/v1/queries/*`、`/api/v1/reports/*` | Compose Multiplatform |
| 同步、投影运维 | `/api/v1/operations/*` | 仅管理员后端操作 |

金额、数量、汇率和成本在 HTTP 合同中使用十进制字符串，例如：

```bash
curl -X POST "$API_ORIGIN/api/v1/cashflow/add" \
  -H "Authorization: Bearer $SESSION_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":"-12.50","counterparty":"Coffee","account":"Wallet","currency":"CNY"}'
```

`viewer` 只能读取；所有写入经适用的应用服务和事务边界。手工新建及部分专用操作没有统一收据重放合同，网络超时不自动重试；导入确认、维护计划和草稿保存使用各自的 `Idempotency-Key` 与结果恢复合同。

## 账单导入

现金导入流程是“文件与账户准备 → 同一流水列表核对并一次确认 → 结果及后续补处理”。Compose 共享界面已接入文件与账户准备、待确认优先的连续分页列表、只读详情、确认收据和错误诊断；币种不足的已选账户会在进入预览前补齐。该入口的代表 Web 流程已做真实浏览器检查，Android／iOS 已完成相称编译和共享单测；设备交互 QA 未执行。流程从 [现金账单导入](docs/import/cash-import-flow.md) 阅读，入库后的模型、展示与操作见 [现金流水](docs/cash-ledger/cash-ledger.md)。投资账单及连接器同步使用各自领域模型，边界见 [导入总览](docs/import/import-flow.md)。

支持的来源包括：`alipay`、`wechat`、`icbc`、`icbc-debit`、`ccb-debit`、`icbc-asia`、`dfzq`、`ibkr`、`schwab`、`usmart-hk` / `usmart_hk`。交易所和 Polymarket 使用受控后端同步，不由客户端接触密钥。

现金来源对应按工作区、来源范围、可靠业务编号或已验证的出现次数核对，不单靠文件摘要或可变内容散列。`source_payload` 保存当前单条来源内容；原文件和密码不留存，流水与配对决定在最终确认时一起保存，账户命令独立立即生效。

## 安全与权限

- `FT_DATABASE_URL` 必须显式配置；不会回退到其他数据库或写第二份账本。
- 生产 `FT_WEB_ORIGIN` 和 Compose API 地址必须是 HTTPS origin，不能包含路径、查询参数或凭据。
- 连接器凭据只从后端受控配置加载；不会出现在请求体、客户端 bundle 或错误响应中。
- 账单密码不写入日志、TokenStore、缓存或数据库。
- 账户有正式事实后只能停用，不能硬删；关系确认、事实删除和投影重建都保留审计语义。

## 开发与验证

```bash
python3 scripts/generate_ui_tokens.py
(cd compose && ./gradlew :shared:wasmJsTest --no-configuration-cache)
(cd compose && ./gradlew :webApp:wasmJsBrowserDistribution --no-configuration-cache)
(cd compose && ./gradlew :backend:test :backend:codegenTest :backend:ktlintCheck :backend:detekt :backend:installDist --no-configuration-cache)
git diff --check
```

Compose Android：

```bash
(cd compose && ./gradlew :androidApp:assembleDebug --no-configuration-cache)
```

Compose Web、Android、iOS 使用 `compose/` 下的 Compose Multiplatform 工程；浏览器自动化入口位于 `compose/qa/web/`。仓库没有根级 npm workspace。

双后端 schema 验证需要显式配置名称以 `_test` 结尾的 PostgreSQL 数据库：

```bash
export FT_TEST_POSTGRES_URL='postgresql://localhost/finance_tracker_test'
(cd compose && ./gradlew :backend:test --tests '*SchemaBootstrap*' --no-configuration-cache)
```

不要把测试指向业务库或非 `_test` 库名。项目规则、变更记录和当前行为事实源见 [`AGENTS.md`](AGENTS.md)、[`openspec/project-context.md`](openspec/project-context.md) 和 [业务能力规格导航](openspec/specs/README.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [Compose 功能地图](docs/feature-map.md) | 产品总览、各模块页面与业务路径、实现证据和跨端待办 |
| [文档索引](docs/README.md) | 当前文档与 OpenSpec 导航 |
| [导入、关系与同步流程](docs/import/import-flow.md) | 现金/投资账单导入和连接器同步概览 |
| [现金流水：存储、展示与操作](docs/cash-ledger/cash-ledger.md) | 从业务对象到列表与统计、详情维护、分类／关联／删除及当前后端入口 |
| [现金账单导入](docs/import/cash-import-flow.md) | 从业务到后端执行、接口与数据、一致性机制的主阅读入口；内含详细参考导航 |
| [UI 设计约束](docs/shared/ui-design-rules.md) | Compose UI 实现与审查约定 |
| [OpenSpec](openspec/) | 行为规格、active changes 与归档 |
| [解析与行情参考](references/) | 账单格式、解析器与行情适配细节 |

账单源 onboarding、投资对账单和账单导出流程优先使用仓库内对应 skill；它们只负责领域工作流，不绕过 API 和客户端边界。

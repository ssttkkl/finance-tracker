# Finance Tracker

多账户、多币种个人财务工具：把消费、储蓄与投资放进同一套可审计账本，并提供投资组合估值与周期表现。

项目现在由 Python/FastAPI 后端和 Compose Multiplatform 客户端组成。后端是金额、余额、权限、来源、关系和审计的唯一事实源；Compose 的 Web、Android、iOS 目标通过同一套 HTTP API 访问它。

## 运行边界

运行时通过 `FT_DATABASE_URL` 显式选择一个事实源——PostgreSQL 或文件型 SQLite。二者共享 Application Service、财务语义与 Alembic schema；不得自动回退（no fallback）、不得双写（dual-write）、不得隐式迁移（implicit migration）。CSV、XLS、PDF 只作导入输入或预览，不是账本。

服务端不会自动建表、创建 workspace 或切换数据库。库不可达、schema 不是当前 head、workspace 不存在时直接失败。SQLite 使用 WAL、外键与有界写锁等待；数据库繁忙、读写权限或 schema 故障会返回脱敏的 `storage.*` 错误码。

## 环境准备

需要 Python 3.11+、`uv`、Node.js 20.19+（运行 Compose Web 本地预览），以及 PostgreSQL 或本地可写 SQLite 路径。

```bash
uv sync
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
uv run alembic upgrade head
```

初始化 workspace（仅在明确需要创建新 workspace 时执行）：

```bash
export FT_WORKSPACE_ID='default'
uv run python -c "
import os
from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
engine = create_relational_engine(os.environ['FT_DATABASE_URL'])
ensure_workspace(create_session_factory(engine), os.environ['FT_WORKSPACE_ID'])
engine.dispose()
"
```

SQLite 示例：

```bash
export FT_DATABASE_URL="sqlite+pysqlite:////absolute/path/finance-tracker.db"
uv run alembic upgrade head
```

## 启动后端与 Compose

后端使用显式的 Uvicorn 工厂入口：

```bash
FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db' \
uv run uvicorn ft.web.app:create_runtime_app --factory --host 127.0.0.1 --port 8000
```

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
| 收支浏览、详情与证据 | `/api/v1/cash-projections`、`/api/v1/cash-records/{transaction_id}`、`/api/v1/evidence/*` | 既有列表基础保留，正式／草稿及新维护合同待接入 |
| 手工流水、余额校准、转账 | `/api/v1/cashflow/*` 或 `POST /api/v1/cash-records` | 既有入口保留，不等同于新维护计划 |
| 现金账单解析、统一预览和确认 | `/api/v1/cash-import/*` | Compose 已接会话、来源账户映射、分页核对、确认收据与错误诊断；功能演示验收仍在进行 |
| 正式编辑、草稿补全与当前关联维护 | `/api/v1/cash-mutations/*`、`PATCH /api/v1/cash-records/{transaction_id}/draft` | 后端已完成，新详情与维护操作待接入 |
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

现金导入流程是“文件与账户准备 → 同一流水列表核对并一次确认 → 结果及后续补处理”。Compose 共享界面已接入文件与账户准备、待确认优先的连续分页列表、只读详情、确认收据和错误诊断；币种不足的已选账户会在进入预览前补齐。本轮功能仍在浏览器演示验收中，Android 仅完成共享编译与主机单测，iOS 未验证。流程从 [现金账单导入](docs/cash-import-flow.md) 阅读，入库后的模型、展示与操作见 [现金流水](docs/cash-ledger.md)。投资账单及连接器同步使用各自领域模型，边界见 [导入总览](docs/import-flow.md)。

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
PYTHONPATH=tests:.:src uv run pytest
uv run alembic heads
git diff --check
```

Compose Android：

```bash
(cd compose && ./gradlew :androidApp:assembleDebug --no-configuration-cache)
```

Compose Web、Android、iOS 使用 `compose/` 下的 Compose Multiplatform 工程；浏览器自动化入口位于 `compose/qa/web/`。仓库没有根级 npm workspace。

双后端契约需要连接专用、名称以 `_test` 结尾的 PostgreSQL 数据库：

```bash
export FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test'
PYTHONPATH=tests:.:src uv run pytest
```

PR 的 SQLite 和 PostgreSQL 常规回归各按测试文件分为 8 片，保留完整收集和测试范围。每片在独立 runner 内顺序执行，PostgreSQL 使用显式配置的专用 `_test` 库；`Backend (SQLite)` 和 `Backend (PostgreSQL)` 只有全部分片成功才通过。性能用例仍由独立任务按原预算执行。

本地复现某片：

```bash
PYTHONPATH=tests:.:src uv run python scripts/run_backend_tests.py --shard-index 0 --shard-count 8
```

不要把测试指向业务库或非 `_test` 库名。项目规则、变更记录和当前行为事实源见 [`AGENTS.md`](AGENTS.md)、[`openspec/project-context.md`](openspec/project-context.md) 和 [业务能力规格导航](openspec/specs/README.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [Compose 功能地图](docs/feature-map.md) | 产品总览、各模块页面与业务路径、实现证据和跨端待办 |
| [文档索引](docs/README.md) | 当前文档与 OpenSpec 导航 |
| [导入、关系与同步流程](docs/import-flow.md) | 现金/投资账单导入和连接器同步概览 |
| [现金流水：存储、展示与操作](docs/cash-ledger.md) | 从业务对象到列表与统计、详情维护、分类／关联／删除及当前后端入口 |
| [现金账单导入](docs/cash-import-flow.md) | 从业务到后端执行、接口与数据、一致性机制的主阅读入口；内含详细参考导航 |
| [UI 设计约束](docs/ui-design-rules.md) | Compose UI 实现与审查约定 |
| [OpenSpec](openspec/) | 行为规格、active changes 与归档 |
| [解析与行情参考](references/) | 账单格式、解析器与行情适配细节 |

账单源 onboarding、投资对账单和账单导出流程优先使用仓库内对应 skill；它们只负责领域工作流，不绕过 API 和客户端边界。

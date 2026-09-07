# Finance Tracker

多账户、多币种个人财务工具：把消费、储蓄与投资放进同一套可审计账本，并在此之上提供持仓估值与财富归因内核。

项目现在由三层组成：Python/FastAPI 后端、React/Vite Web 和 Expo Android/iOS 客户端。后端是金额、余额、权限、来源、关系和审计的唯一事实源；Web 与 Native 都通过同一套 HTTP API 访问它。

## 运行边界

运行时通过 `FT_DATABASE_URL` 显式选择一个事实源——PostgreSQL 或文件型 SQLite。二者共享 Application Service、财务语义与 Alembic schema；不得自动回退（no fallback）、不得双写（dual-write）、不得隐式迁移（implicit migration）。CSV、XLS、PDF 只作导入输入或预览，不是账本。

服务端不会自动建表、创建 workspace 或切换数据库。库不可达、schema 不是当前 head、workspace 不存在时直接失败。SQLite 使用 WAL、外键与有界写锁等待；数据库繁忙、读写权限或 schema 故障会返回脱敏的 `storage.*` 错误码。

## 环境准备

需要 Python 3.11+、`uv`、Node.js 20.19+，以及 PostgreSQL 或本地可写 SQLite 路径。

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

## 启动后端、Web 和 Expo

后端使用显式的 Uvicorn 工厂入口：

```bash
FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db' \
uv run uvicorn ft.web.app:create_runtime_app --factory --host 127.0.0.1 --port 8000
```

根目录安装一次 JavaScript workspace 后，可以分别启动 Web 和 Native：

```bash
npm install
npm run dev:web       # React/Vite Web
npm run start:mobile  # Expo Metro
npm run android       # Android development build
npm run ios           # iOS development build
```

真机调试时将 `EXPO_PUBLIC_FT_API_ORIGIN` 设置为开发机局域网 HTTPS 或 HTTP 地址；生产 Native 构建只接受 HTTPS。Web 本地开发默认通过 Vite 代理访问 `http://127.0.0.1:8000`。

## API 能力

所有 `/api/v1` 业务请求都使用 `Authorization: Bearer <session-token>`，当前 workspace 和角色由服务端会话解析。Native 将 Token 放在 `expo-secure-store`，Web 使用浏览器本地存储；账单密码只在当前导入请求内存中存在。

| 能力 | API 入口 | 客户端承载 |
|---|---|---|
| 登录、注册、workspace、成员 | `/api/v1/auth/*` | Web / Expo |
| 账户管理 | `/api/v1/accounts`、`/api/v1/accounts/{name}` | Web / Expo |
| 收支浏览与证据 | `/api/v1/cash-projections`、`/api/v1/evidence/*` | Web / Expo |
| 手工流水、余额校准、转账 | `/api/v1/cashflow/*` 或 `/api/v1/cash-records` | Web / Expo |
| 账单扫描、映射、预览、确认 | `/api/v1/cash-import/*` | Web / Expo |
| 关系审查与事实删除 | `/api/v1/relations/*`、`/api/v1/cash-facts/*` | Web / Expo |
| 投资查询与事件 | `/api/v1/investment-events`、`/api/v1/investment-portfolio` | Web；Native 后续切片 |
| 报表与查询 | `/api/v1/queries/*`、`/api/v1/reports/*` | Web / Expo |
| 同步、投影运维 | `/api/v1/operations/*` | 仅管理员后端操作 |

金额、数量、汇率和成本在 HTTP 合同中使用十进制字符串，例如：

```bash
curl -X POST "$API_ORIGIN/api/v1/cashflow/add" \
  -H "Authorization: Bearer $SESSION_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"amount":"-12.50","counterparty":"Coffee","account":"Wallet","currency":"CNY"}'
```

`viewer` 只能读取；所有写入仍经现有 Application Service 和 Unit of Work。手工写入首期 online-first，网络超时显示未确认，不自动重试；导入确认使用 `Idempotency-Key`。

## 账单导入

Web 与 Expo 都使用同一个导入流程：选择文件 → 识别/扫描 → 账户映射 → 预览 → 关系审查 → 确认。现金账单由账单字段和工作区映射路由；投资账单在确认时选择 `security` 或 `crypto` 账户。`convert` 不再是独立用户步骤，预览直接留在导入会话中。

支持的来源包括：`alipay`、`wechat`、`icbc`、`icbc-debit`、`ccb-debit`、`icbc-asia`、`dfzq`、`ibkr`、`schwab`、`usmart-hk` / `usmart_hk`。交易所和 Polymarket 使用受控后端同步，不由客户端接触密钥。

导入幂等权威为 `source_type` × `record_id`；重复行不会重复发布。原始来源保留在行内 `source_payload`，关系和映射由服务端最终校验。

## 安全与权限

- `FT_DATABASE_URL` 必须显式配置；不会回退到其他数据库或写第二份账本。
- 生产 `FT_WEB_ORIGIN`、Web 的 `VITE_FT_API_ORIGIN` 和 Native API 地址必须是 HTTPS origin，不能包含路径、查询参数或凭据。
- 连接器凭据只从后端受控配置加载；不会出现在请求体、客户端 bundle 或错误响应中。
- 账单密码不写入日志、TokenStore、缓存或数据库。
- 账户有正式事实后只能停用，不能硬删；关系确认、事实删除和投影重建都保留审计语义。

## 开发与验证

```bash
npm run test:web
npm run build:web
npm run test:shared
npm run typecheck:shared
PYTHONPATH=tests:.:src uv run pytest
uv run alembic heads
git diff --check
```

移动端：

```bash
npm run test --workspace finance-tracker-mobile
npm run typecheck --workspace finance-tracker-mobile
npm run export:android --workspace finance-tracker-mobile
npm run export:ios --workspace finance-tracker-mobile
```

双后端契约需要连接专用、名称以 `_test` 结尾的 PostgreSQL 数据库：

```bash
export FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test'
PYTHONPATH=tests:.:src uv run pytest
```

不要把测试指向业务库或非 `_test` 库名。项目规则、变更记录和当前行为事实源见 [`AGENTS.md`](AGENTS.md)、[`openspec/project-context.md`](openspec/project-context.md) 和 [`openspec/specs/`](openspec/specs/)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/README.md](docs/README.md) | 文档索引 |
| [docs/import-flow.md](docs/import-flow.md) | 导入、关系、同步事务语义 |
| [docs/export-csv-format.md](docs/export-csv-format.md) | 预览/交换 CSV 字段 |
| [docs/database-schema.md](docs/database-schema.md) | ORM 与 Alembic 表结构速查 |
| [docs/productization-refactor-plan.md](docs/productization-refactor-plan.md) | 产品路线 |
| [docs/productization-wealth-report-design.md](docs/productization-wealth-report-design.md) | 财富报告产品决策输入 |
| [openspec/](openspec/) | 主规格、active changes 与历史归档 |
| [references/](references/) | 解析器与行情供应商细节 |

账单源 onboarding、投资对账单和账单导出流程优先使用仓库内对应 skill；它们只负责领域工作流，不绕过 API 和客户端边界。

# Finance Tracker (`ft`)

多账户、多币种个人财务工具：把**消费、储蓄与投资**放进同一套可审计账本，并在此之上做持仓估值与财富归因内核。

运行时通过 `FT_DATABASE_URL` **显式选择一个**事实源——PostgreSQL 或文件型 SQLite。二者共享 Application Service、财务语义与 Alembic schema；**不得自动回退（no fallback）、不得双写（dual-write）、不得隐式迁移（implicit migration）**。CSV / XLS / PDF 只作导入输入或显式导出预览，**不是**账本。

当前工程基线在 `refactor/web`：**Phase 1 已关账**（`002`–`010` 现金/导入 + `011`–`016` schema 收口 + `017` 估值 + `018` 连接器同步）。下一产品方向是 Phase 2 只读账单 Web（新序号 feature）。

## 环境准备

需要 Python 3.11+、`uv`，以及 PostgreSQL 或本地可写 SQLite 路径。

```bash
uv sync
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head    # head / SCHEMA_REVISION: 20260813_27
uv run python -c "
import os
from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace
e = create_relational_engine(os.environ['FT_DATABASE_URL'])
ensure_workspace(create_session_factory(e), os.environ['FT_WORKSPACE_ID'])
e.dispose()
"
```

SQLite 示例：

```bash
export FT_DATABASE_URL="sqlite+pysqlite:///$PWD/finance-tracker.db"
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
```

CLI **不会**自动建表、建 workspace 或回退到文件账本。库不可达、schema 不是当前 head、workspace 不存在时直接失败。

SQLite 使用 WAL、外键与有界写锁等待；繁忙、读写权限或 schema 错误分别以 `storage.busy`、`storage.readonly`、`storage.schema` 报告，诊断信息不暴露凭据或完整路径。内存 SQLite 仅限测试。

## 核心命令

```bash
# 账户（workspace 内名称唯一；cash/loan/lend 可包含多种币种余额）
uv run ft acct add Cash --type cash --currency CNY   # 可选：初始化 CNY 零余额
uv run ft acct add 币安 --type crypto --currency USD # 投资账户：seed → metadata.base_currencies
uv run ft acct add '工行信用卡(1200)' --type loan
uv run ft acct list
uv run ft acct rename Cash Wallet
uv run ft acct deactivate Wallet

# 现金：写入必须显式币种
uv run ft add --amount -12.50 --counterparty Coffee --account Wallet --currency CNY
uv run ft checkin Wallet --balance 1000 --currency CNY --date 2026-07-17
uv run ft transfer \
  --from Wallet --from-currency CNY \
  --to Card --to-currency CNY --amount 300

# 查询
uv run ft report --month 2026-07
uv run ft list --account Wallet --limit 20
```

- 账户类型：`cash` | `loan` | `lend` | `security` | `crypto`
- 币种：任意 3 位字母码（如 CNY/USD/JPY），无白名单
- 有正式事实的账户不能硬删，应 `deactivate`
- 重命名不改历史事实归属

## 原始账单导入（主路径）

一步入账。现金账单**禁止** `--account`（账户由账单字段 + `~/.ft/mapping.yaml` 路由）；投资账单**必须** `--account`。

```bash
# 现金
uv run ft import alipay.csv --source alipay
uv run ft import wechat.xlsx --source wechat
uv run ft import icbc.pdf --source icbc --password-file /tmp/pw.txt
uv run ft import hqmx.xls --source ccb-debit
uv run ft import currentaccounthistory.csv --source icbc-asia

# 投资（先建 security/crypto 账户）
uv run ft acct add 东方证券 --type security --currency CNY
uv run ft import dfzq.pdf --source dfzq --account 东方证券 --password-file /tmp/pw.txt
uv run ft import ibkr.csv --source ibkr --account IBKR
uv run ft import schwab.csv --source schwab --account Schwab
uv run ft import usmart.pdf --source usmart-hk --account uSmart
```

| Source | 典型文件 | 账户 | 说明 |
|---|---|---|---|
| `alipay` | CSV | cash，自动路由 | 支付宝 |
| `wechat` | XLSX | cash，自动路由 | 微信 |
| `icbc` / `icbc-debit` | PDF | cash，自动路由 | 工行信用/借记（统一使用 `pdfplumber`，不依赖 `qpdf` / `mupdf-tools`） |
| `ccb-debit` | XLS | cash，自动路由 | 建行借记 |
| `icbc-asia` | UTF-16 TSV CSV | cash，自动路由 | 工银亚洲活期账户明细 |
| `dfzq` | PDF | security，必填 `--account` | 东方证券 |
| `ibkr` | CSV | security，必填 `--account` | Interactive Brokers |
| `schwab` | CSV | security，必填 `--account` | Charles Schwab |
| `usmart-hk` / `usmart_hk` | PDF | security，必填 `--account` | 盈立证券 |
| `binance` / `okx` / `polymarket` | 文件源名 | 投资，必填 `--account` | 文件导入预留；**API 同步见 `ft sync`** |

**幂等（015 后）**：正式事实上的 **`source_type` × `record_id`**（导入渠道名 × 业务行键）。重复行跳过，不重复发布；**不再**持久化 `import_batches` / `raw_files` / `raw_records`。溯源在行内 `source_payload`。

导入提交正式事实后可触发关系检查（镜像 / 转账 / 退款等）。报告只消费 **活跃事实 + 已 accept 的关系**。

```bash
uv run ft relations pending
uv run ft relations check
uv run ft relations accept <relation_id>   # 待配对关系可加 --other <fact_id>
uv run ft relations reject <relation_id>
uv run ft fact-delete <fact_id> --reason 'duplicate historical row'
```

### 仅预览解析（非账本）

```bash
uv run ft convert statement.csv --source alipay --output preview.csv
uv run ft stock convert statement.pdf --source dfzq --output preview.csv
```

导出 CSV **不会**写库，也**没有** `append` 再提交为正式事实。正式写入只用 `ft import` / `ft sync` / 手动命令。

## 投资：手动、估值、同步

### 手动事件

```bash
uv run ft stock deposit --amount 1000 --currency USD --account IBKR
uv run ft stock buy --ticker aapl.us --shares 2 --price 200 --commission 1 --currency USD --account IBKR
uv run ft stock sell --ticker aapl.us --shares 1 --price 220 --commission 1 --currency USD --account IBKR
uv run ft stock swap --from-ticker btc --from-shares 0.1 --to-ticker eth --to-shares 2 \
  --account Kraken --commission 0.0001 --commission-asset btc
uv run ft stock dividend --ticker aapl.us --amount 5 --currency USD --account IBKR
uv run ft stock checkin --ticker aapl.us --shares 3 --avg-cost 190 --currency USD --account IBKR
uv run ft stock checkin --cash 500 --currency USD --account IBKR
```

买卖在领域上落为 **单行 swap**（现金↔标的 + commission）。金额/数量为精确十进制文本；非有限值与超精度输入 fail-closed。

### 持仓与市值（017）

```bash
uv run ft stock list
uv run ft stock list --display-currency CNY   # 可选：只读 FX 折算为展示币种
```

统一 `ValuationService`（security / crypto / prediction market / cash），状态含 **complete / stale / partial / unsupported**。组合查询有有界行情预算：单项失败不影响其它持仓渲染。

### 连接器同步（018）

手动 API 拉取 → 统一投资事件；增量游标在表 `sync_cursors`。

`~/.ft/credentials.yaml`（自动 `0600`，应 gitignore）：

```yaml
binance:
  api_key: "..."
  api_secret: "..."
kraken:
  api_key: "..."
  api_secret: "..."
okx:
  api_key: "..."
  api_secret: "..."
  password: "..."   # 若交易所要求
polymarket:
  proxy_wallet: "0x..."   # 或 wallet（会解析 proxy）
```

```bash
uv run ft sync --source binance --account 币安
uv run ft sync --source kraken --account Kraken
uv run ft sync --source okx --account OKX
uv run ft sync --source polymarket --account Polymarket
uv run ft sync --source binance --account 币安 --full   # 忽略游标，全量再拉 + 行幂等
```

首批 provider：`binance` | `kraken` | `okx` | `polymarket`。拉取、映射、投影、游标在同一事务语义下 fail-closed；无定时 Worker / secret vault。

## CLI 一览

| 命令 | 作用 |
|---|---|
| `acct {add,list,rename,delete,deactivate,activate}` | 账户 |
| `add` / `checkin` / `transfer` | 现金录入、校准、转账 |
| `list` / `report` | 流水与月报 |
| `import` | 原始账单一步入账 |
| `convert` / `stock convert` | 解析预览 → CSV（非账本） |
| `stock {buy,sell,swap,deposit,withdraw,dividend,checkin,list}` | 投资手动 + 持仓估值 |
| `sync` | 交易所 / Polymarket API 同步 |
| `relations {pending,check,accept,reject,alias-add}` | 关系审查 |
| `fact-delete` | 以可审计方式逻辑删除现金流水 |
| `web` | 仅本机启动收支账本只读 API |

相对旧 `main`（CSV+git）已删除：`append`、`reconcile`、`commit` / `status` / `reset`、`verify`、`stock append`、`stock sync <provider>`。对照见产品讨论记录；操作上以本文为准。

## 收支账本 Web

收支账本的 Web 服务支持邮箱密码登录与工作区成员访问。用户可以创建工作区；管理员可创建限时一次性的「可编辑」或「仅可查看」邀请链接。所有账本 API 都从登录会话的当前工作区解析数据；CLI 仍使用显式 `FT_WORKSPACE_ID`。列表展示已确认关系处理后的收支投影，而不是原始现金流水；内部转账和全额退款保留为可审计投影，但不进入列表。

```bash
export FT_DATABASE_URL='sqlite+pysqlite:////absolute/path/finance-tracker.db'
export FT_WORKSPACE_ID='default'
uv run ft web
```

在另一个终端运行：

```bash
cd web
npm install
npm run dev
```

`npm run dev` 固定在 `http://127.0.0.1:5174`，并将同源 `/api` 请求转发到本机 API
`http://127.0.0.1:8000`。因此，本地开发不需要手动设置 `VITE_FT_API_ORIGIN` 或匹配 API
端口；Python API 仍须先独立运行。端到端测试或其他明确指定的本机 API 可继续在启动前设置
`VITE_FT_API_ORIGIN` 覆盖默认值。

生产预览必须在构建时注入 API 来源，再启动预览服务器：

```bash
VITE_FT_API_ORIGIN='https://your-api.onrender.com' npm run build
npm run start
```

`npm run start` 只服务已经构建的产物，不会重新读取 `VITE_FT_API_ORIGIN`。本地允许带端口的
`http://127.0.0.1` 或 `http://localhost`；部署时 `FT_WEB_ORIGIN` 与 `VITE_FT_API_ORIGIN` 必须是完整的
HTTPS origin（不得包含路径、查询参数或凭据）。配置无效或 API 不可连接时，进程或页面会明确失败，
不会使用其他后端或地址。

### Render 部署

部署两个 Render Service 时，后端使用 Web Service，前端使用 Static Site 或 Web Service。两者可以直接使用
Render 提供的独立公开 URL，不需要自定义域名；前端通过 Bearer 会话令牌访问 API：

```bash
# 后端环境变量
FT_DATABASE_URL='postgresql+psycopg://…'  # Neon URL 使用 psycopg SQLAlchemy 方言
FT_WEB_ORIGIN='https://your-web.onrender.com'

# 后端 Build Command
uv sync --frozen

# 后端 Start Command
uv run alembic upgrade head && uv run uvicorn ft.web.app:create_runtime_app --factory --host 0.0.0.0 --port "$PORT"

# 前端构建环境变量（构建时注入）
VITE_FT_API_ORIGIN='https://your-api.onrender.com'
```

生产环境不会设置或依赖 Cookie。登录/注册响应返回随机会话令牌，前端保存在浏览器 `localStorage`，后续请求通过
`Authorization: Bearer <token>` 发送；显式退出会撤销服务端会话并清除本地令牌。后端仍仅允许
`FT_WEB_ORIGIN` 这一项 CORS 来源，并允许 `Authorization` 预检。不要把 `FT_WORKSPACE_ID` 配给 Web 后端；它只保留给
CLI。首次注册 `admin@ssttkkl.fun` 时，如果既有 `default` 工作区存在，该用户会自动成为其管理员。

当前只交付**收支账本**与**证据详情**。投资账本视图、投资事件、持仓和持仓估值属于 `022-investment-ledger-browser-web`，尚未交付。

## 配置合同

仅接受：

- `FT_DATABASE_URL` — `postgresql+…` 或文件 `sqlite+pysqlite:////…`
- `FT_WORKSPACE_ID` — CLI 必填；Web 后端不使用
- `FT_WEB_ORIGIN` — Web 后端必填的唯一前端 HTTPS origin（本地可省略）
- `VITE_FT_API_ORIGIN` — 前端构建时注入的 API HTTPS origin（本地开发可使用默认代理）

旧 backend / ledger-root 变量会被拒绝。测试专用：`FT_TEST_POSTGRES_URL`（库名须以 `_test` 结尾）、可选 `FT_REQUIRE_TEST_POSTGRES=1`。

## 开发与验证

```bash
uv run pytest
uv run alembic heads    # 期望 20260813_27
uv build
git diff --check
```

双后端契约矩阵需要本机 PostgreSQL `*_test` 库。本 feature 不使用容器：

```bash
export FT_TEST_POSTGRES_URL='postgresql+psycopg:///finance_tracker_test'
export FT_REQUIRE_TEST_POSTGRES=1
uv run pytest
```

不要把测试指到业务库或非 `*_test` 库名（`tests/conftest.py` 可能重置 schema）。

工程原则见 [`openspec/project-context.md`](openspec/project-context.md)（财务正确性与可审计、OpenSpec、测试先行、显式双后端等价、清晰边界）。金额在库中为高精度精确小数；幂等权威为 **`record_id` × `source_type`**。

## 财富归因内核

`ServiceBundle.wealth` 提供 transport-neutral 的自然月 breakdown、日/周/月 series、component evidence 与 rebuild（feature `003`）。固定展示口径与时区策略见该 feature；**不**把可变 snapshot 或即时行情当历史边界输入。

当前 **无** 财富专用 CLI / Web；只读财富报告属 Phase 3。调用方仍须显式 `FT_DATABASE_URL`。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/README.md](docs/README.md) | 文档索引 |
| [docs/productization-refactor-plan.md](docs/productization-refactor-plan.md) | 产品路线（Phase 1 已关） |
| [docs/import-flow.md](docs/import-flow.md) | 导入 / 关系 / 同步事务语义 |
| [docs/export-csv-format.md](docs/export-csv-format.md) | 显式 CSV 导出字段 |
| [docs/database-schema.md](docs/database-schema.md) | ORM + Alembic 表结构速查 |
| [docs/productization-wealth-report-design.md](docs/productization-wealth-report-design.md) | 财富报告产品决策输入（非实施权威） |
| [openspec/](openspec/) | OpenSpec 主规格、active changes 与历史归档 |
| [references/](references/) | 解析器 / 行情供应商细节 |
| [AGENTS.md](AGENTS.md) | AI / OpenSpec 工作流（`CLAUDE.md` 同内容） |

AI 接入新账单源或券商对账单时，优先用项目 skill：

- `.agents/skills/statement-source-onboarding` — 现金账单 + 关系扫描
- `.agents/skills/investment-statement-importer-onboarding` — 投资对账单 → 事件
- `.agents/skills/bill-export` — 按渠道路由，申请、等待、下载并保存微信或支付宝账单文件

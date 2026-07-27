# Finance Tracker (`ft`)

Finance Tracker 是一个支持 PostgreSQL 与文件型 SQLite 的多账户、多币种个人财务工具。它统一记录现金交易、转账、
证券与加密资产事件，并保留原始账单、正式事实和修订之间的可审计关系。

`FT_DATABASE_URL` 显式选择一个运行时事实源：PostgreSQL 或文件型 SQLite。CSV、XLS/XLSX 和 PDF 只用于原始账单输入或用户显式导出；应用不会
把这些文件当作账本、快照、事务日志或回退存储。旧开发账本不会被读取、迁移或自动删除。

## 环境准备

要求 Python 3.11+、`uv`，以及 PostgreSQL 或本地可写的 SQLite 文件目录。

```bash
uv sync
export FT_DATABASE_URL='postgresql+psycopg://localhost/finance_tracker'
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
uv run python -c "import os; from ft.adapters.relational import create_relational_engine, create_session_factory, ensure_workspace; e=create_relational_engine(os.environ['FT_DATABASE_URL']); ensure_workspace(create_session_factory(e), os.environ['FT_WORKSPACE_ID']); e.dispose()"
```

普通 CLI 命令不会自动建表、创建 workspace 或回退到文件存储。数据库不可达、schema 不是当前
Alembic head、workspace 不存在时会直接失败。

文件 SQLite 示例：

```bash
export FT_DATABASE_URL="sqlite+pysqlite:///$PWD/finance-tracker.db"
export FT_WORKSPACE_ID='default'
uv run alembic upgrade head
```

SQLite 启用 WAL、外键和约 5 秒写锁等待；`storage.busy` 表示需等待另一个写入结束后再重试。既有权限过宽的文件会给出不含路径的 permission 警告，不会自动 chmod；`storage.readonly` 应检查文件与父目录权限。`storage.schema` 表示应对已选数据库显式运行迁移。
内存 SQLite 仅限测试。系统 no fallback（不回退）、双写或隐式迁移/同步任一后端。

## 核心命令

```bash
# 账户
uv run ft acct add Cash --type cash --currency CNY  # 可选：创建一个零余额 CNY 口袋
uv run ft acct add '工行信用卡(1200)' --type loan
uv run ft acct list
uv run ft acct rename Cash Wallet
uv run ft acct deactivate Wallet

# 现金、余额校准和转账
uv run ft add --amount -12.50 --counterparty Coffee --account Wallet --currency CNY
uv run ft checkin Wallet --balance 1000 --currency CNY --date 2026-07-17
uv run ft transfer --from Wallet --from-currency CNY --to Card --to-currency CNY --amount 300

# 查询
uv run ft report --month 2026-07
uv run ft list --account Wallet --limit 20
```

账户名在 workspace 内唯一；一个现金、借款或出借账户可同时拥有多个币种口袋。`acct add --currency` 仅创建可选的零余额口袋，所有现金写入、校准和转账都必须显式提供操作币种。账户重命名不会改变历史事实归属；有正式事实引用的账户不能硬删除，应使用 `acct deactivate`。币种接受任意 3 位字母码（如 CNY/USD/JPY），无白名单限制。

从旧的“名称 + 币种”账户模型升级时，运行一次 `uv run alembic upgrade head`。迁移会按名称合并同类型账户、保留所有币种口袋并重写相关事实；同名但类型不同会失败并保持原数据库不变，不提供运行时兼容或自动回退。

## 投资事件

### 手动记录投资操作

```bash
uv run ft stock deposit --amount 1000 --currency USD --account IBKR
uv run ft stock buy --ticker aapl.us --shares 2 --price 200 --commission 1 --currency USD --account IBKR
uv run ft stock sell --ticker aapl.us --shares 1 --price 220 --commission 1 --currency USD --account IBKR
uv run ft stock swap --from-ticker BTC --from-shares 0.1 --to-ticker ETH --to-shares 2 --currency USD --account Kraken
uv run ft stock dividend --ticker aapl.us --amount 5 --currency USD --account IBKR
uv run ft stock checkin --ticker aapl.us --shares 3 --avg-cost 190 --currency USD --account IBKR
uv run ft stock checkin --cash 500 --currency USD --account IBKR
uv run ft stock list
```

### 投资账单直接导入

**东方证券 (DFZQ) PDF 对账单导入：**

```bash
# 需要先创建 security 或 crypto 类型账户
uv run ft acct add 东方证券 --type security --currency CNY

# 导入 DFZQ PDF 对账单（自动解析股票买卖、资金出入、分红等）
uv run ft import dfzq_statement.pdf --source dfzq --account 东方证券

# 支持密码保护的 PDF
uv run ft import dfzq_statement.pdf --source dfzq --account 东方证券 --password-file /tmp/pw.txt
```

**外部工具依赖：**

DFZQ PDF 导入需要以下工具用于 PDF 解密与文本提取：

```bash
# macOS
brew install qpdf mupdf-tools

# Ubuntu/Debian
apt install qpdf mupdf-tools
```

**幂等性保证：**

- 同一文件重复导入会自动检测（通过 SHA256 文件摘要）
- 重复导入返回成功但不会创建新事件（count=0）
- 每笔交易通过业务键（日期+证券+金额+余额）去重
- 快照验证拒绝非有限值（NaN、Infinity）以保证财务正确性

**导入后验证：**

```bash
uv run ft stock list  # 查看持仓
uv run ft report      # 查看资产报表
```

金额、数量、成本和投影使用 `Decimal`/`NUMERIC(38,18)`；非有限值和超过 18 位小数的输入会被拒绝。

## 原始账单直接导入

```bash
# 账户仅由账单字段 + ~/.ft/mapping.yaml 推断；禁止 --account
uv run ft import statement.csv --source alipay
uv run ft import icbc.pdf --source icbc --password-file /tmp/pw.txt
uv run ft import hqmx.xls --source ccb-debit
```

支持的起始矩阵：

| Source | 文件 | 账户类型 | 说明 |
|---|---|---|---|
| `alipay` | CSV | cash (自动路由) | 支付宝账单 |
| `wechat` | XLSX | cash (自动路由) | 微信账单 |
| `icbc` | 加密 PDF | cash (自动路由) | 工行账单 |
| `icbc-debit` | PDF | cash (自动路由) | 工行借记卡 |
| `ccb-debit` | XLS | cash (自动路由) | 建行借记卡 |
| `dfzq` | PDF | security (需指定 --account) | 东方证券对账单 |
| `binance` | API | crypto (需指定 --account) | 币安交易所 (Phase 2) |
| `okx` | API | crypto (需指定 --account) | OKX 交易所 (Phase 2) |
| `polymarket` | API | security (需指定 --account) | Polymarket 预测市场 (Phase 3) |

导入在一个数据库事务内完成内容摘要、batch、raw records、正式事实、revision、projection 和完成状态。
同一文件可写入多个账户（`import_batches.target_account_id` 可空）；重复导入同一
workspace/source/digest 不会重复发布事实；任一行失败会回滚整批。

## Connector 同步

交易所与 Polymarket 可通过 API 手动同步；先创建匹配的账户，并在
`~/.ft/credentials.yaml` 配置只读凭据（文件会被限制为 `0600`）：

```yaml
binance:
  api_key: "your-read-only-api-key"
  api_secret: "your-api-secret"
polymarket:
  proxy_wallet: "0x..."
```

```bash
uv run ft sync --source binance --account 币安
uv run ft sync --source polymarket --account Polymarket
uv run ft sync --source binance --account 币安 --full  # 忽略游标，重拉并依靠幂等去重
```

同步先完整拉取外部记录；事件、快照和同步游标只在同一个事务中一起提交。API、映射或校验失败时，本次不会留下部分写入。

如只想检查解析结果，可显式导出 CSV（账户路由与 import 相同）：

```bash
uv run ft convert statement.csv --source alipay --output preview.csv
uv run ft stock convert statement.pdf --source dfzq --output preview.csv
```

导出文件不会注册成运行时状态，也不能通过 `append` 再成为正式账本；正式导入始终使用 `ft import`。

## 配置合同

仅接受：

- `FT_DATABASE_URL`
- `FT_WORKSPACE_ID`

旧 backend 或 ledger-root 环境变量会被明确拒绝。运行时 URL 必须是 PostgreSQL 或文件型 SQLite；
SQLite 内存 URL 会被明确拒绝。

## 开发与验证

```bash
uv run pytest
uv run alembic heads
uv build
git diff --check
```

完整持久化验证要求真实 PostgreSQL `_test` 数据库。本机推荐 Docker 容器
`finance-tracker-postgres-test`（`127.0.0.1:55432` → 5432，库名须以 `_test` 结尾）：

```bash
# 确保容器在跑
docker start finance-tracker-postgres-test 2>/dev/null || true

export FT_TEST_POSTGRES_URL='postgresql+psycopg://finance_tracker:finance_tracker_test@127.0.0.1:55432/finance_tracker_test'
export FT_REQUIRE_TEST_POSTGRES=1
uv run pytest
```

不要用其它业务容器的 `5432` 端口。`FT_TEST_POSTGRES_URL` 指向的库会被测试重置 schema，
仅允许专用 `*_test` 库（见 `tests/conftest.py`）。
### Constitution 合规性

本项目遵循 5 项工程原则（详见 `.specify/memory/constitution.md`）：

**I. 财务正确性与可审计性**
- ✅ 所有金额使用 `Decimal(28,10)` 精度
- ✅ 每笔导入事件通过 `raw_record_id` 追溯至原始账单
- ✅ 幂等性：`source_digest` (文件级) + `source_identity` (记录级) 双重保证
- ✅ 快照验证拒绝 NaN/Infinity，保证财务状态有限性

**II. Spec Kit 规格驱动**
- ✅ 所有行为源自 `specs/*/spec.md`、`plan.md`、`data-model.md`
- ✅ 实现前完成规格、方案、任务拆分、constitution check
- ✅ 无行为超出规格范围

**III. 测试先行与验证证据**
- ✅ 所有可执行行为、财务逻辑、数据变更先写失败测试
- ✅ 单元测试覆盖 domain 层 (>85%)
- ✅ 集成测试验证完整导入流程
- ✅ Contract 测试证明 PostgreSQL/SQLite 等价性

**IV. 显式数据库选择与行为等价**
- ✅ `FT_DATABASE_URL` 显式选择 PostgreSQL 或 SQLite
- ✅ 双后端 schema 等价（JSONB↔JSON, UUID↔TEXT）
- ✅ 事务原子性：两后端均使用 SERIALIZABLE/WAL+IMMEDIATE
- ✅ Contract 测试矩阵：同一导入→两后端→断言完全等价
- ❌ **明确禁止**：自动回退、双写、隐式跨后端迁移

**V. 清晰边界与最小复杂度**
- ✅ Domain 层纯函数（无 SQLAlchemy 依赖）
- ✅ Parser 返回 dict，不返回 ORM 模型
- ✅ Application Service 编排事务边界
- ✅ 避免过早抽象（Parser registry 用 dict，非插件框架）

## 财富归因内核

财富归因是 transport-neutral 的 Application Service：`ServiceBundle.wealth` 提供自然月 breakdown、日/周/月 series、immutable component evidence 和 rebuild 入口。它固定使用 CNY 与 Asia/Shanghai，并只从正式的 valuation observation、账户生命周期和可审计事实构建结果；不会读取可变 snapshot 或即时行情作为历史输入。

此 feature 不提供 HTTP/Web 路由、认证、Connector、AI 或 MCP surface。调用方必须继续通过显式 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite；没有自动回退、双写或跨后端同步。

## 文档

- [文档索引](docs/README.md)
- [产品化重构顶层路线](docs/productization-refactor-plan.md)
- [财富解释与趋势对比设计](docs/productization-wealth-report-design.md)
- [双数据库运行时 feature](specs/002-dual-database-runtime/spec.md)

<!-- 006-transaction-relations -->
## Transaction relations (006)

After `ft import` commits formal facts, a relation check may create `payment_mirror`,
`transfer_pair` (optional `credit_repayment` subtype), and `refund_offset` relations.
Reports use **active facts + accepted relations** only. Pairing never physically deletes
facts or rewrites amounts. Historical duplicates are handled by audited logical delete
(`ft fact-delete`); re-import of the same source identity publishes a **new** active fact.
Legacy `offset_*` / `transfer_account` / `proposed_action` fields are non-authoritative.
Review: `ft relations pending|accept|reject|later`. Manual re-check: `ft relations check`.

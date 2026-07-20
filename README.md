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
uv run ft acct add Cash --type cash --currency CNY
uv run ft acct list
uv run ft acct rename Cash Wallet --currency CNY
uv run ft acct deactivate Wallet --currency CNY

# 现金、余额校准和转账
uv run ft add --amount -12.50 --counterparty Coffee --account Wallet --currency CNY
uv run ft checkin Wallet --balance 1000 --currency CNY --date 2026-07-17
uv run ft transfer --from Wallet --from-currency CNY --to Card --to-currency CNY --amount 300

# 查询
uv run ft report --month 2026-07
uv run ft list --account Wallet --limit 20
```

有正式事实引用的账户不能硬删除，应使用 `acct deactivate`。账户重命名不会改变历史事实归属。

## 投资事件

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

金额、数量、成本和投影使用 `Decimal`/`NUMERIC(38,18)`；非有限值和超过 18 位小数的输入会被拒绝。

## 原始账单直接导入

```bash
uv run ft import statement.csv --source alipay --account Wallet --currency CNY
```

支持的起始矩阵：

| Source | 文件 |
|---|---|
| `alipay` | CSV |
| `wechat` | XLSX |
| `icbc` | 加密 PDF |
| `icbc-debit` | PDF |
| `ccb-debit` | XLS |
| `dfzq` | PDF |

导入在一个数据库事务内完成内容摘要、batch、raw records、正式事实、revision、projection 和完成状态。
重复导入同一 workspace/source/digest 不会重复发布事实；任一行失败会回滚整批。

如只想检查解析结果，可显式导出 CSV：

```bash
uv run ft convert statement.csv --source alipay --account Wallet --output preview.csv
uv run ft stock convert statement.pdf --source dfzq --account 东方证券 --output preview.csv
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

完整持久化验证要求真实 PostgreSQL `_test` 数据库：

```bash
FT_TEST_POSTGRES_URL='postgresql+psycopg://localhost/finance_tracker_test' \
FT_REQUIRE_TEST_POSTGRES=1 uv run pytest
```

## 财富归因内核

财富归因是 transport-neutral 的 Application Service：`ServiceBundle.wealth` 提供自然月 breakdown、日/周/月 series、immutable component evidence 和 rebuild 入口。它固定使用 CNY 与 Asia/Shanghai，并只从正式的 valuation observation、账户生命周期和可审计事实构建结果；不会读取可变 snapshot 或即时行情作为历史输入。

此 feature 不提供 HTTP/Web 路由、认证、Connector、AI 或 MCP surface。调用方必须继续通过显式 `FT_DATABASE_URL` 选择 PostgreSQL 或 SQLite；没有自动回退、双写或跨后端同步。

## 文档

- [文档索引](docs/README.md)
- [产品化重构顶层路线](docs/productization-refactor-plan.md)
- [财富解释与趋势对比设计](docs/productization-wealth-report-design.md)
- [双数据库运行时 feature](specs/002-dual-database-runtime/spec.md)

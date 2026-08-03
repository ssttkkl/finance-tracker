# Quickstart: 投资连接器同步验证

**Feature**: `018-investment-connector-sync` | **Date**: 2026-07-26

本文档提供端到端验证场景，证明 connector 同步功能正确工作。

## 前置条件

1. Python 3.11+ 和 `uv` 已安装
2. 项目依赖已安装：`uv sync`
3. 数据库已初始化：
   ```bash
   # SQLite
   export FT_DATABASE_URL="sqlite:///path/to/ft.db"
   export FT_WORKSPACE_ID="test"
   uv run alembic upgrade head

   # 或 PostgreSQL
   export FT_DATABASE_URL="postgresql://user:pass@localhost:5432/ft"
   export FT_WORKSPACE_ID="test"
   uv run alembic upgrade head
   ```

## 验证场景 1: 交易所同步（Binance）

### 准备

```bash
# 1. 创建 crypto 账户
ft account add --name 币安 --type crypto --currency USD

# 2. 配置凭据
mkdir -p ~/.ft
cat > ~/.ft/credentials.yaml << 'EOF'
binance:
  api_key: "your-read-only-api-key"
  api_secret: "your-api-secret"
EOF
chmod 600 ~/.ft/credentials.yaml
```

### 执行

```bash
# 首次全量同步
ft sync --source binance --account 币安
```

### 预期结果

- ✅ 输出 `Fetched N trades from API`
- ✅ 每条 trade 映射为 `swap` 事件
- ✅ 每条可识别 ledger 活动也被导入：入金/出金/奖励与 staking/内部转账/独立手续费；未知或异常记录会使整次同步失败而非静默跳过
- ✅ `source_type = binance_api`
- ✅ 快照更新（`ft portfolio --account 币安` 可查看持仓）

### 幂等验证

```bash
# 再次执行同一命令
ft sync --source binance --account 币安
```

- ✅ 输出 `No new trades since last sync`（增量游标生效）
- ✅ 或输出 `0 new events (N already imported)`（全量幂等）

### 全量重拉验证

```bash
ft sync --source binance --account 币安 --full
```

- ✅ 忽略游标，重新拉取所有交易
- ✅ 所有交易被幂等跳过，事件数不变

## 验证场景 2: Polymarket 同步

### 准备

```bash
# 1. 创建 security 账户
ft account add --name Polymarket --type security --currency USD

# 2. 配置凭据
cat >> ~/.ft/credentials.yaml << 'EOF'
polymarket:
  wallet: "0xYourLoginAddress"
EOF
```

### 执行

```bash
ft sync --source polymarket --account Polymarket
```

### 预期结果

- ✅ 自动解析 proxy wallet（若提供 login 地址）
- ✅ `TRADE` 映射为 `swap`；`REDEEM` 映射为 outcome token → USD 的 `swap`；`YIELD` 映射为 USD `dividend`
- ✅ 其他未定义活动类型跳过，不写入账本
- ✅ ticker 格式为 `pm:<slug>:<yes|no>`
- ✅ USD 金额 = `usdcSize`
- ✅ `source_type = polymarket_api`

## 验证场景 3: 错误处理

### 凭据缺失

```bash
# 删除凭据文件后尝试同步
mv ~/.ft/credentials.yaml ~/.ft/credentials.yaml.bak
ft sync --source binance --account 币安
```

- ✅ 退出码 1
- ✅ 输出包含示例配置格式
- ✅ 不泄漏密钥值

```bash
mv ~/.ft/credentials.yaml.bak ~/.ft/credentials.yaml
```

### 账户类型不匹配

```bash
ft account add --name 储蓄卡 --type cash --currency CNY
ft sync --source binance --account 储蓄卡
```

- ✅ 退出码 1
- ✅ 报告「类型为 cash，不能用于 exchange 同步（需要 crypto）」

## 验证场景 4: 双数据库等价

```bash
# 在 SQLite 上同步
export FT_DATABASE_URL="sqlite:///tmp/ft-test.db"
ft db init && ft account add --name 币安 --type crypto --currency USD
ft sync --source binance --account 币安

# 在 PostgreSQL 上同步
export FT_DATABASE_URL="postgresql://..."
ft db init && ft account add --name 币安 --type crypto --currency USD
ft sync --source binance --account 币安

# 比较结果
# 事件数量、金额、ticker、快照持仓应完全一致
```

## 自动化测试命令

```bash
# 运行全部单元测试
uv run pytest tests/unit/test_ccxt_connector.py tests/unit/test_polymarket_connector.py tests/unit/test_credentials.py -v

# 运行 SQLite 集成测试
uv run pytest tests/integration/test_sync_cursor_sqlite.py tests/integration/test_sync_cursor_incremental_sqlite.py tests/integration/test_sync_exchange_sqlite.py tests/integration/test_sync_polymarket_sqlite.py -v

# 运行 PostgreSQL 集成测试（需要 FT_TEST_POSTGRES_URL）
FT_TEST_POSTGRES_URL="postgresql://ft:ft@127.0.0.1:55432/ft_test" \
uv run pytest tests/integration/test_sync_cursor_postgres.py tests/integration/test_sync_cursor_incremental_postgres.py tests/integration/test_sync_exchange_postgres.py tests/integration/test_sync_polymarket_postgres.py -v

# 运行所有相关测试
uv run pytest tests/ -k "sync or connector or credential" -v
```

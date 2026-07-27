# CLI Contract: `ft sync`

**Feature**: `018-investment-connector-sync` | **Date**: 2026-07-26

## 命令签名

```
ft sync --source <provider> --account <name> [--full] [--batch-size <n>]
```

## 参数

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| `--source` | string | ✅ | — | connector 提供者标识（`binance`, `kraken`, `okx`, `polymarket`） |
| `--account` | string | ✅ | — | 目标账户名（必须已存在且类型匹配） |
| `--full` | flag | ❌ | false | 忽略增量游标，全量重新拉取 |
| `--batch-size` | int | ❌ | 500 | 单个处理分块的最大事件数；所有分块在一次原子同步中提交 |

## 有效 source 值与账户类型要求

| source | source_type | 账户类型要求 | 凭据要求 |
|--------|-------------|-------------|----------|
| `binance` | `binance_api` | `crypto` | `api_key` + `api_secret` |
| `kraken` | `kraken_api` | `crypto` | `api_key` + `api_secret` |
| `okx` | `okx_api` | `crypto` | `api_key` + `api_secret` |
| `polymarket` | `polymarket_api` | `security` 或 `crypto` | `wallet` 或 `proxy_wallet` |

## 输出格式

### 成功

```
Syncing binance → 币安...
Fetched 1,234 trades from API.
Batch 1/3: 500 new, 0 skipped.
Batch 2/3: 500 new, 0 skipped.
Batch 3/3: 234 new, 0 skipped.
Sync complete: 1,234 new events imported. Cursor updated.
```

### 成功（增量，无新交易）

```
Syncing binance → 币安...
No new trades since last sync.
```

### 成功（幂等，全部跳过）

```
Syncing binance → 币安 (full)...
Fetched 1,234 trades from API.
Batch 1/3: 0 new, 500 skipped (idempotent).
Batch 2/3: 0 new, 500 skipped (idempotent).
Batch 3/3: 0 new, 234 skipped (idempotent).
Sync complete: 0 new events (1,234 already imported).
```

### 错误：凭据缺失

```
Error: 凭据文件 ~/.ft/credentials.yaml 缺少 'binance' 段。
请创建并写入：
  binance:
    api_key: "your-api-key"
    api_secret: "your-api-secret"
```
退出码：`1`

### 错误：账户不存在或类型不匹配

```
Error: 账户 '币安' 不存在。请先运行 ft account add。
```
或
```
Error: 账户 '储蓄卡' 类型为 cash，不能用于 exchange 同步（需要 crypto）。
```
退出码：`1`

### 错误：API 异常（重试耗尽）

```
Syncing binance → 币安...
Error: API 请求失败（重试 3 次后）: 429 Too Many Requests
已拉取 1,500 条交易，但未能完成全部分页。
本次未写入任何事件、快照或游标；请稍后重试。
请稍后重试: ft sync --source binance --account 币安
```
退出码：`1`

### 错误：数据异常（fail-closed）

```
Syncing binance → 币安...
Fetched 1,234 trades from API.
Error (Chunk 2/3): 交易数据异常，本次同步已整体回滚。
  异常条目: trade_id=12345678, symbol=ETH/???, side=buy — 无法拆分 symbol
  本次未写入任何事件、快照或游标。
请检查交易所数据后重试。
```
退出码：`1`

## 退出码

| 退出码 | 含义 |
|--------|------|
| `0` | 同步成功完成（含 0 新事件的幂等场景） |
| `1` | 任何错误（凭据/账户/API/数据异常） |

## 前置条件

1. `FT_DATABASE_URL` 已设置且指向有效的 PostgreSQL 或 SQLite 数据库
2. `FT_WORKSPACE_ID` 已设置
3. 目标账户已通过 `ft account add` 创建
4. 凭据文件 `~/.ft/credentials.yaml` 已配置对应 provider

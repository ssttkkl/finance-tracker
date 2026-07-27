# Research: 投资连接器同步

**Feature**: `018-investment-connector-sync` | **Date**: 2026-07-26

## 1. ccxt `fetch_my_trades` 与 `fetch_ledger` 分页机制

### Decision
使用 ccxt 统一的 `fetch_my_trades(symbol=None, since=None, limit=None, params={})` 全量拉取所有交易对，并使用 `fetch_ledger(code=None, since=None, limit=None)` 拉取所有资金账本活动。两个端点都必须分页至耗尽；优先使用 provider 支持的 offset/cursor 参数，无法安全推进（重复页、时间戳边界不完整或 provider 不支持分页）时失败关闭，不能把首页当成全历史。两个端点完整拉取成功后才将所有事件交给同一个 UnitOfWork。

### Rationale
- ccxt 统一 API 屏蔽了各交易所分页差异（Binance 用 `fromId`，Kraken 用 `since`，OKX 用 `after`）。
- `since` 分页是 ccxt 推荐的跨交易所通用方式（vs `id`-based 仅部分交易所支持）。
- 不传 `symbol` 参数时返回所有交易对的交易（已在 clarify 中确认）。
- `limit` 默认 500（多数交易所），可在 connector 初始化时配置。

### Alternatives Considered
- **`id`-based 分页**：仅 Binance 支持 `fromId`，不通用，弃用。
- **逐 symbol 分页**：需先获取 symbol 列表再逐一拉取，API 调用数量倍增，弃用。
- **仅 `fetch_orders`**：订单不等于成交或资金变动，弃用。

## 1a. 交易所 Ledger → 投资事件映射

### Decision

`fetch_my_trades` 是成交 `swap` 的唯一规范来源。`fetch_ledger` 的 raw `info.type` 用于全部非成交资金活动：

| ledger type | action | 快照效果 | 幂等键 |
|---|---|---|---|
| `deposit` | `deposit` | 增加该资产 | ledger ID |
| `withdrawal` | `withdraw` | 减少该资产 | ledger ID |
| `staking`、`reward`、`credit`、`rollover` | `dividend` | 增加该资产 | ledger ID |
| `transfer`、`derivativescrossexchangetransfer` | `transfer` | 不改变账户快照，仅保留审计事实 | ledger ID |
| 非零 `fee` | `fee` | 减少 fee currency | `<ledger_id>:fee` |

`trade` ledger 分录是同一成交的资产双腿，已经由 `fetch_my_trades` 表达为一条 swap；不得二次建账。任何未列类型、缺失 ID/timestamp/currency/amount、不可解析 fee 或非零 fee 缺币种都抛出 `ConnectorDataError`，使全次同步回滚。

### Rationale

- 本地安装的 ccxt 4.5.68 显示 Binance、Kraken、OKX 均支持 `fetchMyTrades` 与 `fetchLedger`。
- 对真实 Kraken 的只读 ledger 扫描发现 `deposit`、`reward`、`staking`、`transfer`、`derivativescrossexchangetransfer` 和 `trade`；映射覆盖这些已观察类型且保留 strict fallback。
- Kraken 的 ledger trade 分录是每种资产一条，独立映射会把一笔交换拆成两条单边事实，造成重复记账。

### Alternatives Considered

- **静默跳过未知或错误 ledger**：违反 Constitution I，弃用。
- **将错误 fee 置零**：丢失财务事实，弃用。
- **把内部 transfer 当 deposit/withdraw**：会虚增或虚减同一账户持仓，弃用。

## 2. ccxt Trade → 投资事件映射规则

### Decision
映射规则与旧 worktree (`crypto-account/exchange_sync.py`) 验证的逻辑一致：

| ccxt trade 字段 | 投资事件字段 | 转换规则 |
|---|---|---|
| `symbol` (e.g. `ETH/BTC`) | `from_ticker` / `to_ticker` | 按 `/` 拆分为 base/quote；BUY: from=quote, to=base；SELL: from=base, to=quote |
| `side` | `action` | 固定 `swap` |
| `amount` | `from_amount`(sell) 或 `to_amount`(buy) | 精确十进制 |
| `cost` | `from_amount`(buy) 或 `to_amount`(sell) | `cost = price × amount`；若 API 返回 `None` 则计算 |
| `price` | (不直接存；可放 `source_payload`) | 用于 cost 兜底计算 |
| `fee.cost` | `commission` | 精确十进制；若无费用则 `0` |
| `fee.currency` | `commission_asset` | 小写 |
| `timestamp` | `occurred_at` | UTC 毫秒 → `datetime(UTC)` |
| `id` | `record_id` | 字符串化 |
| 整条 trade dict | `source_payload` | JSON 序列化 |

Ticker 规范化：现有 `ticker_normalize.py` 只有 equity helper，不能错误复用；本 feature 在该模块新增最小的 `normalize_crypto_ticker`，以 `schema.CRYPTO_IDS` 为 canonical ticker 集，并显式处理交易所别名（至少 `XBT` → `btc`）后再小写存储。trade、ledger currency 与 fee currency 必须共用此入口，避免文件/API 导入产生不同持仓 ticker。

### Rationale
- 旧 worktree 已用真实 Binance 数据验证过此映射（`trade_to_rows` 函数）。
- `swap` 是唯一合理的 action：加密交易本质是一种资产换另一种。
- commission_asset 保留原始币种（如 BNB），不折算，保持审计可追溯。

### Alternatives Considered
- **BUY→deposit + SELL→withdraw**：语义错误——交易不是存取款。
- **映射为 buy/sell action**：现有投资事件模型中 `buy`/`sell` 是旧 action，统一用 `swap` 更准确。

## 3. Polymarket Activity API 机制

### Decision
使用 Polymarket Data API 的公开 Activity 端点：
```
GET https://data-api.polymarket.com/activity?user={proxy_wallet}&limit={limit}&offset={offset}
```

分页通过 `offset` 实现（每页默认 500 条）。处理 `TRADE`、`REDEEM` 与 `YIELD`；其他活动类型跳过。

Proxy wallet 解析：若用户提供 login 地址（非 proxy），从 Polymarket profile 页面 HTML 中提取 `proxyAddress`（旧 worktree 已验证此方法）。

### Rationale
- Activity API 无需认证（公开数据），仅需 proxy wallet 地址。
- 旧 worktree (`polymarket_sync.py`) 已用真实数据验证分页和映射逻辑。
- `REDEEM` 是已结算结果仓位换回 USD，映射为结果 ticker 到 USD 的 `swap`；`YIELD` 是 USD 收益，映射为 `dividend`。
- 未定义的活动类型不猜测账务语义，继续跳过。

### Alternatives Considered
- **CLOB API（需认证）**：需要 API key + HMAC 签名，复杂度高，收益低（Activity API 已包含成交信息）。
- **链上数据直接解析**：需要 RPC 节点和 ABI 解析，极度复杂且不可靠。

## 4. Polymarket Activity → 投资事件映射规则

### Decision

| Activity 字段 | 投资事件字段 | 转换规则 |
|---|---|---|
| `slug` + `outcome` | `from_ticker` / `to_ticker` | BUY: from=`usd`, to=`pm:<slug>:<outcome>`；SELL: 反向 |
| `size` | shares 数量 | 精确十进制 |
| `usdcSize` | USD 金额 | 精确十进制；若 `None` 则 `size × price` |
| `price` | (放 `source_payload`) | 单价 |
| `side` | `action` | 固定 `swap` |
| `timestamp` | `occurred_at` | Unix 秒 → `datetime(UTC)` |
| Activity 内部 ID 或 `transactionHash` | `record_id` | 优先使用 Activity 返回的 `id` 字段；若无则用 `transactionHash` |
| 整条 activity dict | `source_payload` | JSON 序列化 |

Ticker 格式：`pm:<slug>:<yes|no>`（小写），与 `PredictionMarketQuoteProvider` 中 gamma-api 使用的标识对齐。

### Rationale
- 旧 worktree 的 `activity_to_stock_row` 已验证此映射。
- USD（而非 USDC）作为现金对手方——Polymarket 用户心理模型是 USD，USDC 是实现细节。
- `commission` 固定为 `0`（Polymarket 不对 CLOB 交易收取显式费用）。

## 5. 凭据管理方案

### Decision
复用旧 worktree 的 `credentials.yaml` 模式，迁移到新架构：

```yaml
# ~/.ft/credentials.yaml
binance:
  api_key: "..."
  api_secret: "..."
kraken:
  api_key: "..."
  api_secret: "..."
polymarket:
  wallet: "0x..."          # login 地址
  # 或
  proxy_wallet: "0x..."    # proxy wallet 地址
```

- 文件路径：`~/.ft/credentials.yaml`（`FT_DIR` 下）
- 权限：自动设为 `0600`
- Gitignore：自动确保在 `~/.ft/.gitignore` 中
- 交易所必填字段：`api_key` + `api_secret`
- Polymarket 必填字段：`wallet` 或 `proxy_wallet`（二选一）

### Rationale
- 旧 worktree 已验证此方案可行且用户接受度好。
- 单用户本地运行——文件权限足够，不需要 vault。
- YAML 格式与现有 `accounts.yaml` 一致，用户学习成本低。

### Alternatives Considered
- **环境变量**：多个交易所时环境变量命名混乱（`FT_BINANCE_API_KEY`/`FT_KRAKEN_API_KEY`/...），弃用。
- **加密存储（keyring/vault）**：constitution 明确「不得为假设中的未来场景预建框架」，当前不需要。
- **数据库存储**：凭据不应依赖特定数据库后端，且不应随 workspace 变化。

## 6. 增量游标设计

### Decision
新建 `sync_cursors` 表，存储增量同步的断点位置：

- 复合唯一键：`(workspace_id, account_id, source_type)`
- 游标值：`cursor_value`（字符串，存储毫秒时间戳或 offset）
- 更新时间：`updated_at`

同步流程：
1. 读取游标（若存在且未传 `--full`）
2. 从游标位置开始分页拉取
3. 同步成功后，UPSERT 游标为最后一条交易的时间戳

游标更新属于同步的同一事务，并与事件和快照一起提交；任一分页、映射或校验失败时，游标不变。

### Rationale
- 专用表语义清晰，生命周期独立。
- UPSERT 保证首次和后续同步都安全。
- 游标值用字符串而非特定类型，适应不同 connector 的游标格式（时间戳、offset、ID）。

### Alternatives Considered
- **从 `import_batches` 推导**：当前无 import_batches 表，且推导逻辑复杂（需找最大 timestamp）。
- **通用 KV 表**：无当前需求，过度抽象。

## 7. 批次事务策略

### Decision
分块处理、单次提交（默认每块 500 条），整体 fail-closed 语义：

1. 首次/全量同步时，API 全量分页拉取到内存列表
2. 按 500 条分批处理
3. 在一个 UnitOfWork 事务中按块执行：映射 → 幂等检查 → 投影 → 快照校验
4. 任一分页、映射或校验异常 → 回滚整个事务并报告失败点或异常条目
5. 所有块均成功后，在同一事务 UPSERT 游标并一次性提交

### Rationale
- 整体 fail-closed 符合 constitution「无法证明安全的异常路径 MUST 失败关闭」，并避免用户面对部分同步结果。
- 分块处理控制循环中的内存和校验粒度；数据库事务保持原子性。
- 完整分页成功是写入前置条件，失败后可安全重试且不会改变游标。

## 8. 重试策略

### Decision
API 调用层（connector adapter 内部）实现指数退避重试：

- 可重试错误：网络超时、HTTP 429、HTTP 5xx
- 不可重试：HTTP 401/403（凭据问题）、HTTP 400（请求错误）
- 重试间隔：1s → 2s → 4s（指数退避，最多 3 次）
- 超过重试次数后抛出 `ConnectorError`，由 SyncService 捕获并 fail-closed

### Rationale
- 交易所 API 限流常见，短暂重试能显著提升成功率。
- 不可重试错误（凭据错误）不应重试以避免账户锁定。
- 3 次重试 + 指数退避是标准实践，总等待 ≤ 7 秒。

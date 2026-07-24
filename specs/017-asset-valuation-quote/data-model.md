# Data Model: 017-asset-valuation-quote

本 feature **无持久化表变更**。以下为内存/API 领域模型（Python dataclass / 等价不可变结构）。

## Enumerations

### AssetKind

| Value | 含义 |
|-------|------|
| `security` | 股票/ETF 等可规范化交易所标识 |
| `crypto` | 加密资产（已知代码表） |
| `prediction_market` | Polymarket 类 outcome |
| `cash` | 现金腿，单价恒 1 |

### QuoteStatus

| Value | 何时 |
|-------|------|
| `complete` | 有有限单价，且 freshness 窗口内（cash 总是） |
| `stale` | 有有限单价，超出 freshness 但未超 maximum |
| `partial` | 资产可路由但本次无可靠单价 |
| `unsupported` | 不在支持矩阵 / 规范化失败 / 类型与标识矛盾 |

### QuoteReason（可选稳定码，字符串）

建议集合（可扩展，测试锁定常用）：

- `ok`
- `unsupported_identity`
- `unsupported_kind`
- `identity_kind_mismatch`
- `provider_error`
- `non_finite_price`
- `empty_provider_response`
- `stale_quote`
- `invalid_quantity`

## Entities

### AssetRef

| Field | Type | Rules |
|-------|------|--------|
| identity | str | 非空 strip 后；批量内可重复 |
| kind | AssetKind | 必填 |
| quantity | Decimal \| null | 若存在：有限且 ≥ 0 |

**校验**:
- identity 空白 → 错误
- quantity NaN/Inf/负 → 错误
- kind 与 identity 明显矛盾（如 kind=cash 且 identity 以 `pm:` 开头）→ `unsupported` 或输入错误（plan：矛盾 → 该项 `unsupported` + `identity_kind_mismatch`，便于批量；单字段 API 同）

### QuoteResult

| Field | Type | Rules |
|-------|------|--------|
| identity | str | 回显输入（或规范化展示形，contract 固定为**输入回显**） |
| kind | AssetKind | 回显 |
| status | QuoteStatus | 必填 |
| unit_price | Decimal \| null | 仅 complete/stale 非 null；有限 |
| quote_currency | str \| null | 与 unit_price 同现；ISO 大写优先 |
| observed_at | datetime \| null | aware；complete/stale 应有 |
| market_value | Decimal \| null | 仅当 quantity 提供且 unit_price 有值：`unit_price * quantity` |
| quantity | Decimal \| null | 回显 |
| reason | str | 稳定码 |
| provider | str \| null | 可选：`cash` / `yfinance` / `coingecko` / `polymarket` / null |

### QuoteBatchResult

| Field | Type |
|-------|------|
| results | list[QuoteResult] | 与输入顺序对齐 |
| complete_count | int | 可选摘要 |
| failed_count | int | partial+unsupported 计数可选 |

## 状态转移（逻辑）

```text
request
  → validate AssetRef
       fail → raise application error (not a QuoteResult)
  → route by kind
       cash → price=1, complete
       else → provider.raw_quote
            missing/error/non-finite → partial
            unsupported route → unsupported
            ok → apply freshness(observed_at, now, kind)
                 within freshness → complete
                 within maximum → stale
                 beyond maximum → partial (drop price)
  → if quantity and price → market_value
```

## 与持仓 DTO 扩展

`PortfolioPositionDTO`（及组合查询等价结构）增加：

| Field | Type | Notes |
|-------|------|--------|
| quote_status | str \| null | QuoteStatus value；现金 complete；无请求时 null |
| quote_reason | str \| null | 可选 |

既有 `current_price` / `market_value` 在 partial/unsupported 时保持 `None`。

## 持久化

无。不修改 Alembic head。

# Data Model: 017-asset-valuation-quote

无持久化表变更。

## Enumerations

### AssetKind

`security` | `crypto` | `prediction_market` | `cash`

### QuoteStatus

`complete` | `stale` | `partial` | `unsupported`

### FxStatus（展示币种折算结果）

`complete` | `partial` | `not_applicable`

- `not_applicable`：计价币种模式或计价币种无市值无需 FX
- `complete`：得到有限正汇率并完成折算
- `partial`：需要 FX 但不可用/非有限

### Reason 码（常用）

`ok`, `unsupported_identity`, `provider_error`, `non_finite_price`, `empty_provider_response`, `stale_quote`, `fx_unavailable`, `currency_unspecified`, `invalid_quantity`, `identity_kind_mismatch`

## AssetRef / QuoteResult / QuoteBatchResult

同原子 API：`identity`, `kind`, `quantity?` → `status`, `unit_price?`, `quote_currency?`, `observed_at?`, `market_value?`, `reason`, `provider?`

金额：有限 Decimal。仅 complete/stale 带单价。

## PortfolioValuationQuery

| Field | Type | Rules |
|-------|------|--------|
| display_currency | str \| null | null=计价币种模式；非 null 则必须为 3 字母 ISO（大小写不敏感，存大写） |

## PositionValuation（映射到 PortfolioPositionDTO）

| Field | Type | Notes |
|-------|------|--------|
| ticker | str | |
| shares | Decimal | |
| total_cost | Decimal | 账本成本，非市价 |
| cost_currency | str | |
| is_cash | bool | |
| current_price | Decimal \| null | **计价币种**单价 |
| market_value | Decimal \| null | **计价币种**市值 |
| quote_currency | str \| null | 行情计价币种 |
| quote_status | str \| null | |
| quote_reason | str \| null | |
| profit | Decimal \| null | 仅当计价币种市值与成本币种一致时可算；否则 null 或仅文档化限制 |
| display_currency | str \| null | |
| display_market_value | Decimal \| null | |
| fx_rate | Decimal \| null | display per 1 quote_currency |
| fx_status | str \| null | |
| fx_reason | str \| null | |

## 逻辑流

```text
load positions
for each non-zero position:
  kind = infer_kind(ticker, cash_set)
  q = valuation.quote(AssetRef(ticker, kind, quantity=shares))
  fill native fields from q
if display_currency:
  validate ISO
  for each with native market_value and quote_currency:
    if quote_currency == display: rate=1, display_mv=native_mv
    else: rate = fx.get_mid(quote_currency, display)
            if rate ok: display_mv = native_mv * rate
            else: fx partial, display_mv=None
```

## 持久化

无 Alembic 变更。

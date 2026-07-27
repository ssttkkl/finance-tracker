# Contract: Valuation Application API（原子）

## ValuationService

### `quote(ref: AssetRef) -> QuoteResult`

- 不做 FX 折算。
- 输入非法：`valuation.invalid_*` 应用错误。
- 源失败 → `status=partial`，不抛业务崩溃。

### `quote_many(refs) -> QuoteBatchResult`

- 顺序对齐；空列表 ok。
- **整批预校验**输入；源失败逐项 partial/unsupported。

## QuoteResult JSON 形

```json
{
  "identity": "aapl.us",
  "kind": "security",
  "status": "complete",
  "unit_price": "190.12",
  "quote_currency": "USD",
  "observed_at": "2026-07-25T12:00:00+00:00",
  "quantity": "10",
  "market_value": "1901.20",
  "reason": "ok",
  "provider": "yfinance"
}
```

## QuoteProvider port

`raw_quote(identity, kind) -> ProviderTick | raise Unsupported | return None`

Composite 路由：cash / security / crypto / prediction_market。

预测市场 v1：`quote_currency` 固定 **`USD`**。

# Contract: Valuation Application API

## ValuationService

### `quote(ref: AssetRef) -> QuoteResult`

- 单资产估值。
- 输入校验失败：抛应用错误（建议码前缀 `valuation.`），例如：
  - `valuation.invalid_identity`
  - `valuation.invalid_quantity`
  - `valuation.invalid_kind`
- 不抛「源失败」给调用方业务层；源失败体现在 `status=partial`。

### `quote_many(refs: Sequence[AssetRef]) -> QuoteBatchResult`

- 顺序与 `refs` 一致。
- 空列表 → 空 `results`，不报错。
- 单项源失败不影响其他项。
- 若采用「整批预校验」，任一 ref 输入非法 → 整批抛错且不返回部分结果（确定性；实现选此更简单）。**选定：整批预校验**。

## QuoteResult JSON 形（CLI/未来 Web 对齐）

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

- 金额字段：**字符串十进制**或 Decimal 序列化约定与项目现有 DTO 一致；测试用 `Decimal` 精确比。
- `unit_price` / `market_value` 在 partial/unsupported 时为 `null`。

## Port: QuoteProvider

```text
raw_quote(identity: str, kind: AssetKind) -> ProviderTick | None
```

`ProviderTick`:
- `price: Decimal`（有限）
- `quote_currency: str`
- `observed_at: datetime`（aware）
- `provider: str`

返回 `None` → Application 映射为 `partial` + `provider_error` 或 `empty_provider_response`。  
Provider 对不支持的 identity 应抛 `UnsupportedQuoteError`（或返回明确 unsupported 哨兵）→ Application 映射 `unsupported`。

### Composite 路由

| kind | provider |
|------|----------|
| cash | CashQuoteProvider（本地） |
| security | SecurityQuoteProvider（yfinance 适配） |
| crypto | CryptoQuoteProvider（CoinGecko 类 HTTP） |
| prediction_market | PredictionMarketQuoteProvider（gamma-api） |

## 与旧 MarketDataProvider

| 旧 | 新 |
|----|----|
| `get_prices(tickers, quote_currency=_) -> dict[str, Decimal]` | 由 `ValuationService.quote_many` 推导；**忽略**未实现的 FX `quote_currency` 折算（与旧行为一致：参数基本未用） |
| 缺键 | `status` 非 complete/stale |

Wiring：`build_services` / `relational/runtime.py` 构造 `ValuationService` 注入 portfolio/queries。

## CLI（可选）

```text
ft quote IDENTITY --kind security|crypto|prediction_market|cash [--quantity Q]
```

- 退出码：输入错误非 0；业务上 unsupported/partial 仍退出 0 并打印 status（避免脚本把「无价」当崩溃）。精确退出码在实现任务中锁定并测。

## 双后端

本 contract 不暴露 SQL。消费方在 PG/SQLite 下行为一致。

# Contract: Portfolio / 持仓估值字段

## PortfolioPositionDTO（扩展）

既有字段保留。新增：

| Field | Type | When set |
|-------|------|----------|
| `quote_status` | `str \| None` | 尝试估值后：`complete`/`stale`/`partial`/`unsupported` |
| `quote_reason` | `str \| None` | 可选稳定码 |

语义：

1. **现金持仓**（`is_cash=True`）：`current_price=1`，`market_value=shares*1`，`quote_status=complete`，`quote_reason=ok`。
2. **非零非现金**：调用估值；complete/stale 填 `current_price` 与 `market_value`；partial/unsupported 则价格字段 `None`，status/reason 仍填。
3. **零股**：可不请求估值；价格字段 `None`，status 可为 `None`。

## FinanceQueryService 账户余额中的证券市值

`_account_balances` 对 security 账户：

- 有 complete/stale 价：用市值累加。
- 否则：**不得**用 `0` 冒充；可回退 `total_cost`（保持旧降级）但不得假装为市价——实现应优先与「无价则成本」旧行为兼容并在 quickstart 注明；更优是在后续 Web 展示 status（本 feature 最低要求：portfolio DTO 带 status）。

## 测试合同

- Fake `ValuationService` / Fake providers 覆盖：全成功、混合、全失败。
- 断言不再存在「唯一路径」直接 `MarketDataProvider.fetch_prices` 而无 status 的 portfolio 组装（允许内部委托）。

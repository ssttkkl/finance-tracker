# Contract: 组合持仓市值（P0）

## PortfolioQueryService.get_portfolio

```text
get_portfolio(*, display_currency: str | None = None) -> PortfolioDTO
```

| 参数 | 行为 |
|------|------|
| `display_currency=None` | **计价币种模式**：只填计价币种价格/市值/status |
| `display_currency="CNY"` 等 | **展示模式**：计价币种字段 + 折算市值/汇率/fx_* |

非法 display_currency → 抛 `valuation.invalid_display_currency`（或 `portfolio.invalid_display_currency`）。

## PortfolioPositionDTO 字段

| 字段 | 计价币种模式 | 展示模式 |
|------|----------|----------|
| current_price | 计价币种单价 | 同左 |
| market_value | 计价币种市值 | 同左 |
| quote_currency | 计价 ISO | 同左 |
| quote_status / quote_reason | 估值状态 | 同左 |
| display_currency | null | 回显大写 ISO |
| display_market_value | null | 折算市值或 null |
| fx_rate | null | display per 1 quote_currency 或 null |
| fx_status / fx_reason | null 或 not_applicable | complete/partial |

## 规则

1. 估值统一走 `ValuationService`（推断 kind 见表 research R5）。
2. 无计价币种市值 → 不折算。
3. FX 失败 → 禁止异币 rate=1。
4. `FinanceQueryService` 证券账户合计：优先计价币种；跨币账户不得用单一数字假装全市场合计，除非调用方指定 display 且各项均可折算；不可折算项排除在合计外并保持可观测（实现选：返回分币种余额元组保持旧形，或文档化 partial 合计——**选定**：账户 balances 在展示模式下尝试 display 合计，缺 FX 的持仓不计入该合计数字，且不得用成本冒充已折算市价；计价币种模式保持按账户主币或分币种既有行为，无价回退成本时不得称为 mark-to-market）。

## FxRateProvider

```text
get_mid(base: str, quote: str, *, day: str | None = None) -> Decimal | None
```

- base=持仓 quote_currency，quote=display_currency
- 返回 **quote 单位 per 1 base**
- 可注入；生产 Frankfurter 类

## 测试合同

- Fake Valuation + Fake FX 覆盖：计价币种多币种、展示折算、FX 失败、非法 display、unsupported ticker。
- 双后端假源一致（可选 wiring 测试）。

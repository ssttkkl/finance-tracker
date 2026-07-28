# Implementation Plan: 实时资产估值与持仓市值

**Branch**: `017-asset-valuation-quote` | **Date**: 2026-07-25 | **Spec**: [spec.md](./spec.md)

**Input**: Living Spec — **P0** 组合持仓市值（计价币种 + 指定展示币种折算）消费统一估值；原子 quote 为 P1 支撑。

## Summary

1. 实现可注入的 **`ValuationService`**（标识+类型→单价/状态/计价币种市值）。
2. **P0** 改造 **`PortfolioQueryService.get_portfolio(display_currency=None|ISO)`**：
   - 计价币种：各持仓按行情计价货币估值 + `quote_status`；
   - 展示币种：计价币种结果 × 只读 FX mid → `display_market_value`（失败不 1:1 默折）。
3. 拆分/重构 `market_data` 为 Quote providers；FX port 可注入。
4. 同步 `FinanceQueryService` 证券市值路径与 fakes/tests。
5. 无 schema、不写账本。

## Technical Context

**Language/Version**: Python 3.11+
**Primary Dependencies**: 现有 ft 六边形；yfinance；urllib HTTP；可选 Frankfurter FX
**Storage**: 无新表
**Testing**: pytest；Fake Valuation + Fake FX；可选 FT_TEST_POSTGRES_URL
**Target Platform**: CLI/library
**Project Type**: dual-backend finance app
**Performance Goals**: 个人组合规模；批内串行真源可接受
**Constraints**: Decimal；禁止异币默认汇率 1；无账本写入
**Scale/Scope**: domain/application/adapters + portfolio DTO + runtime wiring

## Constitution Check

| Principle | Status |
|-----------|--------|
| I 财务正确性 | PASS — Decimal；状态可区分；FX 不改正式金额 |
| II Spec Kit | PASS — 017 artifacts |
| III 测试先行 | PASS — 假源/假 FX 先红后绿 |
| IV 双后端等价 | PASS — 无方言估值；假源一致 |
| V 边界最小 | PASS — port 注入；不建行情平台 |

**Persistence matrix**: 无 schema 变更；算法与后端无关。

**Status**: PASS

## Project Structure

### Docs

```text
specs/017-asset-valuation-quote/
├── plan.md, research.md, data-model.md, quickstart.md, spec.md, tasks.md
└── contracts/valuation-api.md, portfolio-quote-fields.md
```

### Code touch list

```text
src/ft/domain/valuation.py
src/ft/application/valuation.py
src/ft/domain/investment.py          # DTO fields
src/ft/application/investment.py     # PortfolioQueryService P0
src/ft/application/queries.py
src/ft/adapters/market_data.py       # providers + symbol map
src/ft/adapters/fx_rates.py          # injectable today mid / port
src/ft/adapters/relational/runtime.py
tests/unit/domain/test_valuation_quote.py
tests/unit/application/test_valuation_service.py
tests/unit/application/test_portfolio_valuation.py
tests/unit/adapters/test_quote_symbol_map.py
tests/test_application_investment.py
tests/test_application_queries.py
tests/fakes.py
```

## Phase 0 / 1

见 research.md、data-model.md、contracts/*、quickstart.md。

**Kind 推断、FX 语义、DTO 字段、P0/P1 优先级** 以 Living Spec + research 为准。

## Implementation notes

1. 先原子 domain/service（支撑）再立刻做 portfolio P0，避免只交付原子 API。
2. `get_portfolio(display_currency=...)` 为对外主合同。
3. FX：`display_market_value = market_value * get_mid(quote_currency, display_currency)`。
4. 文档 T：路线图指向 017。

## Complexity Tracking

无 constitution 违例。

# Quickstart: 017-asset-valuation-quote

## Prerequisites

- `uv` + Python 3.11+
- 自动化以 **Fake Valuation + Fake FX** 为主
- 可选 `FT_TEST_POSTGRES_URL`

## 1. 原子估值（支撑）

```bash
uv run pytest tests/unit/domain/test_valuation_quote.py tests/unit/application/test_valuation_service.py -q
```

## 2. 符号映射

```bash
uv run pytest tests/unit/adapters/test_quote_symbol_map.py -q
```

## 3. 组合 P0：本币 + 展示币

```bash
uv run pytest tests/test_application_investment.py tests/unit/application/test_portfolio_valuation.py -q
```

期望：
- `get_portfolio()` 多币种持仓各带 quote_status 与本币市值
- `get_portfolio(display_currency="CNY")` 有 display_market_value 与 fx_rate；FX fail 时无默 1:1

## 4. Queries / fakes

```bash
uv run pytest tests/test_application_queries.py -q
```

## 5. 可选双后端

```bash
export FT_TEST_POSTGRES_URL=...
uv run pytest tests/contract/test_valuation_wiring_dual_backend.py -q
```

## 6. 非目标

无新 Alembic head；无 Connector；无财富 rebuild。

# 验证指南：持仓行情报价编排

## 前提

- 在仓库根目录执行命令。
- Python 依赖通过 `uv` 可用。
- PostgreSQL 契约测试需要设置 `FT_TEST_POSTGRES_URL`，并按项目测试约定启用真实测试库。

## 单元验证

```sh
uv run pytest \
  tests/unit/application/test_valuation_service.py \
  tests/unit/application/test_portfolio_valuation.py \
  tests/test_market_data.py -q
```

预期：重复标的仅取价一次；一个阻塞源不阻塞其他源；所有测试在 4 秒预算内结束；批量源保留逐项结果。

## 双后端验证

```sh
FT_REQUIRE_TEST_POSTGRES=1 uv run pytest \
  tests/integration/test_portfolio_query_sqlite.py \
  tests/integration/test_portfolio_query_postgres.py -q
```

预期：SQLite 与 PostgreSQL 在同一假报价输入下返回相同的单价、市值、计价币种、展示币种折算和估值状态。

## 全量回归

```sh
uv run pytest tests/ -q
```

预期：全部既有测试与本 feature 测试通过。真网报价不作为自动化通过条件。

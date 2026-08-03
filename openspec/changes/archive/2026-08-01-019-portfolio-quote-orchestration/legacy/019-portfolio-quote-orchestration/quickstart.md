# 验证指南：持仓行情报价编排

## 前提

- 在仓库根目录执行命令。
- Python 依赖通过 `uv` 可用。
- PostgreSQL 契约测试需要设置 `FT_TEST_POSTGRES_URL`，并按项目测试约定启用真实测试库。

## 确定性时钟目标验证

```sh
uv run pytest \
  tests/test_application_investment.py::test_portfolio_query_uses_valuation_and_never_prices_configured_currency \
  -q
```

预期：固定证券行情保持 `complete` 且市值正确；账户基础币种不请求外部行情；不支持估值的配置币种保持 `unsupported` 且市值为空。

该测试中的 `clock` 仅为测试时间基准，返回与固定 `ProviderTick.observed_at` 相同的 UTC 时间。生产运行时不注入该 `clock`，仍使用 `ValuationService` 的默认当前 UTC 时钟；报价新鲜度窗口和 `complete`、`stale`、`partial`、`unsupported` 的合同不变。

## 受影响测试矩阵

```sh
uv run pytest \
  tests/unit/domain/test_valuation_quote.py \
  tests/unit/application/test_valuation_service.py \
  tests/unit/application/test_portfolio_valuation.py \
  tests/test_application_investment.py -q
```

预期：既有 `complete`、`stale`、`partial`、`unsupported` 的状态、单价和市值语义保持不变；固定行情测试不依赖执行当天日期。019 原有的重复标的、阻塞源和批量请求回归继续由既有测试矩阵覆盖。

## 双后端验证

```sh
FT_REQUIRE_TEST_POSTGRES=1 uv run pytest \
  tests/integration/test_portfolio_query_sqlite.py \
  tests/integration/test_portfolio_query_postgres.py -q
```

预期：SQLite 与 PostgreSQL 在同一假报价输入下返回相同的单价、市值、计价币种、展示币种折算和估值状态。

## 全量回归（已知非阻断失败）

```sh
uv run pytest -q
```

预期：除已记录失败外全部测试通过。真网报价不作为自动化通过条件。

**验证记录（2026-07-30）**：目标测试为 `1 passed in 0.02s`；受影响测试矩阵为 `32 passed in 1.58s`；SQLite 与真实 PostgreSQL 矩阵为 `4 passed in 1.68s`。完整套件为 `1 failed, 1005 passed, 80 skipped, 1 warning in 231.34s`；已知无关失败为 `tests/test_wealth_performance.py::test_fixed_100k_fact_rebuild_and_active_cache_meet_budgets[sqlite]`。023 原始记录的冷路径 P95 为 `10.53 s`，本次复跑为 `7.43 s`，均超过 `5 s` 性能预算。用户已授权本次将其作为非阻断记录；完整套件不因此视为全绿。风险是财富性能回归尚未得到覆盖，准确补跑命令为 `uv run pytest -q`。

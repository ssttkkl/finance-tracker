# Quickstart: 017-asset-valuation-quote

## Prerequisites

- Python 3.11+，`uv` 同步依赖（含可选 yfinance，证券真源路径）。
- 本 feature 自动化以 **假源** 为主，无需网络。
- 双后端冒烟：SQLite 默认；PostgreSQL 使用既有 `FT_TEST_POSTGRES_URL`（如 Docker `127.0.0.1:55432`）。

## 1. 单元：领域状态与市值

```bash
uv run pytest tests/unit/domain/test_valuation_quote.py -q
```

期望：cash=1 complete；freshness → complete/stale/partial；市值=单价×数量；非有限价拒绝进入 complete。

## 2. 单元：Application + Fake providers

```bash
uv run pytest tests/unit/application/test_valuation_service.py -q
```

期望：批量混合成功/unsupported/provider_error；整批非法 quantity 抛错；顺序对齐。

## 3. 适配器映射（无网络）

```bash
uv run pytest tests/unit/adapters/test_quote_symbol_map.py -q
```

期望：`aapl.us`→yfinance `AAPL`；`00700.hk`/`0700.hk`→合法 HK 符号；`btc` 映射存在；未知 crypto unsupported。

## 4. 组合消费

```bash
uv run pytest tests/test_application_investment.py tests/test_application_queries.py -q
```

期望：Fake 估值注入后持仓含 `quote_status`；不可识别 ticker → unsupported 且无虚构价。

## 5. （可选）真实网络手动

```bash
# 需网络；失败不阻塞 CI
uv run ft quote AAPL --kind security
# 或账本形
uv run ft quote aapl.us --kind security
uv run ft quote btc --kind crypto
uv run ft quote pm:some-slug:yes --kind prediction_market
uv run ft quote usd --kind cash
```

期望：打印 status/price；无网络时 partial 而非崩溃。

## 6. 双后端注入冒烟（有 PG 时）

```bash
export FT_TEST_POSTGRES_URL='postgresql+psycopg://…/finance_tracker_test'
uv run pytest tests/contract/test_valuation_wiring_dual_backend.py -q
```

期望：假源下 SQLite 与 PostgreSQL 进程内 quote 结果一致（若 contract 测试已加）。

## 非目标核对

- 无新 Alembic revision。
- 无 Connector、无财富 rebuild、无历史序列命令。

# Implementation Plan: 实时资产估值接口

**Branch**: `017-asset-valuation-quote` | **Date**: 2026-07-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/017-asset-valuation-quote/spec.md`

## Summary

交付 **统一实时估值** Application 合同：按资产标识 + 类型返回精确十进制单价（可选市值）与 **`complete` / `stale` / `partial` / `unsupported`** 状态。用 port 注入证券（yfinance 适配）、加密（CoinGecko 类）、预测市场（Polymarket gamma）、现金（本地单价 1）四类提供方；迁移 `PortfolioQueryService` / `FinanceQueryService` 消费该合同，消除无状态的静默 `get_prices` 唯一路径。不落库、不改 schema、不做历史边界估值或 Connector。

**技术路径**:
1. 领域模型 `ft.domain.valuation`（AssetKind、QuoteStatus、AssetRef、QuoteResult、freshness 纯函数）。
2. `ValuationService.quote` / `quote_many` + `QuoteProvider` 组合路由。
3. 重构/拆分 `ft.adapters.market_data` 为可测试 provider；账本 ticker → 外部符号映射。
4. 持仓 DTO 增加 `quote_status`（及可选 reason）；runtime wiring 注入。
5. 测试：假源单测 + 组合消费 + 可选 dual wiring；真实网络非门禁。

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: 现有 `ft` 六边形结构；`yfinance`（已在 pyproject）；stdlib HTTP（urllib）用于 crypto/polymarket；pytest

**Storage**: N/A 新表；运行时仍 PG/SQLite 显式选择，本 feature 不改迁移 head

**Testing**: pytest；假源为主；`FT_TEST_POSTGRES_URL` 可选 wiring 冒烟

**Target Platform**: macOS/Linux CLI + 库 API（未来 Web 复用同一 Application）

**Project Type**: CLI/library dual-backend finance app

**Performance Goals**: 单批 ≤100 标识；假源 <100ms；真源受网络限制，允许逐 ticker 串行（与现网行为相当）

**Constraints**: 精确 Decimal；非有限价不得 complete；无双写/自动回退；无账本写入；币种不强制 FX 折算

**Scale/Scope**: 新 domain + application + adapters；触及 investment/queries DTO 与 runtime 装配；文档路线图交叉链

## Constitution Check

*GATE: pre-research and post-design*

| Principle | Status | Notes |
|-----------|--------|--------|
| I 财务正确性与可审计性 | PASS | Decimal；无 `0` 冒充市价；状态可区分；不写正式事实 |
| II Spec Kit 规格驱动 | PASS | 仅 `specs/017-asset-valuation-quote/` |
| III 测试先行与验证证据 | PASS | 假源失败测试先于实现；组合字段断言 |
| IV 显式数据库选择与行为等价 | PASS | 无方言价格逻辑；假源结果与后端无关；可选双进程 wiring |
| V 清晰边界与最小复杂度 | PASS | port 注入；不建行情平台/缓存微服务；复用现有 HTTP/yfinance 能力 |

**Persistence parity matrix**（本 feature 无 schema 时）:

| 维度 | PostgreSQL | SQLite | 等价要求 |
|------|------------|--------|----------|
| Schema | 无变更 | 无变更 | head 不变 |
| 估值算法 | 同代码路径 | 同 | 假源输出一致 |
| 事务 | 不涉及 | 不涉及 | — |
| 错误合同 | 同应用错误码 | 同 | — |
| 允许差异 | 外部网络 | 同 | 仅外部源可用性 |

**禁止**: 自动回退、双写、隐式跨后端迁移、文件账本取价。

**Status**: PASS — 无未批准违例

## Project Structure

### Documentation (this feature)

```text
specs/017-asset-valuation-quote/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── valuation-api.md
│   └── portfolio-quote-fields.md
├── checklists/requirements.md
├── spec.md
└── tasks.md            # via /speckit-tasks
```

### Source code (touch list)

```text
src/ft/domain/valuation.py              # NEW: enums, DTOs, freshness, market_value
src/ft/application/valuation.py         # NEW: ValuationService
src/ft/repositories/protocols.py        # optional QuoteProvider protocol
src/ft/adapters/market_data.py          # split/refactor providers + symbol map
src/ft/adapters/quotes/                 # OPTIONAL package if split files preferred
src/ft/domain/investment.py             # PortfolioPositionDTO fields
src/ft/application/investment.py        # PortfolioQueryService → ValuationService
src/ft/application/queries.py           # balances path
src/ft/adapters/relational/runtime.py   # wire ValuationService
src/ft/cli.py                           # optional ft quote
tests/unit/domain/test_valuation_quote.py
tests/unit/application/test_valuation_service.py
tests/unit/adapters/test_quote_symbol_map.py
tests/test_application_investment.py    # update fakes
tests/test_application_queries.py
tests/fakes.py
tests/contract/test_valuation_wiring_dual_backend.py  # optional
```

**Structure decision**: 单包 `src/ft` 内增 domain/application；adapter 可先落在 `market_data.py` 内聚再按需拆文件，避免过度目录。

## Phase 0: Research

见 [research.md](./research.md)。全部开放点已决议（符号映射、状态、批量预校验、不落库、无 FX 折算、组合迁移）。

## Phase 1: Design

| Artifact | Path |
|----------|------|
| 数据模型 | [data-model.md](./data-model.md) |
| Application/Port 合同 | [contracts/valuation-api.md](./contracts/valuation-api.md) |
| 持仓字段合同 | [contracts/portfolio-quote-fields.md](./contracts/portfolio-quote-fields.md) |
| 验证指南 | [quickstart.md](./quickstart.md) |

## Post-design Constitution Check

复评 PASS：设计无持久化分叉、无供应商泄漏进 domain 纯函数、测试以假源锁定财务语义。

## Implementation notes（供 tasks，非抢 implement）

1. **TDD**: 先红 `test_valuation_quote` / `test_valuation_service`，再实现 domain + service。
2. **Symbol map** 单测不碰网络。
3. **Provider** 真源函数保持可替换；构造注入 `fetch` 可调用对象便于测。
4. **DTO 变更** 更新所有构造 `PortfolioPositionDTO` 的测试。
5. **文档**: Polish 时更新 `docs/productization-refactor-plan.md` 将估值指向 `017`。

## Complexity Tracking

无 constitution 违例需辩解。未引入额外框架。

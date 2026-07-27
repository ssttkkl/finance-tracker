# Tasks: 实时资产估值与持仓市值

**Input**: `/specs/017-asset-valuation-quote/`（Living Spec：组合 P0 + 原子 P1）  
**Tests**: 强制 TDD；假源 + 假 FX。

## Format: `- [X] T### [P?] [US#?] Description with file path`

---

## Phase 1: Setup

- [X] T001 Confirm `.specify/feature.json` → `specs/017-asset-valuation-quote` and branch `017-asset-valuation-quote`
- [X] T002 [P] Ensure test dirs exist for `tests/unit/domain/`, `tests/unit/application/`, `tests/unit/adapters/`, `tests/contract/`

---

## Phase 2: Foundational — 原子估值支撑（P1 能力，阻塞组合）

**Purpose**: `ValuationService` + providers 可注入，供 US1/US2 调用

- [X] T003 [P] Write failing domain tests in `tests/unit/domain/test_valuation_quote.py` (cash=1, freshness table, market_value, non-finite rejection)
- [X] T004 Implement `src/ft/domain/valuation.py` until T003 passes
- [X] T005 [P] Write failing `tests/unit/application/test_valuation_service.py` (quote/quote_many, partial/unsupported, batch order, invalid quantity)
- [X] T006 Implement `src/ft/application/valuation.py` `ValuationService` until T005 passes
- [X] T007 [P] Add QuoteProvider protocol in `src/ft/repositories/protocols.py` (or valuation-local) per `contracts/valuation-api.md`
- [X] T008 [P] Write failing `tests/unit/adapters/test_quote_symbol_map.py` (ledger→yfinance/crypto/pm)
- [X] T009 Implement symbol map + CompositeQuoteProvider (cash/security/crypto/pm, injectable IO) in `src/ft/adapters/market_data.py` until T008 passes; wire into ValuationService for integration tests
- [X] T010 Apply freshness and non-finite→partial in service/domain per research R4

**Checkpoint**: 原子估值假源绿（支撑，非产品终点）

---

## Phase 3: User Story 1 — 组合本币市值与状态 (Priority: P0) 🎯

**Goal**: `get_portfolio()` 本币模式消费统一估值  
**Independent Test**: 多币种假估值持仓均有 quote_status；失败项无虚构市价

### Tests

- [X] T011 [P] [US1] Write failing `tests/unit/application/test_portfolio_valuation.py` for native-currency portfolio (USD/HKD/CNY legs, cash=1, unsupported ticker, quote_status)
- [X] T012 [P] [US1] Update/fail `tests/test_application_investment.py` portfolio expectations for new DTO fields

### Implementation

- [X] T013 [US1] Extend `PortfolioPositionDTO` in `src/ft/domain/investment.py` with quote_status, quote_reason, quote_currency, display_* and fx_* fields (defaults null)
- [X] T014 [US1] Implement kind inference helper (research R5) used by portfolio path in `src/ft/application/investment.py` or `src/ft/domain/valuation.py`
- [X] T015 [US1] Refactor `PortfolioQueryService` to depend on `ValuationService`, fill native price/MV/status for non-zero positions in `src/ft/application/investment.py`
- [X] T016 [US1] Wire ValuationService in `src/ft/adapters/relational/runtime.py`; remove sole silent get_prices-only path
- [X] T017 [US1] Update `tests/fakes.py` and all PortfolioPositionDTO constructors

**Checkpoint**: 本币组合 P0 可演示

---

## Phase 4: User Story 2 — 指定展示货币折算 (Priority: P0)

**Goal**: `get_portfolio(display_currency=...)` 可审计 FX 折算  
**Independent Test**: Fake FX 折算精确；FX 失败无 1:1；非法 currency fail-closed

### Tests

- [X] T018 [P] [US2] Extend `tests/unit/application/test_portfolio_valuation.py` for display_currency success, same-currency rate=1, fx_unavailable, invalid display currency
- [X] T019 [P] [US2] Write failing FX provider unit tests with injectable fetcher in `tests/unit/adapters/test_fx_rate_provider.py` (or extend existing fx tests)

### Implementation

- [X] T020 [US2] Add injectable `FxRateProvider` / today-mid helper in `src/ft/adapters/fx_rates.py` (rate = display per 1 base)
- [X] T021 [US2] Implement display_currency path on `PortfolioQueryService.get_portfolio` in `src/ft/application/investment.py` per `contracts/portfolio-quote-fields.md`
- [X] T022 [US2] Align `FinanceQueryService` security valuation with ValuationService + optional display rules in `src/ft/application/queries.py` without claiming cost is mark-to-market
- [X] T023 [US2] Runtime wire FxRateProvider in `src/ft/adapters/relational/runtime.py`

**Checkpoint**: 本币 + 展示币双模式 P0 齐

---

## Phase 5: User Story 3 — 原子 API 加固与批量 (Priority: P1)

**Goal**: 对外原子能力完整可复用  
**Independent Test**: 四类资产 + 批量混合合同

### Tests

- [X] T024 [P] [US3] Extend `tests/unit/application/test_valuation_service.py` for per-kind success and 10-item mixed batch isolation

### Implementation

- [X] T025 [US3] Harden quote_many per-item exception isolation in composite provider / service
- [X] T026 [US3] Optional CLI `ft quote` and/or portfolio display flag in `src/ft/cli.py` + `tests/test_cli.py` if in scope

**Checkpoint**: 原子能力可交给其他系统

---

## Phase 6: Polish

- [X] T027 [P] Optional `tests/contract/test_valuation_wiring_dual_backend.py` Fake valuation+FX on sqlite/pg
- [X] T028 Update `docs/productization-refactor-plan.md` live valuation → `017` (cross-link old 011)
- [X] T029 Run `specs/017-asset-valuation-quote/quickstart.md` commands; note evidence
- [X] T030 Confirm no Alembic head change; mark tasks/spec ready for converge

---

## Dependencies

- Phase 2 → Phase 3 (US1) → Phase 4 (US2) → Phase 5 (US3) → Polish  
- US1/US2 为 P0 主交付；US3 加固原子面  
- T003–T010 可与文档并行但必须先于 T015  

## MVP

**T001–T017（Foundational + US1 本币组合）** 为最小用户可见切片；**T018–T023** 完成 P0 双模式。

## Parallel

- T003/T005/T008 测试并行  
- T011/T012 并行  
- T018/T019 并行  
- T027/T028 并行  

# Tasks: 实时资产估值接口

**Input**: Design documents from `/specs/017-asset-valuation-quote/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: 强制 TDD。可执行行为、财务语义、接口变更先写失败测试再最小实现。无 schema 迁移；双后端等价以假源输出一致 + 可选 wiring 冒烟证明。

**Organization**: 按用户故事分阶段；Setup/Foundational 无故事标签。

## Format: `- [ ] T### [P?] [US#?] Description with file path`

---

## Phase 1: Setup

**Purpose**: 确认 feature 指针与测试目录

- [ ] T001 Confirm `.specify/feature.json` points to `specs/017-asset-valuation-quote` and branch `017-asset-valuation-quote`
- [ ] T002 [P] Create test directories `tests/unit/domain/`, `tests/unit/application/`, `tests/unit/adapters/`, `tests/contract/` if missing placeholders for valuation tests

---

## Phase 2: Foundational (Blocking)

**Purpose**: 领域模型 + Application 骨架 + 假源可注入；所有用户故事依赖此阶段

**⚠️ CRITICAL**: 未完成前不得接真源或改组合查询

- [ ] T003 [P] Write failing domain tests in `tests/unit/domain/test_valuation_quote.py` covering cash unit price 1, freshness complete/stale/partial thresholds per `research.md` R2, market_value = price × quantity, reject non-finite prices for complete/stale
- [ ] T004 Implement domain types and pure helpers in `src/ft/domain/valuation.py` (`AssetKind`, `QuoteStatus`, `AssetRef`, `QuoteResult`, `QuoteBatchResult`, freshness, `compute_market_value`, validation helpers) until T003 passes
- [ ] T005 [P] Write failing application tests in `tests/unit/application/test_valuation_service.py` for `quote`/`quote_many` with Fake providers: success, unsupported, provider_error→partial, batch order, empty batch, invalid quantity whole-batch fail-closed
- [ ] T006 Implement `ValuationService` in `src/ft/application/valuation.py` (prevalidate batch, route by kind, map provider outcomes to statuses, attach market_value) until T005 passes
- [ ] T007 [P] Add `QuoteProvider` / tick protocol surface in `src/ft/repositories/protocols.py` (or valuation-local Protocol module imported by application) matching `contracts/valuation-api.md`
- [ ] T008 Wire a `CompositeQuoteProvider` stub (cash local + injectable security/crypto/pm) in `src/ft/adapters/market_data.py` or `src/ft/adapters/quotes/composite.py` used by tests; no live network required

**Checkpoint**: Fake 端到端 `ValuationService` 绿

---

## Phase 3: User Story 1 — 按标识查询当前单价与状态 (Priority: P1) 🎯 MVP

**Goal**: 四类资产单笔估值 + 状态语义  
**Independent Test**: mock/fake 源下 security/crypto/pm/cash 各至少一条 complete（或 cash complete）；unsupported/partial 无虚构价

### Tests

- [ ] T009 [P] [US1] Extend `tests/unit/application/test_valuation_service.py` for per-kind success paths and identity_kind_mismatch → unsupported
- [ ] T010 [P] [US1] Write failing symbol-map tests in `tests/unit/adapters/test_quote_symbol_map.py` for ledger→provider symbols (`aapl.us`→`AAPL`, HK padding, `.sh`→`.SS`, `.sz`→`.SZ`, crypto map, pm parse)

### Implementation

- [ ] T011 [US1] Implement cash provider path (unit price `1`, currency = identity ISO upper, complete) in adapter/service routing
- [ ] T012 [US1] Implement security symbol mapping + SecurityQuoteProvider adapter (injectable downloader; default yfinance) in `src/ft/adapters/market_data.py` (or `src/ft/adapters/quotes/security.py`)
- [ ] T013 [US1] Implement crypto provider using `CRYPTO_IDS` from `src/ft/schema.py` with injectable HTTP; unmapped → unsupported
- [ ] T014 [US1] Implement prediction_market provider for `pm:{slug}:{yes|no}` with injectable HTTP; missing market → unsupported/partial per research
- [ ] T015 [US1] Apply freshness windows from research R2 after provider tick; beyond maximum → partial and clear price
- [ ] T016 [US1] Ensure non-finite provider prices map to partial + `non_finite_price` in `src/ft/application/valuation.py`

**Checkpoint**: US1 假源矩阵绿；真源可手动可选

---

## Phase 4: User Story 2 — 批量估值与部分成功 (Priority: P1)

**Goal**: `quote_many` 逐项结果、部分成功  
**Independent Test**: 10 项混合夹具成功/失败隔离 100%

### Tests

- [ ] T017 [P] [US2] Add batch isolation tests in `tests/unit/application/test_valuation_service.py` (mixed complete/unsupported/partial; all-fail still returns list; empty list)

### Implementation

- [ ] T018 [US2] Harden `quote_many` ordering, summary counts if any, and whole-batch prevalidation only for input errors in `src/ft/application/valuation.py`
- [ ] T019 [US2] Confirm composite provider does not short-circuit entire batch on single provider exception (catch per item) in adapter layer

**Checkpoint**: US2 独立验收绿

---

## Phase 5: User Story 3 — 可选数量得到市值 (Priority: P2)

**Goal**: quantity → market_value  
**Independent Test**: 有价时市值精确相等；无价时无市值；非法 quantity fail-closed

### Tests

- [ ] T020 [P] [US3] Add quantity/market_value cases in `tests/unit/domain/test_valuation_quote.py` and `tests/unit/application/test_valuation_service.py`

### Implementation

- [ ] T021 [US3] Ensure `AssetRef.quantity` validation and `QuoteResult.market_value` population only when price present in `src/ft/domain/valuation.py` and `src/ft/application/valuation.py`

**Checkpoint**: US3 绿

---

## Phase 6: User Story 4 — 既有组合查询消费统一估值 (Priority: P2)

**Goal**: Portfolio / balances 使用 ValuationService，暴露 quote_status  
**Independent Test**: Fake 估值下持仓 status 可区分；unsupported 无虚构价

### Tests

- [ ] T022 [P] [US4] Update/fail tests in `tests/test_application_investment.py` for `PortfolioPositionDTO.quote_status` and Fake valuation injection
- [ ] T023 [P] [US4] Update `tests/test_application_queries.py` and `tests/fakes.py` to Fake ValuationService / compatible port instead of bare `get_prices` only

### Implementation

- [ ] T024 [US4] Extend `PortfolioPositionDTO` in `src/ft/domain/investment.py` with `quote_status` and optional `quote_reason`
- [ ] T025 [US4] Refactor `PortfolioQueryService` in `src/ft/application/investment.py` to call `ValuationService.quote_many` (infer kind: cash vs security vs crypto vs pm heuristics documented in code comments + research)
- [ ] T026 [US4] Refactor `FinanceQueryService._account_balances` in `src/ft/application/queries.py` to use valuation results; keep cost fallback only when no price without claiming it is mark-to-market
- [ ] T027 [US4] Wire `ValuationService` in `src/ft/adapters/relational/runtime.py`; retire sole silent `MarketDataProvider.get_prices` path (thin delegate allowed)
- [ ] T028 [US4] Fix all PortfolioPositionDTO constructors across tests to include new fields

**Checkpoint**: 组合路径只走统一估值

---

## Phase 7: Polish & Cross-Cutting

- [ ] T029 [P] Optional CLI `ft quote` in `src/ft/cli.py` per `contracts/valuation-api.md` + smoke test in `tests/test_cli.py` if added
- [ ] T030 [P] Optional dual-backend wiring smoke `tests/contract/test_valuation_wiring_dual_backend.py` with Fake provider
- [ ] T031 Update `docs/productization-refactor-plan.md` to point Phase 1 live valuation at `017-asset-valuation-quote` (cross-link old 011 name)
- [ ] T032 Run quickstart commands from `specs/017-asset-valuation-quote/quickstart.md` and record evidence in tasks completion notes
- [ ] T033 Mark feature checklist/spec status ready for converge; ensure no Alembic head change

---

## Dependencies & Execution Order

### Phase dependencies

- Phase 1 → Phase 2 → US1 (Phase 3) → US2 (Phase 4) → US3 (Phase 5) → US4 (Phase 6) → Polish
- US2 依赖 US1 的 provider 路由但可与 US1 测试并行补强
- US3 依赖 domain/service 基础（Phase 2/3）
- US4 依赖 ValuationService 稳定（Phase 2–5 建议完成后再改组合）

### Story completion order

1. **US1 MVP** — 单笔四类资产  
2. **US2** — 批量部分成功  
3. **US3** — 市值  
4. **US4** — 组合接入  

### Parallel opportunities

- T003/T005/T007 在约定接口后可并行起草测试
- T010 与 T009 并行
- T022/T023 并行
- T029/T030/T031 并行

### MVP scope

**T001–T016（Setup + Foundational + US1）** 即可演示正式估值合同；随后 US2–US4 关 Phase 1 组合消费门槛。

---

## Implementation Strategy

1. 红绿 domain → service（假源）  
2. 符号映射与四分 provider  
3. 批量与市值加固  
4. 切换 portfolio/queries wiring  
5. 文档与可选 CLI  

**Format validation**: 所有任务均为 `- [ ] T### …` 且含路径；故事阶段含 `[USn]`。

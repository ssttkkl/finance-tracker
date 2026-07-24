# Feature Specification: Investment Base-Currency Cost Semantics

**Feature Branch**: `012-investment-base-currency-cost`

**Created**: 2026-07-24

**Status**: Complete

**Input**: User: main-branch investment model — account configures base currencies (USD/HKD/CNY/JPY, USDT/USDC, …); those base tickers do not carry cost basis. Current branch still treats positions uniformly with total_cost/cost_currency and only hardcodes fiat for multi-ccy labels.

**Context**: Flow-Forward after **009** (event model), **005** (`metadata.base_currencies`), **011** (multi-ccy cash cost_currency hotfix via `KNOWN_FIAT_CASH_TICKERS`). Restores **account-configured base set** as the sole “no cost basis” rule for investment projection. Does not reopen broker parsers except where wiring must pass base set into projection.

**Extends**: `005-multi-currency-accounts` (base_currencies storage), `009-investment-account-import` (projection), supersedes ad-hoc fiat-only cost labeling in 011 D13 as the long-term rule (fiat set may remain fallback when base list empty).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Base tickers have no cost basis (Priority: P1)

作为多币种证券/加密账户用户，我为账户配置本位币（如 USD+HKD 或 USDT），当我存取本位现金、或本位币之间换汇时，系统只更新余额数量，不维护这些本位仓的成本基础；股票与非本位资产仍累计/释放成本。

**Why this priority**: Matches main product semantics; removes meaningless cost on cash and fixes multi-base FX without hardcoding only ISO fiat.

**Independent Test**: Account base_currencies=`["USD","HKD"]`; deposit HKD; FX swap HKD→USD; buy equity with USD — no cost currency conflict; equity has total_cost in USD; hkd/usd positions may keep total_cost==shares face or zero-cost policy as specified but must not use event currency to conflict.

**Acceptance Scenarios**:

1. **Given** security account with `base_currencies` including USD and HKD, **When** deposit HKD then cash↔cash swap to USD, **Then** both legs update shares only under base rules; MUST NOT raise cost currency conflict.
2. **Given** same account, **When** USD buy of equity ticker, **Then** equity position total_cost increases in USD (event currency); USD base pocket balance decreases without requiring cost-basis semantics for the cash leg.
3. **Given** account base includes USDT, **When** deposit USDT and swap USDT→BTC, **Then** USDT is treated as base (no cost basis); BTC accumulates cost in event currency / policy.
4. **Given** empty base_currencies metadata, **When** projecting, **Then** fallback documented set applies (event currency alone and/or built-in fiat+stable defaults) so imports do not crash; dual-backend identical.

### User Story 2 - Projection API carries base set (Priority: P1)

作为系统，CLI 手动投资命令与 statement 导入 MUST 使用同一套「账户 base_currencies → 投影」规则。

**Independent Test**: Manual `ft stock buy` and `ft import` both pass account bases into apply_*; unit tests cover both paths.

## Edge Cases

- Base ticker vs equity with same symbol: N/A if equity always suffixed (`.us`/`.hk`); bare `usd` is cash.
- Removing a currency from base later: existing positions keep stored fields; new applies use new set (document; no auto rewrite).
- Commission in base cash asset: still reduces base balance; not “cost on cash”.
- Short / soft-start oversell: unchanged from 009.

## Requirements *(mandatory)*

- **FR-001**: Investment projection MUST accept an explicit **base ticker set** (lowercase tickers equivalent to configured ISO/stable codes, e.g. `usd`,`hkd`,`usdt`).
- **FR-002**: For a position whose ticker ∈ base set, projection MUST **not** maintain meaningful cost basis: on quantity changes, set `total_cost` to track **face quantity** in the base unit (`total_cost == shares` after ops, `cost_currency == ticker.upper()`), OR equivalently always rewrite face — MUST NOT propagate released equity cost into base legs as if base were an asset under cost basis. Preferred: **base legs always `total_cost = shares`, `cost_currency = ticker.upper()`** after each apply touching that leg.
- **FR-003**: For ticker ∉ base set, keep 009 cost basis: release proportional total_cost on reduce; on increase from swap, add released (+ commission rules) with `cost_currency = event.currency` (unless already set and consistent).
- **FR-004**: Cash↔cash swap where **both** legs ∈ base: only move face amounts; no cost-basis transfer between them.
- **FR-005**: `KNOWN_FIAT_CASH_TICKERS` alone MUST NOT be the sole product rule; account `metadata.base_currencies` is authoritative when non-empty. Empty base → fallback: union of built-in fiat+stable defaults (usd,hkd,cny,eur,gbp,jpy,usdt,usdc,…) so multi-ccy still works.
- **FR-006**: `RelationalInvestmentCommandRepository.execute` and `InvestmentImportService` MUST load account base_currencies and pass into `apply_investment_command` / `apply_investment_event`.
- **FR-007**: Dual-backend equivalence for same events + same base set.
- **FR-008**: Portfolio query already treats configured currencies as cash (`is_cash`); MUST remain consistent with base set (no regression).

## Success Criteria

- **SC-001**: Multi-base HKD+USD deposit+FX+equity path: 0 cost currency conflicts.
- **SC-002**: USDT-in-base crypto buy: USDT balance face-only; asset has cost.
- **SC-003**: Unit tests for base vs non-base legs; import path integration smoke.
- **SC-004**: Existing usmart/ibkr/dfzq focused suites still pass with bases seeded on test accounts.

## Out of Scope

- Lot/FIFO realized P&amp;L
- Auto-editing user base_currencies from imports
- Valuation quotes
- Changing cash account (non-investment) model

## Assumptions

- Equity tickers use market suffixes (`.us`/`.hk`/…) so they never equal `usd`.
- base_currencies stored uppercase ISO/stable codes in account metadata; projection normalizes to lowercase tickers.

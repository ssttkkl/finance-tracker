# Tasks: Multi-Currency Accounts

**Input**: Design documents from `/specs/005-multi-currency-accounts/`  
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: MANDATORY failing tests before implementation. Persistence/migration changes require SQLite + real PostgreSQL matrix; neither backend may be mocks-only.

**Organization**: Phases by dependency, then user stories (US1–US5).

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no incomplete dependency)
- **[Story]**: US1…US5 from spec.md
- Paths are repo-relative

---

## Phase 1: Setup

**Purpose**: Confirm artifacts and inventory call sites

- [x] T001 Confirm feature artifacts under `specs/005-multi-currency-accounts/` and branch `multi-currency-accounts`
- [x] T002 [P] Inventory account.currency / find(name, currency) call sites in `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, `src/ft/application/cashflow.py`, `src/ft/application/statement_import.py`, `src/ft/application/queries.py`, `src/ft/application/investment.py`, `src/ft/adapters/relational/{models,repositories,imports,queries,wealth_facts,investments,uow}.py`, `src/ft/repositories/protocols.py`, `src/ft/repositories/wealth.py`, `src/ft/acct.py`, `src/ft/cli.py`, `src/ft/report.py`, and tests that construct `AccountDTO(..., currency)`

---

## Phase 2: Foundational (blocking)

**Purpose**: Red tests + domain/schema foundation before story work

**⚠️ CRITICAL**: No user-story implementation until this phase is complete

- [X] T003 [P] Write failing tests for name-unique multi-currency accounts in `tests/test_multi_currency_accounts.py` (create without permanent currency; CNY+JPY add/checkin same account; list multi-pocket; duplicate name fails; missing operation currency fails)
- [X] T004 [P] Write failing tests for import by account name + row currency in `tests/test_statement_import_mapping.py` (or new focused cases): single `工行` account accepts CNY+JPY rows; no currency-match rejection; missing account rolls back; digest idempotent
- [X] T005 [P] Write failing tests for one-time merge migration in `tests/test_multi_currency_migration.py` (same-name same-type merge; type conflict fails; cash valuation identities, coverage owner FKs, and SQLite account index rewritten; dual-backend hooks)
- [X] T006 [P] Write failing wealth multi-currency checkin test in `tests/test_relational_wealth_facts.py` (or extend): two currencies on one account do not clobber identities
- [X] T007 Domain + protocol: remove currency identity from `AccountDTO` / account APIs in `src/ft/domain/accounts.py`, `src/ft/application/accounts.py`, and `AccountRepository` in `src/ft/repositories/protocols.py`; create optional seed currency only; lifecycle by name; `find(name)` only (no `find(name, currency)` ledger-book signature)
- [X] T008 Repository: name-only lookup/add/rename/delete/has_facts in `src/ft/adapters/relational/repositories.py` matching protocols; stop ambiguous currency disambiguation path
- [X] T009 Schema + Alembic one-shot merge in `migrations/versions/20260720_04_multi_currency_accounts.py` and `src/ft/adapters/relational/models.py`: merge survivors, rehang FKs (including coverage owners), rewrite cash valuation identities, drop account.currency + old unique, add `uq_accounts_workspace_name`, relax cash identity check; SQLite rebuilds every directly account-referencing table atomically, restores `ix_accounts_workspace`, and runs `foreign_key_check`; update `tests/test_alembic_migration.py` revision list expectation
- [X] T010 Wealth: account+currency cash identity (`identity="{account_id}:{currency}"`) + name-only `record_lifecycle` / name+op-currency `record_cash_checkin` in `src/ft/adapters/relational/wealth_facts.py`, `src/ft/repositories/wealth.py` (`AccountFact` no longer carries account identity currency)

**Checkpoint**: Red tests exist; domain/schema foundation ready

---

## Phase 3: User Story 1 — 一账户多币种余额 (P1) 🎯 MVP

**Goal**: One account holds multiple currency pockets; list/report show multi-pocket balances

**Independent Test**: Create `工行`, write CNY+JPY, list shows two pockets; duplicate name fails; SQLite+PG

### Tests (must fail first if not already covered)

- [X] T011 [P] [US1] Ensure T003 list/create multi-pocket assertions stay red until impl, then green on SQLite

### Implementation

- [X] T012 [US1] Cash write path uses operation currency as pocket key in `src/ft/application/cashflow.py` (`add_manual_transaction`, `checkin_balance`) — never `account.currency`
- [X] T013 [US1] Finance list/report emit one balance row per pocket in `src/ft/application/queries.py`, `src/ft/adapters/relational/queries.py` (AccountDTO without currency; portfolio base currencies from snapshot/metadata, not account.currency), `src/ft/report.py`, `src/ft/acct.py`
- [X] T014 [US1] CLI account lifecycle name-scoped in `src/ft/cli.py` / `src/ft/acct.py` (add optional seed currency; rename/delete/activate without currency disambiguation)
- [X] T015 [US1] Green US1 tests on SQLite; run PostgreSQL matrix when `FT_TEST_POSTGRES_URL` set

**Checkpoint**: US1 independently demoable

---

## Phase 4: User Story 2 — 手工记账/校准显式币种 (P1)

**Goal**: add/checkin require operation currency; illegal currency fails closed

**Independent Test**: Missing/invalid currency rejected; multi-currency non-overlap

- [X] T016 [P] [US2] Tests for missing/invalid currency on add/checkin in `tests/test_multi_currency_accounts.py` / CLI tests
- [X] T017 [US2] Enforce required normalized operation currency in `src/ft/application/cashflow.py` and CLI flags in `src/ft/cli.py`
- [X] T018 [US2] Green US2 tests SQLite + PG matrix if available

**Checkpoint**: No implicit home currency on cash writes

---

## Phase 5: User Story 3 — 导入按账户名 (P1)

**Goal**: Mapping → name; row currency → pocket; drop account.currency match

**Independent Test**: One `工行` account imports CNY+JPY; no booklet account

- [X] T019 [US3] Import resolve cache by name only; remove currency-match rejection in `src/ft/application/statement_import.py`; keep convert/import account-name routing parity (existing `tests/test_statement_import_mapping.py` convert dist check)
- [X] T020 [US3] `formal_fact_targets` / batch targets use fact currency (not AccountModel.currency) in `src/ft/adapters/relational/imports.py`; align `ImportRepository` notes in `src/ft/repositories/protocols.py` (name-only target resolve; targets still `(account_name, fact_currency)`)
- [X] T021 [US3] Green import tests (`tests/test_statement_import_mapping.py`, dual-backend if present)

**Checkpoint**: Import multi-currency on single account works

---

## Phase 6: User Story 4 — 跨账户跨币种转账 (P2)

**Goal**: Transfer by account name + op currencies; cross-currency requires to-amount

**Independent Test**: A CNY → B JPY with to-amount; missing to-amount fails

- [X] T022 [P] [US4] Tests for transfer name+pocket currencies in `tests/test_multi_currency_accounts.py` / relational contract
- [X] T023 [US4] Transfer resolves accounts by name; pocket selection from from/to currency; snapshot updates use op currencies in `src/ft/application/cashflow.py`
- [X] T024 [US4] Residual investment transfer/portfolio: no account.currency identity; quote from snapshot/metadata/events in `src/ft/application/cashflow.py`, `src/ft/adapters/relational/queries.py`, `src/ft/adapters/relational/investments.py`
- [X] T025 [US4] Green transfer tests SQLite + PG matrix if available

**Checkpoint**: Cross-currency transfer path stable without booklet accounts

---

## Phase 7: User Story 5 — 一次性合并迁移 (P1)

**Goal**: One-shot merge; no runtime compat layer

**Independent Test**: Fixture merge success + type conflict fail on SQLite and PostgreSQL

- [X] T026 [US5] Complete migration data path per `contracts/migration-merge-accounts.md` (survivor rule, FK rehang including coverage owner, valuation rewrite, snapshot rebuild, and SQLite account index preservation) in Alembic revision
- [X] T027 [US5] Green `tests/test_multi_currency_migration.py` on SQLite; PostgreSQL when env set
- [X] T028 [US5] Confirm no remaining `find(name, currency)` ledger-book API or dual-read paths via grep + tests

**Checkpoint**: Migration fail-closed and complete

---

## Phase 8: Polish

- [X] T029 [P] Update `README.md` examples for name-unique accounts + required op currency + one-shot migration
- [X] T030 [P] Align remaining tests still assuming name+currency accounts (`tests/test_relational_contract.py`, `tests/test_cli.py`, `tests/test_open_currency.py`, etc.)
- [X] T031 Run `uv run pytest` (and postgres matrix if env set); fix failures; `uv build` if project provides it
- [X] T032 `$speckit-converge` against spec/plan/tasks after implementation

---

## Dependencies

- Phase 2 before any US implementation
- US1 foundation before US2 polish (cash write path shared)
- US1/US2 before US3 (import depends on name-only find)
- US4 after name-only accounts + cash pockets
- US5 migration tests can start red in Phase 2; green after T009/T026
- Polish after US1–US5 green

## Parallel examples

- T003/T004/T005/T006 red tests in parallel
- T007 domain vs T003 tests after inventory
- T019 import vs T022 transfer tests after foundation
- T029 docs parallel with T030 test cleanup

## Implementation strategy

MVP = Phase 2 + US1 (name-unique multi-pocket + list)  
Then US2 currency enforcement → US3 import → US5 migration completeness → US4 transfer residual → polish.

## Dual-backend verification

Every persistence story must leave SQLite evidence; PostgreSQL evidence when `FT_TEST_POSTGRES_URL` available. Missing PG evidence must be reported, not claimed.

## Notes for implementer

- Main session does not write product code; `speckit_implementer` runs `$speckit-implement` then `$speckit-converge`
- Do not expand into formal FX product
- Do not keep name+currency compatibility APIs

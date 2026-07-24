# Tasks: Fact Field Unification

**Input**: Design documents from `/specs/014-fact-field-unify/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Mandatory failing-first for schema, migration, public contracts, projection parity; SQLite + real PostgreSQL matrix for persistence.

**Organization**: By user story (US1 shared vocabulary/schema, US2 one-shot migration parity, US3 public contracts).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: parallelizable
- **[USn]**: user story label
- Paths are repository-root relative

---

## Phase 1: Setup

- [x] T001 Confirm active feature pointer `.specify/feature.json` → `specs/014-fact-field-unify` and branch `014-fact-field-unify`
- [x] T002 [P] Inventory call sites for cash `description`, investment `kind`, and investment `payload` core keys under `src/ft/` and `tests/` (grep list used for mechanical renames)

---

## Phase 2: Foundational (blocking)

- [x] T003 Add failing schema/contract tests for end-state columns and public field names in `tests/test_fact_field_unify.py` (or split modules): cash has `note` not `description`; investment has `action` + legs not `kind`; public keys use `note` and `occurred_at`
- [x] T004 Add failing migration tests: promote+strip cores; conflict fail-closed; empty residual payload `{}` — cover SQLite path in same module; register PostgreSQL matrix hook per project convention (`FT_TEST_POSTGRES_URL` / existing dual-db fixtures)
- [x] T005 Add failing projection-parity fixture test: pre-shape investment events → migrate → project equals baseline snapshot in `tests/test_fact_field_unify_projection.py`
- [x] T006 Implement Alembic revision `migrations/versions/20260724_07_fact_field_unify.py` (or next free id): cash rename `description`→`note`; investment rename `kind`→`action`; add leg/note columns; backfill from payload; strip CORE_KEYS; fail on action/currency conflict; SQLite rebuild-in-txn if required (see plan + data-model)
- [x] T007 Update ORM `CashTransactionModel` / `InvestmentEventModel` in `src/ft/adapters/relational/models.py` to match end-state data-model
- [x] T008 Wire migration into project alembic chain; ensure `tests/test_alembic_migration.py` (or equivalent) expects new head

**Checkpoint**: Foundation green on SQLite; Postgres matrix for T004/T006 scheduled before story complete claims

---

## Phase 3: User Story 1 — Shared vocabulary & formal columns (P1)

**Goal**: Shared catalog names in storage + formal write/read; investment cores as columns.

**Independent test**: Write cash with `note` and investment with legs/action; inspect DB columns and formal rows.

- [x] T009 [US1] Update cash repository write/read in `src/ft/adapters/relational/repositories.py` for `note` and formal `occurred_at` public mapping (no `description`)
- [x] T010 [US1] Update investment repository write/read in `src/ft/adapters/relational/repositories.py` to persist/read columns (`action`, legs, `note`, …); residual payload non-core only; stop `kind=payload.action` pattern
- [x] T011 [P] [US1] Update `src/ft/schema.py` `CASH_CSV_FIELDS` and `src/ft/domain/imports.py` `CASHFLOW_EXPORT_FIELDS` to catalog names (`note`, `occurred_at`)
- [x] T012 [P] [US1] Update import adapter paths in `src/ft/adapters/relational/imports.py` for new column names
- [x] T013 [US1] Update `src/ft/adapters/relational/wealth_facts.py` to read investment formal columns (not payload cores)
- [x] T014 [US1] Mechanical renames across remaining `src/ft/` consumers of `description` / investment `kind` as action (CLI/application if any)
- [x] T015 [US1] Make T003 tests pass; confirm no production reader of cash `description` or investment `kind`

---

## Phase 4: User Story 2 — One-shot migration without re-accounting (P1)

**Goal**: Consistent data migrates with identical projections; conflicts fail closed; no shims.

**Independent test**: Golden fixture migrate + project; conflict fixture aborts; dual backend.

- [x] T016 [US2] Flesh golden pre-migration seed helpers in `tests/test_fact_field_unify_projection.py` covering swap/deposit/withdraw/dividend and fee/ipo if fixtures exist
- [x] T017 [US2] Assert raw_record linkage counts and soft-delete cash behavior unchanged post-migration in tests
- [x] T018 [US2] Run and pass SQLite migration+parity tests; run real PostgreSQL matrix for same tests
- [x] T019 [US2] Verify migration failure message includes fact id on conflict (test assertion)
- [x] T020 [US2] Grep gate: no shipped dual-read/dual-write/compat alias code for retired names under `src/ft/`

---

## Phase 5: User Story 3 — Public list/export vocabulary (P1)

**Goal**: Public CSV/list keys match contracts; callers/tests updated.

**Independent test**: Export/list cash and investment; headers/keys match contracts.

- [x] T021 [US3] Update public cash list `_public_row` / queries in `src/ft/adapters/relational/repositories.py` and `src/ft/adapters/relational/queries.py` to catalog keys
- [x] T022 [US3] Update investment list assembly to column-based formal row per `contracts/investment-formal-row.md`
- [x] T023 [P] [US3] Update all tests/fixtures expecting `description` or public `date` or investment payload-spread cores under `tests/`
- [x] T024 [US3] Align CLI help/docs snippets if they document old field names under `docs/` or CLI module strings
- [x] T025 [US3] Make contract-oriented tests in T003 for public shapes pass

---

## Phase 6: Polish & cross-cutting

- [x] T026 [P] Update `specs/014-fact-field-unify/quickstart.md` with exact pytest node ids that prove SC-001–SC-008
- [x] T027 Full test suite `uv run pytest` (or project standard) + note any skipped Postgres if unavailable with risk
- [x] T028 Static grep completion evidence for SC-007 (no `description` column usage; no `InvestmentEventModel.kind`; no core-from-payload readers)
- [x] T029 Mark tasks complete; prepare for `$speckit-analyze` / converge

---

## Dependencies

- Phase 1 → Phase 2 → US1 (Phase 3) → US2 (Phase 4) and US3 (Phase 5) can partially overlap after T010, but US2 needs T006–T007 solid
- US3 depends on US1 public mapping (T009–T011)
- Polish after US1–US3 green

## Parallel examples

- T002 inventory || doc reads
- T011 || T012 after T007
- T023 fixture updates || T024 docs after contracts stable

## MVP

T001–T015 (foundation + US1) delivers usable unified schema/API; US2/US3 required for full feature claim per spec.

## Story test criteria

| Story | Independent test |
|-------|------------------|
| US1 | Write/read formal columns & shared names |
| US2 | Migrate golden → projection parity; conflict fails; PG+SQLite |
| US3 | Public export keys = catalog only |

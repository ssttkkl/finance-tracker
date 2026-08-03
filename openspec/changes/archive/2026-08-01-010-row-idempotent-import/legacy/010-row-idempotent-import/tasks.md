# Tasks: Row-Level Idempotent Import

**Input**: Design documents from `/specs/010-row-idempotent-import/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: MANDATORY fail-first for executable/financial/import behavior. Dual-backend SQLite + real PostgreSQL (`FT_TEST_POSTGRES_URL`).

## Format: `- [ ] T### [P?] [US#?] Description with file path`

---

## Phase 1: Setup

- [X] T001 [P] Confirm Docker PG for dual-backend: document URL in specs/010-row-idempotent-import/quickstart.md (finance-tracker-postgres-test :55432)
- [X] T002 [P] Inventory cash/investment short-circuit call sites in src/ft/application/statement_import.py and src/ft/application/investment_import.py

---

## Phase 2: Foundational

**Purpose**: Shared helpers and failing contract skeleton

- [X] T003 Add helper or reuse formal_fact_targets path for investment skip classification in src/ft/adapters/relational/imports.py (document if already sufficient)
- [X] T004 [P] Create tests/contract/test_row_idempotent_import.py skeleton with sqlite+postgresql params (fail until services fixed)
- [X] T005 [P] Create overlapping fixture builders or mini fixtures under tests/fixtures/import_idempotency/ (shared identities + novel rows for cash and/or investment)

**Checkpoint**: Fail-first tests collected and fail for digest short-circuit / investment double-apply

---

## Phase 3: User Story 1 - Same file re-import no double facts (P1) 🎯 MVP

**Goal**: Re-import identical file → new formal count 0; ledger unchanged

**Independent Test**: Import fixture twice (cash + investment); assert counts and balances/positions

### Tests

- [X] T006 [P] [US1] Failing test: investment same-file re-import count=0 without digest short-circuit assumption in tests/integration/test_investment_reimport_idempotent.py (or extend test_ibkr_import.py)
- [X] T007 [P] [US1] Failing test: cash same-file re-import count=0 after removing completed-batch early return in tests/ (appropriate cash import integration)

### Implementation

- [X] T008 [US1] Remove completed-batch early return in src/ft/application/statement_import.py (still start_batch + formal_fact_targets skip)
- [X] T009 [US1] Remove _find_existing_batch early return in src/ft/application/investment_import.py
- [X] T010 [US1] After add_raw_records in investment_import.py, skip raw_ids present in formal_fact_targets; only apply_investment_event + investments.add for novel; return novel count
- [X] T011 [US1] Make T006–T007 green; keep unique constraints as backstop

**Checkpoint**: US1 green on SQLite

---

## Phase 4: User Story 2 - Overlapping files incremental (P1)

**Goal**: A then B with shared identities → only novel formalized

**Independent Test**: Two fixtures share subset of source_identity; final fact count = |union|

### Tests

- [X] T012 [P] [US2] Failing test: investment overlap A→B novel-only count in tests/contract/test_row_idempotent_import.py
- [X] T013 [P] [US2] Failing test: cash overlap A→B if cash fixtures available; else document cash covered by identity skip + T007 path
- [X] T014 [P] [US2] Failing test: reverse order B→A same final identity set

### Implementation

- [X] T015 [US2] Ensure investment path handles different digests with shared identities without double events (T010)
- [X] T016 [US2] Ensure cash path does not reintroduce digest short-circuit (T008)
- [X] T017 [US2] Green T012–T014 on SQLite

**Checkpoint**: Overlap incremental green SQLite

---

## Phase 5: User Story 3 - Job metadata not ledger truth (P2)

**Goal**: Batch/digest are audit only; multiple jobs / reused batch OK; facts identity-unique

### Tests

- [X] T018 [P] [US3] Assert after two imports formal fact count is identity-based; batch status completed does not imply skip without row work (contract assertions)

### Implementation

- [X] T019 [US3] Document OperationResult messages: success with new_rows=0 vs error; optional details keys in application services
- [X] T020 [US3] Verify start_batch digest uniqueness still allows re-entry (research D3); no schema change unless forced

**Checkpoint**: US3 assertions green

---

## Phase 6: Dual-backend & polish

- [X] T021 Run dual-backend matrix with FT_TEST_POSTGRES_URL Docker :55432 for test_row_idempotent_import + investment reimport
- [X] T022 [P] Update specs/010-row-idempotent-import/quickstart.md if CLI messages changed
- [X] T023 [P] Cross-check 009/007 docs: note superseded digest-primary semantics (brief note in 010 research or 009 living cross-link only if needed)
- [X] T024 git status hygiene; no secrets; mark all tasks [X]

---

## Dependencies

- T001–T005 before US1 implementation
- US1 (T008–T011) before US2 reliance on skip path
- US2 before dual-backend sign-off T021
- US3 can parallelize with US2 after T010

## Parallel examples

```bash
# After foundational:
T006, T007 in parallel
# After T010:
T012, T013, T014 in parallel
```

## MVP

T001–T011 (same-file re-import safe on cash+investment without digest short-circuit).

## Notes

- Do not change parser fee maps (009) or cash relations rules except seed only novel facts
- WAIVE only with explicit note if cash overlap fixtures unavailable; investment overlap required
- T003: existing `formal_fact_targets` in `src/ft/adapters/relational/imports.py` already covers cash + investment raw_ids — reused as-is for investment skip (no new helper).

# Implementation Plan: Row-Level Idempotent Import

**Branch**: `010-row-idempotent-import` | **Date**: 2026-07-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/010-row-idempotent-import/spec.md`

## Summary

Remove **file-digest primary short-circuit** as the reason to skip applying statement rows. Cash and investment imports MUST decide formalization solely by **business row identity** (`source_type` + `source_identity`), so overlapping exports apply only novel rows. Keep `import_batches` / `raw_files` as job metadata (digest may still identify a job, but completed digest MUST NOT mean “do no row work”).

**Technical approach**:
1. **Cash** (`StatementImportService`): remove early return when batch status is `completed`; keep existing `formal_fact_targets` skip for identities that already have cash facts.
2. **Investment** (`InvestmentImportService`): remove `_find_existing_batch` early return; after `add_raw_records`, use `formal_fact_targets` to **skip** raw ids that already have formal facts; only map+apply+add for novel ids.
3. **Tests**: same-file re-import count=0; overlapping A then B only novel identities; dual-backend SQLite + Docker PG (`FT_TEST_POSTGRES_URL` :55432).

## Technical Context

**Language/Version**: Python 3.11+

**Primary Dependencies**: SQLAlchemy 2.x, existing `ft` hexagonal stack, pytest

**Storage**: PostgreSQL + SQLite via `FT_DATABASE_URL` / `FT_TEST_POSTGRES_URL` (no schema migration expected)

**Testing**: pytest unit/integration/contract dual-backend

**Target Platform**: CLI + library (same as 007/009)

**Project Type**: CLI/library dual-backend finance app

**Performance Goals**: Overlap import of ≤5k rows: skip path dominated by identity lookup; no full re-apply of known events

**Constraints**: No partial formal facts; Decimal-exact amounts; workspace isolation; Constitution IV parity

**Scale/Scope**: Both cash and investment import entry points; DFZQ/IBKR/Schwab + cash sources already wired

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### I. 财务正确性与可审计性 ✅
- Idempotency by business identity; no double formal facts
- Provenance retained via raw_record_id
- Fail closed on identity bound to wrong account (existing cash)

### II. Spec Kit 规格驱动 ✅
- Driven by `specs/010-row-idempotent-import/spec.md`

### III. 测试先行 ✅
- Fail-first tests: same-file, overlap A→B, reverse B→A, dual-backend

### IV. 双后端 ✅
- Same skip/new counts and ledger outcomes on SQLite and real PostgreSQL
- No auto-fallback / dual-write

### V. 边界与最小复杂度 ✅
- Application-layer orchestration change; reuse `add_raw_records` + `formal_fact_targets`
- No new framework; batch-per-digest uniqueness retained as job key only

**Status**: PASS — no unjustified constitution violations

## Project Structure

### Documentation (this feature)

```text
specs/010-row-idempotent-import/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── import-idempotency.md
└── tasks.md
```

### Source code (touch list)

```text
src/ft/application/statement_import.py   # remove completed-batch short-circuit
src/ft/application/investment_import.py  # remove digest short-circuit; skip known events
src/ft/adapters/relational/imports.py    # reuse formal_fact_targets (no schema change expected)
tests/…                                  # cash + investment overlap matrices
```

## Implementation Phases (design)

### Phase 0 Research
See `research.md`.

### Phase 1 Design
See `data-model.md`, `contracts/import-idempotency.md`, `quickstart.md`.

### Phase 2 Tasks
`/speckit-tasks` → `tasks.md`

### Phase 3 Implementation
Implementer: tests first, then both services, dual-backend green.

## Risks

| Risk | Mitigation |
|------|------------|
| Investment always adds events for reused raw ids | Skip via formal_fact_targets; unique constraint as backstop |
| start_batch reuses same batch for same digest | OK: re-process rows; count only novel |
| CHECKIN identity uniqueness | Same checkin identity not re-applied |
| Cash tests assume digest short-circuit | Update assertions to count=0 after full path |

## Constitution Check (post-design)

Still PASS. Parity matrix: same identity set / amounts / new counts on both backends; allowed: batch UUID/timestamps differ.

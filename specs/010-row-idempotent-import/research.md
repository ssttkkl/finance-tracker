# Research: Row-Level Idempotent Import

**Feature**: 010-row-idempotent-import  
**Date**: 2026-07-23

## Current behavior

### Cash — `StatementImportService.import_statement`

1. Always **parses** file.
2. `start_batch(source_kind, source_digest, …)` — if digest exists, **reuses** same `import_batches.id`.
3. If batch `status == completed` → **return already imported** (count=0) **without** row-level work.
4. Else: `add_raw_records` (reuses raw ids by identity), `formal_fact_targets`, skip ids that already have cash/investment facts, formalize only novel.

**Implication**: Different digests with overlapping identities already incremental for cash. **Same digest** short-circuits (fine for identical files). Spec requires digest never be *sole* reason to skip when novel rows could exist — for identical digest, novel rows cannot exist; still remove short-circuit for uniform code path and SC-005 regression.

### Investment — `InvestmentImportService.import_statement`

1. If completed batch with same `source_kind`+`source_digest` → **return** count=0 (no parse of new work).
2. Else parse, `start_batch`, `add_raw_records` (identity reuse).
3. **Always** `apply_investment_event` + `investments.add` for every returned raw id — **no** `formal_fact_targets` skip.

**Implication**: Overlapping **different** files sharing identities would reuse raw_record_id and attempt second `investment_events` insert → unique `(workspace_id, raw_record_id)` failure or double book if null. Digest short-circuit only protects identical re-import.

## Decisions

### D1: Sole formalization gate = identity → existing formal fact

**Decision**: Skip formalization when raw_record_id already maps to cash or investment fact (cash: existing; investment: add same).

**Rationale**: Spec FR-001; user product intent.

**Alternatives**: Digest primary (status quo) — rejected.

### D2: Remove completed-batch / digest short-circuit as “no work” return

**Decision**: After `start_batch`, always run raw attach + formal_fact_targets skip + formalize novel, even if batch already completed (same digest re-entry).

**Rationale**: FR-002, SC-005; uniform path cash/investment.

**Alternatives**: Keep short-circuit only for investment — rejected (asymmetric).

### D3: Keep unique `(workspace, source_kind, source_digest)` on batches

**Decision**: One batch row per digest remains; re-import same digest reuses batch id (audit job, not ledger).

**Rationale**: Minimal schema change; FR-006 allows batch as job metadata. Spec “may create new batch” is optional — reuse satisfies audit trail via completed_at updates if needed (optional touch completed_at).

**Alternatives**: Always insert new batch (requires relaxing unique or salt digest) — out of scope unless product insists.

### D4: Investment skip uses `formal_fact_targets`

**Decision**: Reuse repository method that returns raw_ids with existing cash **or** investment facts; for investment import, skip if investment (or any) formal fact exists for that raw_id.

**Rationale**: Already implemented for cash; prevents double event.

### D5: No migration

**Decision**: No Alembic change if constraints already exist.

## Open items resolved

| Item | Resolution |
|------|------------|
| Does cash already do overlap? | Yes for different digests; short-circuit only same digest |
| Does investment need skip? | **Yes** — critical |
| CHECKIN re-import | Same identity → skip; new ending balance identity → new event |

## Supersedes (009/007 digest-primary)

As of **010**, completed-batch / `_find_existing_batch` digest short-circuits are removed from both cash and investment import services. File digest remains batch job metadata only. Formalization gate is solely `source_type` + `source_identity` via `formal_fact_targets`. Cross-ref: `specs/009-investment-account-import` and `007-closed-trade-refund-import` historical docs may still mention digest skip; runtime behavior follows this feature.

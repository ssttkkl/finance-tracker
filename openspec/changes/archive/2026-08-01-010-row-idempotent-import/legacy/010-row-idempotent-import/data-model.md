# Data Model: Row-Level Idempotent Import

**Feature**: 010-row-idempotent-import  
**Date**: 2026-07-23

## No new tables

This feature changes **semantics of import orchestration**, not logical schema. Existing entities:

### import_batches (job metadata)

| Field | Role after 010 |
|-------|----------------|
| source_digest | Audit / job key; **not** “ledger already complete” |
| status | Job lifecycle pending/completed |
| unique (workspace, source_kind, source_digest) | One job record per digest (reuse id on re-import) |

### raw_records

| Field | Role |
|-------|------|
| source_type + source_identity | **Business identity** — unique per workspace |
| payload | Parsed row snapshot |
| batch_id | Which job last attached/created this row (may stay original batch if reuse) |

### Formal facts

| Table | Link | Uniqueness |
|-------|------|------------|
| cash_transactions | raw_record_id | one active fact per raw (existing) |
| investment_events | raw_record_id | unique (workspace, raw_record_id) |

## Validation rules (behavioral)

1. Novel identity → insert raw (if needed) + formal fact + projection update.
2. Known identity with formal fact → no second formal fact; no second projection apply.
3. Known identity wrong account → fail closed (cash existing rule).
4. Full overlap → success, new formal count = 0.

## State transition (import job)

```text
start/reuse batch → attach raws by identity → classify novel vs known
  → formalize novel only → validate snapshot if investment → complete batch
```

## Dual-backend parity

| Aspect | PostgreSQL | SQLite | Equivalence |
|--------|------------|--------|-------------|
| Identity unique | UNIQUE constraint | same | same skip/new |
| Event unique raw | UNIQUE | same | no double event |
| Batch unique digest | UNIQUE | same | same batch id reuse |
| Concurrent insert | IntegrityError + race path in add_raw_records | sequential | at most one formal fact |

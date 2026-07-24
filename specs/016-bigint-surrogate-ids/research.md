# Research: 016 Bigint Surrogate IDs

## R1 — Scope after 015

**Decision**: In-scope UUID PK tables only: `accounts`, `cash_transactions`, `investment_events`, `transaction_relations`, `account_aliases`. Plus UUID-shaped FKs to those ids on wealth/lifecycle.

**Rationale**: 015 deleted import/raw/revision/check_run/deletion tables.

## R2 — Public identity

**Decision**: No `public_id`. External/idempotent identity remains `source_type`×`record_id` and account `name`.

## R3 — Integer dialect

**Decision**: SQLAlchemy `BigInteger` with autoincrement; PG BIGINT; SQLite INTEGER affinity.

## R4 — Migration mapping

**Decision**: Transient mapping tables or CTEs inside migration only; not product tables. Fail-closed if FK target missing.

## R5 — Relation endpoints

**Decision**: Store integer fact ids; keep `primary_fact_type` / `secondary_fact_type`.

## R6 — Wealth tables

**Decision**: Keep string PKs (`observation_id`, digests). Only convert `account_id` / `owner_account_id` to int where they referenced UUID accounts.

## R7 — Application id generation

**Decision**: Remove client-side UUID for in-scope PKs; rely on DB. Code that compared id strings still works if coerced to str for display, but storage is int.

## R8 — ~/.ft

**Decision**: Optional delivery gate: backup and upgrade after tests green (same as 015).

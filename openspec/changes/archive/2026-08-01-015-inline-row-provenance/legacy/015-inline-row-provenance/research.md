# Research: 015 正式事实结构清理

## R1 — Idempotency key

**Decision**: Active formal identity = **`(workspace_id, source_type, record_id)`** where both `source_type` and `record_id` are non-empty. `source_type` = import **channel name** (alipay, wechat, usmart, …). `record_id` = platform txn / content-stable key. Do **not** use a parallel `source_identity` column.

**Rationale**: User correction — row keys alone collide across channels; channel is part of identity. Aligns with former raw unique `(source_type, source_identity)` but renames identity field to `record_id` on the fact.

**Alternatives**: Bare `record_id` global unique — rejected (cross-channel false skip). Keep `source_identity` name — rejected (duplicate concept with record_id).

## R2 — Backfill source fields from raw

**Decision**: Migration joins `cash_transactions.raw_record_id` → `raw_records` (and investment equivalent): copy `source_type`, map raw `source_identity` → fact `record_id` when fact.record_id empty or prefer raw identity when fact.record_id empty string; set `source_payload` from raw.payload (JSON). If fact already has non-empty `record_id`, keep it; set `source_type` from raw.source_type or from fact.bill_source when raw missing. Manual facts (null raw_record_id): leave source_type/record_id empty-or-as-is, source_payload null/{}.

**Rationale**: One-shot; raw is current SoT for parsed snapshot.

**Fail-closed**: If after backfill two **active** cash rows share same non-empty `(workspace, source_type, record_id)`, abort migration with ids listed (do not pick winner).

## R3 — Drop price

**Decision**: Remove column; buy/sell projection uses legs (qty on security leg, cash amount on cash leg). Importers that only have price×qty compute cash leg **before** persist.

**Rationale**: User — price is derivable; avoids dual write with legs.

## R4 — Drop revision + record_revisions

**Decision**: Drop table and fact `revision` columns. Wealth source item revision string becomes content digest short hash or constant `"1"` plus content_digest already used for change detection.

**Rationale**: No product edit-history; revision was mostly always 1.

## R5 — relation_check_runs

**Decision**: Delete table and repository methods; `RelationsService` returns stats in memory only.

## R6 — fact_deletion_events

**Decision**: Delete table; logical delete writes only cash row `deleted_at`/`deleted_by`/`delete_reason`.

## R7 — SQLite vs PG migration mechanics

**Decision**: Same as 014: dialect branch in one revision `20260724_08_inline_provenance_cleanup`; SQLite rebuild cash/investment tables; PG ALTER/DROP where safe.

## R8 — ~/.ft

**Decision**: Delivery gate: backup then alembic upgrade on `sqlite+pysqlite:////$HOME/.ft/finance-tracker.db`. Not a second migration language — same revision.

## R9 — Convert offset fields

**Decision**: convert may still compute offset hints **in memory** for human CSV export inspection if useful, but **must not** write those keys into formal fact insert dicts. Prefer stripping at import boundary.

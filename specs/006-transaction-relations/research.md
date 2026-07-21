# Research: Transaction Relations

## Decision 1: Unified relation object (not forced candidate/relation split tables)

**Decision**: One logical `transaction_relations` model carrying `pending_review` / `accepted` / `rejected` / `superseded`. Optional separate tables only if implementation convenience requires it; contracts treat one object family.

**Rationale**: Spec explicitly allows either; unified model minimizes state drift and unique-key complexity for v1.

**Alternatives rejected**:
- Forced dual tables with sync — more moving parts without user-visible gain.
- CSV pending files — constitution/spec forbid restoring CSV reconcile UX.

## Decision 2: Relation kinds and credit repayment subtype

**Decision**: Top-level kinds: `payment_mirror`, `transfer_pair`, `refund_offset`. Credit repayment is `transfer_pair` + `subtype=credit_repayment` (evidence/rule_id also record repayment signals). No `duplicate_of`.

**Rationale**: Spec clarify decisions; repayment is transfer-like for balances but must remain user-distinguishable.

## Decision 3: Active formal fact + logical delete marker

**Decision**: Add durable logical-delete on formal cash facts (preferred: `deleted_at` + `deleted_by`/`delete_reason` or append-only `fact_deletion_events` with the same effect). Active = not deleted. Projections, matching, and row-level identity occupancy use active only.

**Rationale**: Spec requires instance-level delete without banning source identity; audit must remain.

**Alternatives rejected**:
- Physical delete — breaks audit and FK history.
- `duplicate_of` keep-both-count-one — user rejected.

## Decision 4: Row-level idempotency is active-only; re-import publishes new instance

**Decision**: `(workspace, source_type, source_identity)` blocks only when an **active** formal fact already exists. After logical delete, re-import may publish a **new** active formal fact. Must not undelete the old instance. File digest idempotency unchanged (completed digest does not whole-file replay).

**Schema implication**: Current `UNIQUE(workspace_id, raw_record_id)` on cash facts and “skip if formal exists for raw” must become **active-aware**. Options (choose one in implementation, test both backends):
1. Partial unique index on active rows only (PG) + equivalent SQLite unique among active via composite/generated guard.
2. Allow multiple facts per raw_record with check that ≤1 active; re-import may attach a new fact to same or new raw lineage as long as identity rules hold.

**Rationale**: User correction to earlier “ban resurrection forever” mistake.

## Decision 5: Post-import check runs after import commit

**Decision**: `StatementImportService` commits formal facts first. Then register/run `RelationService.check(seeds=new_active_fact_ids)`. Check failure retries later; never rolls back import. Sync in-process is acceptable for v1; durable check-run row recommended for observability/idempotency.

**Rationale**: Spec FR-023/024.

## Decision 6: Candidate search = seed + bounded workspace windows

**Decision**: Seeds = newly imported active facts (or manual seed range). Candidates from entire workspace active facts filtered by kind-specific indexable predicates (amount, currency, time window, account type, card tail, source pair, external ids). No full-table correctness dependency.

**Rationale**: Spec seed+cross-batch model; personal scale but must stay bounded.

## Decision 7: Matching windows & strict Decimal (from main signals, hardened)

**Decision** (spec-fixed, not re-litigated):
- `payment_mirror` auto-accept: same currency, amount exact, Δt ≤10s, cross-text or card-tail, unique candidate; same-day weak → pending.
- `transfer_pair` auto-accept: opposite signs, different accounts, same currency, abs amount exact, Δt ≤10s + transfer signals unique; same-day unionpay/no-card-pay pair allowed when unique.
- `credit_repayment`: cash→loan same currency exact abs, Δt ≤600s; FX repayment Δt ≤10s without amount equality, record both amounts.
- `refund_offset`: candidate ≤30d; auto-accept default ≤14d; order/txn lock may auto-accept 15–30d; one refund → one expense; multi refunds per expense; no amount tolerance; remaining balance exact Decimal.
- Main code’s float `0.01` is **not** a tolerance here.

**Rationale**: Spec clarifications + main rule families without delete/rewrite persistence.

## Decision 8: Projection order

**Decision**: Income/expense projection:
1. Build accepted `payment_mirror` connected components; pick deterministic canonical (platform detail > bank summary; then longer counterparty+description).
2. Exclude both legs of accepted `transfer_pair` (including credit_repayment) from external P&L.
3. Apply accepted `refund_offset` once per logical expense group.
Balance projection: sum all **active** formal cash facts (ignore relation “de-legging”).

**Rationale**: Spec FR-034/035 and cross-kind matrix.

## Decision 9: Cross-kind compatibility

**Decision**: Enforce in service layer:
- Compatible: `payment_mirror + refund_offset`
- Incompatible on same fact: `transfer_pair` with `payment_mirror` or `refund_offset`
Auto path emits pending with conflict evidence; human must supersede before accept.

## Decision 10: Review Inbox surface

**Decision**: CLI/query contract is MVP (`ft relations pending|accept|reject|later` or equivalent). Web UI optional, not correctness gate. `later` keeps `pending_review`.

## Decision 11: Account aliases

**Decision**: New workspace-scoped alias records (card tail / payment method text → account). Used only in relation evidence/scoring. Never override import mapping routes.

## Decision 12: Legacy offset_* / transfer_account fields

**Decision**: Non-authoritative. New import path must not rely on them for nets/transfers. Projection ignores them for correctness. May remain on rows for display compatibility until a later cleanup feature.

## Decision 13: Dual-backend parity

**Decision**: Shared Application Service + logical schema; dialect-only adapter differences. Tests must exercise SQLite and real PostgreSQL for relations, delete/re-import, and projection nets.

**Rationale**: Constitution IV.

## Decision 14: Single-leg transfer_rules

**Decision**: v1 does not create accepted one-sided relations. Text signals may surface as user hints to add missing counterparty; pairing only after both legs exist.

## Open implementation details deferred to tasks (not product ambiguity)

- Exact table names/indexes
- Whether check-run is sync-only or optional background worker
- CLI flag names
- Precise JSON evidence schema versioning (`rule_id` string taxonomy)

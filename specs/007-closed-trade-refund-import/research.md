# Research: Import No-Skip & Closed-Trade Anchors

## Decision 1: Non-funding formal facts instead of skip

**Decision**: Publish closed/failed/zero-fund detail lines as formal cash facts with `funding_status=non_funding` (name illustrative), excluded from default balance occupancy.

**Rationale**: Spec forbids silent skip; refunds need origin anchors; constitution forbids silent discard.

**Alternatives considered**:
- Keep skip, fix only 006 time windows → rejected (mis-pairs reorder success).
- Raw-only without formal fact → weak for 006 matching and user audit.
- Physical placeholder accounts → overkill.

## Decision 2: Layout noise vs transaction lines

**Decision**: Headers, empty rows, page totals, footers are **not** source transaction lines. Only rows that parsers classify as transactions enter the acceptance count.

**Rationale**: User “all entries” means all trades, not PDF chrome.

## Decision 3: Mapping miss fails closed

**Decision**: Unmatched mapping raises; remove runtime silent `skip` default for production import path.

**Rationale**: FR-004; skip is another silent loss channel.

**Alternatives**: Keep skip opt-in via explicit user flag — out of scope unless later product asks; default remains fail-closed.

## Decision 4: Order prefix metadata at import time

**Decision**: When refund `txn_id` matches `{base}_{suffix}`, store `origin_order_id=base` (and full `txn_id`) on the published fact/payload.

**Rationale**: Real Alipay refunds use this pattern; enables correct 006 pairing without title heuristics.

## Decision 5: Balance path

**Decision**: `statement_import` snapshot balance updates skip `funding_status=non_funding` facts; reports that mean “paid consumption” must likewise exclude them (shared helper).

**Rationale**: FR-010/016; closed+full refund net zero.

## Decision 6: Idempotency

**Decision**: Existing source_identity active fact → count as `idempotent_hit`, not failure and not silent skip.

**Rationale**: FR-005.

## Decision 7: Scope of code change

**Decision**: Primary fixes in `convert.py` / importers + `statement_import.py` + schema fields; only minimal relation consumption of `origin_order_id` if required for SC-003/004. Full 006 redesign is non-goal.

## Decision 8: Dual backend

**Decision**: One Alembic revision for any new columns; SQLite+PG tests for acceptance counters and non-funding balance exclusion.

**Rationale**: Constitution IV.

## Decision 9: No alipay refund_offset 补漏 in relation scan

**Decision**: Alipay same-platform order-key refunds are finalized at **import** as `refund_offset`. Relation `check` does **not** re-hunt alipay refunds already linked; it focuses on cross-source mirror, transfers, and non-alipay/no-key refunds.

**Rationale**: Real 3y bills: 151/151 nonzero alipay refunds uniquely match origin with `==` / `startswith(origin+'_')` / `startswith(origin+'*')`. Residual 补漏 count ≈ 0.

**Alternatives considered**: Keep dual-path import+scan soft match — rejected (duplicate risk, weaker than order key).

# Research: Import No-Skip & Alipay Order Refund at Import

## Decision 1: No silent skip

**Decision**: Every source transaction line is accepted or import fails closed.

**Rationale**: User requirement; constitution auditability; closed-row skip caused refund orphans.

## Decision 2: No funding_status field (this phase)

**Decision**: Do **not** add `funding_status` / non-funding enum. Closed expenses import as negative amounts; refunds as positive; pairs cancel in balance sum.

**Rationale**: User rejected extra control plane; real bills show closed expenses pair with refunds under correct order keys (121/121).

**Alternatives considered**:
- non_funding flag excluding closed from balance → rejected as harder to control for this phase.
- keep skipping closed → rejected (orphans).

## Decision 3: Order-key match (not rsplit-only)

**Decision**: Match refund to origin if equal, or `startswith(origin+"_")`, or `startswith(origin+"*")`.

**Rationale**: Real cases: `_mer_advance`, multi-segment `_`, Steam `*clearingId`. Single `rsplit("_",1)` false-negatives (6 closed + 2 Steam).

## Decision 4: Import writes refund_offset; scan does not 补漏 alipay orders

**Decision**: Unique alipay order-key hits create `refund_offset` at import. Relation check skips those; no alipay same-platform refund backfill mission.

**Rationale**: 151/151 nonzero alipay refunds uniquely match; residual 补漏 ≈ 0. Scan remains for mirror/transfer/other sources.

## Decision 5: Mapping miss fails closed

**Decision**: Remove silent mapping `skip` default.

## Decision 6: Layout noise excluded from line count

**Decision**: Headers/footers/empty/page totals are not source transaction lines.

## Decision 7: Dual backend

**Decision**: Any schema/result counters equivalent on PG and SQLite.

## Decision 8: Scope of code

**Decision**: convert/importers + statement_import (+ relation insert at import); minimal check skip-if-linked; no 006 engine rewrite.

# Research: Import No-Skip & Alipay Order Refund at Import

## Decision 1: No silent skip (with one documented skip)

**Decision**: Every source transaction line is accepted, **explicitly skipped as unpaid-closed**, or import fails closed. Silent drop is forbidden.

**Rationale**: User requirement; unpaid-closed rows must not become fake expenses.

## Decision 2: No funding_status; unpaid-closed skipped instead

**Decision**: Do **not** add `funding_status`. **Paid closed** (`交易关闭|支出`) import as negative amounts; refunds positive; pairs cancel. **Unpaid closed** (`交易关闭/已关闭` + non-expense direction + empty payment method) are **skipped** with code comments + counters.

**Rationale**: Real bills: 121/121 paid closed have refunds; ~42 unpaid closed have no refund and would pollute balances if imported as spend.

**Alternatives considered**:
- import unpaid closed + funding flag → rejected by user (control plane).
- skip all 交易关闭 → rejected (breaks paid closed + refund anchors).

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

## Decision 10: Unpaid-closed skip criteria (Alipay)

**Decision**: Skip when `status ∈ {交易关闭, 已关闭}` AND `direction ≠ 支出` AND payment method empty. Comment required at skip site.

**Rationale**: Matches ~42 real rows with no order-key refund; opposite of paid closed expenses (direction=支出, payment method present, always refunded).

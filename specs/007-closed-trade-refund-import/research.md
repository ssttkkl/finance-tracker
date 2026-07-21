# Research: Import No-Skip & Alipay Order Refund at Import

## Decision 1: No silent skip (with one documented skip)

**Decision**: Every source transaction line is accepted, **explicitly skipped** (unpaid-closed or failed-repay-no-debit), or import fails closed. Silent drop is forbidden.

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

## Decision 11: Auth-hold / unfreeze as refund_offset

**Decision**: Treat 芝麻免押下单成功 → 解冻成功 as `refund_offset` at import (hold = origin leg, unfreeze = release leg). Import both rows even when amount is 0.

**Rationale**: User-specified deposit/authorization lifecycle; same "offset" semantics as refund. Real bills: 2 pairs, same calendar day, distinct txn/mer — match by ordered unique same-day status pair when keys differ.

**Alternatives considered**:
- leave as unrelated 0-amount lines → rejected by user
- transfer_pair → rejected (not account transfer; it's hold release)
- relation-scan only → rejected (import-owned, like alipay order refunds)

## Decision 12: Skip 还款失败 when no debit

**Decision**: Skip Alipay `还款失败` when direction is 不计收支 and payment method is empty. Count `skipped_failed_repay`. Comment required. Import `还款成功` always (payment method present in real data).

**Rationale**: 2 real rows are failed auto-repay attempts (1350.30) with empty pay; later `还款成功` with bank card is the real debit. Importing failure would add misleading amount without offset.

**Alternatives considered**: import as audit non-balance → user prefers skip like unpaid-closed; no funding_status field.

## Decision 13: Mapping table freeze (19 buckets)

**Decision**: Freeze Alipay 19-bucket map in spec appendix. Only two business skips: unpaid-closed (42) and failed-repay-no-debit (2). Import 3060/3104. Auth-hold/unfreeze import as refund_offset. No third skip without new evidence.

**Rationale**: Real-bill inventory; pay-empty alone is not a skip (transfer-out/income).

## Decision 14: WeChat dual-row refund at import

**Decision**: Import both WeChat expense rows whose status became refunded and income refund rows; write `refund_offset` at import using pay+embedded amount+time+residual+transfer-return rules. Do not use Alipay txn-prefix. Do not net amounts in convert. Real 3y sample: 0 true orphans; prior ~6% "none" were rule false negatives.

**Rationale**: WeChat export rewrites original payment status and adds income refund row; txn prefixes differ (4200 vs 5030).

## Decision 15: WeChat no business skip in current corpus

**Decision**: No whitelist skip for current WeChat exports (no unpaid-closed rows). Remove silent continues; unknown futures fail closed or FR-008b with counters.

## Decision 16: Plan freeze for implementation

**Decision**: Implement without `funding_status` column. Whitelist skips only. Alipay order-prefix + WeChat dual-row import refund_offset. Convert must not net refund amounts. Scan skips already-linked pairs.

**Rationale**: Final spec 2026-07-21 product decisions.

# Research: Import No-Skip, Raw Payload, Unified Scan

## Decision 1: No silent skip (documented whitelist only)

**Decision**: Accept, whitelist-skip (unpaid-closed / failed-repay), or fail closed.

## Decision 2: No funding_status

**Decision**: Paid closed imports as expense; refunds positive; pairs cancel via amounts + relations after scan.

## Decision 3: Order-key match (alipay)

**Decision**: `==` / `startswith(origin+"_")` / `startswith(origin+"*")`. Not rsplit-only.

## Decision 4 (REVISED 2026-07-22): Import does not write relations

**Decision**: Import publishes formal facts + raw payload only. **No** import-time `refund_offset` / mirror / transfer.

**Rationale**: Unify with bank path — all sources import first, one sync scan pairs everything. Platform hard-key rules unchanged, trigger moves to scan Phase A. Cross-batch (bank then alipay) works in one check.

**Alternatives rejected**:
- Keep import-time platform refunds + scan-only bank → split mental model (user rejected for process unity).
- Defer platform rules without raw fields → scan cannot reconstruct dual-row / order keys.

## Decision 5: Raw payload contract

**Decision**: `raw_records.payload` is the store. Spec lists minimum keys per source_type. Convert/import MUST populate them.

## Decision 6: Scan phases A → B → C

**Decision**:
1. Phase A: alipay/wechat hard-key refunds + auth-unfreeze
2. Phase B: payment_mirror
3. Phase C: bank refunds, transfer, weak/open-leg

**Rationale**: Refund before mirror reduces wrong channel mirrors on refund credits.

## Decision 7: Bank refunds stay in Phase C

**Decision**: No import-time bank refund pairing. Soft merchant+amount matching belongs in relation scan.

## Decision 8: Mapping miss fails closed

## Decision 9: Dual backend equivalence

## Decision 10: Whitelist skips (unpaid-closed, failed-repay)

## Decision 11: Auth-hold / unfreeze as refund_offset in Phase A

## Decision 12: WeChat dual-row rules in Phase A (not import write)

**Decision**: Same FR-029 matching; create relations during check Phase A.

## Decision 13: Convert must not net amounts

**Decision**: `_pair_refunds` must not rewrite amounts as authority; relations only.

## Decision 17: Transfer as dedicated Phase C

**Decision**: After mirror (B), run **transfer_pair / credit_repayment** as **Phase C** using source-native taxonomy gates then fine matching. Bank merchant refunds and weak/open-leg run in **Phase D** after C.

**Rationale**: Real bills — withdraw and card-bridge pairs are reliable; P2P/QR must not enter transfer pool; bank refund is a different problem and must not interleave before transfer settles self-account moves.

## Decision 18: Transfer taxonomy attachment

**Decision**: Canonical Stage-1 tables live in `attachments/transfer-source-taxonomy.md` (alipay status×direction+family, wechat status×type, ccb summary, icbc pm/cp).

## Decision 19: Business-day mirror + diamond refund

**Decision**: Pairing uses raw payload business time in Asia/Shanghai. Bank date-only same-day same-account exact mirrors auto-accept. Platform refund credits × bank 消费退货 auto-mirror. Phase D diamond closes bank refund open-legs via accepted platform refund + mirrors.

**Rationale**: CCB raw date is correct YYYY-MM-DD; occurred_at UTC midnight becomes previous-day 16:00 and falsely weakens mirrors. Diamond uniquely resolves ~half of bank refund open-legs on real bills.

## Decision 20: Scan rule architecture (post dead-code cleanup)

**Active production rules (v6 real-bill hit profile):**

Phase B payment_mirror (priority):
1. refund_dual_source (+/+)
2. bank_date_only (raw YYYY-MM-DD)
3. same_account.exact.business_day (FR-056; nearest multi-cand FR-057)
4. exact.time10 + text/card (cross-account short)
5. same_account exact ≤60s
6. short_window text unique
7. near.weak residual cross-account / multi-ambiguous only

Phase C transfer: withdraw_to_bank, time_window, credit_repayment(.fx), unionpay day
Phase A: scan.alipay.*/scan.wechat.* via platform_refund matchers
Phase D: diamond_via_platform then merchant_or_order open-leg

**Removed dead code:** texts_cross_match, fact_has_active_refund_offset,
RULE_PAYMENT_MIRROR_SAME_DAY_UNIQUE alias, create_import_refund_offsets,
unreachable same-account lag pending branches (subsumed by FR-056).

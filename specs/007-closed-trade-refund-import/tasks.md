# Tasks: 007 Unified Scan Orchestration

**Branch**: `007-import-no-skip`

## Phase 1 — Spec (done this session)

- [x] T100 Living-spec: import raw only; scan A/B/C
- [x] T101 Sync plan/research/data-model/contracts/quickstart

## Phase 2 — Tests first

- [x] T110 Test: import success creates **zero** new refund_offset from import path
- [x] T111 Test: raw payload required keys present for alipay/wechat after import
- [x] T112 Test: after relations check, alipay order-key pair exists (closed+refund)
- [x] T113 Test: wechat dual-row / residual / transfer-return via check not import
- [x] T114 Test: phase order hook or rule_id prefix A before mirror (if observable)
- [x] T115 Test: convert amounts not netted
- [x] T116 Test: whitelist skips still counted
- [x] T117 Dual-backend smoke when FT_TEST_POSTGRES_URL set

## Phase 3 — Implementation

- [x] T120 Populate/fix payload fields in convert → import adapter
- [x] T121 Remove statement_import → create_import_refund_offsets (or no-op)
- [x] T122 RelationService.check: Phase A platform refunds using platform_refund matchers
- [x] T123 Ensure Phase A runs before mirror/transfer evaluation
- [x] T124 Skip active relations; compat import.* rule_ids
- [x] T125 Bank path: no import pairs; rely on Phase C / existing evaluate_refund

## Phase 4 — Verify & ship

- [x] T130 Full pytest subset green
- [x] T131 Optional real ~/.ft/bills import+check smoke
- [x] T132 Commit + push PR branch

## Dependencies

T110–T117 before T120–T125; T130 after implementation.

## Phase 5 — Transfer Phase C

- [x] T140 Spec attachment transfer taxonomy (done with living spec)
- [x] T141 Test: withdraw alipay→bank accepts in check
- [x] T142 Test: wechat QR / P2P transfer not auto transfer_pair
- [x] T143 Test: phase order C before bank refund path (ordering)
- [x] T144 Implement taxonomy tags + withdraw/card-bridge rules in evaluate or phase_c helper
- [x] T145 Wire check: A → B → C transfer → D refund weak
- [x] T146 Push

## Phase 5b — Transfer exclude tiers (strong P2P vs soft platform transfer)

- [x] T147 Test: `闲鱼转账` is strong exclude; never transfer_pair with near equal expense
- [x] T148 Test: 红包/二维码/群收款 remain strong exclude
- [x] T149 Test: bare `微信转账`/`转账备注` alone is NOT strong exclude; bilateral wechat-transfer P2P MUST NOT auto-accept
- [x] T150 Test: withdraw/提现→bank still accepts (regression)
- [x] T151 Implement `TRANSFER_STRONG_EXCLUDE_TOKENS` (+ keep soft tokens separate); demote 微信转账/转账备注 from hard exclude; add 闲鱼转账
- [x] T152 Wire evaluate_transfer_pair / phase_c to use strong exclude; soft path no auto without withdraw/bank evidence
- [x] T153 Re-run unit tests for transfer phase C + refund p2p (no regression on 微信红包-退款)

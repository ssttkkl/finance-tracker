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

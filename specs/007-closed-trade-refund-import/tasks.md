# Tasks: 007 Import No-Skip & Platform Refund at Import

**Branch**: `007-import-no-skip`  
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Phase A — Setup & acceptance contract

- [x] T001 Write failing tests for import acceptance counters (source = published + idempotent + skipped_unpaid_closed + skipped_failed_repay)
- [x] T002 Implement acceptance counter fields on import result DTO / statement_import response
- [x] T003 Fail-closed mapping miss test + remove silent mapping skip path

## Phase B — Alipay no-skip + whitelist skips

- [x] T004 Failing tests: paid `交易关闭|支出` imports; unpaid-closed skipped with counter; failed-repay skipped with counter; 还款成功 imports
- [x] T005 Implement Alipay `_read_alipay_raw` FR-008 / FR-008a / FR-008c with **code comments** at skip sites
- [x] T006 Import 0-yuan non-unpaid-closed rows (stop amount==0 continue for valid lines)
- [x] T007 Auth-hold + unfreeze both import (not unpaid-closed)

## Phase C — Alipay import-time refund_offset

- [x] T008 Failing tests: order prefix `_` / `*` unique → refund_offset; multi-segment `_advance`; Steam `*`; reorder B not linked
- [x] T009 Implement origin match FR-013 helper (pure function + tests)
- [x] T010 Wire import path to create refund_offset (accepted, rule_id auditable) without amount rewrite
- [x] T011 Auth-hold→unfreeze refund_offset FR-014a test + implement
- [x] T012 Relation check skips already-linked alipay order refunds (no 补漏 main path)

## Phase D — WeChat dual-row

- [x] T013 Failing tests: no silent skip for unknown; neutral `/` types import; amounts not netted by convert
- [x] T014 Remove WeChat silent continues (expense fail states, income not INCOME_OK) per FR-027
- [x] T015 Failing tests: full dual-row refund_offset; partial embedded 30d (味多美); residual JD split; 对方已退还; redpacket mer==txn
- [x] T016 Implement WeChat FR-029 matcher + import-time refund_offset; disable amount-mutating _pair_refunds authority
- [x] T017 Scan skip already-linked wechat refund pairs

## Phase E — Integration & real bills

- [x] T018 Integration tests dual-backend when FT_TEST_POSTGRES_URL set
- [x] T019 Real `~/.ft/bills` alipay copy: counters + paid closed + skip counts
- [x] T020 Real wechat copy: 0 business skips; refund pairs; no amount netting
- [x] T021 Update tasks checkboxes; run full related pytest suite

## Dependencies

A → B → C and D (C/D parallel after B) → E

## Parallel examples

- T008–T011 after T005
- T013–T016 after T014 base import

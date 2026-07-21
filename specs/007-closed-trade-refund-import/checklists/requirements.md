# Specification Quality Checklist: Import No-Skip & Alipay Order Refund at Import

**Purpose**: Validate specification completeness before planning  
**Created**: 2026-07-21  
**Updated**: 2026-07-21  
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- 预授权(芝麻免押)→解冻 = import-time `refund_offset` (user decision).
- User decision: **unpaid-closed** and **还款失败未扣款** are skipped (not imported), with code comments; not silent no-skip violation.
- Paid `交易关闭|支出` still imported + order-key refund_offset.
- Converged decisions written into spec:
  - no-skip all sources
  - closed/failed rows import as normal amounts (no funding_status field)
  - alipay order-key refund_offset at import (`_`, `*`); no relation-scan 补漏
  - validated on ~/.ft/bills (151/151 nonzero refunds; 121/121 closed expenses)
- Next: align plan/research, then `/speckit-tasks` → analyze → implementer.

- [x] 附录 19 组合映射表与双跳过白名单已冻结（2026-07-21 重整理）

- [x] 微信双行退款、假单腿根因与 FR-027–032 / 附录已写入（2026-07-21）

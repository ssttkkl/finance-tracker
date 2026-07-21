# Specification Quality Checklist: Transaction Relations

**Purpose**: Validate specification completeness and quality before proceeding to planning  
**Created**: 2026-07-21  
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

- Spec intentionally references main-branch pairing *business signals* and fixed time windows as rule inputs, while forbidding CSV-era physical delete/rewrite persistence and float `0.01` tolerances.
- Review Inbox requires a reviewable decision surface; full Web UI is not a correctness blocker. `ignore/later` stays `pending_review`.
- Dual-database equivalence is required for relation state, report nets, logical deletion, and post-delete re-import.
- Legacy inline `offset_*` / `transfer_account` fields are non-authoritative; independent relation objects own pairing/refund semantics.
- Validation iteration 1 (2026-07-21): all checklist items pass.
- Clarify session 2026-07-21: decisions integrated (single-leg requires counterparty; refund only via post-import relations; credit repayment as transfer_pair subtype; investments only as transfer counterparty; relation check after import commit; no `duplicate_of`; no amount tolerance; cross-kind matrix; logical deletion).
- Completion pass 2026-07-21: concrete time windows, n-way mirrors, ignore/later, legacy offset non-authority, SC-013/014, FR-006b.
- Correction 2026-07-21: logical delete does **not** permanently ban source identity. Re-import same identity after delete publishes a **new active** formal fact (no silent undelete). Row-level idempotency applies only to active facts (FR-006c, SC-015).
- Finalization 2026-07-21: added Active Formal Fact definition, post-delete re-import/raw contract, relation business key, active-only matching, concurrent check equivalence, cross-batch story 9, FR-001a/006d/041, SC-016/017. Status set to Ready for Planning. Checklist 16/16 still passing.
- Mirror calibration 2026-07-21: after real `~/.ft` ledger run, revised `payment_mirror` to main precision — platform×bank only, main-style substring text, global 1:1, bare same-day exact silent (FR-016/016a, SC-003a/003b, US1 scenarios). Implementation must follow updated artifacts (not the reverse).
- Ready for `/speckit-plan` delta or continue implementation against updated FR-016.

# Specification Quality Checklist: Transaction Relations (open-leg pending)

**Purpose**: Validate specification completeness after open-leg pending extension  
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

- 2026-07-21: Extended 006 with **待配对关系** for `refund_offset` + `transfer_pair` only; `payment_mirror` excluded.
- Distinguishes open-leg **pending** from FR-019 ban on single-leg **accepted**.
- Decisions locked: multi/zero candidate → one open-leg; unique weak may stay bilateral; accept requires other_fact_id; evidence candidate_fact_ids top-K=20.
- Next: update plan/data-model/contracts via `/speckit-plan`, then tasks; implement only via `speckit_implementer`.

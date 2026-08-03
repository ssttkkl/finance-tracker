# Research: Relations Kind Decouple

**Date**: 2026-07-22  
**Feature**: `specs/008-relations-kind-decouple`

## R1 — Physical layout: package vs keep flat modules

**Decision**: Use `src/ft/domain/relations/` package with `core/`, `mirror/`, `transfer/`, `refund/`, `pipeline.py`.

**Rationale**: Enforces import boundaries; maps 1:1 to rule packs; tests already split by kind.

**Alternatives considered**:
- Flat `relations_mirror.py` siblings — weaker import discipline.
- Keep single file + sections — does not meet FR-001 independence.
- Plugin entry-points / setuptools — overkill (spec non-goal: engines).

## R2 — Cross-kind dependency mechanism

**Decision**: `MatchContext` filled by pipeline after each phase (accepted edges + `used_fact_ids` + refund remaining map).

**Rationale**: Spec FR-004; diamond needs edges not re-match; avoids pack cross-calls.

**Alternatives considered**:
- Packs query DB for accepted relations mid-match — couples domain to persistence.
- Event bus — unnecessary complexity.
- Shared mutable global registry — hidden coupling, hard tests.

## R3 — Diamond placement

**Decision**: `refund/diamond.py` (option A from design dialogue).

**Rationale**: Output kind is `refund_offset`; product treats chain as refund evidence path; fewer packages.

**Alternatives considered**:
- Fourth pack `chain_refund` — better isolation of “multi-edge” logic but kind name mismatch and extra pipeline step; deferred unless diamond grows.

## R4 — Shared signals module

**Decision**: **No** shared signals module. Duplicate identical Chinese phrases across packs if needed.

**Rationale**: FR-008 conceptual separation; historical bug class is shared/overlapping token tables with different semantics (transfer exclude vs refund P2P family).

**Alternatives considered**:
- One `signals.py` with namespaced constants — still invites “just import EXCLUDE”.
- Signal registry framework — non-goal.

## R5 — Migration strategy

**Decision**: Incremental: core → move packs → pipeline → delete facade; green tests each step.

**Rationale**: ~2.5k LOC + many importers; facade preserves import stability.

**Alternatives considered**:
- Single PR rewrite all imports — higher risk of mixed behavior bugs.
- Copy-paste dual implementation — dual source of truth (forbidden).

## R6 — Behavior parity definition

**Decision**: Compare export of active (non-superseded) relations by `(kind, status, business identity)` where identity is ordered pair or open-leg anchor key; counts per kind×status must match.

**Rationale**: SC-001; superseded audit noise allowed if documented.

**Alternatives considered**:
- UUID equality — unstable across recompute.
- Only counts — hides swap of wrong pairs.

## R7 — Application layer role

**Decision**: `RelationService` retains load/persist/review; recognition limited to `pipeline.run_relation_phases`.

**Rationale**: FR-009; matches 007 import-vs-scan boundary already established.

**Alternatives considered**:
- Move persistence into packs — violates domain purity and dual-DB adapters.

## R8 — Boundary enforcement mechanism

**Decision**: Automated test that fails if `mirror|transfer|refund` modules import another pack’s `signals` or `match` (AST or `importlib` metadata). Document convention in contracts.

**Rationale**: SC-002/003 need more than honor system.

**Alternatives considered**:
- import-linter config only — OK as supplement if added; test is enough for 008.
- Manual review only — insufficient.

## R9 — Schema / migrations

**Decision**: None for 008.

**Rationale**: Structure-only; tables and relation rows unchanged.

## R10 — Interaction with incomplete 007 work

**Decision**: 008 builds on 007 phase order and platform hard-key Phase A; implement on branch that already contains 007 relation scan behavior (or merge 007 first). Spec depends on 006+007 semantics.

**Rationale**: Pipeline order FR-003 copies 007 intent.

**Alternatives considered**:
- Implement 008 on pre-007 code — would re-encode wrong import-time relations.

## Resolved clarifications

No remaining NEEDS CLARIFICATION in technical context for plan.


## R11 — MatchContext edge seed policy

**Decision**: Preload persisted accepted `payment_mirror` + platform `refund_offset` edges, then merge this-run accepts.

**Rationale**: 007 full recompute and diamond chains must see existing accepted graph; this-run-only would drop diamond when mirrors were accepted in a prior check.

**Alternatives considered**: This-run-only (breaks parity); re-query inside diamond pack (violates FR-004).

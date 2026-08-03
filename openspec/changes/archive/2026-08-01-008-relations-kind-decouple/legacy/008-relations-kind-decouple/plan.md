# Implementation Plan: Relations Kind Decouple

**Branch**: `008-relations-kind-decouple` (implement on dedicated branch from current mainline; workspace may still be on `pr/007-…` until switch)  
**Date**: 2026-07-22  
**Spec**: [spec.md](./spec.md)

**Input**: Feature specification `specs/008-relations-kind-decouple/spec.md`

## Summary

Restructure relation **recognition** so `payment_mirror`, `transfer_pair`, and `refund_offset` each own an independent rule boundary (signals + match), with a thin shared core (types, keys, geometry, compatibility, projection) and a single pipeline that runs Phase A→D while passing **MatchContext** (accepted edges, used fact ids, refund remaining). Diamond bank-refund chaining stays a **refund sub-capability** that only reads accepted mirror + platform refund edges—never re-runs mirror matching. **Step A is behavior-preserving**: no change to 006/007 user-visible relation semantics, review APIs, or lexicon policy (strong/soft transfer exclude is a later feature).

## Technical Context

**Language/Version**: Python 3.11+ (project baseline)  
**Primary Dependencies**: existing `ft` domain/application stack; SQLAlchemy adapters unchanged for this feature  
**Storage**: No schema migration required (structure-only). PostgreSQL + SQLite remain dual runtimes for relation persistence already owned by 006/007.  
**Testing**: pytest; existing `tests/test_transaction_relations_*.py`, `tests/test_transfer_phase_c.py`, platform refund matcher tests; add pack-boundary / import-lint tests  
**Target Platform**: CLI + domain library (same as finance-tracker)  
**Project Type**: library/CLI domain refactor  
**Performance Goals**: full recompute on ~10k cash facts remains same order as today (no intentional full Cartesian expansion)  
**Constraints**: zero user-visible semantic drift (SC-001); no pack cross-imports of private signals; Decimal-only money paths unchanged  
**Scale/Scope**: ~2.5k LOC `domain/relations.py` split; `application/relations.py` orchestration thin-wrapper update

## Constitution Check

*GATE: pre-research and post-design*

| Principle | Status | Notes |
|---|---|---|
| I. Financial correctness | PASS | No amount rewrite; relation semantics frozen to 006/007; baseline parity required |
| II. Spec Kit feature discipline | PASS | New Flow-Forward feature 008; cross-links 006/007 |
| III. Test-first | PASS | Plan requires failing boundary tests + green suite + baseline recompute comparison before declare done |
| IV. Dual-database | PASS | No schema change; relation persistence paths unchanged; existing PG+SQLite relation tests must stay green |
| V. Boundaries | PASS | Kind packs isolate rules; pipeline is sole cross-kind join; application remains persistence/review boundary |

**Persistence parity**: N/A new schema. Operational note: relation check continues to use shared Application Service; dual-backend evidence = existing relation integration tests on both backends (no dual-write, no auto-fallback).

**Post-design re-check**: Still PASS — design does not introduce new persistence dialect forks or silent skips.

## Project Structure

### Documentation (this feature)

```text
specs/008-relations-kind-decouple/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── match-context.md
│   ├── rule-pack.md
│   └── pipeline-phases.md
├── checklists/requirements.md
└── tasks.md                 # via /speckit-tasks (not this command)
```

### Source Code (target layout)

```text
src/ft/domain/relations/
  __init__.py                 # public re-exports stable for application/tests
  core/
    types.py                  # enums, FactView, RelationEvidence, RelationProposal
    keys.py                   # ordered_fact_pair, business_key, open_leg_key
    geometry.py               # time/amount/day/card-tail pure helpers
    compatibility.py          # cross_kind_compatible
    projection.py             # project_balances_and_pnl (move as-is)
  mirror/
    match.py                  # evaluate_payment_mirror, match_payment_mirrors_greedy
  transfer/
    signals.py                # TRANSFER_SIGNAL / EXCLUDE + has_* (private to pack)
    taxonomy.py               # withdraw / bank_in / taxonomy_out
    match.py                  # evaluate_transfer_pair, phase_c matchers
  refund/
    signals.py                # REFUND_SIGNAL / P2P_FAMILY + has_* (private)
    hard_key.py               # Phase A orchestration helpers calling platform_refund
    match.py                  # evaluate_refund_offset, merchant/weak/open-leg
    diamond.py                # match_diamond_bank_refunds (reads context edges only)
  pipeline.py                 # run_phases(facts, ctx_seed) -> list[RelationProposal]
src/ft/domain/platform_refund.py   # unchanged pure matchers (refund hard_key collaborator)
src/ft/application/relations.py    # RelationService: load facts, call pipeline, persist, review
tests/
  test_transaction_relations_*.py  # update imports if needed; behavior same
  test_relations_pack_boundaries.py  # NEW: forbid cross-pack signal imports
  test_relations_pipeline_order.py   # NEW: phase order + diamond needs edges
```

**`source_group` placement (pinned):** live under `mirror/` (or mirror-local helper); do not put kind-specific source routing in shared signal tables.

Legacy `src/ft/domain/relations.py` becomes a **temporary facade** re-exporting public symbols, then is removed once imports updated (single rule source: package).

## Implementation Phases (engineering)

### Phase A — Core extraction (behavior-identical)

1. Create package `domain/relations/` and move types/keys/geometry/compatibility/projection without logic change.
2. Keep facade so all existing imports still work.
3. Run full relation test suite.

### Phase B — Kind packs move

1. Move mirror match code → `mirror/`.
2. Move transfer signals + taxonomy + match → `transfer/`.
3. Move refund signals + evaluate + diamond → `refund/` (`diamond.py` under refund).
4. Duplicate string tokens allowed across packs; **no** shared token module.
5. Facade continues to re-export; tests green.

### Phase C — Pipeline + MatchContext

1. Introduce `MatchContext` dataclass (see contracts).
2. Implement `pipeline.run_relation_phases` enforcing A→B→C→D.
3. Wire diamond to consume `ctx.accepted_mirrors` + `ctx.accepted_platform_refunds` only. **Seed policy (pinned):** preload workspace accepted mirrors + platform refunds from DB, then merge this-run accepts after phases A/B (and any earlier accepts); do not use this-run-only context for full recompute.
4. `RelationService` calls pipeline instead of inlined phase spaghetti.
5. Capture baseline recompute summary on fixture/real DB **before** cutover if not already; after cutover compare (SC-001).

### Phase D — Boundary enforcement + cleanup

1. Add import-boundary test or script (pack may not import another pack’s `signals`/`match`).
2. Remove facade `relations.py` monolith file; package `__init__` is public API.
3. Docs/quickstart validation; dual-backend tests.

### Phase E — Explicit non-goals (do not implement in 008)

- Transfer strong/soft exclude lexicon changes (闲鱼 / 微信转账 soft).
- Review UI changes; new relation kinds; schema migrations.

## Complexity Tracking

| Item | Why needed | Why not simpler |
|---|---|---|
| MatchContext | Spec allows data deps without shared signals | Globals or re-querying mirror match would re-couple |
| Temporary facade | Safe incremental migration | Big-bang import rewrite risks large red suite |
| Duplicate token strings | Enforce conceptual separation (FR-008) | Shared token module recreates God table |

## Risks

| Risk | Mitigation |
|---|---|
| Silent behavior drift while moving code | Facade + full suite after each phase; baseline recompute diff |
| Circular imports pack↔pack | Only pipeline imports packs; packs import core only |
| Diamond still calls mirror helpers | Code review + unit test: diamond module import graph |
| Application still embeds phase logic | RelationService must only call pipeline for recognition |
| Dual-backend missed | Run existing PG+SQLite relation tests in CI matrix |

## Baseline Parity Method (SC-001)

1. On pre-change code: `ft relations check` full recompute → export `kind,status,business_key` (or ordered pair + open-leg anchor) for active non-superseded rows.
2. After migration: same command/export.
3. Diff: pending_review + accepted sets must match; superseded-only differences documented.

## Test Strategy

| Layer | What |
|---|---|
| Existing kind tests | Import path update; assert same scenarios |
| NEW boundary | Static or runtime check: `transfer` must not import `refund.signals` etc. |
| NEW pipeline order | Mock packs or fixture facts: without mirror edges diamond yields 0; with edges can yield |
| Integration | RelationService full check on SQLite; PG when available |
| No schema tests | N/A |

## Constitution Check (post-design)

PASS — no unjustified dual-DB fork; financial rules unchanged; boundaries clearer; test-first required in tasks.

# Contract: Pipeline Phases

**Feature**: 008-relations-kind-decouple  
**Aligns with**: 007 unified scan order + 006 compatibility

## Phase order (mandatory)

| Phase | Name | Rule boundary calls | Writes into context after phase |
|---|---|---|---|
| A | Platform hard-key refund | refund hard_key | accepted_platform_refunds (accepted only); used ids |
| B | Payment mirror | mirror match | accepted_mirrors; used ids |
| C | Transfer / credit repayment | transfer match | accepted_transfers (if any); used ids |
| D | Bank/weak/open-leg refund + diamond | refund merchant/open + **diamond** | additional refund proposals |

## Invariants

1. Order is fixed for full recompute and for post-import seed checks (seed selection may limit facts, not reorder phases).
2. Phase D diamond runs only with mirrors/refunds already in context. **Context MUST be seeded with (a) workspace preloaded accepted `payment_mirror` edges and accepted platform-side `refund_offset` edges from persistence, plus (b) accepted proposals produced earlier in this run.** This matches 007 full-recompute semantics; this-run-only seeding is forbidden for full check.
3. Cross-kind compatibility matrix (006) applied when persisting/accepting—not reimplemented ad hoc inside packs.
4. Single orchestration entry for recognition: pipeline. Application must not fork a second phase order.

## Observability

Check run metadata SHOULD record phase order version string e.g. `relations.pipeline.v1` for debug (optional if existing check run fields suffice).

## Non-goals

Pipeline does not evaluate signal tokens; it only sequences pack entrypoints and context updates.

# Contract: Rule Pack Boundary

**Feature**: 008-relations-kind-decouple

## Purpose

Define what each kind pack may export and import so FR-001/FR-004/FR-008 are enforceable.

## Public entrypoints (logical)

| Pack | Entrypoint | Inputs | Output |
|---|---|---|---|
| mirror | `match_payment_mirrors(...)` | facts, optional used | list[RelationProposal] kind=payment_mirror |
| transfer | `match_transfer_pairs_phase_c(...)` | facts, ctx slices as needed, fx provider | list[RelationProposal] kind=transfer_pair |
| refund | `match_platform_hard_key_refunds(...)` | facts (+ payload access as today) | list[RelationProposal] kind=refund_offset |
| refund | `match_refund_merchant_and_open(...)` | facts, remaining map | list[RelationProposal] |
| refund | `match_diamond_bank_refunds(...)` | facts, accepted_mirrors, accepted_platform_refunds | list[RelationProposal] |

Exact Python names may match existing functions after move; contract is the **boundary**, not the spelling.

## Import rules

| From → To | Allowed |
|---|---|
| pack → `relations.core` | yes |
| pack → `domain.platform_refund` (refund only) | yes |
| pack → another pack | **no** |
| pipeline → packs | yes |
| application → pipeline + core public API | yes |
| application → pack internals | discouraged; prefer pipeline |

## Signal ownership

| Signals | Owner pack |
|---|---|
| transfer signal / exclude / withdraw phrases | transfer |
| refund signal / p2p family | refund |
| mirror source group keywords | mirror (or core geometry if truly source routing only—prefer mirror if kind-specific) |

Identical Chinese substrings MAY appear in two packs as separate constants.

## Testing contract

A boundary test MUST fail the build if a forbidden import appears under `src/ft/domain/relations/{mirror,transfer,refund}/`.

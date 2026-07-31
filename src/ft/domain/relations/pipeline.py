"""Relation recognition pipeline Phase B→D (008).

Phase A platform hard-key matching lives in refund.hard_key.match_phase_a_platform_refunds
(pure proposals). Application runs A then this pipeline for B–D, both via RelationProposal
persist — not a second write path inside hard_key.

Phase A (platform hard-key refunds) persists via Application (needs UoW/payload);
callers seed MatchContext with preloaded accepted edges + Phase A results, then
invoke :func:`run_relation_phases` as the **sole** domain matcher for B–D.
"""
from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from decimal import Decimal
from typing import Any

from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.types import (
    FactCandidateIndex,
    FactType,
    FactView,
    MatchContext,
    RelationEdge,
    RelationKind,
    RelationProposal,
    RelationStatus,
)
from ft.domain.relations.mirror.match import match_payment_mirrors_greedy
from ft.domain.relations.refund.diamond import match_diamond_bank_refunds
from ft.domain.relations.refund.match import evaluate_refund_offset
from ft.domain.relations.refund.signals import has_refund_signal
from ft.domain.relations.transfer.match import match_transfer_pairs_phase_c


def _edge(kind: str, a: str, b: str, subtype: str = "") -> RelationEdge:
    return RelationEdge(fact_a_id=a, fact_b_id=b, kind=kind, subtype=subtype or "")


def _mark_used(ctx: MatchContext, proposal: RelationProposal) -> None:
    if proposal.primary_fact_id:
        ctx.used_fact_ids.add(proposal.primary_fact_id)
    if proposal.secondary_fact_id:
        ctx.used_fact_ids.add(proposal.secondary_fact_id)


def _merge_edge_list(ctx: MatchContext, kind: str, pairs: Sequence[tuple[str, str]]) -> None:
    for a, b in pairs:
        if not a or not b:
            continue
        if kind == RelationKind.PAYMENT_MIRROR.value:
            ctx.accepted_mirrors.append(_edge(kind, a, b))
        elif kind == RelationKind.REFUND_OFFSET.value:
            ctx.accepted_platform_refunds.append(_edge(kind, a, b))
        elif kind == RelationKind.TRANSFER_PAIR.value:
            ctx.accepted_transfers.append(_edge(kind, a, b))


def bank_refund_seed_ids(facts: Sequence[FactView], *, blocked: set[str]) -> list[str]:
    """Positive bank rows with refund-ish text (diamond open seeds)."""
    out: list[str] = []
    for f in facts:
        if f.id in blocked:
            continue
        if source_group(f) != "bank":
            continue
        if f.signed_amount <= 0:
            continue
        if has_refund_signal(f.text) or any(
            tok in f.text for tok in ("退货", "退款", "消费退货")
        ):
            out.append(f.id)
    return out


def run_relation_phases(
    facts: Sequence[FactView],
    *,
    ctx: MatchContext | None = None,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
    fx_rate_provider: Callable[..., Decimal | None] | None = None,
    transfer_blocked_ids: set[str] | None = None,
    refund_blocked_ids: set[str] | None = None,
    merchant_refund_seed_ids: Sequence[str] | None = None,
    skip_platform_import_refund_seeds: bool = True,
) -> list[RelationProposal]:
    """Run Phase B → C → D0 diamond → D merchant/open refund matching.

    Parameters
    ----------
    seed_ids:
        Optional seeds for mirror/transfer candidate restriction (006 seed+window).
    merchant_refund_seed_ids:
        Facts that may initiate merchant/weak refund_offset (typically check seeds).
        Defaults to all active cash facts when omitted.
    transfer_blocked_ids / refund_blocked_ids:
        Fact ids already occupied by accepted (or linked) relations from DB / Phase A.
    ctx:
        Must be pre-seeded with persisted accepted mirrors + platform refunds for
        full-check diamond parity (008 seed policy).
    """
    ctx = ctx or MatchContext()
    if fx_rate_provider is not None:
        ctx.fx_rate_provider = fx_rate_provider
    if ctx.remaining_by_expense is None:
        ctx.remaining_by_expense = {}

    transfer_blocked = set(transfer_blocked_ids or ())
    refund_blocked = set(refund_blocked_ids or ())
    # Also block anything already in used_fact_ids from context
    transfer_blocked |= set(ctx.used_fact_ids)
    refund_blocked |= set(ctx.used_fact_ids)

    active = [f for f in facts if not getattr(f, "deleted", False)]
    by_id = {f.id: f for f in active}
    out: list[RelationProposal] = []

    # --- Phase B: payment_mirror ---
    occupied_mirror_fact_ids = {
        fact_id
        for edge in ctx.accepted_mirrors
        for fact_id in (edge.fact_a_id, edge.fact_b_id)
        if fact_id
    }
    mirror_props = match_payment_mirrors_greedy(
        active,
        aliases_by_tail=aliases_by_tail,
        seed_ids=seed_ids,
        index=index,
        occupied_fact_ids=occupied_mirror_fact_ids,
    )
    for p in mirror_props:
        out.append(p)
        if (
            p.status == RelationStatus.ACCEPTED.value
            and p.primary_fact_id
            and p.secondary_fact_id
        ):
            ctx.accepted_mirrors.append(
                _edge(
                    RelationKind.PAYMENT_MIRROR.value,
                    p.primary_fact_id,
                    p.secondary_fact_id,
                    p.subtype or "",
                )
            )
            _mark_used(ctx, p)

    # --- Phase C: transfer_pair ---
    transfer_props = match_transfer_pairs_phase_c(
        active,
        seed_ids=seed_ids,
        index=index,
        fx_rate_provider=ctx.fx_rate_provider,
    )
    for p in transfer_props:
        if p.primary_fact_id in transfer_blocked or (
            p.secondary_fact_id and p.secondary_fact_id in transfer_blocked
        ):
            continue
        out.append(p)
        if p.primary_fact_id:
            transfer_blocked.add(p.primary_fact_id)
        if p.secondary_fact_id:
            transfer_blocked.add(p.secondary_fact_id)
        if (
            p.status == RelationStatus.ACCEPTED.value
            and p.primary_fact_id
            and p.secondary_fact_id
        ):
            ctx.accepted_transfers.append(
                _edge(
                    RelationKind.TRANSFER_PAIR.value,
                    p.primary_fact_id,
                    p.secondary_fact_id,
                    p.subtype or "",
                )
            )
            _mark_used(ctx, p)

    # --- Phase D0: diamond ---
    bank_seeds = bank_refund_seed_ids(active, blocked=refund_blocked)
    diamond_props = match_diamond_bank_refunds(
        active,
        accepted_mirrors=ctx.mirror_pairs(),
        accepted_platform_refunds=ctx.platform_refund_pairs(),
        open_or_pending_bank_refund_ids=bank_seeds,
    )
    for p in diamond_props:
        out.append(p)
        if p.primary_fact_id:
            refund_blocked.add(p.primary_fact_id)
        if p.secondary_fact_id:
            refund_blocked.add(p.secondary_fact_id)
        if p.status == RelationStatus.ACCEPTED.value:
            _mark_used(ctx, p)

    # --- Phase D: merchant / weak / unpaired relation refund (seed-scoped like Application) ---
    if merchant_refund_seed_ids is None:
        refund_seeds = [f for f in active if f.fact_type == FactType.CASH.value]
    else:
        refund_seeds = [by_id[s] for s in merchant_refund_seed_ids if s in by_id]

    remaining = dict(ctx.remaining_by_expense)
    for seed in refund_seeds:
        if seed.id in refund_blocked:
            continue
        if seed.fact_type != FactType.CASH.value:
            continue
        if index is not None:
            others = [
                f
                for f in index.refund_candidates(seed)
                if f.id != seed.id and f.id not in refund_blocked
            ]
        else:
            others = [f for f in active if f.id != seed.id and f.id not in refund_blocked]
        prop = evaluate_refund_offset(seed, others, remaining_by_expense=remaining)
        if prop is None:
            continue
        out.append(prop)
        if prop.primary_fact_id:
            refund_blocked.add(prop.primary_fact_id)
        if prop.secondary_fact_id:
            refund_blocked.add(prop.secondary_fact_id)
        if prop.anchor_fact_id:
            refund_blocked.add(prop.anchor_fact_id)

    return out

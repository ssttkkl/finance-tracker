"""Relation recognition pipeline Phase B→D (008).

Phase A platform hard-key matching lives in refund.hard_key.match_phase_a_platform_refunds
(pure proposals). Application runs A then this pipeline for B–D, both via RelationProposal
persist — not a second write path inside hard_key.

Phase A (platform hard-key refunds) persists via Application (needs UoW/payload);
callers seed MatchContext with preloaded accepted edges + Phase A results, then
invoke :func:`run_relation_phases` as the **sole** domain matcher for B–D.
"""
from __future__ import annotations

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import replace
from decimal import Decimal
from typing import Any

from ft.domain.relations.core.routing import source_group
from ft.domain.relations.core.geometry import _abs_decimal, _as_decimal
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
from ft.domain.relations.mirror.match import (
    evaluate_payment_mirror,
    match_payment_mirrors_greedy,
)
from ft.domain.relations.core.mirror_graph import build_mirror_components, canonical_mirror_fact
from ft.domain.relations.core.keys import stable_fact_order_key
from ft.domain.relations.refund.diamond import match_diamond_bank_refunds
from ft.domain.relations.refund.match import evaluate_refund_offset
from ft.domain.relations.refund.signals import (
    DefaultRefundTextGates,
    has_refund_signal_for_fact,
)
from ft.domain.relations.transfer.match import match_transfer_pairs_phase_c


def _edge(kind: str, a: str, b: str, subtype: str = "") -> RelationEdge:
    return RelationEdge(fact_a_id=a, fact_b_id=b, kind=kind, subtype=subtype or "")


def match_canonical_payment_mirrors(
    facts: Sequence[FactView],
    *,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    occupied_fact_ids: set[str] | None = None,
    blocked_pairs: set[frozenset[str]] | None = None,
) -> list[RelationProposal]:
    """Run the canonical all-fact payment mirror matcher.

    The application layer uses this entry point during reconciliation so the
    complete relation matcher remains owned by the domain pipeline.
    """
    index = FactCandidateIndex(
        facts,
        source_group=source_group,
        refund_gates=DefaultRefundTextGates(),
    )
    return match_payment_mirrors_greedy(
        facts,
        aliases_by_tail=aliases_by_tail,
        account_identifiers_by_value=account_identifiers_by_value,
        index=index,
        occupied_fact_ids=occupied_fact_ids,
        blocked_pairs=blocked_pairs,
    )


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


def _expand_refund_blocked_through_mirrors(
    blocked: set[str], mirror_pairs: Sequence[tuple[str, str]],
) -> None:
    """Block every mirror-equivalent fact of an occupied refund leg."""
    adjacency: dict[str, set[str]] = {}
    for left, right in mirror_pairs:
        if not left or not right:
            continue
        adjacency.setdefault(left, set()).add(right)
        adjacency.setdefault(right, set()).add(left)

    pending = list(blocked)
    while pending:
        fact_id = pending.pop()
        for mirror_id in adjacency.get(fact_id, ()):
            if mirror_id in blocked:
                continue
            blocked.add(mirror_id)
            pending.append(mirror_id)


def _mirror_components_by_fact(
    facts: Sequence[FactView],
    mirror_pairs: Sequence[tuple[str, str]],
) -> dict[str, frozenset[str]]:
    if not mirror_pairs:
        return {}
    components_by_fact: dict[str, frozenset[str]] = {}
    for component in build_mirror_components((fact.id for fact in facts), mirror_pairs):
        frozen = frozenset(component)
        for fact_id in frozen:
            components_by_fact[fact_id] = frozen
    return components_by_fact


def _refund_pair_facts(
    edge: RelationEdge, by_id: Mapping[str, FactView],
) -> tuple[FactView, FactView] | None:
    left, right = by_id.get(edge.fact_a_id), by_id.get(edge.fact_b_id)
    if left is None or right is None:
        return None
    if left.signed_amount < 0 < right.signed_amount:
        return left, right
    if right.signed_amount < 0 < left.signed_amount:
        return right, left
    return None


def _refund_pair_alignment_score(
    expense_proposal: RelationProposal,
    refund_proposal: RelationProposal,
) -> tuple[int, int]:
    """Rank a complete cross-source refund pair without using database ids."""
    strong_rules = {
        "payment_mirror.platform_bank.exact.time10.cross.v2": 4,
        "payment_mirror.platform_bank.short_window.text.unique.v3": 3,
        "payment_mirror.same_account.exact.business_day.v1": 3,
        "payment_mirror.bank_date_only.v1": 3,
        "payment_mirror.refund_dual_source.v1": 4,
    }
    rank = strong_rules.get(expense_proposal.rule_id or "", 1)
    rank += strong_rules.get(refund_proposal.rule_id or "", 1)
    elapsed = (
        expense_proposal.evidence.time_delta_seconds
        + refund_proposal.evidence.time_delta_seconds
    )
    return rank, -elapsed


def _align_refund_pair_mirrors(
    facts: Sequence[FactView],
    ctx: MatchContext,
    *,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    index: FactCandidateIndex | None = None,
) -> tuple[list[RelationProposal], set[str]]:
    """Prefer corresponding expense/refund mirrors for known refund pairs.

    A generic same-day mirror matcher cannot distinguish two equal expenses. If
    one of them is already part of a refund pair, independently matching the
    other expense can make two refund edges converge on one projection root.
    This pre-pass uses only complete, uniquely best cross-source refund-pair
    candidates; unresolved candidates remain for the normal matcher/review.
    """
    by_id = {str(fact.id): fact for fact in facts if not fact.deleted}
    pairs: list[tuple[FactView, FactView]] = []
    for edge in ctx.accepted_platform_refunds:
        pair = _refund_pair_facts(edge, by_id)
        if pair is not None:
            pairs.append(pair)

    platform_pairs: list[tuple[FactView, FactView]] = []
    bank_pairs: list[tuple[FactView, FactView]] = []
    for expense, refund in pairs:
        groups = {source_group(expense), source_group(refund)}
        if len(groups) != 1:
            continue
        if source_group(expense) == "platform":
            platform_pairs.append((expense, refund))
        elif source_group(expense) == "bank":
            bank_pairs.append((expense, refund))

    candidates: list[tuple[
        tuple[int, int],
        tuple[str, str],
        tuple[str, str],
        RelationProposal,
        RelationProposal,
    ]] = []
    bank_pairs_by_expense: dict[str, list[tuple[FactView, FactView]]] = defaultdict(list)
    for bank_pair in bank_pairs:
        bank_pairs_by_expense[bank_pair[0].id].append(bank_pair)
    for platform_expense, platform_refund in platform_pairs:
        if index is None:
            candidate_bank_pairs = bank_pairs
        else:
            bank_expense_ids = {
                fact.id for fact in index.mirror_candidates(platform_expense)
            }
            bank_refund_ids = {
                fact.id for fact in index.mirror_candidates(platform_refund)
            }
            candidate_bank_pairs = [
                (bank_expense, bank_refund)
                for bank_expense_id in bank_expense_ids
                for bank_expense, bank_refund in bank_pairs_by_expense.get(bank_expense_id, ())
                if bank_refund.id in bank_refund_ids
            ]
        for bank_expense, bank_refund in candidate_bank_pairs:
            expense_proposal = evaluate_payment_mirror(
                platform_expense,
                [bank_expense],
                aliases_by_tail=aliases_by_tail,
                account_identifiers_by_value=account_identifiers_by_value,
            )
            refund_proposal = evaluate_payment_mirror(
                platform_refund,
                [bank_refund],
                aliases_by_tail=aliases_by_tail,
                account_identifiers_by_value=account_identifiers_by_value,
            )
            if (
                expense_proposal is None
                or refund_proposal is None
                or expense_proposal.status != RelationStatus.ACCEPTED.value
                or refund_proposal.status != RelationStatus.ACCEPTED.value
            ):
                continue
            candidates.append((
                _refund_pair_alignment_score(expense_proposal, refund_proposal),
                (platform_expense.id, platform_refund.id),
                (bank_expense.id, bank_refund.id),
                expense_proposal,
                refund_proposal,
            ))

    def unique_best(
        items: Sequence[tuple[
            tuple[int, int], tuple[str, str], tuple[str, str],
            RelationProposal, RelationProposal,
        ]],
        key_index: int,
    ) -> set[int]:
        grouped: dict[tuple[str, str], list[tuple[int, tuple[int, int]]]] = defaultdict(list)
        for index, candidate in enumerate(items):
            grouped[candidate[key_index]].append((index, candidate[0]))
        selected: set[int] = set()
        for values in grouped.values():
            best_score = max(score for _index, score in values)
            best = [index for index, score in values if score == best_score]
            if len(best) == 1:
                selected.add(best[0])
        return selected

    platform_best = unique_best(candidates, 1)
    bank_best = unique_best(candidates, 2)
    selected = [
        candidate for index, candidate in enumerate(candidates)
        if index in platform_best and index in bank_best
    ]
    selected.sort(key=lambda item: (item[1], item[2]))

    existing = {
        frozenset((edge.fact_a_id, edge.fact_b_id))
        for edge in ctx.accepted_mirrors
    }
    neighbors: dict[str, set[str]] = defaultdict(set)
    for edge in ctx.accepted_mirrors:
        if edge.fact_a_id and edge.fact_b_id:
            neighbors[edge.fact_a_id].add(edge.fact_b_id)
            neighbors[edge.fact_b_id].add(edge.fact_a_id)
    candidate_fact_ids = {
        fact_id
        for _score, platform_pair, bank_pair, _expense, _refund in candidates
        for fact_id in (*platform_pair, *bank_pair)
    }
    aligned_fact_ids: set[str] = set()
    out: list[RelationProposal] = []
    for _score, _platform_pair, _bank_pair, expense_proposal, refund_proposal in selected:
        proposals = (expense_proposal, refund_proposal)
        if any(
            frozenset((proposal.primary_fact_id, proposal.secondary_fact_id)) not in existing
            and (
                neighbors.get(proposal.primary_fact_id, set())
                - {proposal.secondary_fact_id}
                or neighbors.get(proposal.secondary_fact_id, set())
                - {proposal.primary_fact_id}
            )
            for proposal in proposals
        ):
            continue
        aligned_fact_ids.update((*_platform_pair, *_bank_pair))
        for proposal in proposals:
            left, right = proposal.primary_fact_id, proposal.secondary_fact_id
            if not left or not right or frozenset((left, right)) in existing:
                continue
            out.append(proposal)
            existing.add(frozenset((left, right)))
            neighbors[left].add(right)
            neighbors[right].add(left)
            ctx.accepted_mirrors.append(
                _edge(RelationKind.PAYMENT_MIRROR.value, left, right, proposal.subtype or "")
            )
            _mark_used(ctx, proposal)
    return out, candidate_fact_ids - aligned_fact_ids


def _demote_overlapping_phase_a_refunds(
    facts: Sequence[FactView],
    phase_a: Sequence[RelationProposal],
    accepted_relations: Sequence[Mapping[str, Any]],
    mirror_pairs: Sequence[tuple[str, str]],
) -> tuple[RelationProposal, ...]:
    """Keep hard-key evidence, but cap refunds after mirrors reveal one event.

    Phase A runs before Phase B, so a platform expense/refund pair can be
    accepted before the platform expense is shown to mirror an already
    refunded bank expense.  The two source pairs are then one projection
    event, and persisting both refunds would overdraw that event.  Treat the
    previously accepted relations as consumed event-level amount and demote
    only the new Phase A proposal that would exceed the cap.
    """
    if not mirror_pairs:
        return tuple(phase_a)

    by_id = {str(fact.id): fact for fact in facts}

    def group_for(fact_id: str | None) -> str:
        fact = by_id.get(str(fact_id or ""))
        return source_group(fact) if fact is not None else ""

    components_by_fact = _mirror_components_by_fact(facts, mirror_pairs)
    event_caps: dict[frozenset[str], Decimal] = {}
    for component in set(components_by_fact.values()):
        expenses = [
            by_id[fact_id]
            for fact_id in component
            if fact_id in by_id and by_id[fact_id].signed_amount < 0
        ]
        if expenses:
            event_caps[component] = min(
                _abs_decimal(fact.signed_amount) for fact in expenses
            )

    consumed: dict[frozenset[str], Decimal] = {}
    phase_a_platform_refund_bank_ids: set[str] = set()
    phase_a_platform_refund_components = {
        components_by_fact.get(str(proposal.secondary_fact_id))
        for proposal in phase_a
        if (
            proposal.status == RelationStatus.ACCEPTED.value
            and proposal.kind == RelationKind.REFUND_OFFSET.value
            and proposal.primary_fact_id
            and proposal.secondary_fact_id
            and group_for(proposal.primary_fact_id) == "platform"
            and group_for(proposal.secondary_fact_id) == "platform"
        )
    }
    phase_a_platform_refund_components.discard(None)
    for proposal in phase_a:
        if (
            proposal.status != RelationStatus.ACCEPTED.value
            or proposal.kind != RelationKind.REFUND_OFFSET.value
            or not proposal.primary_fact_id
            or not proposal.secondary_fact_id
            or group_for(proposal.primary_fact_id) != "platform"
            or group_for(proposal.secondary_fact_id) != "platform"
        ):
            continue
        platform_refund = by_id.get(str(proposal.secondary_fact_id))
        if platform_refund is None:
            continue
        for relation in accepted_relations:
            if (
                relation.get("kind") != RelationKind.REFUND_OFFSET.value
                or relation.get("status") != RelationStatus.ACCEPTED.value
            ):
                continue
            bank_refund = by_id.get(str(relation.get("secondary_fact_id") or ""))
            bank_expense = by_id.get(str(relation.get("primary_fact_id") or ""))
            if (
                bank_refund is None
                or bank_expense is None
                or source_group(bank_expense) != "bank"
                or source_group(bank_refund) != "bank"
            ):
                continue
            mirror = evaluate_payment_mirror(platform_refund, [bank_refund])
            if mirror is not None and mirror.status == RelationStatus.ACCEPTED.value:
                phase_a_platform_refund_bank_ids.add(str(bank_refund.id))

    for relation in accepted_relations:
        if (
            relation.get("kind") != RelationKind.REFUND_OFFSET.value
            or relation.get("status") != RelationStatus.ACCEPTED.value
        ):
            continue
        primary_id = str(relation.get("primary_fact_id") or "")
        secondary_id = str(relation.get("secondary_fact_id") or "")
        primary = by_id.get(primary_id)
        refund = by_id.get(secondary_id)
        component = components_by_fact.get(primary_id)
        if (
            component is None
            or primary is None
            or refund is None
            or primary.signed_amount >= 0
            or refund.signed_amount <= 0
        ):
            continue
        # A later platform hard-key pair is stronger than a provisional bank
        # refund.  Let the application supersede that bank edge after the
        # corresponding payment mirrors are persisted, instead of demoting
        # the canonical platform proposal back to review.
        if (
            group_for(primary.id) == "bank"
            and secondary_id in phase_a_platform_refund_bank_ids
        ) or (
            group_for(primary.id) == "bank"
            and components_by_fact.get(secondary_id) is not None
            and components_by_fact.get(secondary_id) in phase_a_platform_refund_components
        ):
            continue
        consumed[component] = consumed.get(component, Decimal("0")) + _abs_decimal(
            refund.signed_amount
        )

    adjusted: list[RelationProposal] = []
    for proposal in phase_a:
        if (
            proposal.status != RelationStatus.ACCEPTED.value
            or proposal.kind != RelationKind.REFUND_OFFSET.value
            or not proposal.primary_fact_id
            or not proposal.secondary_fact_id
        ):
            adjusted.append(proposal)
            continue
        component = components_by_fact.get(proposal.primary_fact_id)
        refund = by_id.get(str(proposal.secondary_fact_id))
        cap = event_caps.get(component) if component is not None else None
        if component is None or cap is None or refund is None or refund.signed_amount <= 0:
            adjusted.append(proposal)
            continue
        refund_amount = proposal.refund_amount
        if refund_amount <= 0:
            refund_amount = _abs_decimal(refund.signed_amount)
        used = consumed.get(component, Decimal("0"))
        if used + refund_amount > cap:
            adjusted.append(
                replace(proposal, status=RelationStatus.PENDING_REVIEW.value)
            )
            continue
        consumed[component] = used + refund_amount
        adjusted.append(proposal)
    return tuple(adjusted)


def _share_refund_remaining_across_mirror_events(
    facts: Sequence[FactView],
    mirror_components_by_fact: Mapping[str, frozenset[str]],
    remaining_by_expense: Mapping[str, Decimal],
) -> dict[str, Decimal]:
    """Project member-level balances onto accepted mirror economic events.

    Accepted payment mirrors describe one expense through multiple source rows.
    A prior partial refund may have been persisted against any member, so the
    remaining balance must be shared by every member before Phase D evaluates
    another refund.  Summing member-level consumed amounts also fails closed
    for historical relations that targeted different members of the same event.
    """
    shared = dict(remaining_by_expense)
    if not mirror_components_by_fact:
        return shared
    by_id = {fact.id: fact for fact in facts}
    visited: set[frozenset[str]] = set()
    for component in mirror_components_by_fact.values():
        if component in visited:
            continue
        visited.add(component)
        expenses = [
            by_id[fact_id]
            for fact_id in component
            if fact_id in by_id and by_id[fact_id].signed_amount < 0
        ]
        if len(expenses) < 2:
            continue
        event_amount = min(_abs_decimal(fact.signed_amount) for fact in expenses)
        consumed = Decimal("0")
        for fact in expenses:
            fact_amount = _abs_decimal(fact.signed_amount)
            fact_remaining = _as_decimal(shared.get(fact.id, fact_amount))
            consumed += max(Decimal("0"), fact_amount - fact_remaining)
        event_remaining = max(Decimal("0"), event_amount - consumed)
        for fact in expenses:
            shared[fact.id] = event_remaining
    return shared


def bank_refund_seed_ids(facts: Sequence[FactView], *, blocked: set[str]) -> list[str]:
    """Positive bank rows with refund-ish text (diamond open seeds)."""
    out: list[str] = []
    for f in sorted(facts, key=stable_fact_order_key):
        if f.id in blocked:
            continue
        if source_group(f) != "bank":
            continue
        if f.signed_amount <= 0:
            continue
        if has_refund_signal_for_fact(f):
            out.append(f.id)
    return out


def run_relation_phases(
    facts: Sequence[FactView],
    *,
    ctx: MatchContext | None = None,
    seed_ids: Sequence[str] | None = None,
    index: FactCandidateIndex | None = None,
    aliases_by_tail: Mapping[str, Sequence[str]] | None = None,
    account_identifiers_by_value: Mapping[str, Sequence[str]] | None = None,
    transfer_blocked_ids: set[str] | None = None,
    refund_blocked_ids: set[str] | None = None,
    merchant_refund_seed_ids: Sequence[str] | None = None,
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
    if ctx.remaining_by_expense is None:
        ctx.remaining_by_expense = {}

    transfer_blocked = set(transfer_blocked_ids or ())
    refund_blocked = set(refund_blocked_ids or ())
    # Also block anything already in used_fact_ids from context
    transfer_blocked |= set(ctx.used_fact_ids)
    refund_blocked |= set(ctx.used_fact_ids)

    active = sorted(
        (f for f in facts if not getattr(f, "deleted", False)),
        key=stable_fact_order_key,
    )
    by_id = {f.id: f for f in active}
    out: list[RelationProposal] = []

    # --- Phase B: payment_mirror ---
    aligned_mirrors, blocked_refund_mirror_ids = _align_refund_pair_mirrors(
        active,
        ctx,
        aliases_by_tail=aliases_by_tail,
        account_identifiers_by_value=account_identifiers_by_value,
        index=index,
    )
    out.extend(aligned_mirrors)
    occupied_mirror_fact_ids = {
        fact_id
        for edge in ctx.accepted_mirrors
        for fact_id in (edge.fact_a_id, edge.fact_b_id)
        if fact_id
    }
    occupied_mirror_fact_ids.update(blocked_refund_mirror_ids)
    mirror_props = match_payment_mirrors_greedy(
        active,
        aliases_by_tail=aliases_by_tail,
        account_identifiers_by_value=account_identifiers_by_value,
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

    # A refund already paired on one source must also occupy every mirror of
    # that refund. Otherwise the same real-world refund can receive a second
    # refund_offset from another source, which makes projection ambiguous.
    _expand_refund_blocked_through_mirrors(refund_blocked, ctx.mirror_pairs())
    mirror_components_by_fact = _mirror_components_by_fact(active, ctx.mirror_pairs())

    # --- Phase C: transfer_pair ---
    transfer_props = match_transfer_pairs_phase_c(
        active,
        seed_ids=seed_ids,
        index=index,
        account_identifiers_by_value=account_identifiers_by_value,
        card_tails_by_value=aliases_by_tail,
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
        _expand_refund_blocked_through_mirrors(refund_blocked, ctx.mirror_pairs())

    # --- Phase D: merchant / weak / unpaired relation refund (seed-scoped like Application) ---
    if merchant_refund_seed_ids is None:
        refund_seeds = [f for f in active if f.fact_type == FactType.CASH.value]
    else:
        refund_seeds = [by_id[s] for s in merchant_refund_seed_ids if s in by_id]

    remaining = _share_refund_remaining_across_mirror_events(
        active, mirror_components_by_fact, ctx.remaining_by_expense,
    )
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
        event_ids: dict[str, str] = {}
        for fact in others:
            component_ids = mirror_components_by_fact.get(fact.id, frozenset({fact.id}))
            component_facts = [by_id[item] for item in component_ids if item in by_id]
            representative = canonical_mirror_fact(component_facts)
            if representative is None:
                representative = min(component_facts, key=stable_fact_order_key)
            event_ids[fact.id] = representative.id
        prop = evaluate_refund_offset(
            seed,
            others,
            remaining_by_expense=remaining,
            candidate_event_ids=event_ids,
        )
        if prop is None:
            continue
        out.append(prop)
        if (
            prop.kind == RelationKind.REFUND_OFFSET.value
            and prop.status == RelationStatus.ACCEPTED.value
            and prop.primary_fact_id
            and prop.secondary_fact_id
        ):
            # A consumed refund fact is always occupied, but a partially
            # refunded expense remains eligible until its Decimal balance is
            # exhausted.  This also applies to later scans via the persisted
            # remaining map prepared by the application layer.
            refund_blocked.add(prop.secondary_fact_id)
            if prop.anchor_fact_id:
                refund_blocked.add(prop.anchor_fact_id)
            refund_amount = _as_decimal(
                (prop.evidence.extras or {}).get("refund_amount")
            )
            component_ids = mirror_components_by_fact.get(
                prop.primary_fact_id, frozenset({prop.primary_fact_id}),
            )
            event_remaining = remaining.get(
                prop.primary_fact_id,
                _abs_decimal(by_id[prop.primary_fact_id].signed_amount)
                if prop.primary_fact_id in by_id else Decimal("0"),
            ) - refund_amount
            for expense_id in component_ids:
                if expense_id in by_id and by_id[expense_id].signed_amount < 0:
                    remaining[expense_id] = event_remaining
            if event_remaining <= 0:
                refund_blocked.update(component_ids)
        else:
            if prop.primary_fact_id:
                refund_blocked.add(prop.primary_fact_id)
            if prop.secondary_fact_id:
                refund_blocked.add(prop.secondary_fact_id)
            if prop.anchor_fact_id:
                refund_blocked.add(prop.anchor_fact_id)
        _expand_refund_blocked_through_mirrors(refund_blocked, ctx.mirror_pairs())

    return out

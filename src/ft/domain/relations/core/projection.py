from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field
from decimal import Decimal
from typing import Any, Mapping, Sequence

from ft.domain.relations.core.geometry import _abs_decimal, _as_decimal
from ft.domain.relations.core.keys import is_open_leg_relation
from ft.domain.relations.core.types import FactType, FactView, RelationKind, RelationStatus
from ft.domain.relations.core.mirror_graph import build_mirror_components, canonical_mirror_fact
from ft.domain.relations.core.record_types import is_refund_in

@dataclass(frozen=True)
class ProjectionResult:
    balances: dict[tuple[str, str], Decimal]
    expenses: dict[str, Decimal]
    income: dict[str, Decimal]
    excluded_transfer_fact_ids: frozenset[str] = frozenset()
    mirror_groups: tuple[frozenset[str], ...] = ()
    net_expense_by_group: dict[str, Decimal] = field(default_factory=dict)


def project_balances_and_pnl(
    facts: Sequence[FactView],
    accepted_relations: Sequence[Mapping[str, Any]],
) -> ProjectionResult:
    """Balance = all active facts; P&L: mirror → exclude transfer → refund_offset.

    `open_leg` rows (null secondary / open_leg evidence) never affect nets even if
    status is incorrectly accepted — FR-042/033.
    """
    active = [f for f in facts if not f.deleted]
    balances: dict[tuple[str, str], Decimal] = defaultdict(lambda: Decimal("0"))
    for fact in active:
        key = (fact.account_name or fact.account_id, str(fact.currency).upper())
        balances[key] += fact.signed_amount

    def _bilateral(rel: Mapping[str, Any]) -> bool:
        if is_open_leg_relation(rel):
            return False
        secondary = rel.get("secondary_fact_id")
        primary = rel.get("primary_fact_id")
        return bool(primary) and bool(secondary)

    accepted = [
        r for r in accepted_relations
        if r.get("status") == RelationStatus.ACCEPTED.value and _bilateral(r)
    ]
    mirror_pairs = [
        (r["primary_fact_id"], r["secondary_fact_id"])
        for r in accepted
        if r.get("kind") == RelationKind.PAYMENT_MIRROR.value
    ]
    transfer_ids: set[str] = set()
    for rel in accepted:
        if rel.get("kind") == RelationKind.TRANSFER_PAIR.value:
            transfer_ids.add(rel["primary_fact_id"])
            transfer_ids.add(rel["secondary_fact_id"])
    refunds = [r for r in accepted if r.get("kind") == RelationKind.REFUND_OFFSET.value]

    fact_by_id = {f.id: f for f in active}
    components = build_mirror_components(fact_by_id.keys(), mirror_pairs)
    component_of: dict[str, frozenset[str]] = {}
    group_sets: list[frozenset[str]] = []
    for comp in components:
        frozen = frozenset(comp)
        group_sets.append(frozen)
        for fid in frozen:
            component_of[fid] = frozen

    expenses: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    income: dict[str, Decimal] = defaultdict(lambda: Decimal("0"))
    net_expense_by_group: dict[str, Decimal] = {}
    seen_groups: set[frozenset[str]] = set()
    refund_secondary_ids = {rel["secondary_fact_id"] for rel in refunds}

    for fact in active:
        if fact.id in transfer_ids:
            continue
        group = component_of.get(fact.id, frozenset({fact.id}))
        if group in seen_groups:
            continue
        seen_groups.add(group)
        members = [fact_by_id[i] for i in group if i in fact_by_id]
        if not members:
            continue
        if any(m.id in transfer_ids for m in members):
            continue
        if group <= refund_secondary_ids:
            continue
        canonical = canonical_mirror_fact(members) or members[0]
        currency = str(canonical.currency).upper()
        amount = canonical.signed_amount
        refund_total = Decimal("0")
        for rel in refunds:
            expense_id = rel["primary_fact_id"]
            refund_id = rel["secondary_fact_id"]
            if expense_id in group:
                refund_fact = fact_by_id.get(refund_id)
                if refund_fact is not None:
                    refund_total += _abs_decimal(refund_fact.signed_amount)
        if amount < 0:
            net = _abs_decimal(amount) - refund_total
            if net < 0:
                net = Decimal("0")
            expenses[currency] += net
            net_expense_by_group[canonical.id] = net
        elif amount > 0 and canonical.id not in refund_secondary_ids:
            if not is_refund_in(canonical):
                income[currency] += amount

    return ProjectionResult(
        balances=dict(balances),
        expenses=dict(expenses),
        income=dict(income),
        excluded_transfer_fact_ids=frozenset(transfer_ids),
        mirror_groups=tuple(group_sets),
        net_expense_by_group=net_expense_by_group,
    )

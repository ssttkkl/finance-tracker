"""收支投影的纯领域模型和确定性构建规则。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Iterable

from ft.domain.decimal import exact_decimal


RULES_VERSION = "cash-projection-v1"
_RELATION_KINDS = frozenset({"payment_mirror", "refund_offset", "transfer_pair"})
_TRANSFER_SUBTYPES = frozenset({
    "", "ordinary_transfer", "cross_currency_remittance", "credit_repayment", "currency_exchange", "bank_security_transfer", "balance_adjustment",
})


class CashProjectionError(ValueError):
    """不包含原始财务数据的稳定投影领域错误。"""

    def __init__(self, code: str) -> None:
        self.code = code
        super().__init__(code)


class EconomicType(str, Enum):
    EXPENSE = "expense"
    INCOME = "income"
    INTERNAL_TRANSFER = "internal_transfer"


class ProjectionComposition(str, Enum):
    PAYMENT_MIRROR = "payment_mirror"
    REFUND_OFFSET = "refund_offset"
    TRANSFER_PAIR = "transfer_pair"


@dataclass(frozen=True)
class CashProjectionFact:
    id: int
    account_id: int
    occurred_at: datetime
    amount: Decimal
    currency: str
    counterparty: str
    category: str
    note: str
    source_type: str | None
    record_id: str
    funding_relation_id: int | None = None

    def __post_init__(self) -> None:
        try:
            amount = exact_decimal(self.amount, "amount")
        except ValueError as exc:
            raise CashProjectionError("projection.invalid_fact") from exc
        if self.id <= 0 or self.account_id <= 0 or self.occurred_at.tzinfo is None:
            raise CashProjectionError("projection.invalid_fact")
        currency = self.currency.upper()
        if len(currency) != 3 or not currency.isalpha():
            raise CashProjectionError("projection.invalid_fact")
        object.__setattr__(self, "amount", amount)
        object.__setattr__(self, "currency", currency)


@dataclass(frozen=True)
class ProjectionRelation:
    id: int
    kind: str
    primary_fact_id: int
    secondary_fact_id: int
    status: str = "accepted"
    subtype: str = ""

    def __post_init__(self) -> None:
        if self.kind not in _RELATION_KINDS or self.id <= 0 or self.primary_fact_id <= 0 or self.secondary_fact_id <= 0:
            raise CashProjectionError("projection.invalid_relation")
        if self.kind == "transfer_pair" and self.subtype not in _TRANSFER_SUBTYPES:
            raise CashProjectionError("projection.invalid_relation")


@dataclass(frozen=True)
class CashProjection:
    projection_id: str
    primary_record: CashProjectionFact
    net_amount: Decimal
    economic_type: EconomicType
    visible: bool
    hidden_reason: str | None
    transfer_subtype: str | None
    member_ids: tuple[int, ...]
    members: tuple[tuple[CashProjectionFact, tuple[str, ...]], ...]
    relations: tuple[ProjectionRelation, ...]
    compositions: tuple[str, ...]
    funding_relation_id: int | None = None

    @property
    def occurred_at(self) -> datetime:
        return self.primary_record.occurred_at


@dataclass(frozen=True)
class CashProjectionBuild:
    projections: tuple[CashProjection, ...]
    rules_version: str = RULES_VERSION

    @property
    def member_ids(self) -> frozenset[int]:
        return frozenset(item for projection in self.projections for item in projection.member_ids)


def _components(ids: Iterable[int], relations: tuple[ProjectionRelation, ...]) -> list[list[int]]:
    parent = {item: item for item in ids}

    def find(item: int) -> int:
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    def union(left: int, right: int) -> None:
        left, right = find(left), find(right)
        if left != right:
            parent[max(left, right)] = min(left, right)

    for relation in relations:
        union(relation.primary_fact_id, relation.secondary_fact_id)
    groups: dict[int, list[int]] = {}
    for item in sorted(parent):
        groups.setdefault(find(item), []).append(item)
    return list(groups.values())


def _validate_group(group: list[int], facts: dict[int, CashProjectionFact], relations: tuple[ProjectionRelation, ...]) -> CashProjection:
    contained = tuple(sorted((item for item in relations if item.primary_fact_id in group and item.secondary_fact_id in group), key=lambda item: item.id))
    mirror_relations = tuple(item for item in contained if item.kind == "payment_mirror")
    for relation in mirror_relations:
        primary, secondary = facts[relation.primary_fact_id], facts[relation.secondary_fact_id]
        if primary.amount != secondary.amount or primary.currency != secondary.currency or (primary.amount < 0) != (secondary.amount < 0):
            raise CashProjectionError("projection.invalid_relation")
    transfer_relations = tuple(item for item in contained if item.kind == "transfer_pair")
    for relation in transfer_relations:
        primary, secondary = facts[relation.primary_fact_id], facts[relation.secondary_fact_id]
        if primary.amount * secondary.amount >= 0:
            raise CashProjectionError("projection.invalid_relation")
        if relation.subtype == "currency_exchange":
            if primary.currency == secondary.currency:
                raise CashProjectionError("projection.invalid_relation")
        elif primary.currency == secondary.currency and abs(primary.amount) != abs(secondary.amount):
            raise CashProjectionError("projection.invalid_relation")
    parent = {item: item for item in group}

    def find(item: int) -> int:
        while parent[item] != item:
            parent[item] = parent[parent[item]]
            item = parent[item]
        return item

    for relation in mirror_relations:
        left, right = find(relation.primary_fact_id), find(relation.secondary_fact_id)
        if left != right:
            parent[max(left, right)] = min(left, right)
    mirror_groups: dict[int, list[int]] = {}
    for item in group:
        mirror_groups.setdefault(find(item), []).append(item)
    canonical: dict[int, int] = {}
    for members in mirror_groups.values():
        incoming = {item: 0 for item in members}
        adjacency = {item: [] for item in members}
        for relation in mirror_relations:
            if relation.primary_fact_id in incoming and relation.secondary_fact_id in incoming:
                incoming[relation.secondary_fact_id] += 1
                adjacency[relation.primary_fact_id].append(relation.secondary_fact_id)
        roots = [item for item in members if incoming[item] == 0]
        if len(roots) != 1:
            raise CashProjectionError("projection.invalid_relation")
        root, reached, stack = roots[0], set(), [roots[0]]
        while stack:
            item = stack.pop()
            if item in reached:
                raise CashProjectionError("projection.invalid_relation")
            reached.add(item)
            stack.extend(adjacency[item])
        if reached != set(members):
            raise CashProjectionError("projection.invalid_relation")
        canonical.update({item: root for item in members})

    logical: list[tuple[ProjectionRelation, int, int]] = []
    seen_edges: set[tuple[str, int, int, str]] = set()
    for relation in contained:
        if relation.kind == "payment_mirror":
            continue
        primary, secondary = canonical[relation.primary_fact_id], canonical[relation.secondary_fact_id]
        key = (relation.kind, primary, secondary, relation.subtype)
        if primary == secondary or key in seen_edges:
            continue
        seen_edges.add(key)
        logical.append((relation, primary, secondary))
    nodes = sorted(set(canonical.values()))
    incoming = {item: 0 for item in nodes}
    adjacency: dict[int, list[int]] = {item: [] for item in nodes}
    for _relation, primary, secondary in logical:
        incoming[secondary] += 1
        adjacency[primary].append(secondary)
    roots = [item for item in nodes if incoming[item] == 0]
    if len(roots) != 1:
        raise CashProjectionError("projection.invalid_relation")
    root_id = roots[0]
    reached, stack = set(), [root_id]
    while stack:
        item = stack.pop()
        if item in reached:
            raise CashProjectionError("projection.invalid_relation")
        reached.add(item)
        stack.extend(adjacency[item])
    if reached != set(nodes):
        raise CashProjectionError("projection.invalid_relation")

    root = facts[root_id]
    if root.funding_relation_id is not None and len(group) != 1:
        raise CashProjectionError("projection.invalid_relation")
    transfer_relations = tuple(item for item, _primary, _secondary in logical if item.kind == "transfer_pair")
    refund_relations = tuple(item for item, _primary, _secondary in logical if item.kind == "refund_offset")
    if transfer_relations and refund_relations:
        raise CashProjectionError("projection.invalid_relation")
    roles: dict[int, set[str]] = {item: set() for item in group}
    roles[root_id].add("root")
    for relation in mirror_relations:
        roles[relation.secondary_fact_id].add("mirror")
    for relation in refund_relations:
        roles[relation.secondary_fact_id].add("refund")
    for relation in transfer_relations:
        roles[relation.primary_fact_id].add("transfer")
        roles[relation.secondary_fact_id].add("transfer")

    if root.funding_relation_id is not None:
        subtype = "bank_security_transfer"
        economic_type, amount, visible, hidden_reason = EconomicType.INTERNAL_TRANSFER, Decimal("0"), True, None
    elif transfer_relations:
        subtype = transfer_relations[0].subtype or "ordinary_transfer"
        if any(item.subtype not in {"", subtype} for item in transfer_relations):
            raise CashProjectionError("projection.invalid_relation")
        economic_type, amount, visible, hidden_reason = EconomicType.INTERNAL_TRANSFER, Decimal("0"), True, None
    elif refund_relations:
        refund_inputs = tuple(
            (facts[primary], facts[secondary])
            for item, primary, secondary in logical
            if item.kind == "refund_offset"
        )
        zero_amount_refund = all(
            expense.amount == 0 and refund.amount == 0 and expense.currency == refund.currency
            for expense, refund in refund_inputs
        )
        if root.amount >= 0 and not zero_amount_refund:
            raise CashProjectionError("projection.invalid_relation")
        refunded = Decimal("0")
        for expense, refund in refund_inputs:
            if not zero_amount_refund and (
                expense.amount >= 0 or refund.amount <= 0 or expense.currency != refund.currency
            ):
                raise CashProjectionError("projection.invalid_relation")
            refunded += refund.amount
        if refunded > -root.amount:
            raise CashProjectionError("projection.invalid_relation")
        amount = root.amount + refunded
        economic_type = EconomicType.EXPENSE
        visible = amount != 0
        hidden_reason = None if visible else "full_refund"
        subtype = None
    elif root.amount < 0:
        economic_type, amount, visible, hidden_reason, subtype = EconomicType.EXPENSE, root.amount, True, None, None
    elif root.amount > 0:
        economic_type, amount, visible, hidden_reason, subtype = EconomicType.INCOME, root.amount, True, None, None
    else:
        economic_type, amount, visible, hidden_reason, subtype = EconomicType.INTERNAL_TRANSFER, Decimal("0"), False, "balance_adjustment", "balance_adjustment"

    members = tuple((facts[item], tuple(sorted(roles[item]))) for item in sorted(group, key=lambda item: (0 if item == root_id else 1, facts[item].occurred_at, item)))
    return CashProjection(
        projection_id=f"cash:{root_id}", primary_record=root, net_amount=amount,
        economic_type=economic_type, visible=visible, hidden_reason=hidden_reason, transfer_subtype=subtype,
        member_ids=tuple(item.id for item, _ in members), members=members, relations=contained,
        compositions=tuple(kind for kind in ("payment_mirror", "transfer_pair", "refund_offset") if any(item.kind == kind for item in contained)),
        funding_relation_id=root.funding_relation_id,
    )


def build_cash_projections(
    facts: Iterable[CashProjectionFact], relations: Iterable[ProjectionRelation],
) -> CashProjectionBuild:
    """按同笔支付、内部转账、退款冲销的固定口径构建完整投影。"""
    fact_list = tuple(facts)
    fact_by_id = {item.id: item for item in fact_list}
    if len(fact_by_id) != len(fact_list):
        raise CashProjectionError("projection.invalid_fact")
    accepted = tuple(item for item in relations if item.status == "accepted")
    for relation in accepted:
        if relation.primary_fact_id == relation.secondary_fact_id or relation.primary_fact_id not in fact_by_id or relation.secondary_fact_id not in fact_by_id:
            raise CashProjectionError("projection.invalid_relation")
    projections = tuple(
        _validate_group(group, fact_by_id, accepted)
        for group in _components(fact_by_id, accepted)
    )
    ordered = tuple(sorted(projections, key=lambda item: item.projection_id))
    if len({member for item in ordered for member in item.member_ids}) != len(fact_by_id):
        raise CashProjectionError("projection.incomplete")
    return CashProjectionBuild(ordered)

"""收支投影的纯领域模型和确定性构建规则。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, localcontext
from enum import Enum
from typing import Iterable

from ft.domain.decimal import exact_decimal


RULES_VERSION = "cash-projection-v3"
_RELATION_KINDS = frozenset({"payment_mirror", "refund_offset", "transfer_pair"})



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
    account_id: int | None
    occurred_at: datetime | None
    amount: Decimal | None
    currency: str | None
    counterparty: str
    category_id: str | None
    note: str
    source_type: str | None
    record_id: str
    funding_relation_id: int | None = None
    cash_granularity: str = "atomic"
    state: str = "posted"
    created_at: datetime | None = None
    needs_attention: bool = False
    record_type: str | None = None
    record_subtype: str | None = None
    stable_source_key: tuple[str, ...] = ()
    record_origin: str = "legacy"

    def __post_init__(self) -> None:
        try:
            if (type(self.id) is not int or self.id <= 0
                or self.account_id is not None and (type(self.account_id) is not int or self.account_id <= 0)
                or self.state not in {"draft", "posted"} or type(self.needs_attention) is not bool
                or self.cash_granularity not in {"atomic", "aggregate"}):
                raise ValueError
            if (not isinstance(self.stable_source_key, tuple)
                or any(not isinstance(value, str) or not value for value in self.stable_source_key)):
                raise ValueError
            for value in (self.occurred_at, self.created_at):
                if value is not None and (not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None):
                    raise ValueError
            if self.state == "posted" and (self.amount is None or self.currency is None or self.occurred_at is None):
                raise ValueError
            if self.state == "draft" and (self.occurred_at is None and self.created_at is None or self.funding_relation_id is not None):
                raise ValueError
            amount = None if self.amount is None else exact_decimal(self.amount, "amount")
            currency = None if self.currency is None else self.currency.upper()
            if currency is not None and (len(currency) != 3 or not currency.isascii() or not currency.isalpha()):
                raise ValueError
        except (TypeError, ValueError, AttributeError, ArithmeticError) as exc:
            raise CashProjectionError("projection.invalid_fact") from exc
        object.__setattr__(self, "amount", amount)
        object.__setattr__(self, "currency", currency)


@dataclass(frozen=True)
class ProjectionRelation:
    id: int
    kind: str
    primary_fact_id: int
    secondary_fact_id: int
    status: str = "accepted"
    applied_amount: Decimal | None = None
    refund_allocation_key: tuple[str, str] | None = None

    def __post_init__(self) -> None:
        if self.kind not in _RELATION_KINDS or self.id <= 0 or self.primary_fact_id <= 0 or self.secondary_fact_id <= 0:
            raise CashProjectionError("projection.invalid_relation")
        if self.refund_allocation_key is not None and (self.kind != "refund_offset"
            or not isinstance(self.refund_allocation_key, tuple) or len(self.refund_allocation_key) != 2
            or any(not isinstance(value, str) or not value for value in self.refund_allocation_key)):
            raise CashProjectionError("projection.invalid_relation")
        if self.applied_amount is not None:
            try:
                amount = exact_decimal(self.applied_amount, "applied_amount")
            except ValueError as exc:
                raise CashProjectionError("projection.invalid_relation") from exc
            if amount < 0:
                raise CashProjectionError("projection.invalid_relation")
            object.__setattr__(self, "applied_amount", amount)


@dataclass(frozen=True)
class CashProjection:
    projection_id: str
    primary_record: CashProjectionFact
    net_amount: Decimal | None
    economic_type: EconomicType | None
    visible: bool
    hidden_reason: str | None
    business_label: str | None
    member_ids: tuple[int, ...]
    members: tuple[tuple[CashProjectionFact, tuple[str, ...]], ...]
    relations: tuple[ProjectionRelation, ...]
    compositions: tuple[str, ...]
    funding_relation_id: int | None = None
    state: str = "posted"
    statistics_eligible: bool = True
    attention_count: int = 0
    economic_root_ids: tuple[int, ...] = ()

    @property
    def occurred_at(self) -> datetime | None:
        return self.primary_record.occurred_at

    @property
    def sort_at(self) -> datetime:
        value = self.primary_record.occurred_at or self.primary_record.created_at
        if value is None:
            raise CashProjectionError("projection.invalid_fact")
        return value

    @property
    def confirmation_status(self) -> str:
        return "pending" if self.state == "draft" or self.attention_count else "confirmed"


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


def transfer_business_label(facts):
    types = {fact.record_type for fact in facts}
    if "repayment" in types:
        return "credit_repayment"
    if types & {"fx_out", "fx_in"}:
        return "currency_exchange"
    if any(fact.record_subtype == "cross_border_remittance" for fact in facts):
        return "cross_currency_remittance"
    return "ordinary_transfer"


def cash_source_order_key(source_type, record_id, *, bindings=(), record_origin="legacy",
    manual_transfer_id=None, amount=None):
    keys = tuple(tuple(item) for item in bindings)
    if keys:
        # Provider bindings are scoped business identities; occurrence bindings disambiguate unnumbered rows.
        return min(keys, key=lambda item: (item[3] != "provider_id", item[1] != "account", item))
    if record_origin == "manual_transfer" and manual_transfer_id and amount is not None:
        return ("manual_transfer", manual_transfer_id, "outgoing" if amount < 0 else "incoming")
    if record_origin in {"legacy", "manual"} and record_id and not record_id.startswith("import-v1:"):
        return (source_type or "manual", "legacy_source_record", record_id)
    return ()


def source_identity_key(fact):
    key = fact.stable_source_key or cash_source_order_key(fact.source_type, fact.record_id, record_origin=fact.record_origin)
    if not key:
        raise CashProjectionError("projection.source_identity_missing")
    return key


def representative_key(fact):
    source = (fact.source_type or "").lower()
    rank = 0 if source in {"alipay", "wechat", "weixin", "支付宝", "微信"} else 1 if source in {
        "ccb_debit", "ccb_credit", "icbc_debit", "icbc_credit", "icbc_asia", "bank", "debit", "credit"} else 2
    return rank, fact.occurred_at, source_identity_key(fact)


def _validate_group(group: list[int], facts: dict[int, CashProjectionFact], relations: tuple[ProjectionRelation, ...], economic_amounts) -> CashProjection:
    group_members = set(group)
    contained = tuple(sorted(
        (
            item
            for item in relations
            if item.primary_fact_id in group_members and item.secondary_fact_id in group_members
        ),
        key=lambda item: item.id,
    ))
    mirror_relations = tuple(item for item in contained if item.kind == "payment_mirror")
    for relation in mirror_relations:
        primary, secondary = facts[relation.primary_fact_id], facts[relation.secondary_fact_id]
        covered_amount = relation.applied_amount
        if primary.cash_granularity == "atomic" and secondary.cash_granularity == "atomic":
            amounts_match = primary.amount == secondary.amount
        else:
            amounts_match = (
                covered_amount is not None
                and covered_amount > 0
                and covered_amount <= abs(primary.amount)
                and covered_amount <= abs(secondary.amount)
            ) or primary.amount == secondary.amount
        if not amounts_match or primary.currency != secondary.currency or (primary.amount < 0) != (secondary.amount < 0):
            raise CashProjectionError("projection.invalid_relation")
    transfer_relations = tuple(item for item in contained if item.kind == "transfer_pair")
    for relation in transfer_relations:
        primary, secondary = facts[relation.primary_fact_id], facts[relation.secondary_fact_id]
        if primary.amount * secondary.amount >= 0:
            raise CashProjectionError("projection.invalid_relation")
        if primary.currency == secondary.currency and abs(primary.amount) != abs(secondary.amount):
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
        if len(members) > 1 and len({source_identity_key(facts[item]) for item in members}) != len(members):
            raise CashProjectionError("projection.source_identity_duplicate")
        root = members[0] if len(members) == 1 else min(members, key=lambda item: representative_key(facts[item]))
        canonical.update({item: root for item in members})

    logical: list[tuple[ProjectionRelation, int, int]] = []
    seen_edges: set[tuple[str, int, int, str]] = set()
    for relation in contained:
        if relation.kind == "payment_mirror":
            continue
        primary, secondary = canonical[relation.primary_fact_id], canonical[relation.secondary_fact_id]
        key = (relation.kind, primary, secondary)
        if primary == secondary or key in seen_edges:
            continue
        seen_edges.add(key)
        if relation.kind == "refund_offset":
            same_edge = tuple(
                item for item in contained
                if item.kind == relation.kind
                and canonical[item.primary_fact_id] == primary
                and canonical[item.secondary_fact_id] == secondary
            )
            explicit_amounts = tuple(
                item.applied_amount for item in same_edge
                if item.applied_amount is not None and item.applied_amount > 0
            )
            if explicit_amounts:
                component_amounts = tuple(
                    (
                        abs(facts[item.primary_fact_id].amount),
                        abs(facts[item.secondary_fact_id].amount),
                    )
                    for item in same_edge
                )
                mirrored_duplicate = (
                    len(set(explicit_amounts)) == 1
                    and len(set(component_amounts)) == 1
                )
                if any(item.refund_allocation_key is not None for item in same_edge):
                    allocations = {}
                    for item in same_edge:
                        if item.refund_allocation_key is None or item.applied_amount is None:
                            raise CashProjectionError("projection.invalid_relation")
                        previous = allocations.setdefault(item.refund_allocation_key, item.applied_amount)
                        if previous != item.applied_amount:
                            raise CashProjectionError("projection.invalid_relation")
                    total_applied = sum(allocations.values(), Decimal("0"))
                else:
                    total_applied = explicit_amounts[0] if mirrored_duplicate else sum(explicit_amounts, Decimal("0"))
                relation = ProjectionRelation(
                    id=relation.id,
                    kind=relation.kind,
                    primary_fact_id=relation.primary_fact_id,
                    secondary_fact_id=relation.secondary_fact_id,
                    status=relation.status,

                    # Payment mirrors describe the same economic refund from
                    # different source rows.  Equal component allocations are
                    # duplicate evidence; distinct component allocations are
                    # portions of one aggregate parent and must be summed.
                    applied_amount=total_applied,
                )
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
    root_amount = economic_amounts.get(root_id, root.amount)
    if root.funding_relation_id is not None and len(group) != 1:
        raise CashProjectionError("projection.invalid_relation")
    transfer_relations = tuple(item for item, _primary, _secondary in logical if item.kind == "transfer_pair")
    refund_relations = tuple(item for item, _primary, _secondary in logical if item.kind == "refund_offset")
    if transfer_relations and refund_relations:
        raise CashProjectionError("projection.invalid_relation")
    if len(group_members) > 1:
        for identifier in group_members:
            source_identity_key(facts[identifier])
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
        business_label = "bank_security_transfer"
        economic_type, amount, visible, hidden_reason = EconomicType.INTERNAL_TRANSFER, Decimal("0"), True, None
    elif transfer_relations:
        business_label = transfer_business_label(tuple(facts[item] for item in group))
        economic_type, amount, visible, hidden_reason = EconomicType.INTERNAL_TRANSFER, Decimal("0"), True, None
    elif refund_relations:
        refund_inputs = tuple(
            (item, facts[primary], facts[secondary])
            for item, primary, secondary in logical
            if item.kind == "refund_offset"
        )
        zero_amount_refund = all(
            expense.amount == 0 and refund.amount == 0 and expense.currency == refund.currency
            for _relation, expense, refund in refund_inputs
        )
        if root_amount >= 0 and not zero_amount_refund:
            raise CashProjectionError("projection.invalid_relation")
        refunded = Decimal("0")
        for relation, expense, refund in refund_inputs:
            if not zero_amount_refund and (
                expense.amount >= 0 or refund.amount <= 0 or expense.currency != refund.currency
            ):
                raise CashProjectionError("projection.invalid_relation")
            applied = relation.applied_amount
            refund_amount = economic_amounts.get(refund.id, refund.amount)
            if applied is not None and applied != refund_amount:
                raise CashProjectionError("projection.invalid_relation")
            refunded += refund_amount
        if refunded > -root_amount:
            raise CashProjectionError("projection.invalid_relation")
        amount = root_amount + refunded
        economic_type = EconomicType.EXPENSE
        visible, hidden_reason = True, None
        business_label = "p2p_return" if any(facts[item].record_type == "transfer_reversal" for item in group) else None
    elif root_amount < 0:
        economic_type, amount, visible, hidden_reason, business_label = EconomicType.EXPENSE, root_amount, True, None, None
    elif root_amount > 0:
        economic_type, amount, visible, hidden_reason, business_label = EconomicType.INCOME, root_amount, True, None, None
    else:
        economic_type, amount, visible, hidden_reason, business_label = EconomicType.INTERNAL_TRANSFER, Decimal("0"), False, "balance_adjustment", "balance_adjustment"

    members = tuple((facts[item], tuple(sorted(roles[item]))) for item in sorted(group, key=lambda item: (0 if item == root_id else 1, facts[item].occurred_at, item)))
    return CashProjection(
        projection_id=f"cash:{root_id}", primary_record=root, net_amount=amount,
        economic_type=economic_type, visible=visible, hidden_reason=hidden_reason, business_label=business_label,
        member_ids=tuple(item.id for item, _ in members), members=members, relations=contained,
        compositions=tuple(kind for kind in ("payment_mirror", "transfer_pair", "refund_offset") if any(item.kind == kind for item in contained)),
        funding_relation_id=root.funding_relation_id,
        attention_count=sum(1 for member, _roles in members if member.needs_attention),
        economic_root_ids=tuple(sorted(item for item in group if canonical[item] == root_id)),
    )


def build_cash_projections(
    facts: Iterable[CashProjectionFact], relations: Iterable[ProjectionRelation], *, economic_amounts=None,
) -> CashProjectionBuild:
    """按同笔支付、内部转账、退款冲销的固定口径构建完整投影。"""
    with localcontext() as arithmetic:
        arithmetic.prec = 80
        return _build_cash_projections(facts, relations, economic_amounts or {})


def _single_projection(fact):
    if fact.funding_relation_id is not None:
        kind, amount, visible, hidden, business_label = EconomicType.INTERNAL_TRANSFER, Decimal("0"), True, None, "bank_security_transfer"
    elif fact.amount < 0:
        kind, amount, visible, hidden, business_label = EconomicType.EXPENSE, fact.amount, True, None, None
    elif fact.amount > 0:
        kind, amount, visible, hidden, business_label = EconomicType.INCOME, fact.amount, True, None, None
    else:
        kind, amount, visible, hidden, business_label = EconomicType.INTERNAL_TRANSFER, Decimal("0"), False, "balance_adjustment", "balance_adjustment"
    return CashProjection(f"cash:{fact.id}", fact, amount, kind, visible, hidden, business_label,
        (fact.id,), ((fact, ("root",)),), (), (), funding_relation_id=fact.funding_relation_id,
        attention_count=int(fact.needs_attention), economic_root_ids=(fact.id,))


def _build_cash_projections(facts, relations, economic_amounts):
    try:
        fact_list, relation_list = tuple(facts), tuple(relations)
    except TypeError:
        raise CashProjectionError("projection.invalid_fact") from None
    if any(not isinstance(item, CashProjectionFact) for item in fact_list):
        raise CashProjectionError("projection.invalid_fact")
    fact_by_id = {item.id: item for item in fact_list}
    if len(fact_by_id) != len(fact_list):
        raise CashProjectionError("projection.invalid_fact")
    if any(not isinstance(item, ProjectionRelation) for item in relation_list):
        raise CashProjectionError("projection.invalid_relation")
    accepted = tuple(item for item in relation_list if item.status == "accepted")
    business_keys = set()
    for relation in accepted:
        pair = (relation.primary_fact_id, relation.secondary_fact_id)
        key = (relation.kind, tuple(sorted(pair)) if relation.kind == "payment_mirror" else pair)
        if key in business_keys and relation.applied_amount is None:
            raise CashProjectionError("projection.invalid_relation")
        business_keys.add(key)
        if (relation.primary_fact_id == relation.secondary_fact_id or relation.primary_fact_id not in fact_by_id or relation.secondary_fact_id not in fact_by_id
            or fact_by_id[relation.primary_fact_id].state != "posted" or fact_by_id[relation.secondary_fact_id].state != "posted"):
            raise CashProjectionError("projection.invalid_relation")
    if not accepted and all(type(fact) is CashProjectionFact for fact in fact_list):
        projections = tuple(_single_projection(fact) for fact in fact_list if fact.state == "posted")
    else:
        groups = _components({identifier: fact for identifier, fact in fact_by_id.items() if fact.state == "posted"}, accepted)
        component_by_fact = {
            fact_id: component_index
            for component_index, group in enumerate(groups)
            for fact_id in group
        }
        relations_by_component: list[list[ProjectionRelation]] = [
            [] for _group in groups
        ]
        for relation in accepted:
            primary_component = component_by_fact.get(relation.primary_fact_id)
            secondary_component = component_by_fact.get(relation.secondary_fact_id)
            if primary_component is None or primary_component != secondary_component:
                raise CashProjectionError("projection.invalid_relation")
            relations_by_component[primary_component].append(relation)
        projections = tuple(
            _validate_group(group, fact_by_id, tuple(relations_by_component[index]), economic_amounts)
            for index, group in enumerate(groups)
        )
    projections += tuple(CashProjection(
        projection_id=f"cash:{fact.id}", primary_record=fact, net_amount=fact.amount, economic_type=None,
        visible=True, hidden_reason=None, business_label=None, member_ids=(fact.id,),
        members=((fact, ("root",)),), relations=(), compositions=(), state="draft", statistics_eligible=False, attention_count=1,
    ) for fact in fact_list if fact.state == "draft")
    ordered = tuple(sorted(projections, key=lambda item: item.projection_id))
    if len({member for item in ordered for member in item.member_ids}) != len(fact_by_id):
        raise CashProjectionError("projection.incomplete")
    return CashProjectionBuild(ordered)

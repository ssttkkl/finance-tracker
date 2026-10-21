"""当前最终关系图的完整财务校验；不搜索或替换任何配对。"""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from decimal import Decimal, localcontext

from ft.domain.cash_projection import CashProjectionError, CashProjectionFact, ProjectionRelation, build_cash_projections
from ft.domain.cash_relation_policy import CashRelationError, RelationEndpoint, validate_relation_endpoint, validate_relation_roles
from ft.domain.decimal import exact_decimal


@dataclass(frozen=True)
class CashRelationGraphEdge:
    id: int
    kind: str
    primary_transaction_id: str
    secondary_transaction_id: str | None
    primary_component_id: str | None
    secondary_component_id: str | None
    primary_role: str | None
    secondary_role: str | None
    subtype: str = ""
    applied_amount: Decimal | None = None
    state: str = "confirmed"
    active: bool = True


@dataclass(frozen=True)
class JointRelationMember:
    id: str
    decision_group_id: str | None
    row_revision: int
    state: str = "draft"
    active: bool = True


def validate_relation_graph_edge_input(relation):
    """在任何引用查找前统一验证关系输入结构，不解释来源证据。"""
    if (not isinstance(relation, CashRelationGraphEdge) or type(relation.id) is not int or relation.id <= 0
        or not isinstance(relation.kind, str) or relation.kind not in {"payment_mirror", "refund_offset", "transfer_pair"}
        or not isinstance(relation.state, str) or relation.state not in {"draft", "confirmed", "ignored"}
        or type(relation.active) is not bool or not isinstance(relation.subtype, str)
        or not isinstance(relation.primary_transaction_id, str) or not relation.primary_transaction_id
        or any(value is not None and (not isinstance(value, str) or not value) for value in (
            relation.secondary_transaction_id, relation.primary_component_id, relation.secondary_component_id,
            relation.primary_role, relation.secondary_role))):
        raise CashRelationError("relation_data_invalid")
    if relation.applied_amount is not None:
        try:
            if not isinstance(relation.applied_amount, Decimal) or exact_decimal(relation.applied_amount) < 0:
                raise ValueError
        except (ValueError, TypeError, ArithmeticError):
            raise CashRelationError("relation_amount_invalid") from None


def validate_joint_relation_context(expected_members, current_members, decided_ids):
    """调用方从库加载完整组；客户端传入的子集不能代表当前组。"""
    def members(values):
        result = {}
        try:
            values = iter(values)
        except TypeError:
            raise CashRelationError("relation_group_changed") from None
        for item in values:
            if (not isinstance(item, JointRelationMember) or not isinstance(item.id, str) or not item.id
                or item.id in result or type(item.row_revision) is not int or item.row_revision < 1
                or item.decision_group_id is not None and (not isinstance(item.decision_group_id, str) or not item.decision_group_id)
                or type(item.active) is not bool or not isinstance(item.state, str) or item.state not in {"draft", "confirmed", "ignored"}):
                raise CashRelationError("relation_group_changed")
            result[item.id] = item
        return result
    expected, current = members(expected_members), members(current_members)
    if not isinstance(decided_ids, (tuple, list, set, frozenset)) or any(not isinstance(value, str) or not value for value in decided_ids):
        raise CashRelationError("relation_group_changed")
    decided = set(decided_ids)
    if not decided <= expected.keys():
        raise CashRelationError("relation_group_changed")
    touched = {expected[identifier].decision_group_id for identifier in decided} - {None}
    for group in touched:
        before = {identifier: member for identifier, member in expected.items() if member.decision_group_id == group}
        after = {identifier: member for identifier, member in current.items() if member.decision_group_id == group}
        if before != after or any(not member.active or member.state != "draft" for member in after.values()):
            raise CashRelationError("relation_group_changed")
        if not before.keys() <= decided:
            raise CashRelationError("relation_group_incomplete")
    for identifier in decided:
        if current.get(identifier) != expected[identifier] or not current[identifier].active or current[identifier].state != "draft":
            raise CashRelationError("relation_group_changed")


class _Equivalence:
    def __init__(self, identifiers):
        self._parents = {item: item for item in identifiers}

    def root(self, item):
        root = item
        while self._parents[root] != root:
            root = self._parents[root]
        while self._parents[item] != item:
            parent = self._parents[item]
            self._parents[item] = root
            item = parent
        return root

    def join(self, left, right):
        left, right = self.root(left), self.root(right)
        if left != right:
            self._parents[max(left, right)] = min(left, right)


def _components(facts, components):
    parents = {}
    for fact in facts:
        if not isinstance(fact, CashProjectionFact) or fact.state != "posted" or type(fact.id) is not int or fact.id <= 0 or str(fact.id) in parents:
            raise CashRelationError("relation_endpoint_incomplete")
        parents[str(fact.id)] = fact
    by_component, by_parent = {}, defaultdict(list)
    for component in components:
        validate_relation_endpoint(component)
        if component.component_id in by_component or component.transaction_id not in parents:
            raise CashRelationError("relation_endpoint_incomplete")
        by_component[component.component_id] = component
        by_parent[component.transaction_id].append(component)
    for identifier, fact in parents.items():
        parts = by_parent[identifier]
        if (not parts or any(part.currency.upper() != fact.currency for part in parts)
            or sum((part.amount for part in parts), Decimal("0")) != fact.amount
            or any(fact.amount < 0 < part.amount or fact.amount > 0 > part.amount or fact.amount == 0 != part.amount for part in parts)
            or len({(part.record_type, part.record_subtype) for part in parts}) != 1
            or fact.cash_granularity == "atomic" and (len(parts) != 1 or parts[0].account_id != str(fact.account_id))
            or fact.cash_granularity == "aggregate" and fact.account_id is not None):
            raise CashRelationError("relation_endpoint_incompatible")
    return parents, by_component


def build_cash_relation_graph(facts, components, relations, *, funding_component_ids=()):
    """在相同最终图上检查角色、组成项额度、占用和父级投影。

    只消费活动正式父流水的完整组成项；未确认关系不合并、不占用。
    """
    try:
        facts, components, relations, funding = tuple(facts), tuple(components), tuple(relations), tuple(funding_component_ids)
    except TypeError:
        raise CashRelationError("relation_data_invalid") from None
    if any(not isinstance(value, str) or not value for value in funding):
        raise CashRelationError("relation_data_invalid")
    with localcontext() as arithmetic:
        arithmetic.prec = 80
        return _build(facts, components, relations, set(funding))


def _build(facts, components, relations, funding):
    parents, by_component = _components(facts, components)
    if not funding <= by_component.keys():
        raise CashRelationError("relation_endpoint_incomplete")
    funding.update(part.component_id for part in components if parents[part.transaction_id].funding_relation_id is not None)
    component_mirrors, parent_mirrors = _Equivalence(by_component), _Equivalence(parents)
    prepared, identifiers, business_keys, transfer_occupied = [], set(), set(), set()
    for relation in relations:
        validate_relation_graph_edge_input(relation)
        if relation.id in identifiers:
            raise CashRelationError("relation_data_invalid")
        identifiers.add(relation.id)
        if not relation.active or relation.state != "confirmed":
            continue
        primary, secondary = by_component.get(relation.primary_component_id), by_component.get(relation.secondary_component_id)
        if (primary is None or secondary is None or primary.transaction_id != relation.primary_transaction_id
            or secondary.transaction_id != relation.secondary_transaction_id or primary.transaction_id == secondary.transaction_id):
            raise CashRelationError("relation_endpoint_incomplete")
        validate_relation_roles(relation.kind, relation.subtype, primary, secondary, relation.primary_role, relation.secondary_role)
        pair = (primary.component_id, secondary.component_id)
        key = relation.kind, relation.subtype, tuple(sorted(pair)) if relation.kind == "payment_mirror" else pair
        if key in business_keys:
            raise CashRelationError("relation_data_invalid")
        business_keys.add(key)
        if funding.intersection(pair):
            raise CashRelationError("relation_endpoint_occupied")
        try:
            if relation.applied_amount is None:
                amount = (min(primary.amount.copy_abs(), secondary.amount.copy_abs()) if relation.kind == "payment_mirror"
                    else secondary.amount.copy_abs() if relation.kind == "refund_offset" else Decimal("0"))
            elif not isinstance(relation.applied_amount, Decimal):
                raise ValueError
            else:
                amount = exact_decimal(relation.applied_amount)
            if amount < 0 or amount > min(primary.amount.copy_abs(), secondary.amount.copy_abs()):
                raise ValueError
            if relation.kind in {"payment_mirror", "refund_offset"} and amount == 0 and (primary.amount != 0 or secondary.amount != 0):
                raise ValueError
        except (ValueError, TypeError, ArithmeticError):
            raise CashRelationError("relation_amount_invalid") from None
        if relation.kind == "payment_mirror":
            parent_mirrors.join(primary.transaction_id, secondary.transaction_id)
            # Partial coverage alone does not prove two entire components are
            # the same allocation; only complete mirrors grant that identity.
            if amount == primary.amount.copy_abs() == secondary.amount.copy_abs():
                component_mirrors.join(*pair)
        elif relation.kind == "refund_offset":
            if primary.amount == secondary.amount == 0 and (parents[primary.transaction_id].amount != 0 or parents[secondary.transaction_id].amount != 0):
                raise CashRelationError("relation_endpoint_incompatible")
            if amount > primary.amount.copy_abs():
                raise CashRelationError("relation_amount_invalid")
        else:
            if transfer_occupied.intersection(pair):
                raise CashRelationError("relation_endpoint_occupied")
            transfer_occupied.update(pair)
            if primary.currency.upper() == secondary.currency.upper() and primary.amount.copy_abs() != secondary.amount.copy_abs():
                raise CashRelationError("relation_amount_invalid")
        prepared.append((relation, primary, secondary, amount))
    allocations, refund_targets = {}, defaultdict(set)
    for relation, primary, secondary, amount in prepared:
        if relation.kind != "refund_offset":
            continue
        key = component_mirrors.root(primary.component_id), component_mirrors.root(secondary.component_id)
        previous = allocations.setdefault(key, amount)
        if previous != amount:
            raise CashRelationError("relation_amount_invalid")
        refund_targets[key[1]].add(parent_mirrors.root(primary.transaction_id))
    if any(len(targets) > 1 for targets in refund_targets.values()):
        raise CashRelationError("relation_endpoint_occupied")
    spent = defaultdict(lambda: Decimal("0"))
    for (expense, refund), amount in allocations.items():
        spent[expense] += amount
        spent[refund] += amount
    for component in components:
        if spent[component_mirrors.root(component.component_id)] > component.amount.copy_abs():
            raise CashRelationError("relation_refund_exceeds_remaining")
    projection_relations = tuple(ProjectionRelation(relation.id, relation.kind, int(primary.transaction_id), int(secondary.transaction_id),
        subtype=relation.subtype, applied_amount=amount,
        refund_allocation_key=(component_mirrors.root(primary.component_id), component_mirrors.root(secondary.component_id))
            if relation.kind == "refund_offset" else None)
        for relation, primary, secondary, amount in sorted(prepared, key=lambda item: item[0].id))
    try:
        return build_cash_projections(facts, projection_relations)
    except CashProjectionError:
        raise CashRelationError("relation_graph_invalid") from None

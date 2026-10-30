"""当前现金关系的角色约束与局部提交分组；不执行候选搜索。"""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
import re
from typing import Iterable

from migrations.cash_relation_v3.decimal import exact_decimal
from migrations.cash_relation_v3.record_type import validate_cash_record_subtype


class CashRelationError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


CASH_FUNDING_TYPES = {
    "cash_to_investment": frozenset({"investment_out", "transfer_out"}),
    "investment_to_cash": frozenset({"investment_in", "transfer_in"}),
}


def cash_funding_role_compatible(direction, amount, record_type):
    """既有资金角色仅依赖现金类型及固定方向，不重读机构描述。"""
    try:
        value = exact_decimal(amount)
        return (record_type in CASH_FUNDING_TYPES.get(direction, ())
            and (value < 0 if direction == "cash_to_investment" else value > 0))
    except (TypeError, ValueError, ArithmeticError):
        return False


@dataclass(frozen=True)
class RelationEndpoint:
    component_id: str
    transaction_id: str
    amount: Decimal | None
    currency: str | None
    account_id: str | None
    record_type: str | None
    record_subtype: str | None
    state: str = "posted"
    active: bool = True


@dataclass(frozen=True)
class RelationRoleEvidence:
    """只由来源规划器构造；不从客户端决定或其他关系复制。"""

    primary_original_expense: bool = False
    secondary_original_expense: bool = False
    investment_refund_hard_key: bool = False
    p2p_return_hard_key: bool = False


def validate_relation_endpoint(endpoint):
    if (not isinstance(endpoint, RelationEndpoint) or endpoint.state != "posted" or endpoint.active is not True
        or not all(isinstance(value, str) and value for value in (endpoint.component_id, endpoint.transaction_id, endpoint.account_id))
        or not isinstance(endpoint.currency, str) or not re.fullmatch(r"[A-Za-z]{3}", endpoint.currency)
        or not isinstance(endpoint.amount, Decimal)):
        raise CashRelationError("relation_endpoint_incomplete")
    try:
        exact_decimal(endpoint.amount)
        if not isinstance(endpoint.record_type, str) or not isinstance(endpoint.record_subtype, str):
            raise ValueError
        validate_cash_record_subtype(endpoint.record_type, endpoint.record_subtype)
    except ValueError:
        raise CashRelationError("relation_endpoint_incompatible") from None


def _expense(endpoint, *, investment=False):
    return endpoint.amount < 0 and endpoint.record_type in ({"consumption", "refund", "investment_out"} if investment else {"consumption", "refund"})


def _refund(endpoint):
    return endpoint.amount > 0 and endpoint.record_type == "refund"


TRANSFER_OUT_TYPES = frozenset({"transfer_out", "withdrawal_out", "repayment", "fx_out"})
TRANSFER_IN_TYPES = frozenset({"transfer_in", "withdrawal_in", "repayment", "fx_in"})


def validate_relation_roles(kind, primary, secondary, primary_role, secondary_role):
    """校验共同角色与端点约束；完整图另验占用和退款额度。"""
    validate_relation_endpoint(primary)
    validate_relation_endpoint(secondary)
    if primary.component_id == secondary.component_id or primary.transaction_id == secondary.transaction_id:
        raise CashRelationError("relation_endpoint_incompatible")
    roles = (primary_role, secondary_role)
    same_currency = primary.currency.upper() == secondary.currency.upper()
    valid = False
    if kind == "payment_mirror":
        valid = (roles == ("expense", "expense") and _expense(primary) and _expense(secondary)
            or roles == ("refund", "refund") and _refund(primary) and _refund(secondary))
        valid = valid and primary.account_id == secondary.account_id and same_currency and primary.amount == secondary.amount
    elif kind == "refund_offset":
        zero_refund = (primary.amount == secondary.amount == 0 and primary.record_type in {"consumption", "refund", "investment_out"}
            and secondary.record_type == "refund")
        valid = (roles == ("expense", "refund") and (_expense(primary, investment=True) and _refund(secondary) or zero_refund)
            or roles == ("p2p_out", "p2p_in") and primary.record_type == secondary.record_type == "transfer_reversal"
            and primary.amount < 0 < secondary.amount)
        valid = valid and primary.account_id == secondary.account_id and same_currency
    elif kind == "transfer_pair":
        valid = (roles == ("outgoing", "incoming") and primary.record_type in TRANSFER_OUT_TYPES
            and secondary.record_type in TRANSFER_IN_TYPES and primary.amount < 0 < secondary.amount
            and (not same_currency or primary.amount.copy_abs() == secondary.amount))
    if not valid:
        raise CashRelationError("relation_endpoint_incompatible")


def infer_relation_roles(kind, primary, secondary):
    """从正式类型和金额推导角色；人工指定无需来源发现证据。"""
    validate_relation_endpoint(primary)
    validate_relation_endpoint(secondary)
    if kind == "payment_mirror":
        roles = ("refund", "refund") if _refund(primary) and _refund(secondary) else ("expense", "expense")
    elif kind == "refund_offset":
        roles = (("p2p_out", "p2p_in") if primary.record_type == secondary.record_type == "transfer_reversal"
            else ("expense", "refund"))
    else:
        roles = ("outgoing", "incoming")
    validate_relation_roles(kind, primary, secondary, *roles)
    return roles


def discover_relation_roles(kind, primary, secondary, *, evidence=RelationRoleEvidence()):
    """发现阶段使用当前来源证据；确认阶段只使用共同硬约束。"""
    if not isinstance(evidence, RelationRoleEvidence) or any(type(value) is not bool for value in (
        evidence.primary_original_expense, evidence.secondary_original_expense,
        evidence.investment_refund_hard_key, evidence.p2p_return_hard_key,
    )):
        raise CashRelationError("relation_role_unproven")
    roles = infer_relation_roles(kind, primary, secondary)
    for endpoint, role, proven in ((primary, roles[0], evidence.primary_original_expense), (secondary, roles[1], evidence.secondary_original_expense)):
        if role == "expense" and endpoint.record_type == "refund" and not proven:
            raise CashRelationError("relation_role_unproven")
    if kind == "refund_offset" and primary.record_type == "investment_out" and not evidence.investment_refund_hard_key:
        raise CashRelationError("relation_role_unproven")
    if roles == ("p2p_out", "p2p_in") and not evidence.p2p_return_hard_key:
        raise CashRelationError("relation_role_unproven")
    return roles


@dataclass(frozen=True)
class RelationLink:
    id: str
    primary_transaction_id: str
    secondary_transaction_id: str | None
    state: str = "draft"
    active: bool = True
    decision_group_id: str | None = None


class _Groups:
    def __init__(self):
        self.parent = {}

    def root(self, item):
        self.parent.setdefault(item, item)
        root = item
        while self.parent[root] != root:
            root = self.parent[root]
        while self.parent[item] != item:
            parent = self.parent[item]
            self.parent[item] = root
            item = parent
        return root

    def join(self, left, right):
        left, right = self.root(left), self.root(right)
        if left != right:
            self.parent[max(left, right)] = min(left, right)


def _links(links):
    for link in links:
        if (not isinstance(link, RelationLink)
            or not all(isinstance(value, str) and value for value in (link.id, link.primary_transaction_id))
            or link.secondary_transaction_id is not None and (not isinstance(link.secondary_transaction_id, str) or not link.secondary_transaction_id)
            or link.decision_group_id is not None and (not isinstance(link.decision_group_id, str) or not link.decision_group_id)
            or link.state not in {"draft", "confirmed", "ignored"} or type(link.active) is not bool):
            raise CashRelationError("relation_link_invalid")
        yield link


def affected_relation_closure(seed_ids: Iterable[str], old_links: Iterable[RelationLink], new_links: Iterable[RelationLink]):
    """调用方提供相关旧、新图；未确认边不用于合并闭包。"""
    groups = _Groups()
    seeds = frozenset(seed_ids)
    if any(not isinstance(item, str) or not item for item in seeds):
        raise CashRelationError("relation_seed_invalid")
    for links in (old_links, new_links):
        for link in _links(links):
            if link.active and link.state == "confirmed" and link.secondary_transaction_id is not None:
                groups.join(link.primary_transaction_id, link.secondary_transaction_id)
    roots = {groups.root(item) for item in seeds}
    return frozenset(item for item in groups.parent if groups.root(item) in roots)


def relation_commit_groups(old_links: Iterable[RelationLink], changes: Iterable[RelationLink]):
    """按旧组、共享端点和联合决定分组；每组必须校验完整最终图。"""
    groups = _Groups()
    for link in _links(old_links):
        if link.active and link.state == "confirmed" and link.secondary_transaction_id is not None:
            groups.join(link.primary_transaction_id, link.secondary_transaction_id)
    changes = tuple(_links(changes))
    if len({link.id for link in changes}) != len(changes):
        raise CashRelationError("relation_change_duplicate")
    decisions = {}
    for link in changes:
        groups.root(link.primary_transaction_id)
        if link.secondary_transaction_id is not None:
            groups.join(link.primary_transaction_id, link.secondary_transaction_id)
        if link.decision_group_id is not None:
            anchor = decisions.setdefault(link.decision_group_id, link.primary_transaction_id)
            groups.join(anchor, link.primary_transaction_id)
    grouped = {}
    for link in changes:
        grouped.setdefault(groups.root(link.primary_transaction_id), []).append(link.id)
    return tuple(sorted(tuple(sorted(refs)) for refs in grouped.values()))


def cash_relation_candidate_types(record_types):
    """合法角色的保守类型超集；只用于读取预筛选，仍须完整关系校验。"""
    groups = (
        frozenset({"consumption", "refund", "investment_out"}),
        TRANSFER_OUT_TYPES | TRANSFER_IN_TYPES,
        frozenset({"transfer_reversal"}),
    )
    types = set(record_types)
    return frozenset(kind for group in groups if group & types for kind in group)

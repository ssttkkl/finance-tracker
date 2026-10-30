"""工作区事务内的局部关系上下文及无写入的联合校验。"""
from __future__ import annotations

from dataclasses import dataclass, replace
from hashlib import sha256
import json

from ft.domain.cash_relation_graph import build_cash_relation_graph, validate_joint_relation_context, validate_relation_graph_edge_input
from ft.domain.cash_relation_policy import CashRelationError, RelationRoleEvidence, infer_relation_roles


@dataclass(frozen=True)
class CurrentCashRelationContext:
    seed_ids: frozenset[int]
    decision_group_ids: tuple[str, ...]
    transaction_ids: frozenset[int]
    facts: tuple
    components: tuple
    relations: tuple
    joint_members: tuple
    transaction_revisions: tuple
    active_transaction_ids: frozenset[int]
    component_versions: tuple
    relation_revisions: tuple
    relation_groups: tuple
    funding_versions: tuple
    funding_component_ids: frozenset[str]
    issue_versions: tuple = ()
    entry_versions: tuple = ()
    source_order_keys: tuple = ()

    @property
    def token(self):
        payload = (self.transaction_revisions, self.component_versions, self.relation_revisions, self.relation_groups, self.funding_versions, self.issue_versions, self.entry_versions, self.source_order_keys,
            tuple((member.id, member.decision_group_id, member.row_revision, member.state, member.active) for member in self.joint_members))
        return sha256(json.dumps(payload, separators=(",", ":")).encode()).hexdigest()


@dataclass(frozen=True)
class PreparedCashRelationGraph:
    affected_transaction_ids: frozenset[int]
    relations: tuple
    build: object


class CashRelationGraphService:
    """调用方负责授权及工作区写锁；本服务不提交，也不改变 ORM 对象。"""

    def __init__(self, session, workspace_id):
        from ft.adapters.relational.cash_relation_context import RelationalCashRelationContextRepository
        self._repository = RelationalCashRelationContextRepository(session, workspace_id)

    def load(self, transaction_ids, *, decision_group_ids=()):
        return CurrentCashRelationContext(**self._repository.read(transaction_ids, decision_group_ids=decision_group_ids))

    def validate(self, expected, *, updates=(), additions=(), evidence_by_relation=None, cleanup_group_ids=()):
        if not isinstance(expected, CurrentCashRelationContext):
            raise CashRelationError("relation_context_changed")
        current = self.load(expected.seed_ids, decision_group_ids=expected.decision_group_ids)
        if current.token != expected.token:
            raise CashRelationError("relation_context_changed")
        return self._validate_current(current, updates=updates, additions=additions,
            evidence_by_relation=evidence_by_relation, cleanup_group_ids=cleanup_group_ids,
            expected_members=expected.joint_members)

    def _validate_current(self, current, *, updates=(), additions=(), evidence_by_relation=None,
                          cleanup_group_ids=(), expected_members=None):
        if not isinstance(current, CurrentCashRelationContext):
            raise CashRelationError("relation_context_changed")
        previous = {edge.id: edge for edge in current.relations}
        try:
            updates, additions = tuple(updates), tuple(additions)
        except TypeError:
            raise CashRelationError("relation_data_invalid") from None
        changes = updates + additions
        for edge in changes:
            validate_relation_graph_edge_input(edge)
        if (len({edge.id for edge in changes}) != len(changes) or any(edge.id not in previous for edge in updates)
            or any(edge.id in previous for edge in additions)):
            raise CashRelationError("relation_context_changed")
        grouped = dict(current.relation_groups)
        try:
            cleanup = set(cleanup_group_ids)
        except TypeError:
            raise CashRelationError("relation_data_invalid") from None
        if (any(not isinstance(group, str) or not 1 <= len(group) <= 128 for group in cleanup)
            or not cleanup <= set(current.decision_group_ids)):
            raise CashRelationError("relation_group_incomplete")
        targets = {edge.id: edge for edge in updates}
        for group in cleanup:
            ids = {int(member.id) for member in current.joint_members if member.decision_group_id == group}
            if not ids or not ids <= targets.keys():
                raise CashRelationError("relation_group_incomplete")
            active = []
            for identifier in ids:
                old, target = previous[identifier], targets[identifier]
                if not old.active:
                    if target != old:
                        raise CashRelationError("relation_group_changed")
                else:
                    active.append(target)
            if (any(edge.active and edge.state == "confirmed" for edge in active)
                or not all(not edge.active for edge in active) and len({(edge.state, edge.active) for edge in active}) != 1):
                raise CashRelationError("relation_group_incomplete")
        decided = {str(edge.id) for edge in updates if previous[edge.id].state == "draft" and edge.id in grouped
            and grouped[edge.id] not in cleanup}
        if any(grouped[int(identifier)] not in current.decision_group_ids for identifier in decided):
            raise CashRelationError("relation_group_incomplete")
        validate_joint_relation_context(current.joint_members if expected_members is None else expected_members, current.joint_members, decided)
        for group in {grouped[int(identifier)] for identifier in decided}:
            members = [targets[int(member.id)] for member in current.joint_members if member.decision_group_id == group]
            if len({(edge.state, edge.active) for edge in members}) != 1:
                raise CashRelationError("relation_group_incomplete")
        parents = {str(identifier) for identifier in current.transaction_ids}
        active_parents = {str(identifier) for identifier in current.active_transaction_ids}
        component_owners = {str(version[0]): str(version[1]) for version in current.component_versions}
        for edge in changes:
            for parent, component in ((edge.primary_transaction_id, edge.primary_component_id), (edge.secondary_transaction_id, edge.secondary_component_id)):
                if (parent is not None and (parent not in parents or edge.active and parent not in active_parents)
                    or component is not None and component_owners.get(component) != parent):
                    raise CashRelationError("relation_endpoint_incomplete")
            if edge.secondary_transaction_id is None and (edge.secondary_component_id is not None or edge.secondary_role is not None):
                raise CashRelationError("relation_endpoint_incompatible")
            if edge.primary_transaction_id == edge.secondary_transaction_id:
                raise CashRelationError("relation_endpoint_incompatible")
        evidence_by_relation = {} if evidence_by_relation is None else evidence_by_relation
        if not isinstance(evidence_by_relation, dict) or evidence_by_relation.keys() - {edge.id for edge in changes}:
            raise CashRelationError("relation_data_invalid")
        by_component = {part.component_id: part for part in current.components}
        def structure(edge):
            return (edge.kind, edge.primary_transaction_id, edge.secondary_transaction_id,
                edge.primary_component_id, edge.secondary_component_id, edge.primary_role, edge.secondary_role)
        for edge in changes:
            old = previous.get(edge.id)
            if edge.active and edge.state == "confirmed" and (old is None or old.state != "confirmed" or not old.active or structure(edge) != structure(old)):
                primary, secondary = by_component.get(edge.primary_component_id), by_component.get(edge.secondary_component_id)
                if primary is None or secondary is None:
                    raise CashRelationError("relation_endpoint_incomplete")
                roles = infer_relation_roles(edge.kind, primary, secondary)
                if roles != (edge.primary_role, edge.secondary_role):
                    raise CashRelationError("relation_role_unproven")
        final = dict(previous)
        final.update((edge.id, edge) for edge in changes)
        edges = tuple(final[key] for key in sorted(final))
        pending = {str(parent_id) for _identifier, parent_id, _revision in current.issue_versions}
        pending.update(identifier for edge in edges if edge.active and edge.state == "draft"
            for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier is not None)
        facts = tuple(replace(fact, needs_attention=str(fact.id) in pending)
            if fact.needs_attention != (str(fact.id) in pending) else fact for fact in current.facts)
        build = build_cash_relation_graph(facts, current.components, edges, funding_component_ids=current.funding_component_ids)
        resolved = {relation.id: relation.applied_amount for projection in build.projections for relation in projection.relations}
        edges = tuple(replace(edge, applied_amount=resolved[edge.id]) if edge.id in resolved else edge for edge in edges)
        return PreparedCashRelationGraph(current.transaction_ids, edges, build)

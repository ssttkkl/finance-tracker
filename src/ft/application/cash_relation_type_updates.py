"""流水类型写入后的局部关系维护；调用方负责工作区锁和事务。"""
from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import datetime, timezone

from sqlalchemy import update

from ft.adapters.relational.cash_relations import CurrentCashRelationRepository, _id
from ft.adapters.relational.models import CashInvestmentFundingRelationModel, TransactionRelationModel
from ft.adapters.relational.projections import RelationalCashProjectionRepository
from ft.application.cash_relation_context import CashRelationGraphService, CurrentCashRelationContext
from ft.domain.cash_relation_graph import build_cash_relation_graph
from ft.domain.cash_relation_policy import CashRelationError, cash_funding_role_compatible, validate_relation_roles


@dataclass(frozen=True)
class CashRelationTypeUpdateResult:
    affected_transaction_ids: frozenset[int]
    removed_relation_ids: frozenset[int]
    removed_funding_ids: frozenset[int]
    stale_relation_ids: frozenset[int]
    build: object
    projection_status: dict | None


class CashRelationTypeUpdateService:
    def __init__(self, session, workspace_id):
        self._session, self._workspace_id = session, workspace_id

    def maintain(self, before, type_changed_ids, *, updated_transaction_ids=None):
        """先验证完整剩余图，再解除失效确认边并替换旧组；不提交。"""
        if not isinstance(before, CurrentCashRelationContext):
            raise CashRelationError("relation_context_changed")
        try:
            changed = frozenset(_id(value) for value in type_changed_ids)
            updated = changed if updated_transaction_ids is None else frozenset(_id(value) for value in updated_transaction_ids)
        except TypeError:
            raise CashRelationError("relation_data_invalid") from None
        if not changed <= before.seed_ids or not changed <= updated or not updated <= before.transaction_ids:
            raise CashRelationError("relation_context_changed")
        self._session.flush()
        current = CashRelationGraphService(self._session, self._workspace_id).load(before.seed_ids, decision_group_ids=before.decision_group_ids)
        old_revisions, revisions = dict(before.transaction_revisions), dict(current.transaction_revisions)
        def financial(facts):
            return tuple((fact.id, fact.account_id, fact.amount, fact.currency, fact.occurred_at, fact.cash_granularity) for fact in facts)
        if (current.transaction_ids != before.transaction_ids or current.active_transaction_ids != before.active_transaction_ids
            or financial(current.facts) != financial(before.facts)
            or any(revision != old_revisions.get(identifier) for identifier, revision in revisions.items() if identifier not in updated)
            or any(revisions.get(identifier, 0) < old_revisions[identifier] for identifier in updated)
            or replace(current, transaction_revisions=before.transaction_revisions).token != before.token):
            raise CashRelationError("relation_context_changed")
        endpoints = {part.component_id: part for part in current.components}
        removed = set()
        for edge in current.relations:
            if not edge.active or edge.state != "confirmed" or not changed.intersection(
                int(identifier) for identifier in (edge.primary_transaction_id, edge.secondary_transaction_id) if identifier is not None):
                continue
            primary, secondary = endpoints.get(edge.primary_component_id), endpoints.get(edge.secondary_component_id)
            if primary is None or secondary is None:
                raise CashRelationError("relation_endpoint_incomplete")
            try:
                validate_relation_roles(edge.kind, edge.subtype, primary, secondary, edge.primary_role, edge.secondary_role)
            except CashRelationError as exc:
                if exc.code != "relation_endpoint_incompatible":
                    raise
                removed.add(edge.id)
        funding_removed, funding_by_parent = set(), {}
        for identifier, component_id, _investment_id, direction in current.funding_versions:
            endpoint = endpoints.get(str(component_id))
            if endpoint is None:
                raise CashRelationError("relation_endpoint_incomplete")
            if int(endpoint.transaction_id) in changed and not cash_funding_role_compatible(direction, endpoint.amount, endpoint.record_type):
                funding_removed.add(identifier)
            else:
                funding_by_parent.setdefault(int(endpoint.transaction_id), identifier)
        facts = tuple(replace(fact, funding_relation_id=funding_by_parent.get(fact.id)) for fact in current.facts)
        edges = tuple(replace(edge, active=False) if edge.id in removed else edge for edge in current.relations)
        funding_components = {str(component_id) for identifier, component_id, _event_id, _direction in current.funding_versions if identifier not in funding_removed}
        build = build_cash_relation_graph(facts, current.components, edges, funding_component_ids=funding_components)
        now = datetime.now(timezone.utc)
        relation_revisions = dict(current.relation_revisions)
        for identifier in sorted(removed):
            result = self._session.execute(update(TransactionRelationModel).where(
                TransactionRelationModel.workspace_id == self._workspace_id, TransactionRelationModel.id == identifier,
                TransactionRelationModel.row_revision == relation_revisions[identifier],
                TransactionRelationModel.active.is_(True), TransactionRelationModel.state == "confirmed",
            ).values(active=False, decision_source="system", decided_by="system", decided_at=now,
                decision_reason="record_type_changed", row_revision=TransactionRelationModel.row_revision + 1))
            if result.rowcount != 1:
                raise CashRelationError("relation_context_changed")
        for identifier, component_id, investment_id, direction in current.funding_versions:
            if identifier not in funding_removed:
                continue
            result = self._session.execute(update(CashInvestmentFundingRelationModel).where(
                CashInvestmentFundingRelationModel.workspace_id == self._workspace_id, CashInvestmentFundingRelationModel.id == identifier,
                CashInvestmentFundingRelationModel.cash_transaction_component_id == component_id,
                CashInvestmentFundingRelationModel.investment_event_id == investment_id, CashInvestmentFundingRelationModel.direction == direction,
                CashInvestmentFundingRelationModel.status == "accepted", CashInvestmentFundingRelationModel.active_slot == "active",
            ).values(status="rejected", active_slot=f"type-change-{identifier}", decided_by="system", decided_at=now, decision_reason="record_type_changed"))
            if result.rowcount != 1:
                raise CashRelationError("relation_context_changed")
        actual_updates = {identifier for identifier in updated if revisions[identifier] != old_revisions[identifier]}
        stale = CurrentCashRelationRepository(self._session, self._workspace_id).mark_candidates_stale(actual_updates)
        projections = RelationalCashProjectionRepository(self._session, self._workspace_id)
        state = projections.ready_state_lock_or_none()
        status = projections.replace_active_components(build, set(before.transaction_ids | current.transaction_ids), state=state) if state is not None else None
        return CashRelationTypeUpdateResult(before.transaction_ids | current.transaction_ids, frozenset(removed), frozenset(funding_removed), stale, build, status)

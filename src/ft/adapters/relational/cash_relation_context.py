"""按父流水及明确决定组读取最终图输入，不加载来源正文。"""
from __future__ import annotations

from sqlalchemy import select

from ft.domain.cash_projection import CashProjectionError, CashProjectionFact
from ft.domain.cash_relation_graph import CashRelationGraphEdge, JointRelationMember
from ft.domain.cash_relation_policy import CashRelationError, RelationEndpoint
from .cash_relations import CurrentCashRelationRepository, _batches, _id
from .cash_attention import read_cash_attention
from .models import CashInvestmentFundingRelationModel, CashTransactionComponentModel, CashTransactionModel


_PARENT_FIELDS = ("id", "row_revision", "state", "deleted_at", "account_id", "cash_granularity", "amount", "currency",
    "occurred_at", "record_type", "record_subtype", "counterparty", "category_id", "note", "source_type", "record_id")


class RelationalCashRelationContextRepository:
    def __init__(self, session, workspace_id):
        self._session, self._workspace_id = session, workspace_id
        self._relations = CurrentCashRelationRepository(session, workspace_id)

    def _parents(self, ids):
        result = {}
        for batch in _batches(sorted(ids)):
            rows = self._session.execute(select(*(getattr(CashTransactionModel, key) for key in _PARENT_FIELDS)).where(
                CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.id.in_(batch))).mappings()
            result.update((row["id"], dict(row)) for row in rows)
        return result

    def read(self, transaction_ids, *, decision_group_ids=()):
        try:
            seeds = frozenset(_id(value) for value in transaction_ids)
            groups = tuple(sorted(set(decision_group_ids)))
            if any(not isinstance(group, str) or not 1 <= len(group) <= 128 for group in groups):
                raise ValueError
            for group in groups:
                group.encode("utf-8")
        except (TypeError, ValueError, UnicodeError):
            raise CashRelationError("relation_data_invalid") from None
        members = self._relations.graph_rows_for_groups(groups)
        if set(groups) != {row["decision_group_id"] for row in members}:
            raise CashRelationError("relation_group_changed")
        wanted = set(seeds)
        wanted.update(row[key] for row in members for key in ("primary_transaction_id", "secondary_transaction_id") if row[key] is not None)
        parents = self._parents(wanted)
        if wanted != parents.keys():
            raise CashRelationError("relation_endpoint_incomplete")
        closure = self._relations.confirmed_closure(wanted)
        parents.update(self._parents(closure - parents.keys()))
        rows = {row["id"]: row for row in self._relations.graph_rows_for_transactions(closure)}
        rows.update((row["id"], row) for row in members)
        ordered = tuple(rows[key] for key in sorted(rows))
        attention = read_cash_attention(self._session, self._workspace_id, parents, relation_rows=ordered)
        formal = {identifier: parent for identifier, parent in parents.items() if parent["state"] == "posted" and parent["deleted_at"] is None}
        component_rows = {}
        for batch in _batches(sorted(parents)):
            for row in self._session.execute(select(CashTransactionComponentModel.id, CashTransactionComponentModel.cash_transaction_id,
                CashTransactionComponentModel.account_id, CashTransactionComponentModel.amount, CashTransactionComponentModel.currency,
                CashTransactionComponentModel.ordinal).where(
                    CashTransactionComponentModel.workspace_id == self._workspace_id,
                    CashTransactionComponentModel.cash_transaction_id.in_(batch))).mappings():
                component_rows[row["id"]] = dict(row)
        funding = {}
        for batch in _batches(sorted(component_rows)):
            for row in self._session.execute(select(CashInvestmentFundingRelationModel.id,
                CashInvestmentFundingRelationModel.cash_transaction_component_id, CashInvestmentFundingRelationModel.investment_event_id,
                CashInvestmentFundingRelationModel.direction).where(CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                    CashInvestmentFundingRelationModel.cash_transaction_component_id.in_(batch), CashInvestmentFundingRelationModel.status == "accepted",
                    CashInvestmentFundingRelationModel.active_slot == "active")).mappings():
                funding[row["id"]] = dict(row)
        funding_by_parent = {}
        for row in sorted(funding.values(), key=lambda value: value["id"]):
            funding_by_parent.setdefault(component_rows[row["cash_transaction_component_id"]]["cash_transaction_id"], row["id"])
        try:
            facts = tuple(CashProjectionFact(parent["id"], parent["account_id"], parent["occurred_at"], parent["amount"], parent["currency"],
                parent["counterparty"], parent["category_id"], parent["note"], parent["source_type"], parent["record_id"],
                funding_relation_id=funding_by_parent.get(parent["id"]), cash_granularity=parent["cash_granularity"],
                needs_attention=parent["id"] in attention.transaction_ids)
                for parent in sorted(formal.values(), key=lambda value: value["id"]))
        except (CashProjectionError, TypeError, ValueError, AttributeError):
            raise CashRelationError("relation_endpoint_incompatible") from None
        components = tuple(RelationEndpoint(str(row["id"]), str(row["cash_transaction_id"]), row["amount"], row["currency"],
            str(row["account_id"]) if row["account_id"] is not None else None,
            formal[row["cash_transaction_id"]]["record_type"], formal[row["cash_transaction_id"]]["record_subtype"])
            for row in sorted(component_rows.values(), key=lambda value: value["id"]) if row["cash_transaction_id"] in formal)
        edges = tuple(CashRelationGraphEdge(row["id"], row["kind"], str(row["primary_transaction_id"]),
            str(row["secondary_transaction_id"]) if row["secondary_transaction_id"] is not None else None,
            str(row["primary_component_id"]) if row["primary_component_id"] is not None else None,
            str(row["secondary_component_id"]) if row["secondary_component_id"] is not None else None,
            row["primary_role"], row["secondary_role"], subtype=row["subtype"], state=row["state"], active=row["active"],
            applied_amount=None if row["kind"] in {"payment_mirror", "refund_offset"} and row["applied_amount"] == 0 else row["applied_amount"])
            for row in ordered)
        return dict(seed_ids=seeds, decision_group_ids=groups, transaction_ids=frozenset(closure), facts=facts, components=components, relations=edges,
            joint_members=tuple(JointRelationMember(str(row["id"]), row["decision_group_id"], row["row_revision"], row["state"], row["active"]) for row in members),
            transaction_revisions=tuple((identifier, parents[identifier]["row_revision"]) for identifier in sorted(parents)),
            active_transaction_ids=frozenset(identifier for identifier, parent in parents.items() if parent["deleted_at"] is None),
            component_versions=tuple((row["id"], row["cash_transaction_id"], row["account_id"],
                format(row["amount"], "f") if row["amount"] is not None else None, row["currency"], row["ordinal"])
                for row in sorted(component_rows.values(), key=lambda value: value["id"])),
            relation_revisions=tuple((row["id"], row["row_revision"]) for row in ordered),
            issue_versions=attention.issue_versions,
            relation_groups=tuple((row["id"], row["decision_group_id"]) for row in ordered if row["decision_group_id"] is not None),
            funding_versions=tuple((row["id"], row["cash_transaction_component_id"], row["investment_event_id"], row["direction"]) for row in sorted(funding.values(), key=lambda value: value["id"])),
            funding_component_ids=frozenset(str(row["cash_transaction_component_id"]) for row in funding.values()
                if component_rows[row["cash_transaction_component_id"]]["cash_transaction_id"] in formal))

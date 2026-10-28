"""收支投影的关系型派生读模型适配器。"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from hashlib import sha256
import json
from uuid import uuid4

from sqlalchemy import and_, delete, func, insert, select
from sqlalchemy.orm import aliased

from ft.domain.cash_projection import (
    CashProjectionBuild,
    CashProjectionError,
    CashProjectionFact,
    ProjectionRelation,
    RULES_VERSION,
    build_cash_projections,
)
from ft.domain.canonical import canonical_bytes

from .cash_attention import read_cash_attention
from .cash_relations import CurrentCashRelationRepository, _batches
from .workspace_lock import lock_workspace
from .models import (
    CashProjectionDatasetModel,
    CashProjectionMemberModel,
    CashProjectionModel,
    CashProjectionRelationModel,
    CashProjectionStateModel,
    CashInvestmentFundingRelationModel,
    CashCategoryModel,
    CashTransactionModel,
    CashTransactionComponentModel,
    CashTransactionIssueModel,
    TransactionRelationModel,
)


PROJECTION_WRITE_BATCH_SIZE = 900


def _now() -> datetime:
    return datetime.now(timezone.utc)


@dataclass(frozen=True)
class _ValidatedProjectionSources:
    repository: object
    transaction: object
    digest: str
    facts: tuple
    relations: tuple


class RelationalCashProjectionRepository:
    """只映射持久化；归并和金额规则始终留在领域层。"""

    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def _state(self, *, create: bool = False, lock: bool = False) -> CashProjectionStateModel | None:
        statement = select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == self._workspace_id)
        if lock:
            if lock_workspace(self._session, self._workspace_id) is None:
                from sqlalchemy.exc import NoResultFound
                raise NoResultFound()
            if self._session.bind.dialect.name == "postgresql":
                statement = statement.with_for_update()
            statement = statement.execution_options(populate_existing=True)
        state = self._session.scalar(statement)
        if state is None and create:
            state = CashProjectionStateModel(workspace_id=self._workspace_id, rules_version=RULES_VERSION)
            self._session.add(state)
            self._session.flush()
        return state

    def status(self) -> dict:
        state = self._state()
        if state is None:
            return {
                "availability": "uninitialized", "projection_version": 0, "source_revision": 0,
                "rules_version": RULES_VERSION, "last_build_status": "never", "active_dataset_id": None,
                "projection_count": 0, "member_count": 0, "last_error_code": None, "last_error_summary": None,
            }
        return self._status_for_state(state)

    @staticmethod
    def _status_for_state(state: CashProjectionStateModel) -> dict:
        return {
            "availability": state.availability, "projection_version": state.projection_version,
            "source_revision": state.source_revision, "rules_version": state.rules_version,
            "last_build_status": state.last_build_status, "active_dataset_id": state.active_dataset_id,
            "projection_count": state.projection_count, "member_count": state.member_count,
            "last_error_code": state.last_error_code, "last_error_summary": state.last_error_summary,
            "last_build_id": state.last_build_id,
        }

    def read_sources(self) -> tuple[tuple[CashProjectionFact, ...], tuple[ProjectionRelation, ...]]:
        return self.read_sources_for_facts(None)

    def read_validated_sources(self):
        digest = self.source_digest()
        facts, relations = self.read_sources()
        return _ValidatedProjectionSources(self, self._session.get_transaction(), digest, facts, relations)

    def _parent_ids_for_components(self, component_ids):
        result = set()
        for batch in _batches(sorted(component_ids)):
            result.update(self._session.scalars(select(CashTransactionComponentModel.cash_transaction_id).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
                CashTransactionComponentModel.id.in_(batch))))
        return result

    def confirmed_transaction_closure(self, fact_ids: set[int], *, input_is_components: bool = False) -> set[int]:
        """Translate explicit component references once, then retain parent seeds."""
        members = {int(item) for item in fact_ids if item is not None}
        seeds = self._parent_ids_for_components(members) if input_is_components else members
        return CurrentCashRelationRepository(self._session, self._workspace_id).confirmed_closure(seeds)

    def affected_transaction_closure(self, transaction_ids, *, state):
        """Close both current membership and new confirmed edges, never candidates."""
        frontier = {int(item) for item in transaction_ids}
        members, visited_groups = set(), set()
        while frontier:
            confirmed = self.confirmed_transaction_closure(frontier)
            members.update(confirmed)
            groups = set()
            for batch in _batches(sorted(confirmed)):
                groups.update(self._session.scalars(select(CashProjectionMemberModel.projection_row_id).where(
                    CashProjectionMemberModel.workspace_id == self._workspace_id,
                    CashProjectionMemberModel.dataset_id == state.active_dataset_id,
                    CashProjectionMemberModel.cash_transaction_id.in_(batch))))
            frontier = set()
            for batch in _batches(sorted(groups - visited_groups)):
                frontier.update(self._session.scalars(select(CashProjectionMemberModel.cash_transaction_id).where(
                    CashProjectionMemberModel.workspace_id == self._workspace_id,
                    CashProjectionMemberModel.dataset_id == state.active_dataset_id,
                    CashProjectionMemberModel.projection_row_id.in_(batch))))
            visited_groups.update(groups)
            frontier.difference_update(members)
        return members

    def read_sources_for_facts(
        self, fact_ids: set[int] | None,
        *, input_is_components: bool = False,
    ) -> tuple[tuple[CashProjectionFact, ...], tuple[ProjectionRelation, ...]]:
        requested_ids = None if fact_ids is None else {int(item) for item in fact_ids}
        fact_filter = [
            CashTransactionModel.workspace_id == self._workspace_id,
            CashTransactionModel.deleted_at.is_(None),
        ]
        if requested_ids is not None:
            if not requested_ids:
                return (), ()
            requested_parents = self._parent_ids_for_components(requested_ids) if input_is_components else requested_ids
            fact_filter.append(CashTransactionModel.id.in_(requested_parents))
        funding_component = aliased(CashTransactionComponentModel)
        funding_relation_id = select(CashInvestmentFundingRelationModel.id).join(
            funding_component,
            and_(
                funding_component.workspace_id == CashInvestmentFundingRelationModel.workspace_id,
                funding_component.id == CashInvestmentFundingRelationModel.cash_transaction_component_id,
            ),
        ).where(
            CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
            funding_component.cash_transaction_id == CashTransactionModel.id,
            CashInvestmentFundingRelationModel.status == "accepted",
            CashInvestmentFundingRelationModel.active_slot == "active",
        ).order_by(CashInvestmentFundingRelationModel.id).limit(1).scalar_subquery()
        fact_rows = self._session.execute(
            select(CashTransactionModel, funding_relation_id.label("funding_relation_id"))
            .where(*fact_filter)
            .order_by(CashTransactionModel.id)
        ).all()
        identifiers = {row.id for row, _funding in fact_rows}
        rows = CurrentCashRelationRepository(self._session, self._workspace_id).graph_rows_for_transactions(identifiers)
        attention = read_cash_attention(self._session, self._workspace_id, identifiers, relation_rows=rows)
        facts = tuple(self._fact_from_model(row, needs_attention=row.id in attention.transaction_ids,
            funding_relation_id=funding_relation_id) for row, funding_relation_id in fact_rows)
        related = {row[key] for row in rows if row["active"] and row["state"] == "confirmed"
            for key in ("primary_transaction_id", "secondary_transaction_id") if row[key] is not None}
        relations = ()
        if related:
            from .cash_relation_context import RelationalCashRelationContextRepository
            from ft.domain.cash_relation_graph import build_cash_relation_graph
            from ft.domain.cash_relation_policy import CashRelationError
            context = RelationalCashRelationContextRepository(self._session, self._workspace_id).read(related)
            if not context["transaction_ids"] <= identifiers:
                raise CashProjectionError("projection.incomplete")
            try:
                graph = build_cash_relation_graph(context["facts"], context["components"], context["relations"],
                    funding_component_ids=context["funding_component_ids"])
            except CashRelationError:
                raise CashProjectionError("projection.invalid_relation") from None
            relations = tuple(sorted((edge for projection in graph.projections for edge in projection.relations), key=lambda edge: edge.id))
        return facts, relations

    def source_digest(self) -> str:
        cash_transactions = self._session.scalars(
            select(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.deleted_at.is_(None),
            ).order_by(CashTransactionModel.id).execution_options(populate_existing=True)
        ).all()
        components_by_parent: dict[int, list[CashTransactionComponentModel]] = {}
        for component in self._session.scalars(
            select(CashTransactionComponentModel).where(
                CashTransactionComponentModel.workspace_id == self._workspace_id,
            ).order_by(
                CashTransactionComponentModel.cash_transaction_id,
                CashTransactionComponentModel.ordinal,
                CashTransactionComponentModel.id,
            ).execution_options(populate_existing=True)
        ):
            components_by_parent.setdefault(component.cash_transaction_id, []).append(component)
        payload = {
            "facts": [
                {
                    "id": item.id,
                    "state": item.state,
                    "row_revision": item.row_revision,
                    "created_at": item.created_at,
                    "record_type": item.record_type,
                    "record_subtype": item.record_subtype,
                    "account_id": item.account_id,
                    "source_type": item.source_type,
                    "record_id": item.record_id,
                    "source_payload": item.source_payload,
                    "occurred_at": item.occurred_at,
                    "amount": item.amount,
                    "currency": item.currency,
                    "counterparty": item.counterparty,
                    "note": item.note,
                    "category_id": item.category_id,
                    "cash_granularity": item.cash_granularity,
                    "components": [
                        {
                            "id": component.id,
                            "account_id": component.account_id,
                            "amount": component.amount,
                            "currency": component.currency,
                            "ordinal": component.ordinal,
                            "label": component.label,
                            "source_key": component.source_key,
                        }
                        for component in components_by_parent.get(item.id, ())
                    ],
                }
                for item in cash_transactions
            ],
            "relations": [
                {
                    "id": item.id,
                    "kind": item.kind,
                    "primary_fact_id": item.primary_component_id,
                    "secondary_fact_id": item.secondary_component_id,
                    "primary_fact_type": "cash",
                    "secondary_fact_type": "cash",
                    "state": item.state,
                    "row_revision": item.row_revision,
                    "primary_transaction_id": item.primary_transaction_id,
                    "secondary_transaction_id": item.secondary_transaction_id,
                    "primary_role": item.primary_role,
                    "secondary_role": item.secondary_role,
                    "decision_group_id": item.decision_group_id,
                    "subtype": item.subtype,
                    "rule_id": item.rule_id,
                    "applied_amount": item.applied_amount,
                }
                for item in self._session.scalars(
                    select(TransactionRelationModel).where(
                        TransactionRelationModel.workspace_id == self._workspace_id,
                        TransactionRelationModel.state.in_(("confirmed", "draft")),
                        TransactionRelationModel.active.is_(True),
                    ).order_by(TransactionRelationModel.id).execution_options(populate_existing=True)
                )
            ],
            "issues": [tuple(row) for row in self._session.execute(select(
                CashTransactionIssueModel.id, CashTransactionIssueModel.transaction_id, CashTransactionIssueModel.revision,
            ).where(CashTransactionIssueModel.workspace_id == self._workspace_id).order_by(CashTransactionIssueModel.id))],
            "funding_relations": [
                {
                    "id": item.id,
                    "cash_transaction_component_id": item.cash_transaction_component_id,
                    "investment_event_id": item.investment_event_id,
                    "direction": item.direction,
                    "status": item.status,
                    "rule_id": item.rule_id,
                    "evidence": item.evidence,
                    "active_slot": item.active_slot,
                }
                for item in self._session.scalars(
                    select(CashInvestmentFundingRelationModel).where(
                        CashInvestmentFundingRelationModel.workspace_id == self._workspace_id,
                        CashInvestmentFundingRelationModel.status == "accepted",
                        CashInvestmentFundingRelationModel.active_slot == "active",
                    ).order_by(CashInvestmentFundingRelationModel.id).execution_options(populate_existing=True)
                )
            ],
        }
        return sha256(canonical_bytes(payload)).hexdigest()

    def rebuild_failure_context(self) -> dict:
        """先锁定工作区和状态行，再读取全量构建输入。"""
        state = self._state(create=True, lock=True)
        assert state is not None
        return {
            "active_dataset_id": state.active_dataset_id,
            "source_revision": state.source_revision,
        }

    def ready_state_lock_or_none(self) -> CashProjectionStateModel | None:
        """锁定工作区后确认是否存在可维护的活动数据集。"""
        state = self._state(lock=True)
        if state is None or state.availability != "ready" or not state.active_dataset_id:
            return None
        return state

    def require_ready_state_lock(self) -> CashProjectionStateModel:
        """锁定可维护的活动数据集，未就绪时中止派生维护。"""
        state = self.ready_state_lock_or_none()
        if state is None:
            raise RuntimeError("projection.unavailable")
        return state

    def create_staging_dataset(self, *, source_digest: str, rules_version: str) -> str:
        state = self._state(create=True, lock=True)
        assert state is not None
        dataset_id = str(uuid4())
        state.last_build_status = "running"
        state.last_build_id = dataset_id
        state.build_started_at = _now()
        state.last_error_code = None
        state.last_error_summary = None
        self._session.add(CashProjectionDatasetModel(
            id=dataset_id, workspace_id=self._workspace_id, state="staging", source_revision=state.source_revision,
            source_digest=source_digest, rules_version=rules_version,
        ))
        return dataset_id

    def _staging_dataset(self, dataset_id):
        self._state(lock=True)
        dataset = self._session.scalar(select(CashProjectionDatasetModel).where(
            CashProjectionDatasetModel.id == dataset_id, CashProjectionDatasetModel.workspace_id == self._workspace_id)
            .execution_options(populate_existing=True))
        if dataset is None or dataset.workspace_id != self._workspace_id or dataset.state != "staging":
            raise RuntimeError("projection.incomplete")
        return dataset

    def replace_dataset(self, dataset_id: str, build: CashProjectionBuild, *, projection_version: int) -> None:
        self._staging_dataset(dataset_id)
        from ft.adapters.relational.cash_entries import CashEntryRepository
        from ft.adapters.relational.models import CashEntryMemberModel
        live = {member.id for projection in build.projections for member, _ in projection.members}
        existing = set(self._session.scalars(select(CashEntryMemberModel.transaction_id).where(
            CashEntryMemberModel.workspace_id == self._workspace_id)))
        CashEntryRepository(self._session, self._workspace_id).remove_members(existing - live)
        self._reconcile_entries(build.projections)
        for model in (CashProjectionRelationModel, CashProjectionMemberModel, CashProjectionModel):
            self._session.execute(delete(model).where(model.workspace_id == self._workspace_id, model.dataset_id == dataset_id))
        self._insert_projections(dataset_id, build.projections, projection_version)

    def _insert_projections(self, dataset_id: str, projections, projection_version: int) -> None:
        projections = tuple(projections)
        if not projections:
            return
        projection_ids = {projection.projection_id for projection in projections}
        if len(projection_ids) != len(projections):
            raise RuntimeError("projection.incomplete")
        parent_mappings = [self._projection_mapping(dataset_id, projection, projection_version) for projection in projections]
        if len(projections) == 1:
            parent = CashProjectionModel(**parent_mappings[0])
            self._session.add(parent)
            self._session.flush()
            parent_ids = {projections[0].projection_id: parent.id}
        else:
            self._execute_batches(insert(CashProjectionModel), parent_mappings)
            parent_rows = [
                row
                for projection_id_batch in self._batches(sorted(projection_ids))
                for row in self._session.execute(
                    select(CashProjectionModel.id, CashProjectionModel.projection_id).where(
                        CashProjectionModel.workspace_id == self._workspace_id,
                        CashProjectionModel.dataset_id == dataset_id,
                        CashProjectionModel.projection_id.in_(projection_id_batch),
                    )
                ).all()
            ]
            parent_ids = {row.projection_id: row.id for row in parent_rows}
        if len(parent_ids) != len(projections) or set(parent_ids) != projection_ids:
            raise RuntimeError("projection.incomplete")
        member_mappings = []
        relation_mappings = []
        for projection in projections:
            projection_row_id = parent_ids[projection.projection_id]
            member_mappings.extend(
                {
                    "workspace_id": self._workspace_id,
                    "dataset_id": dataset_id,
                    "projection_row_id": projection_row_id,
                    "cash_transaction_id": member.id,
                    "roles_json": list(roles),
                    "ordinal": ordinal,
                }
                for ordinal, (member, roles) in enumerate(projection.members)
            )
            relation_mappings.extend(
                {
                    "workspace_id": self._workspace_id,
                    "dataset_id": dataset_id,
                    "projection_row_id": projection_row_id,
                    "transaction_relation_id": relation.id,
                    "kind": relation.kind,
                    "subtype": relation.subtype,
                    "ordinal": ordinal,
                }
                for ordinal, relation in enumerate(projection.relations)
            )
        self._execute_batches(insert(CashProjectionMemberModel), member_mappings)
        self._execute_batches(insert(CashProjectionRelationModel), relation_mappings)

    def _reconcile_entries(self, projections):
        from ft.adapters.relational.cash_entries import CashEntryRepository
        self._entry_rows = CashEntryRepository(self._session, self._workspace_id).reconcile(projections)

    def _projection_mapping(self, dataset_id: str, projection, projection_version: int) -> dict:
        entry = getattr(self, "_entry_rows", {}).get(projection.projection_id)
        if entry is None:
            from ft.adapters.relational.cash_entries import CashEntryRepository
            entry = CashEntryRepository(self._session, self._workspace_id).current(projection.primary_record.id)
        return {
            "workspace_id": self._workspace_id,
            "dataset_id": dataset_id,
            "projection_id": projection.projection_id,
            "entry_id": entry.id if entry else None,
            "root_cash_transaction_id": projection.primary_record.id,
            "funding_relation_id": projection.funding_relation_id,
            "state": projection.state,
            "statistics_eligible": projection.statistics_eligible,
            "attention_count": projection.attention_count,
            "sort_at": projection.sort_at,
            **({"created_at": projection.primary_record.created_at} if projection.primary_record.created_at is not None else {}),
            "economic_type": projection.economic_type.value if projection.economic_type is not None else None,
            "transfer_subtype": projection.transfer_subtype,
            "net_amount": projection.net_amount,
            "currency": projection.primary_record.currency,
            "occurred_at": projection.primary_record.occurred_at,
            "account_id": projection.primary_record.account_id,
            "counterparty": entry.counterparty if entry else projection.primary_record.counterparty,
            "category_id": entry.category_id if entry else projection.primary_record.category_id,
            "category_path": self._category_path(entry.category_id if entry else projection.primary_record.category_id),
            "note": entry.note if entry else projection.primary_record.note,
            "source_type": projection.primary_record.source_type,
            "record_id": projection.primary_record.record_id,
            "visible": projection.visible,
            "hidden_reason": projection.hidden_reason,
            "has_payment_mirror": "payment_mirror" in projection.compositions,
            "has_refund_offset": "refund_offset" in projection.compositions,
            "has_transfer_pair": "transfer_pair" in projection.compositions,
            "member_count": len(projection.members),
            "accepted_relation_count": len(projection.relations),
            "built_projection_version": projection_version,
        }

    def _category_path(self, category_id: str | None) -> str | None:
        if not category_id:
            return None
        return self._session.scalar(select(CashCategoryModel.category_path).where(
            CashCategoryModel.workspace_id == self._workspace_id,
            CashCategoryModel.id == category_id,
        ))

    def _batches(self, values):
        for start in range(0, len(values), PROJECTION_WRITE_BATCH_SIZE):
            yield values[start:start + PROJECTION_WRITE_BATCH_SIZE]

    def _execute_batches(self, statement, mappings) -> None:
        for batch in self._batches(mappings):
            self._session.execute(statement, batch)

    def _projection_rows_for_members(
        self,
        dataset_id: str,
        member_ids: set[int],
    ) -> list[CashProjectionModel]:
        """Resolve member IDs through their covering index before loading rows."""
        if not member_ids:
            return []
        projection_ids = set(self._session.scalars(
            select(CashProjectionMemberModel.projection_row_id).where(
                CashProjectionMemberModel.workspace_id == self._workspace_id,
                CashProjectionMemberModel.dataset_id == dataset_id,
                CashProjectionMemberModel.cash_transaction_id.in_(member_ids),
            )
        ).all())
        if not projection_ids:
            return []
        return list(self._session.scalars(
            select(CashProjectionModel).where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == dataset_id,
                CashProjectionModel.id.in_(projection_ids),
            )
        ).all())

    def current_references(self, transaction_ids, *, state):
        """有界定位当前成员；调用方在同一快照内读取并去重展示项。"""
        if state is None or state.active_dataset_id is None:
            return {}
        result = {}
        for batch in _batches(sorted({int(value) for value in transaction_ids})):
            members = select(CashProjectionMemberModel.cash_transaction_id,
                CashProjectionMemberModel.projection_row_id).where(
                    CashProjectionMemberModel.workspace_id == self._workspace_id,
                    CashProjectionMemberModel.dataset_id == state.active_dataset_id,
                    CashProjectionMemberModel.cash_transaction_id.in_(batch)).cte("reference_members").prefix_with(
                        "MATERIALIZED", dialect="postgresql")
            projections = select(CashProjectionModel.id, CashProjectionModel.workspace_id, CashProjectionModel.dataset_id,
                CashProjectionModel.projection_id, CashProjectionModel.attention_count).where(
                    CashProjectionModel.id.in_(select(members.c.projection_row_id))).cte("reference_projections").prefix_with(
                        "MATERIALIZED", dialect="postgresql")
            rows = self._session.execute(select(members.c.cash_transaction_id,
                projections.c.projection_id, projections.c.attention_count).join(projections,
                    projections.c.id == members.c.projection_row_id).where(
                        projections.c.workspace_id == self._workspace_id,
                        projections.c.dataset_id == state.active_dataset_id))
            result.update((str(row.cash_transaction_id), {"transaction_id": str(row.cash_transaction_id),
                "projection_id": row.projection_id, "attention_count": row.attention_count}) for row in rows)
        return result

    def replace_active_components(
        self,
        build: CashProjectionBuild,
        affected_fact_ids: set[int],
        *,
        state=None,
        known_new_fact_ids: set[int] | None = None,
    ) -> dict:
        state = state or self.require_ready_state_lock()
        from ft.adapters.relational.cash_entries import CashEntryRepository
        visible_members = {member.id for projection in build.projections for member, _ in projection.members}
        CashEntryRepository(self._session, self._workspace_id).remove_members(set(affected_fact_ids) - visible_members)
        self._reconcile_entries(build.projections)
        dataset_id = state.active_dataset_id
        lookup_fact_ids = set(affected_fact_ids) - {
            int(item) for item in (known_new_fact_ids or set())
        }
        old_rows = []
        if lookup_fact_ids:
            old_by_id = {
                row.id: row for row in self._session.scalars(
                    select(CashProjectionModel).where(
                        CashProjectionModel.workspace_id == self._workspace_id,
                        CashProjectionModel.dataset_id == dataset_id,
                        CashProjectionModel.root_cash_transaction_id.in_(lookup_fact_ids),
                    )
                ).all()
            }
            root_fact_ids = {
                int(row.root_cash_transaction_id) for row in old_by_id.values()
            }
            remaining_fact_ids = lookup_fact_ids - root_fact_ids
            if remaining_fact_ids:
                for row in self._projection_rows_for_members(dataset_id, remaining_fact_ids):
                    old_by_id[row.id] = row
            old_rows = list(old_by_id.values())
        old_ids = sorted({row.id for row in old_rows})
        replaced_member_ids = set(affected_fact_ids)
        old_member_ids: set[int] = set()
        next_version = state.projection_version + 1
        replacement_projections = tuple(
            projection for projection in build.projections
            if replaced_member_ids.intersection(projection.member_ids)
        )

        # Independent single-record changes only update denormalized fields.
        # Keep projection/member identities in place, and handle a whole batch
        # in one ORM flush instead of querying and deleting each old member.
        if (
            old_rows
            and len(old_rows) == len(lookup_fact_ids)
            and len(replacement_projections) == len(old_rows)
            and all(row.member_count == 1 for row in old_rows)
            and all(not projection.relations for projection in replacement_projections)
        ):
            replacement_by_id = {
                projection.projection_id: projection
                for projection in replacement_projections
            }
            for old_row in old_rows:
                replacement = replacement_by_id.get(old_row.projection_id)
                if replacement is None or replacement.member_ids != (old_row.root_cash_transaction_id,):
                    break
                for field, value in self._projection_mapping(
                    dataset_id, replacement, next_version,
                ).items():
                    setattr(old_row, field, value)
            else:
                state.projection_version = next_version
                state.source_revision += 1
                state.last_build_status = "succeeded"
                state.last_error_code = None
                state.last_error_summary = None
                return self._status_for_state(state)

        if old_ids:
            old_member_ids = set(self._session.scalars(
                select(CashProjectionMemberModel.cash_transaction_id).where(
                    CashProjectionMemberModel.projection_row_id.in_(old_ids),
                )
            ).all())
            replaced_member_ids.update(old_member_ids)

        # A common single-record edit only changes fields denormalized into
        # the one visible row. Keep the member row and projection identity in
        # place instead of delete/reinsert, while preserving the same
        # transaction and version semantics.
        if (
            len(old_rows) == 1
            and len(replacement_projections) == 1
            and old_rows[0].member_count == 1
            and len(replacement_projections[0].members) == 1
            and not replacement_projections[0].relations
            and replacement_projections[0].projection_id == old_rows[0].projection_id
            and set(replacement_projections[0].member_ids) == old_member_ids
        ):
            for field, value in self._projection_mapping(
                dataset_id, replacement_projections[0], next_version,
            ).items():
                setattr(old_rows[0], field, value)
            state.projection_version = next_version
            state.source_revision += 1
            state.last_build_status = "succeeded"
            state.last_error_code = None
            state.last_error_summary = None
            return self._status_for_state(state)

        old_by_projection = {row.projection_id: row for row in old_rows}
        if set(old_by_projection) == {item.projection_id for item in replacement_projections}:
            stored_members = {row.id: [] for row in old_rows}
            stored_relations = {row.id: [] for row in old_rows}
            for member in self._session.scalars(select(CashProjectionMemberModel).where(CashProjectionMemberModel.projection_row_id.in_(old_ids)).order_by(CashProjectionMemberModel.ordinal)):
                stored_members[member.projection_row_id].append((member.cash_transaction_id, member.roles_json))
            for relation in self._session.scalars(select(CashProjectionRelationModel).where(CashProjectionRelationModel.projection_row_id.in_(old_ids)).order_by(CashProjectionRelationModel.ordinal)):
                stored_relations[relation.projection_row_id].append((relation.transaction_relation_id, relation.kind, relation.subtype))
            if all(stored_members[old_by_projection[item.projection_id].id] == [(member.id, list(roles)) for member, roles in item.members]
                and stored_relations[old_by_projection[item.projection_id].id] == [(relation.id, relation.kind, relation.subtype) for relation in item.relations]
                for item in replacement_projections):
                for item in replacement_projections:
                    row = old_by_projection[item.projection_id]
                    for field, value in self._projection_mapping(dataset_id, item, next_version).items():
                        if getattr(row, field) != value:
                            setattr(row, field, value)
                state.projection_version = next_version
                state.source_revision += 1
                state.last_build_status = "succeeded"
                state.last_error_code = None
                state.last_error_summary = None
                return self._status_for_state(state)

        if old_ids:
            self._session.execute(delete(CashProjectionRelationModel).where(CashProjectionRelationModel.projection_row_id.in_(old_ids)))
            self._session.execute(delete(CashProjectionMemberModel).where(CashProjectionMemberModel.projection_row_id.in_(old_ids)))
            self._session.execute(delete(CashProjectionModel).where(CashProjectionModel.id.in_(old_ids)))
        self._insert_projections(dataset_id, replacement_projections, next_version)
        replacement_member_ids = {
            member.id
            for projection in replacement_projections
            for member, _roles in projection.members
        }
        if len(replacement_member_ids) != sum(len(projection.members) for projection in replacement_projections):
            raise RuntimeError("projection.incomplete")
        state.projection_version = next_version
        state.source_revision += 1
        state.last_build_status = "succeeded"
        state.last_error_code = None
        state.last_error_summary = None
        state.projection_count = state.projection_count - len(old_ids) + len(replacement_projections)
        state.member_count = state.member_count - len(old_member_ids) + len(replacement_member_ids)
        return self._status_for_state(state)

    def _fact_from_model(self, row: CashTransactionModel, *, needs_attention=None, funding_relation_id=None) -> CashProjectionFact:
        if needs_attention is None:
            needs_attention = row.id in read_cash_attention(self._session, self._workspace_id, {row.id}).transaction_ids
        return CashProjectionFact(
            id=row.id,
            account_id=row.account_id,
            occurred_at=row.occurred_at,
            amount=row.amount,
            currency=row.currency,
            counterparty=row.counterparty,
            category_id=row.category_id,
            note=row.note,
            source_type=row.source_type,
            record_id=row.record_id,
            cash_granularity=row.cash_granularity, state=row.state, created_at=row.created_at,
            needs_attention=needs_attention, funding_relation_id=funding_relation_id,
        )

    def replace_standalone_fact(
        self,
        row: CashTransactionModel,
        *,
        state=None,
    ) -> dict:
        """Update one standalone projection without rereading its source graph."""
        state = state or self.require_ready_state_lock()
        build = build_cash_projections((self._fact_from_model(row),), ())
        projection = build.projections[0]
        self._reconcile_entries(build.projections)
        mapping = self._projection_mapping(
            state.active_dataset_id, projection, state.projection_version + 1,
        )
        mapping.pop("workspace_id", None)
        mapping.pop("dataset_id", None)
        mapping.pop("projection_id", None)
        mapping.pop("root_cash_transaction_id", None)
        result = self._session.execute(
            CashProjectionModel.__table__.update().where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.root_cash_transaction_id == row.id,
            ).values(**mapping, updated_at=_now())
        )
        if result.rowcount != 1:
            # A ready projection can be absent after an interrupted migration
            # or an externally rebuilt dataset. Preserve the normal repair path
            # instead of silently reporting success.
            return self.replace_active_components(
                build, {row.id}, state=state,
            )
        state.projection_version += 1
        state.source_revision += 1
        state.last_build_status = "succeeded"
        state.last_error_code = None
        state.last_error_summary = None
        return self._status_for_state(state)

    def refresh_display_fields(
        self, fact_id: int, *, counterparty: str, note: str, state=None,
    ) -> dict:
        """Refresh a root record's denormalized display fields without rebuilding its group."""
        state = state or self.require_ready_state_lock()
        self._session.execute(
            CashProjectionModel.__table__.update().where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.root_cash_transaction_id == int(fact_id),
            ).values(
                counterparty=counterparty,
                note=note,
                updated_at=_now(),
                built_projection_version=state.projection_version + 1,
            )
        )
        state.projection_version += 1
        state.source_revision += 1
        state.last_build_status = "succeeded"
        state.last_error_code = None
        state.last_error_summary = None
        return self._status_for_state(state)

    def remove_active_components(self, affected_fact_ids: set[int], *, state=None) -> dict:
        """Remove standalone active projection rows before source deletion."""
        state = state or self.require_ready_state_lock()
        from ft.adapters.relational.cash_entries import CashEntryRepository
        CashEntryRepository(self._session, self._workspace_id).remove_members(affected_fact_ids)
        if len(affected_fact_ids) == 1:
            # The common unrelated-delete path is rooted by the fact itself.
            # Avoid the member reverse-join, whose PostgreSQL planner can
            # choose a disastrous plan for a large active dataset.
            rows = self._session.scalars(
                select(CashProjectionModel).where(
                    CashProjectionModel.workspace_id == self._workspace_id,
                    CashProjectionModel.dataset_id == state.active_dataset_id,
                    CashProjectionModel.root_cash_transaction_id.in_(affected_fact_ids),
                )
            ).all()
        else:
            rows = self._projection_rows_for_members(state.active_dataset_id, affected_fact_ids)
        row_ids = [row.id for row in rows]
        if row_ids:
            self._session.execute(delete(CashProjectionRelationModel).where(
                CashProjectionRelationModel.projection_row_id.in_(row_ids),
            ))
            self._session.execute(delete(CashProjectionMemberModel).where(
                CashProjectionMemberModel.projection_row_id.in_(row_ids),
            ))
            self._session.execute(delete(CashProjectionModel).where(
                CashProjectionModel.id.in_(row_ids),
            ))
        state.projection_version += 1
        state.source_revision += 1
        state.projection_count -= len(row_ids)
        state.member_count -= sum(row.member_count for row in rows)
        state.last_build_status = "succeeded"
        state.last_error_code = None
        state.last_error_summary = None
        return self._status_for_state(state)

    def publish_dataset(self, dataset_id: str, *, source_digest: str, rules_version: str, _validated_sources=None) -> dict:
        state = self._state(create=True, lock=True)
        assert state is not None
        dataset = self._staging_dataset(dataset_id)
        if dataset_id == state.active_dataset_id:
            raise RuntimeError("projection.incomplete")
        # Re-reading the canonical source immediately before publish prevents stale snapshots.
        if self.source_digest() != source_digest:
            raise RuntimeError("projection.concurrent_update")
        self._session.execute(
            CashProjectionDatasetModel.__table__.update().where(
                CashProjectionDatasetModel.workspace_id == self._workspace_id,
                CashProjectionDatasetModel.state == "active",
            ).values(state="staging")
        )
        # Keep counts portable across PostgreSQL and SQLite without dialect SQL.
        projection_count = self._session.scalar(select(func.count()).select_from(CashProjectionModel).where(
            CashProjectionModel.dataset_id == dataset_id))
        member_count = self._session.scalar(select(func.count()).select_from(CashProjectionMemberModel).where(
            CashProjectionMemberModel.dataset_id == dataset_id))
        if _validated_sources is None:
            source_count = len(self.read_sources()[0])
        else:
            if (type(_validated_sources) is not _ValidatedProjectionSources or _validated_sources.repository is not self
                or _validated_sources.transaction is not self._session.get_transaction()
                or _validated_sources.digest != source_digest):
                raise RuntimeError("projection.incomplete")
            source_count = self._session.scalar(select(func.count()).select_from(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id, CashTransactionModel.deleted_at.is_(None)))
            if source_count != len(_validated_sources.facts):
                raise RuntimeError("projection.incomplete")
        if member_count != source_count:
            raise RuntimeError("projection.incomplete")
        dataset.state, dataset.published_at = "active", _now()
        self._session.flush()
        previous_dataset_id = state.active_dataset_id
        state.active_dataset_id = dataset_id
        state.projection_version += 1
        state.availability = "ready"
        state.rules_version = rules_version
        state.last_build_status = "succeeded"
        state.build_finished_at = _now()
        state.projection_count, state.member_count = projection_count, member_count
        self._session.flush()
        if previous_dataset_id is not None:
            self._session.execute(delete(CashProjectionDatasetModel).where(
                CashProjectionDatasetModel.workspace_id == self._workspace_id,
                CashProjectionDatasetModel.id == previous_dataset_id))
        return self.status()

    def record_failed_build(
        self,
        *,
        active_dataset_id: str | None,
        source_revision: int,
        error_code: str,
    ) -> bool:
        """仅在没有新构建或源变更时记录一次安全的失败诊断。"""
        state = self._state(create=True, lock=True)
        assert state is not None
        if (
            state.active_dataset_id != active_dataset_id
            or state.source_revision != source_revision
        ):
            return False
        state.last_build_status = "failed"
        state.last_error_code = error_code
        state.last_error_summary = "收支投影构建失败"
        state.build_finished_at = _now()
        return True

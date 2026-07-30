"""收支投影的关系型派生读模型适配器。"""
from __future__ import annotations

from datetime import datetime, timezone
from hashlib import sha256
import json
from uuid import uuid4

from sqlalchemy import delete, select

from ft.domain.cash_projection import (
    CashProjectionBuild,
    CashProjectionFact,
    ProjectionRelation,
    RULES_VERSION,
)
from ft.domain.wealth import canonical_bytes

from .models import (
    CashProjectionDatasetModel,
    CashProjectionMemberModel,
    CashProjectionModel,
    CashProjectionRelationModel,
    CashProjectionStateModel,
    CashTransactionModel,
    TransactionRelationModel,
    WorkspaceModel,
)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class RelationalCashProjectionRepository:
    """只映射持久化；归并和金额规则始终留在领域层。"""

    def __init__(self, session, workspace_id: str):
        self._session = session
        self._workspace_id = workspace_id

    def _state(self, *, create: bool = False, lock: bool = False) -> CashProjectionStateModel | None:
        statement = select(CashProjectionStateModel).where(CashProjectionStateModel.workspace_id == self._workspace_id)
        if lock:
            workspace = select(WorkspaceModel).where(WorkspaceModel.id == self._workspace_id)
            if self._session.bind.dialect.name == "postgresql":
                workspace = workspace.with_for_update()
                statement = statement.with_for_update()
            self._session.execute(workspace).one()
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
        return {
            "availability": state.availability, "projection_version": state.projection_version,
            "source_revision": state.source_revision, "rules_version": state.rules_version,
            "last_build_status": state.last_build_status, "active_dataset_id": state.active_dataset_id,
            "projection_count": state.projection_count, "member_count": state.member_count,
            "last_error_code": state.last_error_code, "last_error_summary": state.last_error_summary,
            "last_build_id": state.last_build_id,
        }

    def read_sources(self) -> tuple[tuple[CashProjectionFact, ...], tuple[ProjectionRelation, ...]]:
        facts = tuple(
            CashProjectionFact(
                id=row.id, account_id=row.account_id, occurred_at=row.occurred_at, amount=row.amount,
                currency=row.currency, counterparty=row.counterparty, category=row.category, note=row.note,
                source_type=row.source_type, record_id=row.record_id,
            )
            for row in self._session.scalars(
                select(CashTransactionModel).where(
                    CashTransactionModel.workspace_id == self._workspace_id,
                    CashTransactionModel.deleted_at.is_(None),
                ).order_by(CashTransactionModel.id)
            )
        )
        relations = tuple(
            ProjectionRelation(
                id=row.id, kind=row.kind, primary_fact_id=row.primary_fact_id,
                secondary_fact_id=row.secondary_fact_id, status=row.status, subtype=row.subtype,
            )
            for row in self._session.scalars(
                select(TransactionRelationModel).where(
                    TransactionRelationModel.workspace_id == self._workspace_id,
                    TransactionRelationModel.status == "accepted",
                    TransactionRelationModel.primary_fact_type == "cash",
                    TransactionRelationModel.secondary_fact_type == "cash",
                    TransactionRelationModel.secondary_fact_id.is_not(None),
                ).order_by(TransactionRelationModel.id)
            )
        )
        return facts, relations

    def source_digest(self) -> str:
        payload = {
            "facts": [
                {
                    "id": item.id,
                    "account_id": item.account_id,
                    "source_type": item.source_type,
                    "record_id": item.record_id,
                    "source_payload": item.source_payload,
                    "occurred_at": item.occurred_at,
                    "amount": item.amount,
                    "currency": item.currency,
                    "counterparty": item.counterparty,
                    "note": item.note,
                    "category": item.category,
                }
                for item in self._session.scalars(
                    select(CashTransactionModel).where(
                        CashTransactionModel.workspace_id == self._workspace_id,
                        CashTransactionModel.deleted_at.is_(None),
                    ).order_by(CashTransactionModel.id)
                )
            ],
            "relations": [
                {
                    "id": item.id,
                    "kind": item.kind,
                    "primary_fact_id": item.primary_fact_id,
                    "secondary_fact_id": item.secondary_fact_id,
                    "primary_fact_type": item.primary_fact_type,
                    "secondary_fact_type": item.secondary_fact_type,
                    "status": item.status,
                    "subtype": item.subtype,
                    "rule_id": item.rule_id,
                    "confidence": item.confidence,
                    "evidence_json": item.evidence_json,
                }
                for item in self._session.scalars(
                    select(TransactionRelationModel).where(
                        TransactionRelationModel.workspace_id == self._workspace_id,
                        TransactionRelationModel.status == "accepted",
                        TransactionRelationModel.primary_fact_type == "cash",
                        TransactionRelationModel.secondary_fact_type == "cash",
                        TransactionRelationModel.secondary_fact_id.is_not(None),
                    ).order_by(TransactionRelationModel.id)
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

    def require_ready_state_lock(self) -> CashProjectionStateModel:
        """锁定可维护的活动数据集，未初始化时拒绝源写入。"""
        state = self._state(lock=True)
        if state is None or state.availability != "ready" or not state.active_dataset_id:
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

    def replace_dataset(self, dataset_id: str, build: CashProjectionBuild, *, projection_version: int) -> None:
        self._session.execute(delete(CashProjectionRelationModel).where(CashProjectionRelationModel.dataset_id == dataset_id))
        self._session.execute(delete(CashProjectionMemberModel).where(CashProjectionMemberModel.dataset_id == dataset_id))
        self._session.execute(delete(CashProjectionModel).where(CashProjectionModel.dataset_id == dataset_id))
        for projection in build.projections:
            self._insert_projection(dataset_id, projection, projection_version)

    def _insert_projection(self, dataset_id, projection, projection_version):
        row = CashProjectionModel(
                workspace_id=self._workspace_id, dataset_id=dataset_id, projection_id=projection.projection_id,
                root_cash_transaction_id=projection.primary_record.id, economic_type=projection.economic_type.value,
                transfer_subtype=projection.transfer_subtype, net_amount=projection.net_amount,
                currency=projection.primary_record.currency, occurred_at=projection.primary_record.occurred_at,
                account_id=projection.primary_record.account_id, counterparty=projection.primary_record.counterparty,
                category=projection.primary_record.category, note=projection.primary_record.note,
                source_type=projection.primary_record.source_type, record_id=projection.primary_record.record_id,
                visible=projection.visible, hidden_reason=projection.hidden_reason,
                has_payment_mirror="payment_mirror" in projection.compositions,
                has_refund_offset="refund_offset" in projection.compositions,
                has_transfer_pair="transfer_pair" in projection.compositions,
                member_count=len(projection.members), accepted_relation_count=len(projection.relations),
                built_projection_version=projection_version,
        )
        self._session.add(row)
        self._session.flush()
        for ordinal, (member, roles) in enumerate(projection.members):
            self._session.add(CashProjectionMemberModel(
                    workspace_id=self._workspace_id, dataset_id=dataset_id, projection_row_id=row.id,
                    cash_transaction_id=member.id, roles_json=list(roles), ordinal=ordinal,
            ))
        for ordinal, relation in enumerate(projection.relations):
            self._session.add(CashProjectionRelationModel(
                    workspace_id=self._workspace_id, dataset_id=dataset_id, projection_row_id=row.id,
                    transaction_relation_id=relation.id, kind=relation.kind, subtype=relation.subtype, ordinal=ordinal,
            ))

    def replace_active_components(self, build: CashProjectionBuild, affected_fact_ids: set[int]) -> dict:
        state = self.require_ready_state_lock()
        dataset_id = state.active_dataset_id
        old_rows = self._session.scalars(
            select(CashProjectionModel)
            .join(CashProjectionMemberModel, CashProjectionMemberModel.projection_row_id == CashProjectionModel.id)
            .where(CashProjectionModel.dataset_id == dataset_id, CashProjectionMemberModel.cash_transaction_id.in_(affected_fact_ids))
        ).all()
        stale_rows = self._session.scalars(
            select(CashProjectionModel)
            .join(CashProjectionMemberModel, CashProjectionMemberModel.projection_row_id == CashProjectionModel.id)
            .join(
                CashTransactionModel,
                (CashTransactionModel.workspace_id == CashProjectionMemberModel.workspace_id)
                & (CashTransactionModel.id == CashProjectionMemberModel.cash_transaction_id),
            )
            .where(
                CashProjectionModel.dataset_id == dataset_id,
                CashTransactionModel.deleted_at.is_not(None),
            )
        ).all()
        old_ids = sorted({row.id for row in (*old_rows, *stale_rows)})
        replaced_member_ids = set(affected_fact_ids)
        if old_ids:
            replaced_member_ids.update(self._session.scalars(
                select(CashProjectionMemberModel.cash_transaction_id).where(
                    CashProjectionMemberModel.projection_row_id.in_(old_ids),
                )
            ).all())
        if old_ids:
            self._session.execute(delete(CashProjectionRelationModel).where(CashProjectionRelationModel.projection_row_id.in_(old_ids)))
            self._session.execute(delete(CashProjectionMemberModel).where(CashProjectionMemberModel.projection_row_id.in_(old_ids)))
            self._session.execute(delete(CashProjectionModel).where(CashProjectionModel.id.in_(old_ids)))
        next_version = state.projection_version + 1
        for projection in build.projections:
            if replaced_member_ids.intersection(projection.member_ids):
                self._insert_projection(dataset_id, projection, next_version)
        source_count = len(self.read_sources()[0])
        member_count = len(self._session.scalars(select(CashProjectionMemberModel).where(CashProjectionMemberModel.dataset_id == dataset_id)).all())
        if source_count != member_count:
            raise RuntimeError("projection.incomplete")
        state.projection_version = next_version
        state.source_revision += 1
        state.last_build_status = "succeeded"
        state.last_error_code = None
        state.last_error_summary = None
        state.projection_count = len(self._session.scalars(select(CashProjectionModel).where(CashProjectionModel.dataset_id == dataset_id)).all())
        state.member_count = member_count
        return self.status()

    def publish_dataset(self, dataset_id: str, *, source_digest: str, rules_version: str) -> dict:
        state = self._state(create=True, lock=True)
        assert state is not None
        # Re-reading the canonical source immediately before publish prevents stale snapshots.
        if self.source_digest() != source_digest:
            raise RuntimeError("projection.concurrent_update")
        self._session.execute(
            CashProjectionDatasetModel.__table__.update().where(
                CashProjectionDatasetModel.workspace_id == self._workspace_id,
                CashProjectionDatasetModel.state == "active",
            ).values(state="retired")
        )
        dataset = self._session.get(CashProjectionDatasetModel, dataset_id)
        if dataset is None or dataset.workspace_id != self._workspace_id:
            raise RuntimeError("projection.incomplete")
        # Keep counts portable across PostgreSQL and SQLite without dialect SQL.
        projection_count = len(self._session.scalars(select(CashProjectionModel).where(CashProjectionModel.dataset_id == dataset_id)).all())
        member_count = len(self._session.scalars(select(CashProjectionMemberModel).where(CashProjectionMemberModel.dataset_id == dataset_id)).all())
        source_count = len(self.read_sources()[0])
        if member_count != source_count:
            raise RuntimeError("projection.incomplete")
        dataset.state, dataset.published_at = "active", _now()
        self._session.flush()
        retired_ids = self._session.scalars(
            select(CashProjectionDatasetModel.id)
            .where(
                CashProjectionDatasetModel.workspace_id == self._workspace_id,
                CashProjectionDatasetModel.state == "retired",
            )
            .order_by(CashProjectionDatasetModel.published_at.desc(), CashProjectionDatasetModel.id.desc())
            .offset(1)
        ).all()
        if retired_ids:
            self._session.execute(
                delete(CashProjectionDatasetModel).where(CashProjectionDatasetModel.id.in_(retired_ids))
            )
        state.active_dataset_id = dataset_id
        state.projection_version += 1
        state.availability = "ready"
        state.rules_version = rules_version
        state.last_build_status = "succeeded"
        state.build_finished_at = _now()
        state.projection_count, state.member_count = projection_count, member_count
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

"""收支投影的单笔与批量分类命令。"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select, update

from ft.adapters.relational.models import (
    CashCategoryModel,
    CashProjectionMemberModel,
    CashProjectionModel,
    CashProjectionStateModel,
    CashTransactionModel,
)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class CashClassificationService:
    """以收支投影为边界同步其全部现金流水的收支分类。"""

    def __init__(self, sessions, workspace_id: str):
        self._sessions = sessions
        self._workspace_id = workspace_id

    def set_category(self, *, projection_ids: list[str] | tuple[str, ...], projection_version: int, category_id: str | None) -> dict:
        ids = tuple(dict.fromkeys(str(item).strip() for item in projection_ids if str(item).strip()))
        if not ids:
            raise ValueError("category.projections_required")
        with self._sessions.begin() as session:
            state_query = select(CashProjectionStateModel).where(
                CashProjectionStateModel.workspace_id == self._workspace_id,
            )
            if session.bind.dialect.name == "postgresql":
                state_query = state_query.with_for_update()
            state = session.scalar(state_query)
            if state is None or state.availability != "ready" or not state.active_dataset_id:
                raise ValueError("projection.unavailable")
            if int(state.projection_version) != int(projection_version):
                raise ValueError("projection.version_conflict")

            target = None
            if category_id is not None:
                target = session.scalar(select(CashCategoryModel).where(
                    CashCategoryModel.workspace_id == self._workspace_id,
                    CashCategoryModel.id == str(category_id),
                ))
                if target is None:
                    raise ValueError("category.not_found")

            rows = session.scalars(select(CashProjectionModel).where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.projection_id.in_(ids),
                CashProjectionModel.visible.is_(True),
            )).all()
            if len(rows) != len(ids):
                raise ValueError("projection.version_conflict")
            row_by_id = {row.projection_id: row for row in rows}
            if set(row_by_id) != set(ids):
                raise ValueError("projection.version_conflict")
            row_ids = tuple(row.id for row in rows)
            member_ids = tuple(dict.fromkeys(session.scalars(select(
                CashProjectionMemberModel.cash_transaction_id,
            ).where(
                CashProjectionMemberModel.workspace_id == self._workspace_id,
                CashProjectionMemberModel.dataset_id == state.active_dataset_id,
                CashProjectionMemberModel.projection_row_id.in_(row_ids),
            ).order_by(CashProjectionMemberModel.projection_row_id, CashProjectionMemberModel.ordinal)).all()))
            if not member_ids:
                raise ValueError("projection.version_conflict")

            session.execute(update(CashTransactionModel).where(
                CashTransactionModel.workspace_id == self._workspace_id,
                CashTransactionModel.id.in_(member_ids),
                CashTransactionModel.deleted_at.is_(None),
            ).values(category_id=target.id if target else None))
            next_version = int(state.projection_version) + 1
            for row in rows:
                row.category_id = target.id if target else None
                row.category_path = target.category_path if target else None
                row.built_projection_version = next_version
                row.updated_at = _now()
            state.projection_version = next_version
            state.updated_at = _now()
            state.source_revision += 1
            return {
                "projection_version": next_version,
                "projection_count": len(rows),
                "updated_transaction_count": len(member_ids),
                "category_id": target.id if target else None,
            }

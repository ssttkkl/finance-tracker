"""收支投影的单笔与批量分类命令。"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import select

from ft.adapters.relational.cash_projection_visibility import maintainable_projection_conditions
from ft.adapters.relational.projections import RelationalCashProjectionRepository

from ft.adapters.relational.models import (
    CashCategoryModel,
    CashProjectionMemberModel,
    CashProjectionModel,
)


def _now() -> datetime:
    return datetime.now(timezone.utc)


class CashClassificationService:
    """按当前展示选择修改稳定收支对象的公共分类。"""

    def __init__(self, sessions, workspace_id: str):
        self._sessions = sessions
        self._workspace_id = workspace_id

    def set_category(self, *, projection_ids: list[str] | tuple[str, ...], projection_version: int, category_id: str | None) -> dict:
        ids = tuple(dict.fromkeys(str(item).strip() for item in projection_ids if str(item).strip()))
        if not ids:
            raise ValueError("category.projections_required")
        with self._sessions.begin() as session:
            state = RelationalCashProjectionRepository(session, self._workspace_id).ready_state_lock_or_none()
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
                *maintainable_projection_conditions(self._workspace_id, state.active_dataset_id),
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
            if not member_ids or len(member_ids) != sum(row.member_count for row in rows):
                raise ValueError("projection.version_conflict")

            from ft.adapters.relational.cash_entries import CashEntryRepository
            entries = CashEntryRepository(session, self._workspace_id)
            changed = set()
            for row in rows:
                entry = entries.current(row.root_cash_transaction_id)
                if entry is None:
                    raise ValueError("projection.version_conflict")
                before = entry.revision
                entries.patch(row.root_cash_transaction_id, {"category_id": target.id if target else None}, expected_revision=entry.revision)
                if entry.revision != before:
                    changed.add(entry.id)
            next_version = int(state.projection_version)
            category_value, category_path = (target.id, target.category_path) if target else (None, None)
            if changed or any(row.category_id != category_value or row.category_path != category_path for row in rows):
                next_version += 1
                for row in rows:
                    row.category_id = category_value
                    row.category_path = category_path
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

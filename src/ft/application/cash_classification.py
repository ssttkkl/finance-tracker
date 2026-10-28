"""收支投影的单笔与批量分类命令。"""
from __future__ import annotations

from datetime import datetime, timezone

from sqlalchemy import func, select
from sqlalchemy.orm import aliased

from ft.adapters.relational.cash_projection_visibility import maintainable_projection_conditions
from ft.adapters.relational.projections import RelationalCashProjectionRepository

from ft.adapters.relational.models import (
    CashCategoryModel, CashEntryModel, CashEntryMemberModel,
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

            all_members = CashEntryMemberModel.__table__.alias("all_entry_members")
            member_count = select(func.count()).select_from(all_members).where(
                all_members.c.workspace_id == CashEntryModel.workspace_id,
                all_members.c.entry_id == CashEntryModel.id).correlate(CashEntryModel).scalar_subquery()
            target_query = select(CashProjectionModel).where(
                CashProjectionModel.workspace_id == self._workspace_id,
                CashProjectionModel.dataset_id == state.active_dataset_id,
                CashProjectionModel.projection_id.in_(ids),
                *maintainable_projection_conditions(self._workspace_id, state.active_dataset_id),
            ).limit(len(ids)).subquery()
            selected_projection = aliased(CashProjectionModel, target_query)

            selected = session.execute(select(selected_projection, CashEntryModel, CashEntryMemberModel, member_count).join(
                CashProjectionMemberModel,
                (CashProjectionMemberModel.workspace_id == selected_projection.workspace_id)
                & (CashProjectionMemberModel.dataset_id == selected_projection.dataset_id)
                & (CashProjectionMemberModel.projection_row_id == selected_projection.id),
            ).join(CashEntryMemberModel,
                (CashEntryMemberModel.workspace_id == CashProjectionMemberModel.workspace_id)
                & (CashEntryMemberModel.transaction_id == CashProjectionMemberModel.cash_transaction_id),
            ).join(CashEntryModel,
                (CashEntryModel.workspace_id == CashEntryMemberModel.workspace_id)
                & (CashEntryModel.id == CashEntryMemberModel.entry_id),
            )).all()
            row_by_id, entry_by_row, members_by_row = {}, {}, {}
            for row, entry, member, entry_member_count in selected:
                row_by_id[row.projection_id] = row
                if (entry_member_count != row.member_count or entry.root_transaction_id != row.root_cash_transaction_id
                    or entry_by_row.setdefault(row.id, entry).id != entry.id):
                    raise ValueError("projection.version_conflict")
                members_by_row.setdefault(row.id, {})[member.transaction_id] = member
            rows = list(row_by_id.values())
            if set(row_by_id) != set(ids) or any(
                len(members_by_row[row.id]) != row.member_count for row in rows):
                raise ValueError("projection.version_conflict")
            category_value = target.id if target else None
            changed = set()
            for row in rows:
                entry = entry_by_row[row.id]
                if entry.category_id != category_value or "category_id" not in entry.manual_fields:
                    entry.category_id = category_value
                    entry.manual_fields = sorted(set(entry.manual_fields) | {"category_id"})
                    entry.revision += 1
                    changed.add(entry.id)
                    if row.member_count == 1:
                        member, = members_by_row[row.id].values()
                        member.standalone_values = {**member.standalone_values, "category_id": category_value}
                        member.standalone_manual_fields = sorted(set(member.standalone_manual_fields) | {"category_id"})
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
                "updated_transaction_count": sum(len(members) for members in members_by_row.values()),
                "category_id": target.id if target else None,
            }
